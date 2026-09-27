# Creating a signed TrailRelay release APK

The current release is `0.3.0` (`versionCode` 3) with application ID
`com.trailrelay.app`. Debug builds use `com.trailrelay.app.debug`.
Release builds disable debugging and retain the existing disabled optimization.
Gradle source files contain no release signing credentials.

`./gradlew :app:assembleRelease` produces
`app/build/outputs/apk/release/app-release-unsigned.apk`. This unsigned file is
not the distributable APK.

## Local signing with the existing identity

1. Run `./gradlew :app:testDebugUnitTest`, `./gradlew :app:assembleDebug`, and `./gradlew :app:assembleRelease` from the repository root.
2. Locate the installed SDK Build Tools using `sdk.dir` in `local.properties`. The commands below use the installed `36.0.0` tools; check the path for future releases.
3. Align the unsigned APK before signing, then sign interactively with the existing keystore and `trailrelay` alias. Run these Fish-compatible commands from the repository root:

```fish
set build_tools /home/jeffreyc/Android/Sdk/build-tools/36.0.0
mkdir -p app/build/outputs/apk/signed-release
$build_tools/zipalign -p -f 4 app/build/outputs/apk/release/app-release-unsigned.apk app/build/outputs/apk/release/app-release-aligned.apk
$build_tools/apksigner sign --ks ~/.local/share/trailrelay/keys/trailrelay-release.jks --ks-key-alias trailrelay --out app/build/outputs/apk/signed-release/TrailRelay-0.3.0.apk app/build/outputs/apk/release/app-release-aligned.apk
```

Enter passwords only at the terminal prompts. Do not put them in command arguments, environment variables, chat, or repository files. Never generate a new key for an update.

4. Verify without modifying the signed APK:

```fish
$build_tools/zipalign -c 4 app/build/outputs/apk/signed-release/TrailRelay-0.3.0.apk
$build_tools/apksigner verify --verbose --print-certs app/build/outputs/apk/signed-release/TrailRelay-0.3.0.apk
$build_tools/aapt dump badging app/build/outputs/apk/signed-release/TrailRelay-0.3.0.apk
```

Confirm package `com.trailrelay.app`, version name `0.3.0`, version code `3`, one signer, and a signer certificate SHA-256 matching the published `v0.2.0` APK. Never run alignment in modification mode after signing.

5. Record the exact signed APK byte size and create the checksum companion:

```fish
stat -c %s app/build/outputs/apk/signed-release/TrailRelay-0.3.0.apk
pushd app/build/outputs/apk/signed-release
sha256sum TrailRelay-0.3.0.apk > TrailRelay-0.3.0.apk.sha256
popd
```

Publish only the verified signed `TrailRelay-0.3.0.apk` and its `.apk.sha256` companion. Commit release version/documentation changes, push `main`, and then create and push the annotated `v0.3.0` tag at that commit. Publish a stable GitHub Release using the release notes below and verify its asset name, size, and digest against the local artifact.

Keep the keystore and passwords outside the repository. Signing files, APKs,
and AABs are ignored by Git; never force-add them. Use the same release signing
identity for every update.

## Physical-device checks before publishing

Install the signed APK on a physical device. The debug and release package IDs
have separate app data; do not uninstall either app to test the other.

- Check location permission, GPS speed, North Up / Heading Up, pan, follow, recenter, and saved trail selection. Open both endpoints in a map app.
- Switch Aerial / Hybrid / Topo and confirm the map choice survives restart; expand and collapse the selected-trail sheet and check control placement.
- Import and reopen a GPX, and preview then download a Community trail.
- Download each map mode for a saved route, pause and resume, and confirm each works without networking inside its corridor at zooms 12–16. Check existing aerial packages after upgrading.
- Confirm Downloads & Storage removes routes and each map-mode package separately while preserving the others.
- Confirm Keep screen awake persists and changes display timeout behavior.

Build success does not verify these device behaviors. Publish the verified APK
under the matching version tag using `docs/release-notes-0.3.0.md`.
