param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'use_local_tools.ps1')
$root = Split-Path $PSScriptRoot -Parent
$missing = 0
function Check([string]$Name, [bool]$Ok, [string]$Detail) {
    if ($Ok) { Write-Host "[OK] $Name - $Detail" }
    else { Write-Host "[MISSING] $Name - $Detail"; $script:missing++ }
}
Check 'Git' ([bool](Get-Command git -ErrorAction SilentlyContinue)) 'git on PATH'
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java -ErrorAction SilentlyContinue).Source }
$javaOk = $false
if ($java -and (Test-Path -LiteralPath $java)) {
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $java
    $info.Arguments = '-version'
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::Start($info)
    $version = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    $process.Dispose()
    if ($version -match 'version "(\d+)') { $javaOk = [int]$Matches[1] -ge 17 }
    Write-Host $version.Trim()
}
Check 'JDK >= 17' $javaOk 'Select a JDK compatible with the project AGP and Gradle versions; Java 11 is insufficient.'
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
Check 'Android SDK' (Test-Path -LiteralPath $sdk) $sdk
Check 'ADB' (Test-Path -LiteralPath (Join-Path $sdk 'platform-tools/adb.exe')) 'Install Android SDK Platform-Tools.'
foreach ($file in @('gradlew.bat', 'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties', 'app/build.gradle.kts')) {
    Check $file (Test-Path -LiteralPath (Join-Path $root $file)) 'Required Android project file'
}
if ($missing -gt 0) { Write-Host "$missing prerequisite(s) missing. See docs/development.md."; exit 1 }
Write-Host 'Basic prerequisites found. Run build_and_test.ps1 to verify compatibility.'
exit 0
