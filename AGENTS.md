# TrailRelay

TrailRelay is a simple local-first, offline-first Android trail navigation app.

## Core product

The primary experience is:

Install APK → grant location → see yourself on USGS aerial imagery → browse/import a trail → open the trail → download map coverage around it → continue using it without network access.

Favor simplicity over configurability.

Do not turn TrailRelay into a generic GIS application.

## Platform

* Android only for now.
* Kotlin.
* Android Views/XML.
* MapLibre Native Android.
* Do not migrate to Compose.
* Do not add cross-platform frameworks.
* Do not add a backend.
* Do not add accounts or authentication.
* Do not require user API keys.

## Maps

USGS / The National Map supplies the map imagery.

Primary map:

* USGSImageryOnly
* aerial imagery
* default map

Potential secondary map later:

* USGSTopo

Do not add other map providers unless explicitly requested.

## Trail architecture

TrailRelay trails are GPX-based.

GPX is the canonical portable trail geometry format.

TrailRelay has two conceptual trail sources:

* IMPORTED — GPX selected by the user from local storage
* COMMUNITY — GPX downloaded from the TrailRelay community catalog

The COMMUNITY source will be implemented later.

Do not use USGS Trails as the primary TrailRelay trail catalog.

USGS trail-overlay experimentation exists separately and should not influence the core GPX architecture.

## Local-first philosophy

A downloaded or imported trail must remain usable without network access.

Prefer:

1. local trail metadata
2. locally stored GPX files
3. downloaded map coverage
4. network resources only when needed

The app should remain useful without an account or backend.

## GPX files

Original GPX files should be stored in application-private persistent storage.

Do not store entire GPX XML documents as database blobs.

Parse GPX into an internal Trail model for rendering and indexing.

Support normal GPX track structures first:

* gpx
* trk
* trkseg
* trkpt

Support standard metadata where useful:

* name
* desc
* ele

Do not attempt to support every proprietary GPX extension unless explicitly requested.

## Trail persistence

Use SQLiteOpenHelper for TrailRelay's local trail index.

Do not introduce Room or another ORM unless explicitly requested later.

SQLite stores trail metadata and indexes.

GPX files remain ordinary files in app-private storage.

Trail records should be designed so IMPORTED and COMMUNITY trails can eventually use the same model.

## Community catalog — future architecture

The community catalog is intended to be static and backend-free initially.

Expected future design:

GitHub repository
→ individual trail metadata + GPX files
→ generated catalog.json
→ GitHub Pages/static hosting
→ TrailRelay downloads catalog
→ search/filter locally
→ user downloads chosen GPX

Do not implement this until explicitly requested.

Do not add:

* accounts
* authentication
* upload APIs
* custom backend
* server database

## Map UI

The map is the primary interface.

Keep it uncluttered.

Preserve:

* USGS aerial imagery
* GPS/location indicator
* initial centering
* manual panning
* recenter/follow

Trail selection should primarily come from the trail browser/library.

Do not make precise tapping of thin trail lines the only way to select a trail.

## Offline maps

Offline imagery will be implemented after the local GPX trail library works.

The eventual flow is:

selected GPX trail
→ determine geometry/bounds
→ download useful USGS map coverage
→ persist it
→ work with networking disabled

Prefer MapLibre's supported offline APIs before inventing custom tile-storage infrastructure.

Do not implement offline imagery unless the current task explicitly requests it.

## Architecture

Keep responsibilities small and understandable.

Expected areas:

* map/
* location/
* trails/
* offline/ later

Avoid:

* Hilt/Dagger
* repository/use-case architecture for its own sake
* service/factory abstraction layers
* unnecessary dependencies
* giant god objects

Prefer Android/JDK APIs where they are sufficient.

## Scope discipline

One task should have one measurable outcome.

Do not implement adjacent features because they seem useful.

Do not redesign unrelated working code.

Do not add dependencies without explaining why the existing Android/JDK/MapLibre capabilities are insufficient.

If an unrelated improvement is noticed, mention it rather than implementing it.

## Codex validation rules

Do not launch an Android emulator unless the task explicitly contains:

CHECKPOINT: EMULATOR TEST

Do not run:

* emulator commands
* connectedAndroidTest
* connectedCheck
* instrumentation tests
* adb install
* adb shell
* UI automation

unless explicitly requested.

Never wait for an emulator during normal development.

For Kotlin-only changes, prefer:

./gradlew :app:compileDebugKotlin

For complete milestones, resources, manifests, or Gradle changes, use:

./gradlew :app:assembleDebug

The developer performs physical-device testing manually.

Do not claim physical-device behavior was verified unless it actually was.

## APK

Milestones should produce:

app/build/outputs/apk/debug/app-debug.apk

Do not configure release signing until explicitly requested.

## Definition of done

A normal coding task is complete when:

* requested behavior is implemented
* appropriate compilation/build validation passes
* unrelated features were not added
* files changed are summarized
* physical-device checks are clearly listed

Successful compilation is not proof of runtime behavior.

## Developer Verification and Milestone Handoff

The developer is the person performing the physical-device checks and deciding when a milestone is ready to commit. The developer uses CachyOS / Arch Linux with Fish as the interactive shell. Do not provide Bash heredoc syntax. The Android project is built with Gradle from the repository root.

The normal TrailRelay workflow is to test on a real Android device with `adb`, not an emulator. Do not start or require an emulator unless the developer explicitly requests one. Automated tests and Gradle builds do not replace manual device verification for behavior that depends on Android hardware, permissions, MapLibre, document providers, networking, or persisted app state.

### Gradle verification

For a cheap Kotlin compile check when appropriate, run from the repository root:

    ./gradlew :app:compileDebugKotlin

For normal milestone validation, run:

    ./gradlew :app:testDebugUnitTest
    ./gradlew :app:assembleDebug
    git diff --check

For release-related work, also run:

    ./gradlew :app:assembleRelease

The debug APK is normally located at:

    app/build/outputs/apk/debug/app-debug.apk

### Physical-device adb workflow

Start by checking the connected device:

    adb devices

The device must appear with the state `device`. `unauthorized` means the device has not accepted the computer's USB debugging authorization, and `offline` means adb cannot communicate with it normally; resolve that before testing.

For a debug build while keeping the release build installed, use:

    adb install -r app/build/outputs/apk/debug/app-debug.apk

Debug builds use the package name `com.trailrelay.app.debug`, while release builds use `com.trailrelay.app`. The separate application IDs allow both builds to remain installed on the same device, with separate app data. The debug app is labeled `TrailRelay Dev`; the release app remains labeled `TrailRelay`.

Do not uninstall the release app when installing the debug build. If uninstalling a build is actually necessary, warn the developer explicitly first because it erases that build's app-local data, including imported and community trails, database state, and offline downloads. Use the matching package name:

    adb uninstall com.trailrelay.app.debug
    adb uninstall com.trailrelay.app

Useful manual checks include:

    adb shell am force-stop com.trailrelay.app.debug
    adb logcat

Do not invent complicated adb automation. Manual physical-device testing is the normal TrailRelay workflow.

Automated tests do not replace manual device verification for:

* GPS/location permission and location display
* follow/recenter behavior
* MapLibre rendering
* GPX import through Android's document picker
* persistence across app restart
* Community catalog networking/downloads
* offline imagery downloads
* actual offline use after networking is disabled

When a milestone changes one of these areas, the final report must state exactly what the developer should test on the phone, including the relevant setup and expected result where useful.

### Milestone completion protocol

At the end of every milestone, Codex reports:

1. What changed.
2. Files changed.
3. Tests and Gradle commands actually run, with their results.
4. Anything not tested or that still requires physical-device verification.
5. Exact developer commands and manual checks to perform next.
6. Git status and whether the working tree is ready to commit.

Codex must not automatically commit, merge, tag, push, delete branches, create GitHub releases, or otherwise change repository history or remotes unless the developer explicitly authorizes the git workflow. Once implementation and verification are complete, end the milestone with one short optional handoff: "If everything looks good after your device test, I can handle the git workflow (commit, merge, tag, push, and branch cleanup) if you want." Do not repeatedly ask this during implementation.

If the developer explicitly authorizes the git workflow, inspect `git status` and the diff first, then perform only the operations appropriate to that milestone. For ordinary feature milestones, the preferred sequence is:

* commit the milestone branch
* switch to `main`
* merge the milestone branch
* push `main`
* optionally create and push a milestone tag if the milestone warrants one
* delete the completed local milestone branch

Never create a version release tag such as `v0.1.0`, publish a GitHub Release, or modify repository visibility unless the developer explicitly requests that specific release or publication action.
