# Build the Download Studio APK on Windows

The delivered folder is an Android Studio project. Use these exact versions to match the project configuration:

- JDK 17 or newer (JDK 17 is the configured/verified baseline for Android Gradle Plugin 8.13.x)
- Android SDK Platform 36
- Android SDK Build Tools 35.0.0 or newer
- Gradle 8.13
- Android Gradle Plugin 8.13.2 (declared in `build.gradle.kts`)
- Kotlin 2.2.10 (declared in `build.gradle.kts`)

## Option A — Android Studio

1. Install Android Studio and open SDK Manager.
2. Install **Android SDK Platform 36** and **Android SDK Build-Tools 36.0.0**.
3. Confirm Android Studio is using JDK 17 or newer.
4. Install Gradle 8.13 if Android Studio asks for a local Gradle distribution; or use the Gradle 8.13 distribution from the official Gradle downloads page.
5. Open the `DownloadStudioAndroid` folder in Android Studio.
6. Sync the project, then select **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
7. Android Studio writes the debug APK to:

   `app\build\outputs\apk\debug\app-debug.apk`

## Option B — Command line

Install JDK 17+, Android Studio/Android SDK Platform 36 and Gradle 8.13. Set `ANDROID_HOME` to your SDK folder if it is not already set, then open PowerShell in this project folder and run:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
gradle --version
gradle assembleDebug
```

Or double-click `build-apk.bat`. The script checks the SDK and Gradle versions before invoking the build.

## Option C — GitHub Actions

The project includes `.github/workflows/build-apk.yml`. Push the project to a GitHub repository, then open **Actions → Build Android APK → Run workflow**. After it completes, download the `download-studio-android-debug-apk` workflow artifact. The artifact contains the actual `app-debug.apk` created by Android's build tools.

## Install the debug APK

Transfer `app-debug.apk` to the phone and open it. If Android asks, allow installation from the file manager/browser used to open it. This is a debug-signed APK for sideload testing, not a Play Store release build.
