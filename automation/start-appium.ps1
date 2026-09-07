#Requires -Version 5.1
<#
.SYNOPSIS
    One-click Appium Server launcher (idempotent: reuses if already running).
.DESCRIPTION
    Startup script for the Fanqie automation Appium Server.
    - Probes port 4723 first to avoid duplicate instances
    - Starts appium --base-path / in background if not running
    - Polls /status endpoint until ready:true or timeout (30s)
    - Exit codes: 0=ready, 1=failed
.NOTES
    Usage: Right-click -> "Run with PowerShell", or .\start-appium.ps1 in terminal
#>

$ErrorActionPreference = 'Stop'
$Port = 4723
$BaseUrl = "http://127.0.0.1:$Port"
$StatusUrl = "$BaseUrl/status"
$TimeoutSec = 30
$PollIntervalMs = 1000

function Test-AppiumReady {
    try {
        $resp = Invoke-WebRequest -Uri $StatusUrl -UseBasicParsing -TimeoutSec 3
        $json = $resp.Content | ConvertFrom-Json
        return ($json.value.ready -eq $true)
    } catch {
        return $false
    }
}

function Test-PortListening {
    try {
        $tcp = Test-NetConnection -ComputerName 127.0.0.1 -Port $Port -WarningAction SilentlyContinue
        return $tcp.TcpTestSucceeded
    } catch {
        return $false
    }
}

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  Appium Server Launcher (Idempotent)" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host ""

# Step 1: Probe if already running
if (Test-PortListening) {
    if (Test-AppiumReady) {
        Write-Host "[OK] Appium Server already running and ready ($BaseUrl)" -ForegroundColor Green
        Write-Host "     You can run FanqieRunner in IntelliJ now." -ForegroundColor Gray
        exit 0
    } else {
        Write-Host "[WARN] Port $Port is occupied but /status not ready, waiting..." -ForegroundColor Yellow
    }
} else {
    Write-Host "[INFO] Port $Port not listening, starting Appium Server..." -ForegroundColor Cyan
}

# Step 2: Check appium command availability
try {
    $appiumVersion = & appium --version 2>&1 | Select-Object -First 1
    Write-Host "[INFO] Appium version: $appiumVersion" -ForegroundColor Gray
} catch {
    Write-Host "[ERROR] appium command not found. Install: npm install -g appium" -ForegroundColor Red
    Write-Host "         Or fix execution policy: Set-ExecutionPolicy -Scope CurrentUser RemoteSigned" -ForegroundColor Red
    exit 1
}

# Step 3: Start Appium Server in background
Write-Host "[INFO] Starting appium --base-path / (background process)..." -ForegroundColor Cyan
$processArgs = @{
    FilePath = 'appium'
    ArgumentList = @('--base-path', '/')
    WindowStyle = 'Hidden'
    PassThru = $true
    RedirectStandardOutput = "$env:TEMP\appium-stdout.log"
    RedirectStandardError = "$env:TEMP\appium-stderr.log"
}
$proc = Start-Process @processArgs
Write-Host "[INFO] Appium process started (PID: $($proc.Id))" -ForegroundColor Gray
Write-Host "[INFO] Logs: $env:TEMP\appium-stdout.log" -ForegroundColor Gray

# Step 4: Poll until ready
Write-Host "[INFO] Waiting for Appium Server ready (timeout: ${TimeoutSec}s)..." -ForegroundColor Cyan
$elapsed = 0
$ready = $false
while ($elapsed -lt $TimeoutSec) {
    Start-Sleep -Milliseconds $PollIntervalMs
    $elapsed++
    if (Test-AppiumReady) {
        $ready = $true
        break
    }
    Write-Host "." -NoNewline -ForegroundColor Gray
}
Write-Host ""

# Step 5: Output result
if ($ready) {
    Write-Host ""
    Write-Host "============================================================" -ForegroundColor Green
    Write-Host "  [OK] Appium Server is ready" -ForegroundColor Green
    Write-Host "  URL: $BaseUrl" -ForegroundColor Green
    Write-Host "  Status: /status => ready:true" -ForegroundColor Green
    Write-Host "============================================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "You can now run FanqieRunner in IntelliJ." -ForegroundColor Cyan
    exit 0
} else {
    Write-Host ""
    Write-Host "[ERROR] Appium Server not ready within ${TimeoutSec}s" -ForegroundColor Red
    Write-Host "        Check logs: $env:TEMP\appium-stdout.log" -ForegroundColor Red
    Write-Host '        Or run manually: appium --base-path /' -ForegroundColor Red
    exit 1
}
