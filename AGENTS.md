# TrailRelay

TrailRelay is a simple offline-first Android trail navigation app.

## Product

The core experience is:

Install APK → grant location → see yourself on aerial imagery → show real nearby trails → select a trail → Download Offline → continue using the downloaded area without network access.

This is the only product goal for v0.1.

## Platform

* Android only.
* Kotlin.
* Traditional Android Views/XML.
* MapLibre Native Android.
* Do not migrate to Compose.
* Do not add cross-platform frameworks.
* Do not add a backend.
* Do not add accounts or authentication.
* Do not require user API keys.

## Data sources

Use official U.S. Geological Survey / The National Map sources for v0.1.

Primary map:

* USGSImageryOnly
* aerial imagery
* default map

Secondary map later:

* USGSTopo

Trails:

* USGSTrails
* use real service data
* never add fake/sample trails to production behavior

Do not add other map providers unless explicitly requested.

## Architecture

Keep the project intentionally small.

Preferred responsibilities:

* MainActivity: screen orchestration only
* map/: MapLibre setup and styles
* location/: Android location handling
* trails/: USGS trail querying and trail models
* offline/: MapLibre offline pack handling

Avoid giant files, but do not introduce architecture frameworks just for abstraction.

Do NOT add:

* Hilt/Dagger
* Room
* navigation frameworks
* networking frameworks
* repository/use-case architecture
* unnecessary dependencies

Prefer Android/JDK APIs where practical.

## Location

Use Android platform location APIs.

Do not require Google Play Services for location.

Request only the permissions actually needed.

## UI

The map is the product.

Main screen should be almost entirely the map.

Initially expose only:

* location/recenter
* Trails
* selected trail information
* Download Offline

Avoid dashboards, cards everywhere, configuration screens, developer controls, onboarding flows, API-key screens, and provider settings.

## Offline

Use MapLibre's supported offline APIs before inventing custom tile storage.

For the first working implementation:

* download a simple padded bounding region around the selected trail
* keep the implementation understandable
* optimize into an adaptive corridor later

Do not build a custom tile database unless MapLibre's supported offline implementation proves insufficient.

Downloaded coverage must survive process death and application restart.

## Codex validation rules

THIS SECTION IS IMPORTANT.

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

Never wait for an emulator to boot during ordinary implementation work.

For ordinary Kotlin-only changes, prefer:

./gradlew :app:compileDebugKotlin

For changes involving resources, manifest, Gradle, or a complete milestone, use:

./gradlew :app:assembleDebug

Do not repeatedly rerun successful checks unless additional code affecting them changed.

The developer will perform real-device testing manually.

If something requires real-device validation, state what needs to be tested instead of blocking the task waiting for a device or emulator.

## APK milestones

At the end of a milestone, produce a debug APK with:

./gradlew :app:assembleDebug

Expected output:

app/build/outputs/apk/debug/app-debug.apk

Do not configure release signing until explicitly requested.

## Scope discipline

One task means one outcome.

Do not implement unrelated improvements.

Do not redesign working code during a focused task.

Do not add dependencies without explaining why the Android/JDK/MapLibre APIs already available are insufficient.

If you notice an unrelated improvement, mention it in the final summary instead of implementing it.

## Definition of done

A normal coding task is done when:

* requested behavior is implemented
* code compiles using the validation level appropriate to the change
* no unrelated behavior was added
* changed files are summarized
* any required real-device checks are listed

Successful compilation is not proof of runtime behavior. Do not claim device behavior was verified unless it actually was.
