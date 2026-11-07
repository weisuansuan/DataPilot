param(
    [ValidateSet('mock', 'live')]
    [string]$Mode = 'mock',
    [ValidateRange(1, 5)]
    [int]$MaxCalls = 5,
    [ValidateRange(5, 3600)]
    [int]$IntervalSeconds = 10,
    [ValidateSet('configured', 'json', 'sse')]
    [string]$StreamMode = 'configured',
    [string]$OutputDirectory = '.runtime/evaluations'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot '.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    throw 'Local .env is missing. Run .\scripts\start-mysql.ps1 first.'
}
. (Join-Path $PSScriptRoot 'import-local-env.ps1')
Import-DataPilotLocalEnvironment -Exclude @('DATAPILOT_DB_ROOT_PASSWORD', 'DATAPILOT_DB_SEED_PASSWORD')

if ($Mode -eq 'live' -and [string]::IsNullOrWhiteSpace($env:QINIU_API_KEY)) {
    throw 'Live evaluation requires QINIU_API_KEY in the backend process environment.'
}

$previousJavaToolOptions = [Environment]::GetEnvironmentVariable('JAVA_TOOL_OPTIONS', 'Process')
if ($previousJavaToolOptions -notmatch '(?:^|\s)-Djdk\.net\.unixdomain\.tmpdir(?:=|\s|$)') {
    $socketDirectory = Join-Path $projectRoot '.runtime\jdk-sockets'
    [void][System.IO.Directory]::CreateDirectory($socketDirectory)
    $socketOption = "-Djdk.net.unixdomain.tmpdir=`"$socketDirectory`""
    $updatedJavaToolOptions = if ([string]::IsNullOrWhiteSpace($previousJavaToolOptions)) { $socketOption } else { "$previousJavaToolOptions $socketOption" }
    [Environment]::SetEnvironmentVariable('JAVA_TOOL_OPTIONS', $updatedJavaToolOptions, 'Process')
}

$arguments = "--datapilot.evaluation.mode=$Mode --datapilot.evaluation.max-calls=$MaxCalls --datapilot.evaluation.interval-seconds=$IntervalSeconds --datapilot.evaluation.output-directory=$OutputDirectory --datapilot.ai.max-repair-attempts=0 --datapilot.ai.max-provider-retries=0"
if ($Mode -eq 'live' -and $StreamMode -ne 'configured') {
    $stream = if ($StreamMode -eq 'sse') { 'true' } else { 'false' }
    $arguments += " --datapilot.ai.stream=$stream"
}
$fullOutputDirectory = [System.IO.Path]::GetFullPath((Join-Path $projectRoot $OutputDirectory))
$arguments = $arguments.Replace("--datapilot.evaluation.output-directory=$OutputDirectory", "--datapilot.evaluation.output-directory=$fullOutputDirectory")
Push-Location (Join-Path $projectRoot 'backend')
try {
    & .\mvnw.cmd '-B' '-ntp' '-Dspring-boot.run.main-class=com.datapilot.ai.evaluation.Text2SqlEvaluationApplication' "-Dspring-boot.run.arguments=$arguments" 'spring-boot:run'
    if ($LASTEXITCODE -ne 0) { throw "Text2SQL evaluation exited with code $LASTEXITCODE." }
}
finally {
    Pop-Location
    [Environment]::SetEnvironmentVariable('JAVA_TOOL_OPTIONS', $previousJavaToolOptions, 'Process')
}
