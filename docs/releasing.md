# Creating a signed TrailRelay release APK

The current release is `0.2.0` (`versionCode` 2) with application ID
`com.trailrelay.app`. Debug builds use `com.trailrelay.app.debug`.
Release builds disable debugging and retain the existing disabled optimization.
Gradle source files contain no release signing credentials.

`./gradlew :app:assembleRelease` produces
`app/build/outputs/apk/release/app-release-unsigned.apk`. This unsigned file is
not the distributable APK.

## Local signing with the existing identity

1. Run `./gradlew :app:testDebugUnitTest`, `./gradlew :app:assembleDebug`, and `./gradlew :app:assembleRelease` from the repository root.
2. In Android Studio, choose **Build > Generate Signed Bundle / APK > APK** and select the `app` module.
3. Select the existing TrailRelay release keystore stored outside the repository and its existing `trailrelay` key alias. Enter credentials only in the local signing wizard. Never create or rotate the key for an update.
4. Choose the **release** variant and write the signed APK to `app/build/outputs/apk/signed-release/`. Name the distribution copy `TrailRelay-0.2.0.apk`.
5. Verify the distribution APK with SDK Build Tools: run `zipalign -c -v 4 /path/to/TrailRelay-0.2.0.apk` and `apksigner verify --verbose --print-certs /path/to/TrailRelay-0.2.0.apk`. Confirm both pass and that the certificate matches the previous release.

Keep the keystore and passwords outside the repository. Signing files, APKs,
and AABs are ignored by Git; never force-add them. Use the same release signing
identity for every update.

## Physical-device checks before publishing

Install the signed APK on a physical device. The debug and release package IDs
have separate app data; do not uninstall either app to test the other.

- Check the map, location permission, GPS, follow, recenter, and saved trail selection.
- Import and reopen a GPX, and preview then download a Community trail.
- Download aerial coverage, pause and resume it, and confirm route and imagery work without networking inside the saved area.
- Confirm Downloads & Storage removes routes and aerial packages separately.
- Confirm Keep screen awake persists and changes display timeout behavior.

Build success does not verify these device behaviors. Publish the verified APK
under the matching version tag using `docs/release-notes-0.2.0.md`.
