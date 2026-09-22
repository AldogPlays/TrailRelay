# Creating the signed 0.1.0 APK locally

The app uses `com.trailrelay.app`, version name `0.1.0`, and version code `1`.
The release build disables debugging and retains the existing disabled optimization.
No release signing configuration or credentials are stored in Gradle source files.
`./gradlew :app:assembleRelease` produces an unsigned APK at
`app/build/outputs/apk/release/app-release-unsigned.apk`; this is not the installable release artifact.
Debug builds and CI require no personal signing material.

## Local signing

1. Open the project in Android Studio compatible with AGP 9.4.1. Install SDK Platform 37 and use the project's JDK 17 toolchain. Let Gradle sync complete.
2. Run `./gradlew :app:testDebugUnitTest` and `./gradlew :app:assembleDebug` from the repository root.
3. Choose **Build > Generate Signed Bundle / APK > APK**, then select the `app` module.
4. Choose **Create new** for the keystore. Store it outside the repository, for example `~/TrailRelay-signing/trailrelay-release.jks`. Choose private passwords, key alias `trailrelay`, a validity of at least 25 years, and fill in the certificate identity fields. Enter passwords only in the local wizard; do not put them in commands or source files.
5. Back up the keystore securely and save its passwords and alias in a password manager. Keep this same signing key for future APK updates.
6. Continue the wizard using that keystore and alias. Choose the **release** build variant and destination `app/build/outputs/apk/signed-release/`. Finish the build. Use the wizard's **locate** link to find the signed APK, then copy it as `TrailRelay-0.1.0.apk` for eventual distribution.
7. Verify it using the installed SDK Build Tools: run `<sdk>/build-tools/<version>/apksigner verify --verbose --print-certs /path/to/TrailRelay-0.1.0.apk`, replacing the placeholders with local paths. Confirm verification succeeds and retain the certificate fingerprint for future releases.

Use the signing wizard again for later releases with the existing keystore; no checked-in signing properties are needed. Keystores, common local signing/password property files, and APK/AAB outputs are ignored. Never force-add those files or save credentials in tracked Gradle files.

These steps follow the [Android app-signing documentation](https://developer.android.com/studio/publish/app-signing).

## Physical-device checks before publishing

Install the signed APK manually on a physical device. An existing debug-signed installation cannot be updated with the release key. Use a separate device or preserve original GPX files before uninstalling the debug app; uninstalling removes its local library and downloaded imagery.

- Confirm the app name is **TrailRelay**, and check USGS imagery, location permission, GPS, follow, and recenter.
- Import a GPX, reopen the app, and confirm the trail persists in **My Trails**.
- Search/filter the community catalog, download a GPX, and open it from the local library.
- Download offline coverage, check pause/resume, then disable networking and reopen the app to verify the saved trail and imagery in the downloaded area. Check package removal afterward.

Build success alone does not verify these behaviors. After device checks, review the release notes and manually publish the signed APK under a GitHub Release tagged `v0.1.0` when ready. No workflow publishes it automatically. Increment `versionCode` for future releases.
