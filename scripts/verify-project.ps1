$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'import-local-env.ps1')
Import-DataPilotLocalEnvironment -Exclude @('DATAPILOT_DB_ROOT_PASSWORD', 'DATAPILOT_DB_SEED_PASSWORD')

Push-Location (Join-Path $projectRoot 'backend')
try {
    & .\mvnw.cmd -B -ntp clean verify
    if ($LASTEXITCODE -ne 0) {
        throw "Backend tests or build failed with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}

Push-Location (Join-Path $projectRoot 'frontend')
try {
    & npm.cmd run typecheck
    if ($LASTEXITCODE -ne 0) { throw "Frontend type check failed with exit code $LASTEXITCODE." }
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw "Frontend build failed with exit code $LASTEXITCODE." }
}
finally {
    Pop-Location
}
