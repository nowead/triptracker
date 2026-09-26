param([switch]$Device)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'use_local_tools.ps1')
$root = Split-Path $PSScriptRoot -Parent
$wrapper = Join-Path $root 'gradlew.bat'
if (-not (Test-Path -LiteralPath $wrapper)) {
    [Console]::Error.WriteLine('Gradle Wrapper missing. Create the Android project first; see docs/development.md.')
    exit 1
}
Push-Location $root
try {
    $tasks = @(':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug')
    if ($Device) { $tasks += ':app:connectedDebugAndroidTest' }
    & $wrapper --console=plain @tasks
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally { Pop-Location }
exit 0
