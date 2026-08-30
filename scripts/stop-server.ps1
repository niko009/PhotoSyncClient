$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$pidFile = Join-Path $root "output\server.pid"

if (-not (Test-Path $pidFile)) {
    Write-Output "No PID file found. Server is not tracked as running."
    exit 0
}

$serverPid = Get-Content $pidFile | Select-Object -First 1
if (-not $serverPid) {
    Remove-Item $pidFile -Force
    Write-Output "PID file was empty. Cleared."
    exit 0
}

$process = Get-Process -Id $serverPid -ErrorAction SilentlyContinue
if ($null -eq $process) {
    Remove-Item $pidFile -Force
    Write-Output "Process $serverPid is not running. Cleared stale PID file."
    exit 0
}

Stop-Process -Id $serverPid -Force
Remove-Item $pidFile -Force
Write-Output "Stopped server process $serverPid."
