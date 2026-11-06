$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'import-local-env.ps1')
Import-DataPilotLocalEnvironment

$docker = Get-Command docker -ErrorAction SilentlyContinue
$dockerExe = if ($docker) { $docker.Source } else { Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\resources\bin\docker.exe' }
if (-not (Test-Path -LiteralPath $dockerExe) -and -not $docker) { throw 'Docker CLI is unavailable.' }

$previousPath = $env:PATH
$env:PATH = "$(Split-Path -Parent $dockerExe);$env:PATH"
try {
    $sqlPath = Join-Path $projectRoot 'database\init\003-analytics-views.sql'
    $sql = [System.IO.File]::ReadAllText($sqlPath)
    Push-Location $projectRoot
    try {
        $sql | & $dockerExe compose exec -T mysql sh -lc 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --database=datapilot --batch'
        if ($LASTEXITCODE -ne 0) { throw 'Could not apply the analytics view migration.' }
    }
    finally { Pop-Location }
    Write-Host 'Applied the versioned analytics views to the local datapilot schema.'
}
finally { $env:PATH = $previousPath }
