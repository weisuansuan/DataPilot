$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot '.env'

if (Test-Path -LiteralPath $envFile) {
    Write-Host 'A local .env already exists; it was left unchanged.'
    return
}

$templatePath = Join-Path $projectRoot '.env.example'
$content = [System.IO.File]::ReadAllText($templatePath)
foreach ($name in @('DATAPILOT_DB_ROOT_PASSWORD', 'DATAPILOT_DB_READ_PASSWORD', 'DATAPILOT_DB_SEED_PASSWORD')) {
    $password = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
    $content = [regex]::Replace($content, "(?m)^$name=.*$", "$name=$password")
}
[System.IO.File]::WriteAllText($envFile, $content, [System.Text.UTF8Encoding]::new($false))
Write-Host 'Created ignored .env with local database credentials. Secret values were not displayed.'
