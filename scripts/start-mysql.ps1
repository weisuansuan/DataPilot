$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$docker = Get-Command docker -ErrorAction SilentlyContinue
if ($docker) {
    $dockerExe = $docker.Source
} else {
    $dockerExe = Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\resources\bin\docker.exe'
    if (-not (Test-Path -LiteralPath $dockerExe)) {
        $dockerExe = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
    }
}
if (-not (Test-Path -LiteralPath $dockerExe) -and -not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker CLI is unavailable. Install/start Docker Desktop and rerun scripts/check-environment.ps1.'
}

$envFile = Join-Path $projectRoot '.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    & (Join-Path $PSScriptRoot 'setup-local-env.ps1')
}

Push-Location $projectRoot
$previousPath = $env:PATH
if ($dockerExe -and (Test-Path -LiteralPath $dockerExe)) {
    $env:PATH = "$(Split-Path -Parent $dockerExe);$env:PATH"
}
try {
    & $dockerExe compose up -d --wait mysql
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL Compose startup failed with exit code $LASTEXITCODE. See docs/troubleshooting.md."
    }
}
finally {
    $env:PATH = $previousPath
    Pop-Location
}
