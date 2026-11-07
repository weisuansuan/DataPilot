$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'import-local-env.ps1')
Import-DataPilotLocalEnvironment -Exclude @('DATAPILOT_DB_ROOT_PASSWORD', 'DATAPILOT_DB_SEED_PASSWORD')

Push-Location (Join-Path $projectRoot 'backend')
$previousJavaToolOptions = [Environment]::GetEnvironmentVariable('JAVA_TOOL_OPTIONS', 'Process')
if ($previousJavaToolOptions -notmatch '(?:^|\s)-Djdk\.net\.unixdomain\.tmpdir(?:=|\s|$)') {
    $socketDirectory = Join-Path $projectRoot '.runtime\jdk-sockets'
    [void][System.IO.Directory]::CreateDirectory($socketDirectory)
    $socketOption = "-Djdk.net.unixdomain.tmpdir=`"$socketDirectory`""
    $updatedJavaToolOptions = if ([string]::IsNullOrWhiteSpace($previousJavaToolOptions)) {
        $socketOption
    } else {
        "$previousJavaToolOptions $socketOption"
    }
    [Environment]::SetEnvironmentVariable('JAVA_TOOL_OPTIONS', $updatedJavaToolOptions, 'Process')
}
try {
    & .\mvnw.cmd -B -ntp clean verify
    if ($LASTEXITCODE -ne 0) {
        throw "Backend tests or build failed with exit code $LASTEXITCODE."
    }
}
finally {
    [Environment]::SetEnvironmentVariable('JAVA_TOOL_OPTIONS', $previousJavaToolOptions, 'Process')
    Pop-Location
}

Push-Location (Join-Path $projectRoot 'frontend')
try {
    & npm.cmd run test:unit
    if ($LASTEXITCODE -ne 0) { throw "Frontend unit tests failed with exit code $LASTEXITCODE." }
    & npm.cmd run typecheck
    if ($LASTEXITCODE -ne 0) { throw "Frontend type check failed with exit code $LASTEXITCODE." }
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw "Frontend build failed with exit code $LASTEXITCODE." }
}
finally {
    Pop-Location
}
