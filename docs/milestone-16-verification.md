# Milestone 16: Map usability and trail context

Baseline: `v0.3.0`. Branch: `milestone/map-usability-trail-context`.
Physical-device verification was approved before the final Git publication workflow.
The staged test instructions and earlier handoff notes below are retained as a
record of how this milestone was verified; they are not current approval gates.

## Heading investigation

The installed MapLibre 13.6.1 AAR's `classes.jar` was inspected with `javap -c -p`.
This verifies the dependency actually used by the app, rather than assuming the
current upstream implementation matches. The Maven sources JAR was unavailable.

The existing pipeline was:

1. `LocationComponentCompassEngine` uses Android `TYPE_ROTATION_VECTOR` (11).
   If unavailable, it falls back to accelerometer (1) plus magnetic field (2).
2. Android's rotation matrix gives a magnetic-north reference. The SDK remaps it
   for display rotations 0/90/180/270 using X/Y, Y/-X, -X/-Y, and -Y/X respectively.
   It also remaps tilted phones (pitch beyond ±45 degrees, or inverted roll).
   This transformation is retained; adding another screen-rotation correction
   would double-correct it.
3. `getOrientation()[0]` becomes degrees, generally -180..180. The SDK emits at
   most once per 500 ms. Its fallback sensor vectors use a 0.45 low-pass factor;
   the rotation-vector path uses the platform's fusion. At the original baseline,
   TrailRelay added no filter.
4. `TRACKING_COMPASS` forwards this value through `feedNewCompassBearing` to the
   camera bearing animator. The animator chooses the short rotation and normally
   interpolates over 500 ms. There was no declination correction in this path.
   North Up uses `TRACKING_GPS_NORTH`, which keeps the camera bearing at zero.

The map's zero bearing is geographic north. Passing magnetic azimuth directly
therefore introduces the local declination difference. This is a concrete
reference mismatch, but does not prove it explains all of the reported phone
offset: calibration, nearby magnetic materials, sensor fusion and animation lag
can still affect the physical result.

The new `TrueNorthCompass` wraps the SDK engine once. Both the compass puck and
the camera receive `normalize(magneticHeading + declination)` in [0, 360).
Declination comes from Android `GeomagneticField`, using actual fix latitude,
longitude, available finite altitude (otherwise zero), and current wall-clock
time. It updates with usable location fixes. Until a location is available the
engine falls back to magnetic heading and diagnostics say declination is
unavailable. The most recent derived declination is retained between fixes.
There is no device-specific offset or constant declination.

Style activation checks whether the compass is already wrapped, preventing
double correction. Existing follow state, recenter behavior, sensor/display
remapping, and SDK camera smoothing remain in use.

Sources: [Android orientation sensors](https://developer.android.com/develop/sensors-and-location/sensors/sensors_position),
[GeomagneticField](https://developer.android.com/reference/android/hardware/GeomagneticField),
[MapLibre camera bearing](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.camera/-camera-position/index.html).

Debuggable builds log at most once every two seconds with `TrailRelayHeading`:
raw magnetic azimuth after SDK display remapping, declination, corrected heading,
display rotation (Android constants 0/1/2/3), camera target, sampled map bearing,
and compass accuracy. `mapBearing` is the actual camera position at logging time;
it can lag `cameraTarget` while the SDK animates. `cameraTarget=inactive` means
Heading Up is not currently following. Accuracy 0 means unreliable, 1 low,
2 medium, and 3 high; the SDK reports the active sensor's status. No coordinates
are logged by this diagnostic. Release builds do not emit this tag.

## Final heading stability pass

The live path is the MapLibre rotation-vector compass (or accelerometer/magnetic
fallback), SDK display/tilt remapping and 500 ms sample cap, geomagnetic
declination to true north, user mount offset on compass only, then AUTO selection
between compass and reliable movement course. Previously every accepted source
bearing went directly to MapLibre's short-arc 500 ms camera animator; TrailRelay
did not filter or suppress small heading changes. The fallback compass sensor
path already has SDK low-pass filtering, while the rotation-vector path uses
Android sensor fusion.

Now a numeric circular filter runs **after** AUTO source selection. It uses the
shortest signed angular difference, so crossing 359°/0° never sweeps through
south. For differences below 6°, it applies gain 0.30 for compass or 0.40 for
course. From 6° to under 20°, gain is 0.65 or 0.75 respectively. At 20° and
above, gain is 1.0, leaving MapLibre's existing 500 ms camera animation as the
only turn smoothing. A 1.5° visual deadband suppresses camera updates while the
internal estimate continues to accumulate a sustained small turn. Source changes
use at least 0.8 gain from the displayed bearing and the same shortest arc; a
large disagreement corrects immediately into the SDK animation. Invalid source
samples leave the filter state unchanged, and pause/reset clears it. A deliberate
change of Heading offset reseeds the filter immediately. No hidden static
alignment correction was added, and course bearing never receives the mount
offset. MapLibre camera animation settings were left unchanged because the
filter's fast-turn path adds no delay and its deadband reduces redundant animator
restarts.

Debug builds log at most once per two seconds, including rejected/deadbanded
samples: raw magnetic heading, true heading, offset, course, selected source and
reason, pre-filter target, filtered estimate, angular delta, deadband, ages,
accuracy, final camera target, and observed map bearing. No coordinates are logged.

## Geometry and quality rules

`PreparedRoute` retains great-circle edges, tangent vectors and cumulative
distances, using the existing GPX horizontal-distance radius (6,371,008.8 m).
Preparation happens once for each selected geometry object. Each accepted fix
scans the prepared edges without per-edge allocations or GPX parsing. UI timer
refreshes reuse the projection and only reevaluate freshness.

Projection considers positions between vertices and edge endpoints. Segment gaps
remain gaps. Zero-length edges and isolated points cannot win a projection.
Invalid coordinates and ambiguous antipodal edges do not establish connectivity.

The GPX parser preserves track segments and rejects invalid input coordinates.
Every segment is conservatively treated as a separate connected component, even
if two segments touch. A is the first usable segment's first point; B is the last
usable segment's last point. Only endpoints in the projected point's component
receive along-route distances. Cumulative recorded length excludes gaps and is
not used to invent connectivity. Ties at crossings use the first nearest edge in
GPX order; the app does not infer travel direction.

Endpoint marker placement reuses `TrailEndpoints`. Endpoints within 10 m produce
one A/B marker. A loop still has two ordered along-route distances to the shared
endpoint (one towards each end of the stored geometry). Local letter bitmaps
avoid any glyph download, including offline style reloads.

Context requires a fix aged 0–15 seconds with horizontal accuracy >0 and ≤25 m.
It clears on location failure/permission loss and on pause. The existing five
second HUD refresh removes stale readings even when no new fix arrives.
Distance within max(10 m, reported accuracy) reads “Near trail”. Other distances
use approximate wording: 50-foot steps with accuracy ≤15 m, otherwise 100-foot
steps, then tenths of a mile from 0.1 mile upward. Very short endpoint distances
read “Within 50 ft” or “Within 100 ft”. GPS uncertainty is not a guarantee, and
these numbers are informational. The real location puck is never snapped.

Zoom uses MapLibre's tracking-aware camera zoom when following, and camera zoom
updates otherwise. Buttons are 48 dp within the existing lower-right group, so
they inherit MapShell's position-driven fade, stable resting coordinates and
hidden touch behavior. No new animation system is introduced.

Optional elevation was deferred: location altitude availability alone does not
establish a reliable elevation display. The installed SDK exposes no built-in
scale-bar UI; adding a plugin or custom scale calculation was deferred.

## Developer verification

From the repository root, in Fish:

```fish
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
git diff --check
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -v time 'TrailRelayHeading:D' '*:S'
```

Install only when the device state is `device`. Launch **TrailRelay Dev**; the
release installation and its data remain separate. No emulator or automated
device tests are part of this milestone.

1. **Heading:** obtain a location fix, activate Heading Up and compare against
   known geographic directions outdoors, away from magnetic mounts. Slowly rotate
   through N/E/S/W and across 359/0, with the phone flat and at normal viewing tilt.
   Try both landscape rotations if supported. Inspect raw + declination modulo
   360 against corrected/target heading, then let the camera settle and compare
   mapBearing. Verify the correction remains single after repeated style changes
   and background/resume. Low sensor accuracy still requires device calibration;
   software declination correction cannot cure magnetic interference.
2. **Orientation interaction:** in both orientations, pan away. First orientation
   press must show North Up in place; second must show Heading Up and recenter.
   Recenter must follow the selected orientation. North Up follows position with
   north at the top; Heading Up follows position and phone heading.
3. **Zoom:** +/− change one zoom level, pinch still works, and button zoom preserves
   target, orientation, follow and selection. Repeat while following and panned,
   near min/max zoom, and after selecting a route.
4. **Endpoints:** select an ordinary trail and inspect A/B legibility in Aerial,
   Hybrid and Topo. Browse trails must have no endpoint markers. A closed/near-loop
   must show one A/B marker. Repeat for Community preview; switch styles repeatedly
   and confirm selection, markers and context survive.
5. **Context:** select a nearby trail, expand Details and walk near/along it where
   practical. Distance from trail and A/B distances should react sensibly. A/B
   values on one continuous route approximately sum to its recorded length.
   For disconnected GPX segments, unreachable endpoints must say unavailable
   across a segment gap; the location puck stays at the reported position.
6. **Location quality:** try approximate permission or a poor indoor fix. Accuracy
   worse than 25 m must show the concise unavailable state. Disable location and
   confirm numbers disappear; leave the app without fixes past the freshness
   window and confirm stale values are removed within the five-second refresh.
   Restore precise location and verify readings recover.
7. **Offline:** first save/import a route and download imagery. Disable Wi-Fi and
   mobile data while retaining GPS. Context and markers must work using that
   geometry; switch styles with downloaded coverage. For an already loaded
   transient preview, disable networking and verify context still works.
8. **UI:** collapsed dock remains name and metadata. Details stay compact and scroll
   inside the established half-screen sheet, including large font settings. Slowly
   drag the sheet up/down: Layers, zoom, Heading and Recenter stay at resting
   coordinates, fade before overlap, cannot be tapped when hidden and return as
   clearance opens. Check portrait and landscape for control crowding.

Report physical-device results before any commit, merge, push or tag.

## Presentation polish after functional verification

The developer confirmed the milestone's functionality on a physical phone before
this presentation pass. The new visual result still needs physical approval.

Audit and focused changes:

| Area | Finding and treatment |
| --- | --- |
| Lower map controls | Independent circles lacked grouping. Use two compact, elevated rounded groups (zoom and orientation/recenter), with matching Layers surface, 48 dp cells, 24 dp icons, shared padding and ripple. Heading's existing activated state now has a visible tonal treatment. |
| Explore / speed | Preserve navigation button treatment; make speed a flat tonal information surface with scalable text height. Add tooltips to direct controls, including current orientation and map mode. |
| Selected sheet | Preserve the collapsed subtree. Use it as the shared name/distance header; place region, chips, actions and progress first, then three neutral trail-position metrics, compact metadata rows, and description last. Keep the half-screen cap and internal scroll. |
| Trail Detail | Move concise distance/difficulty/location into the header and actions above long content. Reuse section titles, body typography and two-column metadata anatomy. Replace redundant section cards with spacing. |
| My Trails / Community | Their shared compact title/metadata/chip row already matches. Retain it, along with Community's fixed toolbar and scrolling Search + Filter row. |
| Downloads & Storage | Align existing row surfaces, outlines, padding and chip spacing with trail rows. Make removal actions quiet error-colored text buttons; keep progress, GPX/map separation and operation logic unchanged. |
| Settings | Retain the simple switch/summary layout; explicitly enforce its 48 dp switch target and Material body typography. |
| Dialogs / sheets | Existing confirmations, map chooser, endpoint chooser and Explore already use Material components. Change overlap choices to Explore-style text rows. Replace filter dialog spinners with Material exposed dropdowns; preserve options, selected values, clear/apply behavior and filtering model. |
| Shared resources | Reuse existing 4/8/12/16/24 dp spacing and 12 dp corners. Add small shared styles for map cells, section headings, metric values, dropdowns and text/destructive buttons; consolidate map elevation and chip dimensions. |

No heading math, declination, projection, distance rules, endpoint semantics,
map styles, persistence, download operations, navigation destinations, app-bar
shells, inset handling, or geometry-driven control fading changed in this pass.

Visual review on **TrailRelay Dev**, at normal and increased system font size:

- Map: the two right-hand groups and Layers belong together; speed is clearly
  information; Explore remains navigation. Check icon state, tooltips, touch
  targets, shadows and spacing in portrait and landscape.
- Sheet: collapsed appearance is unchanged. Expanded content is scannable, A/B
  and From trail are distinct metrics, action hierarchy is clear, and metadata
  and long descriptions have lower priority. Check missing/stale GPS and segment
  gaps as well as normal metric values. Scroll long content and reach all actions.
- Drag the sheet slowly: controls stay fixed, fade before collision, cannot be
  tapped while invisible, and return as clearance opens. Verify the navigation
  bar surface and half-screen cap remain correct.
- Compare Trail Detail with the selected sheet, and compare My Trails, Community
  and Downloads rows. Check active progress and distinct removal actions.
- Open Explore, overlapping-route choices, map choices, endpoint choices,
  confirmations and filters. For filters, choose values, apply, reopen, clear and
  apply again; results should match the prior behavior. Check Community's fixed
  header and scrolling search row. Settings should remain a simple switch row.

Use the same installation commands above. Automated validation cannot establish
the visual result on the phone; do not commit until that result is approved.

## Responsiveness investigation and follow-up

The developer reported slow interaction and saved trails appearing late after the
polish pass. These are source-confirmed blocking paths; their relative impact on
the reported phone has not yet been measured:

- Browse used a serial `mapNotNull(store.load)` over the entire library and only
  published the resulting map after the last GPX and catalog read completed.
  One slow file therefore delayed every saved trail. The same worker also handled
  Community networking and route opening, so unrelated work could delay Browse.
- Every Browse refresh reopened and reparsed every saved GPX. Returning from a
  destination could request refresh both from the result callback and `onStart`.
  Selecting a trail then parsed it again. DB and GPX reads were already background
  work; this was repeated work and queue blocking, not main-thread GPX parsing.
- `TrailOverlay.render` converted all coordinates to GeoJSON objects on main on
  every route restoration/update. Style reloads repeated that conversion. Sources
  and layers were already created once per style, not on every GPS callback.
- `renderTrailPosition` built `PreparedRoute` on main and scanned every selected
  edge on main for fresh fixes. This does not run for Browse-only routes, but a
  long selected route competes with UI/style callbacks. `renderCard` additionally
  rescanned endpoints on each offline status update.
- `MapShell` unconditionally scheduled a full details measurement from its summary
  layout callback. Measurement toggles details visibility, requesting layout again.
  [Android's View.layout implementation](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/view/View.java)
  dispatches those callbacks for required layout even when bounds are unchanged.
  This permits a measure/layout feedback cycle. The callback now checks actual
  width/height changes. Sheet geometry and position-driven fading are unchanged.
- Offline progress notifications rebuilt the selected sheet for every event.
  Trail Detail removed/reinflated metadata rows even when their values were
  unchanged. Neither behavior is needed for progress updates.

Fixes are limited to these paths:

1. An Activity-owned `SavedGeometryCache` shares completed and in-flight parses
   between Browse, saved selection and local Community preview. Path/size/mtime
   changes invalidate an entry. Entries are pruned to the current library and
   discarded on Activity destruction. Standalone Trail Detail integrity checks,
   downloads and offline preparation still own their separate validation reads.
2. Two bounded GPX readers allow another file to finish while one is slow or
   broken. Each success publishes independently; no all-library barrier remains.
   Metadata loading and local selection no longer wait behind Community network
   work. Repeated Browse refresh requests coalesce, and unchanged tracks reuse
   the same geometry identity.
3. One render worker retains per-trail encoded GeoJSON features and prepares source data
   off main. One job runs at a time; later changes replace pending work rather than
   accumulating jobs. Compatible partial libraries can render immediately. Removed
   routes and superseded selections cannot be applied. Style callbacks restore
   retained data without GPX reads or coordinate conversion. MapLibre mutations
   remain on main. Identical source data is not resent on routine UI/location work.
4. One separate selected-context worker prepares cumulative geometry and endpoints
   once per selection and computes projections. It processes the latest fix after
   any running job, without queuing every fix. A still-fresh previous result remains
   visible while a new one is calculated, using its own fix's accuracy and age.
   Stale results and obsolete selections are suppressed. The existing three metric
   views are updated; GPS callbacks do not rebuild the full sheet or map layers.
5. Selected-sheet offline notifications coalesce to at most four updates per second;
   Browse does not rebuild a hidden sheet for each notification. Trail Detail keeps
   unchanged metadata rows. Repeated requests for the same loaded/loading map style
   are ignored; initial style selection uses the saved mode directly.
6. The shared download label is **Download Map**. Trail Detail's secondary map
   action is content-sized. No download behavior or broad styling was changed.

### Timing diagnostics

Only debuggable builds emit `TrailRelayPerf`. No coordinates, GPX paths or trail
names are logged; per-file identifiers are hashes. No frame listener is installed.

```fish
adb logcat -v time 'TrailRelayPerf:D' '*:S'
```

Phases: `activity_to_style`, `style_ready`, `browse_db`, `gpx_parse`,
`browse_first_available`, `browse_available`, `overlay_prepare`, `route_render`,
`context_prepare`, `context_project`, `location_ui`, and `sheet_measure`.
Durations are milliseconds and include the executing thread. `route_render`
measures the main-thread source/layer handoff, not the native renderer's first
drawn frame. `location_ui` and `sheet_measure` logging is throttled; the latter
includes the number of measurements since its previous log.

The September 27 phone log (22 saved routes, before the incremental encoding fix)
shows initial style readiness at 231 ms, first GPX availability at 393 ms, and all
GPX availability at 7,317 ms. Main-thread source handoffs took about 0–16 ms;
location UI processing took 1–7 ms. Warm library reloads took 57–168 ms without
new GPX parses. Style switches took 19–28 ms. These are observed phase timings,
not first-drawn-frame measurements.

That log exposed remaining repeated serialization: overlay preparation grew from
77 ms for one route to 2,070 ms for 22 routes. The final handoff followed GPX
availability by about three seconds. The worker cached Feature objects, but still
encoded every accumulated coordinate on every progressive publication. It now
caches each route's encoded features, encoding only new/replaced geometry and
joining those fragments into the collection. MapLibre still encodes all feature
content; coordinate precision, escaping, segment gaps and properties are retained.
`overlay_prepare` now reports `encodedRoutes` and collection `chars` so this can
be checked on the phone. Removed routes are evicted. `activity_to_style` is emitted
only for the first style request; previously its later values described total
Activity age, not style-switch latency.

No physical-device timings had been supplied at this follow-up stage. JVM tests verify
shared parsing, independent progress with a blocked file, cache invalidation,
obsolete-read rejection, prepared overlay reuse, and route removal; these are
correctness checks, not phone benchmarks.

Install the rebuilt debug APK using the commands above. With several saved trails
(including a large one), cold-launch while capturing logs, first with location
disabled and then enabled. Usable trails should appear as files finish rather
than waiting for the entire library. Return from My Trails/Community repeatedly:
unchanged files should not emit new `gpx_parse` timings in that Activity lifetime.
Select a long route, pan/zoom and drag the sheet while GPS updates arrive; confirm
A/B context remains responsive and expires when location becomes stale. Rapidly
switch Aerial/Hybrid/Topo and route selections, then clear selection; no obsolete
route should reappear. Check offline download progress and **Download Map** labels
in the sheet and Trail Detail. Recheck heading, markers and control fading.

These automated results alone did not authorize Git publication; the developer
subsequently approved the physical result and the Milestone 16 Git workflow.

### Persistent geometry follow-up

The developer confirmed incremental GeoJSON encoding fixed the overlay bottleneck.
Three subsequent cold processes still took approximately **6.65 s, 6.98 s, and
6.50 s** to load all 22 routes, with the first available around **0.4 s**. These
are actual phone measurements before the persistent cache. No phone measurements
for the persistent-cache APK are available yet.

`DerivedRouteCache` now stores versioned binary `GpxTrack` snapshots in the private
`files/derived-routes` directory. This intentionally survives ordinary cache
eviction, but is disposable and never canonical. Deleting it causes GPX parsing
and regeneration. GPX files and SQLite metadata remain authoritative. The existing
two-reader/in-flight memory cache loads these snapshots; there is no additional
loader/executor or selected-route preprocessing during Browse startup.

Each entry includes trail ID, canonical relative path, GPX size/mtime, and schema
version. A CRC32 over the derived payload detects corruption; the canonical GPX
is not hashed during startup. Reads validate counts, coordinates, elevations and
payload completion. Missing canonical files cannot be hidden by cache hits.
Missing, stale, incompatible or corrupt entries fall back to the existing parser.
Cache writes use temporary files and atomic rename and are best-effort. Imports,
Community downloads/repairs and successful uncached map loads populate the cache.
Route removal deletes its entry. Replacements invalidate it. Segment boundaries,
isolated points, elevations and metadata round-trip without simplifying geometry.
Schema must be bumped if the representation or parser interpretation changes.
As with the in-memory cache, an external edit preserving both size and mtime is
not detected; app-managed replacements publish a new file or identity.

New debug phases: `route_cache_hit`, `route_cache_miss`, `route_cache_invalid`,
`route_cache_read`, `route_cache_write`. `route_cache` summarizes cumulative disk
hits/misses/parse attempts for the Activity at the end of Browse loading; warm
in-memory refreshes do not add disk hits. Invalid entries count as misses.
Existing `gpx_parse` and `encodedRoutes` diagnostics remain. Existing fixed 50 ms
map-publication batching is unchanged.

Stage A: populate/migrate (from the repository root):

```fish
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.trailrelay.app.debug
adb logcat -v time -T 1 'TrailRelayPerf:D' '*:S'
```

Launch **TrailRelay Dev** manually while logging. Let all 22 routes load; expect
misses, GPX parses and successful writes for previously uncached routes. Record
`browse_first_available`, `browse_available` and the final `route_cache` summary.
Stop Logcat with Ctrl-C.

Stage B: true cold process restart, retaining the derived files:

```fish
adb shell am force-stop com.trailrelay.app.debug
adb logcat -v time -T 1 'TrailRelayPerf:D' '*:S'
```

Launch **TrailRelay Dev** manually again. Expect approximately 22 disk hits,
zero GPX parses for unchanged saved routes, and `hits=22 misses=0 reparsed=0`.
Compare Browse timings against the measured 6.50–6.98 s baseline. Verify all 22
trails appear, select a trail and check A/B markers and fresh-location context,
switch Aerial/Hybrid/Topo, clear selection, and confirm no stale/missing routes.
Repeat offline. Return the Stage B logs to measure the actual phone improvement;
JVM success is not a phone performance measurement.

### Final field-readiness test

**A. Derived geometry cache.** Stage A, populate or migrate the cache:

```fish
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.trailrelay.app.debug
adb logcat -v time -T 1 'TrailRelayPerf:D' '*:S'
```

Launch TrailRelay Dev manually and let all routes load. Record misses, writes,
`gpx_parse`, `browse_first_available` and `browse_available`. Stop Logcat with
Ctrl-C. Stage B, a new process with the same app data:

```fish
adb shell am force-stop com.trailrelay.app.debug
adb logcat -v time -T 1 'TrailRelayPerf:D' '*:S'
```

Launch again. Record `browse_first_available`, `browse_available`,
the `route_cache` summary, and any `gpx_parse` events. Earlier phone runs loaded
the full library in 6.65, 6.98 and 6.50 seconds. Stage B should normally show
about 22 hits, no unchanged-route parses, and a markedly shorter full load.
There are no post-cache phone timing numbers yet.

**B. Heading, stationary and safely moving.** Settings → Heading offset starts at
0°. In Heading Up, compare the device/mount direction with map orientation while
stopped. Try a nonzero setting for the angled mount; positive rotates the
compass-derived bearing clockwise. Reset to 0°. While safely moving, expect a
reliable course to take over after two good fixes; offset must not affect course.
Check source, age, accuracy and rejection reason in the diagnostic below. Course
requires speed ≥3 m/s to enter, stays until below 2 m/s, and requires two fix
samples to change mode. A bearing with reported accuracy >25° or a stale fix is
rejected; without a bearing-accuracy field, horizontal accuracy must be ≤15 m.
Compass requires sensor accuracy at least medium and a sample no older than 3 s.
MapLibre's built-in sensor fusion/display remapping and animation remain in use.
No mount correction is hidden in the 0° default. Rotate through north, resume
the app, and switch styles; look for staleness or flipping. Pan, press orientation
once for North Up in place, then again for Heading Up with recenter.

```fish
adb logcat -v time -T 1 'TrailRelayHeading:D' '*:S'
```

**C. Location.** Watch puck, uncertainty circle and follow camera in open sky,
under weaker GPS and after temporary signal loss. Fixes older than 20 s are not
treated as current. Fixes wider than 50 m can still update the real reported puck
and uncertainty circle but do not steer follow/recenter. Duplicate/older fixes
and blatant short-interval jumps are rejected. Selected-trail context retains its
separate stricter 15 s / 25 m rule. No projection moves the puck onto a route.

```fish
adb logcat -v time -T 1 'TrailRelayLocation:D' '*:S'
```

**D. Raster quality.** Compare Aerial, Hybrid and Topo at the same place and zoom;
compare online and downloaded offline imagery at the same scale. All bundled
sources were already correctly declared 256 px, matching the USGS services, and
all use level 16 as their maximum display detail. Offline packages use 12–16
with the identical bundled styles and pixel ratio 1. The camera previously
allowed zoom 19, well beyond the level-16 source. Camera/+ zoom now stops at 16;
at that camera zoom the 256px raster source is still limited to level 16 and may
enlarge those pixels. No style
tileSize, URLs, source maxzoom, or offline zoom limits were changed. Native imagery
resolution varies by geography and cannot be improved by overzooming. USGS
service metadata: [Aerial](https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryOnly/MapServer?f=pjson),
[Hybrid](https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryTopo/MapServer?f=pjson),
[Topo](https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer?f=pjson).

**E. Loops.** Select a closed loop and a near-closed loop. Each gets one A/B
marker and a “Loop route” heading with From trail; no two endpoint distances.
Select an ordinary route for neutral A and B distances. Nearby endpoints across
two disconnected GPX segments remain distinct, not a false loop.

**F. Network failure.** Disable network with downloaded coverage available.
There must be no large USGS error banner or repeated toast/snackbar. A small
passive cloud-status glyph may appear if tiles actually fail. Tap it for a short
explanation and Retry map; offline content should remain usable. Restore network.

**G. Offline coverage.** Enable Settings → Show offline coverage (off by default).
The map shows a small XYZ square tile grid at the current integer source zoom,
only in the viewport and only for the current Aerial/Hybrid/Topo mode. Complete
packages show subtle filled squares with solid outlines; incomplete packages show
only dashed amber squares meaning requested, not confirmed downloaded. New
packages use their stored corridor MultiPolygon only to decide which squares
intersect it; legacy packages use their native bounding rectangle. No corridor
circles are drawn. MapLibre's public Android status reports counts and whole-
region completion, not the identities of individual completed tiles. A complete
region supports highlighting its required current-zoom squares as available;
incomplete squares cannot truthfully claim download. The grid uses floor(camera
zoom + 1) for the 256px raster source on MapLibre's 512px camera scale, capped
to source/package zoom 12–16; when more than 256 candidate squares
would be needed it disappears instead of stalling. It updates on package/setting/
mode changes and camera idle, not each camera frame. Pan and zoom, switch styles,
test an incomplete package if available, and disable the toggle: overlay vanishes.

**H. Regression.** Check Zoom +/−, Layers, Heading, Recenter, speed HUD, selected
sheet, ordinary A/B routes, Community Preview, style switching, Downloads &
Storage, offline map use and map-control fading around the sheet. “Download Map”
wording remains.

No automated build verifies GPS hardware, compass calibration, imagery quality,
native MapLibre drawing or actual offline coverage on the phone. The developer
approved physical verification before the Milestone 16 Git publication workflow.
