You are responsible for implementing this Android application end-to-end. Do not stop after planning or scaffolding. Inspect the existing project, implement the complete MVP, fix build errors, run tests, build the APK, and report the exact final artifact path and SHA-256.

Do not ask me questions unless you encounter a genuinely blocking decision that cannot be resolved safely. Make reasonable engineering decisions yourself.

# Product identity

- Persian name: پاک‌یار
- English/internal name: PakYar
- Application ID/package: ir.mehran.pakyar
- Version name: 0.1.0
- Version code: 1
- Minimum Android version: Android 8.0 / API 26
- Compile SDK: use the installed API 37
- Target SDK: API 37
- Programming language: Kotlin
- UI: Jetpack Compose with Material 3
- Layout direction: RTL
- Distribution: direct sideloaded APK, not Google Play
- Internet access: forbidden and unnecessary
- Ads, analytics, accounts, cloud services, telemetry, and tracking: forbidden
- Primary language: Persian
- Offline-only application

Rename the existing sample package and files from com.example.myapplication or any other placeholder package to ir.mehran.pakyar. Remove all placeholder UI, tests, strings, and resources that are no longer needed.

# Product objective

Build an extremely simple and safe storage-cleaner application, primarily for elderly or non-technical users who receive many photos, videos, audio files, and documents through WhatsApp and Telegram.

The app must help users identify and delete accessible large or old media files without navigating Android’s complicated file manager.

The app must adapt at runtime to:

- Android version
- available storage volumes
- granted permissions
- installed messaging applications
- available storage APIs
- manufacturer-specific behavior when possible

Never hard-code behavior specifically for POCO or Xiaomi. It must work across common Android phones from Samsung, Xiaomi, Google, OnePlus, Motorola, Huawei, Honor, Nokia, and other manufacturers.

Do not assume that a specific filesystem path always exists.

# Supported applications

Detect and support these packages when installed:

- WhatsApp: com.whatsapp
- WhatsApp Business: com.whatsapp.w4b
- Telegram: org.telegram.messenger
- Telegram X: org.thunderdog.challegram

Add the required package visibility entries under `<queries>` in AndroidManifest.xml.

Recognize accessible media using package names, RELATIVE_PATH, bucket names, and known public folder patterns, including reasonable variations of:

- Android/media/com.whatsapp/WhatsApp/Media
- Android/media/com.whatsapp.w4b/WhatsApp Business/Media
- WhatsApp/Media
- WhatsApp Business/Media
- Telegram
- Telegram Images
- Telegram Video
- Telegram Audio
- Telegram Documents
- Android/media/org.telegram.messenger
- Android/media/org.thunderdog.challegram

These paths are detection hints only. Do not rely exclusively on hard-coded paths.

# Android storage strategy

Implement a capability-based storage layer rather than checking phone models.

Support these access strategies:

1. MediaStore for indexed images, videos, audio, and downloads.
2. Storage Access Framework for user-selected folders and removable storage.
3. Direct shared-storage access when All Files Access is granted.
4. Legacy storage permissions where applicable on Android 8 and 9.
5. Safe fallback behavior when access is unavailable.

Manifest permissions must be version-appropriate:

- MANAGE_EXTERNAL_STORAGE
- READ_MEDIA_IMAGES
- READ_MEDIA_VIDEO
- READ_MEDIA_AUDIO
- READ_EXTERNAL_STORAGE with an appropriate maxSdkVersion
- WRITE_EXTERNAL_STORAGE only for legacy Android versions with maxSdkVersion 28

Do not add INTERNET permission.

For Android 11 and newer:

- Explain why storage access is needed in simple Persian.
- Provide a button that opens ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION for this package.
- Detect whether Environment.isExternalStorageManager() is granted.
- If All Files Access is denied, keep the application usable through MediaStore and Storage Access Framework.
- Do not loop permission dialogs or prevent the user from reaching the app.

For Android 13 and newer:

- Request granular image, video, and audio permissions only when needed.
- Handle partial or denied access gracefully.

For Android 14 and newer:

- Correctly handle limited visual-media access.
- Do not present partial access as complete access.

For Storage Access Framework:

- Allow the user to add WhatsApp, Telegram, Download, or another folder manually.
- Persist URI permissions using takePersistableUriPermission.
- Store selected folder URIs locally.
- Allow removing or replacing an authorized folder.
- Scan DocumentFile trees safely without blocking the main thread.

Important restriction:

The app must never claim it can directly delete another application’s private cache or files inside inaccessible Android/data locations. Do not attempt exploits, root access, ADB access, accessibility abuse, or permission bypasses.

For inaccessible private cache, provide a clearly labeled Persian button that opens the installed application’s Android settings page using ACTION_APPLICATION_DETAILS_SETTINGS. Explain that the user must select Storage/حافظه and Clear cache/پاک کردن حافظه پنهان manually.

Never offer to clear app data because that could log the user out or remove important data.

# Main interface

Create a polished but extremely simple Persian RTL interface with large touch targets and readable typography.

The home screen must contain:

1. App title: پاک‌یار
2. A storage summary card showing:
    - total storage
    - used storage
    - available storage
    - a progress indicator
3. A prominent primary button:
    - «بررسی حافظه»
4. A summary after scanning:
    - number of files found
    - total accessible size
    - estimated deletable size
5. Four large category cards:
    - «ویدئوهای بزرگ»
    - «فایل‌های قدیمی»
    - «فایل‌های واتساپ»
    - «فایل‌های تلگرام»
6. A secondary section:
    - «انتخاب پوشه»
    - «پاک کردن حافظه پنهان واتساپ»
    - «پاک کردن حافظه پنهان تلگرام»
7. A small settings/about button.

Use simple icons plus text. Never rely on icons alone.

Minimum touch target size must be 48dp, preferably larger for primary actions. Use high contrast, restrained colors, and avoid dense screens.

# Scanning behavior

All scanning must happen off the main thread using coroutines.

Scan accessible:

- images
- videos
- audio
- documents
- downloaded files

For each file collect when available:

- content URI or document URI
- display name
- MIME type
- size
- last modified timestamp
- relative path
- storage volume
- inferred source application
- thumbnail or type icon
- access method
- whether deletion is currently possible

Do not load full-resolution files to create list thumbnails.

Classify sources as:

- WhatsApp
- WhatsApp Business
- Telegram
- Telegram X
- Download
- Other

Default definitions:

- Large video: 100 MB or larger
- Large non-video file: 25 MB or larger
- Old file: older than 30 days

Provide selectable age filters:

- older than 7 days
- older than 30 days
- older than 90 days
- all dates

Provide size filters and sorting:

- largest first
- oldest first
- newest first

Handle missing, zero, invalid, or unknown metadata safely.

Show scan progress and allow cancellation. Repeated scans must not create duplicate entries.

# File list and selection

Each category opens a file list.

Every row or grid item must show:

- thumbnail or file-type icon
- filename
- source application
- formatted file size
- formatted date
- selection checkbox

Provide:

- select individual files
- select all visible files
- deselect all
- preview images and videos through safe content URIs
- display the total selected size continuously
- a large bottom action button such as «حذف ۱۲ فایل و آزاد کردن ۱٫۴ گیگابایت»

Do not preselect every file automatically.

# Safe deletion

Safety is more important than convenience.

Before every deletion operation:

- show a confirmation dialog
- show the number of selected files
- show the total size
- clearly state that deletion may be permanent
- provide «انصراف» and «حذف فایل‌ها»
- visually distinguish the destructive action

Never delete automatically, silently, periodically, in the background, or immediately after scanning.

Use the correct deletion mechanism for each item:

- ContentResolver and MediaStore APIs for MediaStore items
- MediaStore.createDeleteRequest or RecoverableSecurityException flows where system confirmation is required
- DocumentsContract or DocumentFile deletion for authorized SAF documents
- direct File deletion only when legally accessible and the required permission is granted

After deletion:

- verify each deletion result
- refresh MediaStore when necessary
- remove only successfully deleted items from the UI
- show deleted count, failed count, and actual freed size
- keep failed files visible
- never report estimated size as actual freed size

Do not create a fake trash folder that duplicates files and consumes additional storage.

# State and persistence

Use a clean architecture appropriate for this project without unnecessary complexity.

Suggested layers:

- UI
- ViewModel/state
- storage repository
- permission/capability manager
- scanner
- deletion coordinator
- source classifier

Persist only:

- authorized SAF folder URIs
- user-selected age threshold
- user-selected size threshold
- onboarding completion
- optional display preferences

Use DataStore Preferences unless there is a strong reason not to.

Do not persist private filenames or scan history unnecessarily.

# Accessibility and localization

All user-facing strings must be in strings.xml and localized in Persian.

Requirements:

- RTL layout
- Persian-friendly text
- clear content descriptions
- support font scaling
- do not truncate critical actions at large font sizes
- support TalkBack
- avoid color-only meaning
- large buttons
- simple error messages with a clear next action

Persian text examples:

- «برای پیدا کردن فایل‌های حجیم، اجازه دسترسی به حافظه لازم است.»
- «بررسی حافظه»
- «در حال بررسی فایل‌ها…»
- «هیچ فایل قابل حذفی پیدا نشد.»
- «دسترسی کامل داده نشد؛ فقط فایل‌های قابل مشاهده نمایش داده می‌شوند.»
- «حذف فایل‌ها ممکن است دائمی باشد.»
- «رفتن به تنظیمات»
- «انتخاب پوشه»
- «تلاش دوباره»

Use Persian numerals only if formatting remains reliable and readable; otherwise use locale-aware formatting.

# Onboarding and permission UX

On first launch, show a short onboarding flow with no more than three screens:

1. What the app does
2. What it can and cannot access
3. Request or configure access

Explain clearly:

- The app works offline.
- Files are processed only on the device.
- Nothing is uploaded.
- The app cannot automatically clear protected private cache.
- The user always confirms deletion.

The user must be able to skip broad access and use limited functionality.

# Settings/About screen

Include:

- application version
- Android version
- device manufacturer and model for diagnostic display only
- current storage-access status
- list of authorized SAF folders
- button to manage All Files Access
- buttons to open WhatsApp and Telegram app settings
- privacy statement:
  «پاک‌یار آفلاین است و هیچ فایلی را ارسال یا آپلود نمی‌کند.»
- button to rescan storage
- button to reset app preferences without deleting user media

Do not use model/manufacturer information to determine storage behavior unless a documented capability check requires it. Displaying it for diagnostics is acceptable.

# Error handling

Handle these states explicitly:

- permission denied
- permission partially granted
- selected folder permission revoked
- storage volume removed
- file disappeared during scan
- deletion rejected by system
- deletion partially failed
- unsupported or corrupt media
- insufficient memory
- scan cancelled
- messaging application not installed
- no accessible files found

Never crash because one file cannot be read.

Do not expose technical stack traces to the user. Log useful diagnostic information locally through Logcat without including sensitive filenames when avoidable.

# Visual design

Use Material 3 and support light and dark themes.

Create an adaptive launcher icon using local vector resources. Use a simple storage/cleaning concept. Do not download copyrighted icons or images.

The UI should feel trustworthy and calm, not like an aggressive “phone booster” app.

Do not include:

- fake performance claims
- RAM boosting
- battery optimization claims
- antivirus claims
- fake progress
- advertising
- subscriptions
- gamification
- scare tactics

# Engineering constraints

- Prefer Android SDK and stable AndroidX APIs.
- Avoid unnecessary third-party dependencies.
- Use existing Gradle and Android Gradle Plugin versions unless a change is necessary.
- Do not downgrade the working build environment.
- Do not use deprecated APIs without a version-gated compatibility reason.
- Do not suppress important lint or permission warnings without justification.
- Do not add secrets, API keys, network endpoints, or remote configuration.
- Keep all code maintainable and clearly named.
- Use Git checkpoints before major implementation stages if Git is available.
- Do not overwrite unrelated user files or settings.

# Tests

Add meaningful tests for at least:

- source classification from paths
- age classification
- large-file classification
- byte-size formatting
- Android-version capability decisions
- deletion-result aggregation

If an emulator or physical device is unavailable:

- run all JVM unit tests that do not require a device
- run lint if feasible
- run assembleDebug
- clearly mark instrumentation tests as not executed

Do not create placeholder tests that only assert static text.

# Required validation

Before completion:

1. Run a Gradle clean build.
2. Run JVM unit tests.
3. Run lint or explain the exact blocking reason.
4. Run `assembleDebug`.
5. Verify that the generated APK exists.
6. Compute the APK SHA-256.
7. Check AndroidManifest.xml and confirm there is no INTERNET permission.
8. Confirm package name is ir.mehran.pakyar.
9. Confirm minSdk is 26 and target/compile SDK are 37.
10. Confirm all user-facing strings are externalized.
11. Confirm the app does not claim to delete inaccessible private cache.
12. Confirm the main screen is RTL and functional at large font scale.
13. If a connected device or emulator is available, install and launch the APK, capture Logcat errors, and fix any startup crash.
14. If no device is available, do not pretend device testing occurred.

# Completion criteria

Do not stop after writing code.

Continue autonomously through implementation, compilation, testing, error fixing, and APK generation.

The task is complete only when:

- Gradle build exits with code 0
- unit tests pass
- the debug APK exists
- the application launches if a test device is available
- no critical known issue is hidden
- limitations are explicitly reported

At the end, provide a concise report containing:

- features implemented
- Android versions supported
- permissions used and why
- tests executed with exact results
- build command and exit code
- APK absolute path
- APK size
- APK SHA-256
- whether it was installed and launched on a real device/emulator
- any remaining limitations
- exact manual test checklist for WhatsApp and Telegram on Android 12

Begin now by inspecting the current project and build configuration, then implement the application. Do not return only a plan.