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
