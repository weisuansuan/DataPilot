param(
    [int]$OrderCount = -1
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot '.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    throw 'Local .env is missing. Run .\scripts\start-mysql.ps1 first.'
}
. (Join-Path $PSScriptRoot 'import-local-env.ps1')
Import-DataPilotLocalEnvironment -Exclude @('DATAPILOT_DB_ROOT_PASSWORD', 'DATAPILOT_DB_READ_PASSWORD', 'DATAPILOT_LLM_API_KEY')

if ($OrderCount -eq -1) {
    $OrderCount = [int]$env:DATAPILOT_SEED_ORDER_COUNT
}
if ($OrderCount -lt 10 -or $OrderCount -gt 1000000) {
    throw 'OrderCount must be between 10 and 1,000,000.'
}

Push-Location (Join-Path $projectRoot 'backend')
try {
    $dataArgument = "--datapilot.seed.order-count=$OrderCount"
    & .\mvnw.cmd -B -ntp "-Dspring-boot.run.profiles=seed" "-Dspring-boot.run.arguments=$dataArgument" spring-boot:run
    if ($LASTEXITCODE -ne 0) {
        throw "Synthetic data generation failed with exit code $LASTEXITCODE. Rerun the command; it regenerates only the marked datapilot synthetic dataset."
    }
}
finally {
    Pop-Location
}
