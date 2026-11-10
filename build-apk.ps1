$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

if (-not $env:ANDROID_HOME -and (Test-Path "$env:LOCALAPPDATA\Android\Sdk")) {
    $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
}
if (-not $env:ANDROID_SDK_ROOT -and $env:ANDROID_HOME) {
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
}
if (-not $env:ANDROID_HOME) {
    throw 'Android SDK not found. Install Android Studio and SDK Platform 36, then set ANDROID_HOME.'
}
if (-not (Test-Path (Join-Path $env:ANDROID_HOME 'platforms\android-36\android.jar'))) {
    throw "Android SDK Platform 36 is missing under $env:ANDROID_HOME. Install it with Android Studio SDK Manager."
}
$gradle = Get-Command gradle -ErrorAction SilentlyContinue
if (-not $gradle) {
    throw 'Gradle 8.13 was not found on PATH. Install Gradle 8.13 or open the project in Android Studio and choose Build > Build APK(s).'
}
& gradle --version
if ($LASTEXITCODE -ne 0) { throw 'Gradle could not start.' }
& gradle assembleDebug
if ($LASTEXITCODE -ne 0) { throw 'APK build failed. Check JDK 17+, Gradle 8.13 and Android SDK Platform 36.' }
$apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'
if (Test-Path $apk) { Write-Host "APK created: $apk" } else { throw 'Gradle completed but the expected APK was not found.' }
