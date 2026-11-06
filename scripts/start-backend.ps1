$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'import-local-env.ps1')
Import-DataPilotLocalEnvironment

$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $java) {
    throw 'JDK 21 is required. Install JDK 21 and open a new PowerShell terminal.'
}

$mavenWrapper = Join-Path $projectRoot 'backend\mvnw.cmd'
if (-not (Test-Path -LiteralPath $mavenWrapper)) {
    throw 'The Maven Wrapper is missing. See README.md for project setup.'
}

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

Push-Location (Join-Path $projectRoot 'backend')
try {
    & $mavenWrapper 'spring-boot:run'
    if ($LASTEXITCODE -ne 0) {
        throw "Backend exited with code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
    [Environment]::SetEnvironmentVariable('JAVA_TOOL_OPTIONS', $previousJavaToolOptions, 'Process')
}
