# PakYar / پاک‌یار

PakYar is a Persian, elderly-first Android application that helps people review accessible media and files before choosing what to remove. It is designed around large readable typography, RTL support, clear permission explanations, and deliberate deletion confirmation.

## Highlights

- Accessible Persian Material 3 interface using Vazirmatn
- Storage overview and category-based file review
- Local image and video thumbnails, list and grid views, filtering, and selection totals
- Least-privilege Android media and folder access with understandable status
- Review-first deletion flow with confirmation and result reporting
- Insets-aware layouts and large touch targets for easier use

## Android support

- Package name: `ir.mehran.pakyar`
- Minimum SDK: 26 (Android 8.0)
- Target SDK: 37
- Compile SDK: 37

## Build requirements

Use a supported JDK (Android Studio's bundled JBR is suitable) and an Android SDK with platform 37 installed. The Gradle Wrapper is included.

Build a Debug APK:

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon
```

Run JVM unit tests:

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```

The Debug APK is written under `app\build\outputs\apk\debug\`.

## Release signing

Release signing credentials and signing keys are intentionally not stored in Git. A locally owned signing key is required to build a Release APK. The included `build-signed-release.ps1` obtains the required signing values interactively and exposes them only as temporary process environment variables for Gradle.

Expected Release APK output:

`app\build\outputs\apk\release\app-release.apk`

Never commit a keystore, password, signing-properties file, or local SDK configuration.
