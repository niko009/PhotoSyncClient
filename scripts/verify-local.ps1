param(
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$UseTcpPipes
)
$ErrorActionPreference = 'Stop'
$previousJavaHome = $env:JAVA_HOME
$previousJavaOptions = $env:JAVA_TOOL_OPTIONS
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {
        throw 'Pass -JavaHome pointing to a working JDK 17 installation.'
    }
    $env:JAVA_HOME = $JavaHome
    if ($UseTcpPipes) {
        # JDK 17 PipeImpl falls back to TCP when it cannot bind an AF_UNIX
        # socket. This works around broken Windows AF_UNIX support locally.
        $unavailableSocketDirectory = Join-Path (Get-Location) '.gradle/disabled-unix-sockets/missing'
        if (Test-Path -LiteralPath $unavailableSocketDirectory) {
            throw 'The AF_UNIX fallback path must not exist.'
        }
        $env:JAVA_TOOL_OPTIONS = "$previousJavaOptions `"-Djdk.net.unixdomain.tmpdir=$unavailableSocketDirectory`"".Trim()
    }
    dotnet test server/PhotoSync.Server.slnx --verbosity minimal
    if ($LASTEXITCODE -ne 0) { throw 'Server tests failed.' }
    & .\gradlew.bat --no-daemon :android:app:testDebugUnitTest :android:app:assembleDebug :android:app:compileDebugAndroidTestKotlin
    if ($LASTEXITCODE -ne 0) { throw 'Android verification failed.' }
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:JAVA_TOOL_OPTIONS = $previousJavaOptions
    Pop-Location
}
