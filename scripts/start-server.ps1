param(
    [string]$Url = "http://0.0.0.0:5187"
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $root "output"
$pidFile = Join-Path $outputDir "server.pid"

New-Item -ItemType Directory -Force -Path $outputDir | Out-Null

if (Test-Path $pidFile) {
    $existingPid = Get-Content $pidFile | Select-Object -First 1
    if ($existingPid -and (Get-Process -Id $existingPid -ErrorAction SilentlyContinue)) {
        Write-Output "Server is already running with PID $existingPid."
        exit 0
    }

    Remove-Item $pidFile -Force
}

$projectPath = Join-Path $root "server\PhotoSync.Server\PhotoSync.Server.csproj"
$dllPath = Join-Path $root "server\PhotoSync.Server\bin\Debug\net10.0\PhotoSync.Server.dll"

& dotnet build $projectPath | Out-Null
if (-not (Test-Path $dllPath)) {
    throw "Built server DLL not found at $dllPath"
}

$process = Start-Process dotnet `
    -ArgumentList @($dllPath, "--urls", $Url) `
    -WorkingDirectory $root `
    -PassThru `
    -WindowStyle Hidden

Start-Sleep -Milliseconds 500
if ($process.HasExited) {
    throw "Server process exited immediately."
}

Set-Content -Path $pidFile -Value $process.Id
Write-Output "Server started with PID $($process.Id) on $Url"
