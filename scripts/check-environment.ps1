$ErrorActionPreference = 'Continue'
$projectRoot = Split-Path -Parent $PSScriptRoot
$problems = [System.Collections.Generic.List[string]]::new()

function Get-DockerExecutable {
    $command = Get-Command docker -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    $perUserDocker = Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\resources\bin\docker.exe'
    if (Test-Path -LiteralPath $perUserDocker) {
        return $perUserDocker
    }

    $standardDocker = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
    if (Test-Path -LiteralPath $standardDocker) {
        return $standardDocker
    }

    return $null
}

$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $java) {
    Write-Host 'BLOCKED  JDK: not found'
    $problems.Add('Install JDK 21 and open a new PowerShell terminal.')
} else {
    $javaVersion = (& $java.Source -version 2>&1 | Out-String)
    if ($javaVersion -match 'version "21(?:\.|"|\+)') {
        Write-Host 'READY    JDK 21'
    } else {
        Write-Host 'BLOCKED  JDK 21: installed Java version does not match'
        $problems.Add('Select JDK 21 using JAVA_HOME and PATH.')
    }
}

$node = Get-Command node -ErrorAction SilentlyContinue
if (-not $node) {
    Write-Host 'BLOCKED  Node.js: not found'
    $problems.Add('Install Node.js 22.12 or later and open a new PowerShell terminal.')
} else {
    $nodeVersion = (& $node.Source --version) -replace '^v', ''
    $nodeParts = $nodeVersion.Split('.')
    $nodeMajor = [int]$nodeParts[0]
    $nodeMinor = [int]$nodeParts[1]
    if ($nodeMajor -gt 22 -or ($nodeMajor -eq 22 -and $nodeMinor -ge 12)) {
        Write-Host "READY    Node.js $nodeVersion"
    } else {
        Write-Host "BLOCKED  Node.js $nodeVersion; Vite requires Node.js 22.12 or later"
        $problems.Add('Install Node.js 22.12 or later.')
    }
}

$npm = Get-Command npm.cmd -ErrorAction SilentlyContinue
if ($npm) {
    Write-Host ("READY    npm " + (& $npm.Source --version))
} else {
    Write-Host 'BLOCKED  npm: not found'
    $problems.Add('Install the Node.js LTS package including npm.')
}

$git = Get-Command git -ErrorAction SilentlyContinue
if ($git) {
    Write-Host ("READY    Git " + (& $git.Source --version))
} else {
    Write-Host 'BLOCKED  Git: not found'
    $problems.Add('Install Git for Windows.')
}

$wrapper = Join-Path $projectRoot 'backend\mvnw.cmd'
if (Test-Path -LiteralPath $wrapper) {
    Write-Host 'READY    Maven Wrapper 3.9.16'
} else {
    Write-Host 'BLOCKED  Maven Wrapper: not generated'
    $problems.Add('Run the backend setup and generate Maven Wrapper.')
}

$docker = Get-DockerExecutable
if (-not $docker) {
    Write-Host 'BLOCKED  Docker CLI: not found'
    Write-Host '         Install Docker Desktop: https://docs.docker.com/desktop/setup/install/windows-install/'
    $problems.Add('Install Docker Desktop to use the MySQL and Testcontainers integration tests.')
} else {
    $clientVersion = (& $docker version --format '{{.Client.Version}}' 2>$null | Out-String).Trim()
    if ($LASTEXITCODE -eq 0) {
        Write-Host "READY    Docker CLI $clientVersion"
    } else {
        Write-Host 'BLOCKED  Docker CLI could not start'
    }

    $serverVersion = (& $docker info --format '{{.ServerVersion}}' 2>$null | Out-String).Trim()
    if ($LASTEXITCODE -eq 0) {
        Write-Host "READY    Docker Engine $serverVersion"
    } else {
        Write-Host 'BLOCKED  Docker Engine is not running or is not accessible to this user'
        $problems.Add('Open Docker Desktop, wait for the Engine to start, then re-run this script.')
    }

    $composeVersion = (& $docker compose version --short 2>$null | Out-String).Trim()
    if ($LASTEXITCODE -eq 0) {
        Write-Host "READY    Docker Compose $composeVersion"
    } else {
        Write-Host 'BLOCKED  Docker Compose plugin is unavailable'
        $problems.Add('Update or repair Docker Desktop to install its Compose plugin.')
    }
}

$identity = & git var GIT_AUTHOR_IDENT 2>$null
if ($LASTEXITCODE -eq 0 -and $identity) {
    Write-Host 'READY    Git commit identity'
} else {
    Write-Host 'BLOCKED  Git commit identity is not configured'
    $problems.Add('Configure Git identity locally in this repository before making a commit. Global Git settings were not changed.')
}

if ($problems.Count -gt 0) {
    Write-Host ''
    Write-Host 'Action required:'
    $problems | ForEach-Object { Write-Host "- $_" }
    exit 1
}

Write-Host ''
Write-Host 'Environment check passed. No credential values were read or printed.'
