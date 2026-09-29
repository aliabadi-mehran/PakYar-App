# Release build procedure

PakYar Release credentials are local-only and are deliberately excluded from source control.

1. Keep the organization or owner-controlled signing key outside this repository.
2. In PowerShell at the project root, run `./build-signed-release.ps1`.
3. Select the local keystore and enter the alias and passwords only in the masked prompts.
4. The script supplies `PAKYAR_KEYSTORE_PATH`, `PAKYAR_STORE_PASSWORD`, `PAKYAR_KEY_ALIAS`, and `PAKYAR_KEY_PASSWORD` to the Gradle process temporarily, then clears them.
5. Verify the generated signed artifact at `app\\build\\outputs\\apk\\release\\app-release.apk` before distribution.

Do not place signing credentials, local paths, or key files in Gradle properties, source code, Git, issue trackers, or chat.
