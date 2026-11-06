$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot '.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    throw 'Local .env is missing. Run .\scripts\start-mysql.ps1 first.'
}
. (Join-Path $PSScriptRoot 'import-local-env.ps1')
Import-DataPilotLocalEnvironment -Exclude @('DATAPILOT_DB_ROOT_PASSWORD', 'DATAPILOT_DB_SEED_PASSWORD', 'DATAPILOT_LLM_API_KEY')

$docker = Get-Command docker -ErrorAction SilentlyContinue
$dockerExe = if ($docker) { $docker.Source } else { Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\resources\bin\docker.exe' }
$previousPath = $env:PATH
if (Test-Path -LiteralPath $dockerExe) {
    $env:PATH = "$(Split-Path -Parent $dockerExe);$env:PATH"
}
$sqlFile = Join-Path $projectRoot 'database\validation\001-business-checks.sql'
$sql = [System.IO.File]::ReadAllText($sqlFile)
Push-Location $projectRoot
try {
    $output = $sql | & $dockerExe compose exec -T mysql sh -lc 'MYSQL_PWD="$DATAPILOT_DB_READ_PASSWORD" exec mysql --user="$DATAPILOT_DB_READ_USER" --database="$MYSQL_DATABASE" --batch --raw'
    if ($LASTEXITCODE -ne 0) {
        throw "Dataset validation failed with exit code $LASTEXITCODE."
    }
    $output | ForEach-Object { Write-Output $_ }
    $passedChecks = @($output | Where-Object { $_ -match "`tPASS`t" })
    if ($passedChecks.Count -ne 12 -or $output -match "`tFAIL`t") {
        throw "Expected 12 passing business-data checks; received $($passedChecks.Count)."
    }
}
finally {
    $env:PATH = $previousPath
    Pop-Location
}
