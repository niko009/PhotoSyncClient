$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$root = (Resolve-Path $root).Path
$gradle = Join-Path $root "gradlew.bat"
$apkPath = Join-Path $root "android\app\build\outputs\apk\debug\app-debug.apk"
$localPropertiesPath = Join-Path $root "local.properties"

function Get-AdbPath {
    $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbCommand) {
        return $adbCommand.Source
    }

    if (Test-Path $localPropertiesPath) {
        $sdkDirLine = Get-Content $localPropertiesPath | Where-Object { $_ -like "sdk.dir=*" } | Select-Object -First 1
        if ($sdkDirLine) {
            $sdkDir = $sdkDirLine.Substring("sdk.dir=".Length).Replace("\\", "\").Replace("\:", ":")
            $candidate = Join-Path $sdkDir "platform-tools\adb.exe"
            if (Test-Path $candidate) {
                return $candidate
            }
        }
    }

    throw "adb was not found in PATH or Android SDK platform-tools."
}

Write-Output "Building debug APK..."
Set-Location $root
 
$adbPath = Get-AdbPath
Write-Output $adbPath
Write-Output "Connected devices:"
& $adbPath devices -l

 