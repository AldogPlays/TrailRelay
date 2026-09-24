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

TrailRelay has two trail sources:

* IMPORTED — GPX selected by the user from local storage
* COMMUNITY — GPX downloaded from the TrailRelay community catalog

Community uses the static TrailRelay-Trails catalog and hosted GPX files. A Community
GPX can be previewed transiently before download; downloading saves it through
TrailStore. For map display, use a valid local GPX immediately before considering
any Community network fetch.

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

IMPORTED and COMMUNITY records use the same Trail model.

## Community catalog

The catalog is static and backend-free:

GitHub repository
→ individual trail metadata + GPX files
→ generated catalog.json
→ GitHub Pages/static hosting
→ TrailRelay downloads catalog
→ search/filter locally
→ user downloads chosen GPX

Catalog entries can be searched and filtered locally. Preview GPX files are
transient until the user downloads a route into TrailStore.

Do not add:

* accounts
* authentication
* upload APIs
* custom backend
* server database

## Map UI

Map is Home. Normal launch enters Browse mode, which shows locally saved trails.
Selecting a route enters Selected Trail mode and hides unrelated route overlays.
Community Preview uses the same map selection experience without presenting the
route as saved. Taps on overlapping routes use a chooser.

Top-level destinations are Map, My Trails, Community, Downloads & Storage, and
Settings. Settings currently contains Keep screen awake.

Keep it uncluttered.

Preserve:

* USGS aerial imagery
* GPS/location indicator
* initial centering
* manual panning
* recenter/follow

Trail selection is available from the map and trail lists. Do not make precise
tapping of thin trail lines the only way to select a trail.

## Offline maps

Offline USGS aerial imagery uses MapLibre offline regions:

selected GPX trail
→ determine geometry/bounds
→ download useful USGS map coverage
→ persist it
→ work with networking disabled

Downloads provide visible progress and support pause, resume, and deletion.
Downloads & Storage manages GPX route files and aerial packages as separate
resources, including separate removal actions and available size information.
Keep MapLibre's offline region architecture rather than inventing tile storage.

## Architecture

Keep responsibilities small and understandable.

Expected areas:

* map/
* location/
* trails/
* offline/

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

## UI layout guardrails

* Keep Views/XML with Material 3; no Compose.
* Map is edge-to-edge. Inset overlay controls, never the map itself.
* List/detail screens use the shared Material toolbar/app-bar content shell.
* App-bar surfaces continue through the status-bar region; no fake spacer views.
* Apply system-bar insets once; avoid duplicated root and toolbar padding.
* No hamburger or navigation drawer without an explicit future product decision.
* Map application navigation uses the Explore modal bottom sheet.
* Use shared spacing resources and preserve 48dp minimum effective touch targets.
* Community keeps a fixed toolbar/status-bar shell above one compact Search + Filter row that scrolls away with results.
* Never fetch Community GPX for map display when a valid local GPX exists.
* Downloads & Storage manages route files and aerial packages as separate resources.
* Map selection uses the persistent, edge-attached bottom sheet; avoid floating information cards.
* A selected trail starts collapsed; its content-sized details state stops around half the usable screen height and scrolls internally when needed.
* Main-map contextual controls must account for each other's bottom/system insets.
* Long-running user actions retain visible progress and state feedback.
* Major UI changes require physical-device visual verification before Git approval; Gradle alone is insufficient.

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

Release signing uses the existing identity and the process in `docs/releasing.md`.

## Definition of done

A normal coding task is complete when:

* requested behavior is implemented
* appropriate compilation/build validation passes
* unrelated features were not added
* files changed are summarized
* physical-device checks are clearly listed

Successful compilation is not proof of runtime behavior.

## Routine milestone Git workflow

When the developer describes a new TrailRelay milestone in plain English while working from this repository, Codex manages the routine local Git setup.

Before modifying files for a new milestone, inspect:

    git status
    git branch --show-current
    git log --oneline --decorate -8

If the working tree contains unexpected uncommitted changes, stop and explain the situation. Do not stash, reset, discard, or overwrite those changes.

When the working tree is clean, normally ensure development starts from `main`. If the current branch is not `main`, switch to the existing local `main` branch without changing or deleting any work. Update `main` with:

    git pull --ff-only

If `main` cannot be fast-forwarded cleanly, stop and explain the problem rather than resolving history automatically. Codex does not need to ask permission to inspect repository state, switch to a clean `main` branch, fast-forward `main`, or create the milestone branch.

Create an appropriately named milestone branch automatically using:

    milestone/<short-descriptive-name>

Choose the short descriptive name from the milestone when it is obvious; do not ask the developer to name the branch in that case. Never overwrite or delete an existing branch to make room for a milestone branch. If the obvious name already exists, choose a clear unused variant or stop and explain the collision before modifying files.

During milestone development, Codex may edit files, add or remove files required by the milestone, inspect Git status/diffs/history, run the Gradle verification described below, use `adb` only according to the physical-device rules below, and make iterative fixes. Keep the milestone focused. Do not commit partial implementation work unless the developer explicitly requests an intermediate commit.

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

Once implementation and automated validation are complete, Codex does not commit yet. Codex reports:

1. What changed.
2. Files changed.
3. Tests and Gradle commands actually run, with their results.
4. Anything not tested or that still requires physical-device verification.
5. Exact developer commands and manual checks to perform next.
6. Git status and whether the working tree is ready to commit.

When physical-device verification is relevant, wait for the developer's result before proposing the Git workflow. If the developer reports that device testing works, or otherwise approves the completed milestone, inspect the final status and diff again. Then explain in plain English exactly what Git actions are prepared, including the intended commit, merge into `main`, push, any milestone tag, and completed local branch cleanup. Wait for explicit approval before performing any of those actions. A simple response such as `yes`, `do it`, `go ahead`, or `handle the git workflow` counts as approval for the actions just described. Do not repeatedly ask for approval during implementation.

If the developer explicitly authorizes the git workflow, inspect `git status` and the diff first, then perform only the operations appropriate to that milestone. For ordinary feature milestones, the preferred sequence is:

* commit the milestone branch
* switch to `main`
* merge the milestone branch
* push `main`
* create and push an appropriately numbered milestone tag when the milestone warrants one
* delete the completed local milestone branch

Before choosing a milestone tag number, inspect existing milestone tags and determine the next number automatically. Do not require the developer to track milestone numbers manually. Milestone tags such as `milestone-9-something` are separate from version release tags.

Never create a version release tag such as `v0.1.0`, publish a GitHub Release, or modify repository visibility unless the developer explicitly requests that specific release or publication action.

Never force-push, rewrite published history, amend an already-pushed commit unless explicitly requested, or delete remote branches or tags unless explicitly requested.

Normal milestone approval does not authorize release publication. Never create or move version tags such as `v0.1.0`, `v0.2.0`, or `v1.0.0`; publish a GitHub Release; change repository visibility; upload release APKs/AABs; or rotate or generate release signing credentials. These actions require separate explicit release-specific authorization.
