$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'import-local-env.ps1')
Import-DataPilotLocalEnvironment

$node = Get-Command node -ErrorAction SilentlyContinue
$npm = Get-Command npm.cmd -ErrorAction SilentlyContinue
if (-not $node -or -not $npm) {
    throw 'Node.js 22.12 or later and npm are required. Install Node.js LTS and open a new PowerShell terminal.'
}

Push-Location (Join-Path $projectRoot 'frontend')
try {
    if (-not (Test-Path -LiteralPath (Join-Path (Get-Location) 'node_modules\vite\bin\vite.js'))) {
        Write-Host 'Installing the exact frontend versions from package-lock.json...'
        & $npm.Source 'ci' '--no-audit' '--no-fund'
        if ($LASTEXITCODE -ne 0) {
            throw "npm ci failed with exit code $LASTEXITCODE."
        }
    }

    & $npm.Source 'run' 'dev'
    if ($LASTEXITCODE -ne 0) {
        throw "Frontend exited with code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}
