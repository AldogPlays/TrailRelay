# Preparing and signing TrailRelay 0.4.0

The current release uses `versionName` **0.4.0**, `versionCode` **4**, and
application ID `com.trailrelay.app`. The upgrade baseline is **0.3.0** (code 3).
Debug builds use `com.trailrelay.app.debug` and separate app data.
Release builds disable debugging and retain the existing disabled optimization.
Gradle source files contain no release signing credentials.

Validation and signing are local. This repository has no GitHub Actions workflow;
GitHub Actions is disabled in the repository settings. No hosted CI is needed.

## 1. Build and validate locally

Run these Fish-compatible commands from the repository root:

```fish
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
git diff --check
```

If the SDK is not detected, configure `sdk.dir` in the ignored `local.properties`
or set `ANDROID_HOME` to your installed Android SDK. Release assembly produces
`app/build/outputs/apk/release/app-release-unsigned.apk`. It is not installable
as a signed update and is not the distributable APK.

## 2. Align and sign using the existing identity

The commands below use the installed SDK Build Tools 36.0.0; check this path
against your SDK installation. Align before signing:

```fish
set build_tools /home/jeffreyc/Android/Sdk/build-tools/36.0.0
mkdir -p app/build/outputs/apk/signed-release
$build_tools/zipalign -p -f 4 app/build/outputs/apk/release/app-release-unsigned.apk app/build/outputs/apk/release/app-release-aligned.apk
$build_tools/apksigner sign --ks ~/.local/share/trailrelay/keys/trailrelay-release.jks --ks-key-alias trailrelay --out app/build/outputs/apk/signed-release/TrailRelay-0.4.0.apk app/build/outputs/apk/release/app-release-aligned.apk
```

Enter passwords only at the terminal prompts. Never put them in command arguments,
environment variables, chat, or repository files. Use the existing keystore and
`trailrelay` alias; never generate a new key for an update. Do not align or otherwise
modify the signed APK after signing.

## 3. Verify package, signature, and checksum

```fish
$build_tools/zipalign -c 4 app/build/outputs/apk/signed-release/TrailRelay-0.4.0.apk
$build_tools/apksigner verify --verbose --print-certs app/build/outputs/apk/signed-release/TrailRelay-0.4.0.apk
$build_tools/aapt dump badging app/build/outputs/apk/signed-release/TrailRelay-0.4.0.apk
```

Confirm successful alignment and signature verification, package
`com.trailrelay.app`, version name `0.4.0`, version code `4`, and one signer.

Download the published 0.3.0 APK and checksum to compare the signing identity:

```fish
mkdir -p app/build/outputs/apk/upgrade-baseline
gh release download v0.3.0 --repo AldogPlays/TrailRelay --pattern 'TrailRelay-0.3.0.apk*' --dir app/build/outputs/apk/upgrade-baseline
pushd app/build/outputs/apk/upgrade-baseline
sha256sum -c TrailRelay-0.3.0.apk.sha256
popd
$build_tools/apksigner verify --verbose --print-certs app/build/outputs/apk/upgrade-baseline/TrailRelay-0.3.0.apk
```

The signer certificate SHA-256 of 0.4.0 must match the verified published 0.3.0
APK. Stop if it differs; do not uninstall the existing app to bypass a signature
mismatch. If the baseline files already exist, verify and reuse them.

Record the final signed APK size and create its checksum companion:

```fish
stat -c %s app/build/outputs/apk/signed-release/TrailRelay-0.4.0.apk
pushd app/build/outputs/apk/signed-release
sha256sum TrailRelay-0.4.0.apk > TrailRelay-0.4.0.apk.sha256
sha256sum -c TrailRelay-0.4.0.apk.sha256
popd
```

## 4. Test the upgrade on your physical device

Keep the release app installed. Uninstalling it or clearing its app data erases
saved GPX files, trail metadata, settings, and offline maps. TrailRelay Dev is a
separate app and cannot establish that the release app's data survives upgrading.

1. Connect the phone and run `adb devices`. Proceed only when its state is
   `device`; resolve `unauthorized` or `offline` first.
2. Open the installed release app and confirm it is 0.3.0. You can inspect its
   version with `adb shell dumpsys package com.trailrelay.app`. If there is no
   release app installed, install the verified baseline below and populate it
   before continuing. Do not downgrade a newer installed version.

   ```fish
   adb install app/build/outputs/apk/upgrade-baseline/TrailRelay-0.3.0.apk
   ```

3. In 0.3.0, retain at least one imported GPX and one downloaded Community trail.
   Record the saved trail count, map choice, Keep screen awake setting, and offline
   packages. Download Aerial, Hybrid, and Topo for a route if available and confirm
   those packages work with networking disabled. Retain existing legacy aerial
   packages if your app data has any; do not manufacture a legacy test by clearing
   app data. Keep original imported GPX files outside the app as a backup.
4. Install the signed candidate as an update:

   ```fish
   adb install -r app/build/outputs/apk/signed-release/TrailRelay-0.4.0.apk
   adb shell dumpsys package com.trailrelay.app
   ```

   Expect `Success`, version name 0.4.0, and version code 4. If installation fails,
   preserve the existing app and report the error; do not uninstall or clear data.
5. Open TrailRelay and confirm the saved trail count, imported and Community GPX
   files, map choice, Keep screen awake setting, and existing offline packages
   survived. Close and reopen the app and check them again.

## 5. Verify behavior before publication

- **Offline upgrade:** disable Wi-Fi and mobile data. Open both retained trails
  and switch Aerial / Hybrid / Topo inside downloaded coverage at zooms 12–16.
  Confirm existing packages still render after an app restart. A passive
  map-source status glyph may appear; downloaded content must remain usable.
- **Navigation:** outdoors, check permission, location puck and accuracy circle,
  speed, North Up / Heading Up, manual pan, follow, and recenter. Rotate through
  north and background/resume the app. Test an angled mount with Heading offset,
  restore 0°, and confirm reliable moving course is unaffected by that offset.
  Perform movement checks safely with a passenger operating the phone if driving.
- **Trail context:** select an ordinary route and check A/B markers and approximate
  distances. A loop uses one A/B marker. If available, test a disconnected-segment
  GPX: the UI must not imply a path across the gap. Weak or stale GPS should make
  trail-position context unavailable; the location puck must not snap to a route.
- **Controls and layers:** check Zoom +/−, Layers, Heading, Recenter, speed HUD,
  sheet expansion/collapse, and control placement. Confirm zoom stops at 16 and
  map choice persists across restart. Open both endpoints in an installed map app.
- **Coverage diagnostic:** enable Show offline coverage. Complete packages show
  filled squares and incomplete packages show dashed requested squares. Switch
  map modes, pan/zoom, then disable the setting and confirm the grid disappears.
- **Library and downloads:** restore networking, import/reopen another GPX, preview
  and download a Community trail, and pause/resume a map download. Using disposable
  test data, verify deleting a map package preserves its route and other map modes,
  and deleting a route preserves its map packages.
- **Route loading:** reopen the app with a populated library and confirm all routes
  appear, selection works, and nothing is stale or missing. Do not claim a measured
  speed improvement without actual before/after phone timings.

Record the phone model/Android version, installed candidate version, and pass/fail
results. Build success does not verify hardware or native MapLibre behavior.
If a check fails, keep the release unpublished and fix/rebuild/re-sign/retest.

## 6. Publish only after upgrade verification

After signing and device testing, record the results and have the maintainer
approve the release candidate. Publish the reviewed version and documentation
on `main`, create the annotated `v0.4.0` tag at that release commit, and publish
a stable GitHub Release using `docs/release-notes-0.4.0.md`.

Immediately before publication, recheck repository status, local validation,
version metadata, and signature. If a rebuild changes the APK, sign and test that
exact artifact again. Upload only the verified signed `TrailRelay-0.4.0.apk` and
its `.apk.sha256` companion, never the unsigned APK or signing files. Verify the
published asset name, byte size, and digest against the tested local artifact.
Keep README's release status consistent with the published release.
Keep GitHub Actions disabled. Never force-add the keystore, passwords, APKs, or AABs.
