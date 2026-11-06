$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$docker = Get-Command docker -ErrorAction SilentlyContinue
$dockerExe = if ($docker) { $docker.Source } else { Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\resources\bin\docker.exe' }
if (-not (Test-Path -LiteralPath $dockerExe) -and -not $docker) {
    throw 'Docker CLI is unavailable.'
}

$previousPath = $env:PATH
$env:PATH = "$(Split-Path -Parent $dockerExe);$env:PATH"
Push-Location $projectRoot
try {
    $columnCount = & $dockerExe compose exec -T mysql sh -lc 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --database=information_schema --batch --skip-column-names --execute="SELECT COUNT(*) FROM COLUMNS WHERE TABLE_SCHEMA = ''datapilot'' AND TABLE_NAME = ''dataset_metadata'' AND COLUMN_NAME = ''refund_observation_cutoff''"'
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the local datapilot schema.' }
    if ([int]$columnCount -gt 0) {
        Write-Host 'Refund observation cutoff migration is already applied.'
        return
    }

    $sqlPath = Join-Path $projectRoot 'database\migrations\002-refund-observation-cutoff.sql'
    $sql = [System.IO.File]::ReadAllText($sqlPath)
    $sql | & $dockerExe compose exec -T mysql sh -lc 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --database=datapilot --batch'
    if ($LASTEXITCODE -ne 0) { throw 'The local datapilot schema migration failed.' }
    Write-Host 'Applied refund observation cutoff migration to the local datapilot schema.'
}
finally {
    Pop-Location
    $env:PATH = $previousPath
}
