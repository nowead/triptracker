$projectRoot = Split-Path $PSScriptRoot -Parent
$localJdk = Join-Path $projectRoot '.tools/jdk'
$localSdk = Join-Path $projectRoot '.tools/android-sdk'
if (Test-Path -LiteralPath (Join-Path $localJdk 'bin/java.exe')) { $env:JAVA_HOME = $localJdk }
if (Test-Path -LiteralPath $localSdk) { $env:ANDROID_HOME = $localSdk }
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.tools/gradle-user-home'
$env:ANDROID_USER_HOME = Join-Path $projectRoot '.tools/android-user-home'
if ($env:JAVA_HOME) { $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH }
