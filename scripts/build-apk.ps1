$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$root = (Resolve-Path $root).Path
$gradle = Join-Path $root "gradlew.bat"
$apkPath = Join-Path $root "android\app\build\outputs\apk\debug\app-debug.apk"

Write-Output "Building debug APK..."
Set-Location $root
& $gradle --no-daemon :android:app:assembleDebug

if (-not (Test-Path $apkPath)) {
    throw "Debug APK was not produced at $apkPath."
}

Write-Output $apkPath
