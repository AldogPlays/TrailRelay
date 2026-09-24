# TrailRelay

TrailRelay is an **offline-first Android trail navigation app**, maintained by Jeffrey Conton as a solo project.

Open the app → see your location on USGS aerial imagery → import or download a GPX trail → open the trail → download offline map coverage → use it without network access.

## What works today

- USGS / The National Map aerial imagery with foreground GPS location, follow, and recenter.
- A map-first Browse view of saved GPX trails, plus a selected-trail details sheet and overlapping-route chooser.
- Local GPX import and a persistent **My Trails** library.
- Community search, filtering, route preview, and GPX downloads.
- **Downloads & Storage** for route files and offline aerial packages, with progress, pause/resume, and separate removal.
- **Settings** with Keep screen awake.
- No TrailRelay account, backend, or user API key required.

TrailRelay is Android-only, written in Kotlin with Android Views/XML and MapLibre Native. It supports Android 8.0 (API 26) and newer.

## Using it offline

Open a locally stored trail and choose **Download Offline**. Review the coverage information and start the download. Wait for **Available Offline**, then check the trail with networking disabled before heading out.

Coverage currently uses the trail's bounding box with about 1.5 km of padding on each side, at zooms 12–16. Large areas are rejected before downloading. Imagery outside the downloaded area or zoom range may need a network connection. An interrupted download can be resumed from the trail's offline screen.

## Release and safety

The current release is **0.2.0**. See the [release notes](docs/release-notes-0.2.0.md) and [GitHub Releases](https://github.com/AldogPlays/TrailRelay/releases).

TrailRelay is a solo project. Trail and catalog metadata may be incomplete, and downloaded coverage should be checked before relying on it remotely. You are responsible for checking trail legality, closures, land access, and safe navigation. Keep another navigation and safety option available; TrailRelay should not be your sole system in remote areas.

## Community trails and maps

Community trails are GPX-based. The public static catalog and trail data live in [TrailRelay-Trails](https://github.com/AldogPlays/TrailRelay-Trails), with the catalog hosted on GitHub Pages. The app searches and filters the catalog locally and saves downloaded GPX files for offline use.

Aerial imagery currently comes from USGS / The National Map's `USGSImageryOnly` service. Attribution: **USDA, USGS The National Map: Orthoimagery**. See [The National Map](https://www.usgs.gov/programs/national-geospatial-program/national-map). TrailRelay is an independent project and is not endorsed by USGS.

## Building

Use Android Studio compatible with the project's Android Gradle Plugin (currently 9.4.1), and install Android SDK Platform 37 through SDK Manager. The Gradle daemon is pinned to **JDK 17** in `gradle/gradle-daemon-jvm.properties`; the wrapper is configured to provision that toolchain when needed. Initial setup and dependency downloads require internet access.

```sh
git clone https://github.com/AldogPlays/TrailRelay.git
cd TrailRelay
```

Open the project in Android Studio and let Gradle sync finish. From the repository root:

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

On Windows, use `gradlew.bat`. The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Development testing currently primarily uses a physical Android device. An emulator is not required for these JVM tests or the debug build. Successful compilation does not verify GPS or offline behavior on a device.

GitHub Actions runs the unit tests and builds the debug APK for pushes and pull requests to `main`, without signing secrets or automatic publishing. For a signed release APK, follow the [local release instructions](docs/releasing.md).

## Architecture

Source lives under `app/src/main/java/com/trailrelay/app/`:

| Area | Responsibility |
| --- | --- |
| `map/` | MapLibre Native map, bundled USGS style, and GPX overlay |
| `location/` | Foreground GPS updates |
| `trails/` | GPX parsing/import, My Trails, and local persistence |
| `trails/community/` | GitHub Pages catalog, local search/filtering, and GPX downloads |
| `offline/` | MapLibre offline regions, trail association, and download lifecycle |

GPX is the canonical trail geometry format. SQLite (`SQLiteOpenHelper`) indexes trail metadata; original GPX files remain in app-private persistent storage. MapLibre manages downloaded imagery in its offline database.

## Project philosophy

Keep trails local, avoid account requirements, and use portable GPX files that users own. Favor an uncluttered, map-first interface and small, verified milestones over large rewrites.

## Possible next steps

This roadmap is non-binding; priorities may change:

- UI/UX polish
- Improved offline corridor planning
- A topo map option
- Trail catalog improvements
- Tracks and waypoints

## License

Copyright 2026 Jeffrey Conton.

TrailRelay source code is licensed under the [Apache License 2.0](LICENSE). The license text is reproduced from the [Apache Software Foundation](https://www.apache.org/licenses/LICENSE-2.0.txt). Third-party dependencies, imagery, and trail data retain their respective terms and attribution requirements.
