function Import-DataPilotLocalEnvironment {
    param([string[]]$Exclude = @())

    $projectRoot = Split-Path -Parent $PSScriptRoot
    $envFile = Join-Path $projectRoot '.env'
    if (-not (Test-Path -LiteralPath $envFile)) {
        return
    }

    foreach ($line in Get-Content -LiteralPath $envFile) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) {
            continue
        }

        $parts = $line -split '=', 2
        if ($parts.Count -ne 2) {
            continue
        }

        $name = $parts[0].Trim()
        if ($name -notmatch '^DATAPILOT_[A-Za-z0-9_]+$') {
            continue
        }
        if ($name -in $Exclude) {
            continue
        }

        $value = $parts[1].Trim()
        if ($value.Length -ge 2 -and $value[0] -eq $value[-1] -and $value[0] -in @('"', "'")) {
            $value = $value.Substring(1, $value.Length - 2)
        }

        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
}
