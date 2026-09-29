package com.trailrelay.app.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.location.Location
import android.graphics.PointF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.trailrelay.app.Perf
import com.trailrelay.app.R
import com.trailrelay.app.location.ForegroundLocation
import com.trailrelay.app.trails.GpxTrack
import com.trailrelay.app.trails.Trail
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.CompassEngine
import org.maplibre.android.location.CompassListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.tile.TileOperation
import com.trailrelay.app.offline.OfflineDownloads
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.expressions.Expression

/** Map styling, location display, and deliberately simple camera following. */
class TrailMap(
    private val context: Context,
    private val view: MapView,
    state: Bundle?,
    private val activityStarted: Long,
    initialMode: MapMode,
    private val onError: (Int?) -> Unit,
) {
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var locationStyle: Style? = null
    private var mode = initialMode
    private val styleRequests = MapModeRequests()
    private var locationAllowed = false
    private var latest: Location? = null
    private var compassEngine: CompassEngine? = null
    private var compassActive = false
    private var lastHeading: Float? = null
    private var headingTimeMillis = 0L
    private var trueNorthCompass: TrueNorthCompass? = null
    private var headingOffset = 0
    private var coverageVisible = false
    private var coverageSignature = ""
    private var coverageJson = "{\"type\":\"FeatureCollection\",\"features\":[]}"
    private var coverageRegions: List<PreparedCoverageRegion> = emptyList()
    private var coverageCandidates: List<TileCoordinate>? = null
    @Volatile private var coverageDefinitionRequest = 0
    @Volatile private var coverageTilesRequest = 0
    private var lastHeadingLog = 0L
    private val headingDiagnostics = (context.applicationInfo.flags and
        android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val compassListener = object : CompassListener {
        override fun onCompassChanged(heading: Float) {
            if (heading.isFinite()) {
                lastHeading = heading
                headingTimeMillis = SystemClock.elapsedRealtime()
            }
        }
        override fun onCompassAccuracyChange(status: Int) = Unit
    }
    private fun logHeadingSample() {
        if (!headingDiagnostics) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastHeadingLog < 2_000L) return
        lastHeadingLog = now
        val engine = trueNorthCompass ?: return
        val filter = engine.filter
        Log.d("TrailRelayHeading", "raw=${engine.rawHeading} reference=magnetic " +
            "declination=${engine.declination ?: "unavailable"} true=${engine.correctedHeading} " +
            "offset=${engine.headingOffset} course=${latest?.bearing?.takeIf { latest?.hasBearing() == true }} " +
            "source=${engine.decision.source} reason=${engine.decision.reason} " +
            "target=${filter.target} filtered=${filter.filtered} delta=${filter.angularDelta} " +
            "deadband=${filter.deadbandApplied} sensorAgeMs=${engine.sensorAgeMs} fixAgeMs=${engine.courseAgeMs} " +
            "bearingAccuracy=${latest?.let { if (android.os.Build.VERSION.SDK_INT >= 26 && it.hasBearingAccuracy()) it.bearingAccuracyDegrees else null }} " +
            "displayRotation=${view.display?.rotation} cameraTarget=" +
            (if (follow.following && follow.orientation == MapOrientation.HEADING_UP) filter.displayed else "inactive") +
            " mapBearing=${map?.cameraPosition?.bearing} accuracy=${engine.lastAccuracySensorStatus}")
    }
    private val follow = FollowState(MapOrientation.NORTH_UP,
        state?.getBoolean("following", true) ?: true,
        state?.getBoolean("northResetPending", false) ?: false)
    private var centered = state?.getBoolean("centered", false) ?: false
    private var selectedTrail: Pair<Trail, GpxTrack>? = null
    private var browseTrails: Map<String, GpxTrack> = emptyMap()
    private var browseVisible = true
    private val overlayWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val coverageWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val overlayPreparation = OverlayPreparation()
    private var overlayBusy = false
    private var preparedBrowse: Map<String, GpxTrack>? = null
    private var preparedSelected: GpxTrack? = null
    private var preparedVisible = true
    private var preparedOverlay: OverlayGeometry? = null
    private var renderedStyle: Style? = null
    private var renderedOverlay: OverlayGeometry? = null
    private var loadingMode: MapMode? = null
    private var mapErrorReported = false
    private var overlayRefreshPosted = false
    private val overlayRefresh = Runnable { overlayRefreshPosted = false; renderTrails() }
    var onTrailTap: ((List<String>) -> Unit)? = null
    var onPan: (() -> Unit)? = null
    val northResetPending get() = follow.northResetPending
    fun nextOrientationPress() = follow.nextOrientationPress()
    private var fitTrailPending = state?.getBoolean("fitTrailPending") ?: false
    private var destroyed = false
    private var offlineTrailId: String? = null
    private var trailCameraUntouched = false
    private val handler = Handler(Looper.getMainLooper())
    private val tileTimeout = Runnable {
        Log.w(TAG, "No USGS map tile loaded within 30 seconds")
        reportMapError()
    }

    init {
        view.addOnRenderErrorListener {
            Log.e(TAG, "MapLibre renderer reported an error")
            reportMapError()
        }
        view.addOnTileActionListener { operation, x, y, z, _, _, source ->
            if (style != null && source == mode.rasterSourceId) {
                if (operation == TileOperation.Error) {
                    reportMapError()
                } else if (operation == TileOperation.LoadFromNetwork ||
                    operation == TileOperation.LoadFromCache) {
                    handler.post {
                        if (!destroyed) {
                            handler.removeCallbacks(tileTimeout)
                        }
                    }
                }
            }
        }
        view.addOnDidFinishLoadingMapListener {
            if (!destroyed && style != null) {
                handler.removeCallbacks(tileTimeout)
                mapErrorReported = false
                onError(null)
            }
        }
        view.addOnDidFailLoadingMapListener { message ->
            Log.e(TAG, "USGS map load failed: $message")
            reportMapError()
        }
        view.getMapAsync { ready ->
            if (!destroyed) {
                map = ready
                ready.uiSettings.isCompassEnabled = false
                ready.uiSettings.isLogoEnabled = false
                ready.uiSettings.isAttributionEnabled = false
                ready.setMinZoomPreference(1.0)
                // USGS advertises maxScale at level 16; higher camera zoom only enlarges pixels.
                ready.setMaxZoomPreference(16.0)
                if (state == null) {
                    ready.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(39.5, -98.35), 3.0))
                }
                ready.addOnCameraMoveStartedListener { reason ->
                    if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                        follow.pan()
                        onPan?.invoke()
                        if (ready.locationComponent.isLocationComponentActivated) {
                            ready.locationComponent.cameraMode = CameraMode.NONE
                        }
                        fitTrailPending = false
                        trailCameraUntouched = false
                    }
                }
                ready.addOnCameraIdleListener { requestCoverageTiles() }
                ready.addOnMapClickListener { point ->
                    if (selectedTrail != null) false else {
                        val screen = ready.projection.toScreenLocation(point)
                        val ids = uniqueTrailHits(ready.queryRenderedFeatures(PointF(screen.x, screen.y),
                            TrailOverlay.BROWSE_HIT_LAYER).map {
                            it.getStringProperty(TrailOverlay.TRAIL_ID)
                        })
                        if (ids.isEmpty()) false else { onTrailTap?.invoke(ids); true }
                    }
                }
                loadStyle()
            }
        }
    }

    private fun reportMapError() {
        handler.post {
            if (!destroyed && !mapErrorReported) {
                mapErrorReported = true
                Log.w(TAG, "USGS map source unavailable")
                onError(R.string.map_failed)
            }
        }
    }

    fun loadStyle(requestedMode: MapMode = mode) {
        if (mode != requestedMode) {
            ++coverageDefinitionRequest
            ++coverageTilesRequest
            coverageSignature = ""
            coverageJson = "{\"type\":\"FeatureCollection\",\"features\":[]}"
            coverageRegions = emptyList()
            coverageCandidates = null
        }
        mode = requestedMode
        val ready = map ?: return
        if (loadingMode == requestedMode || style?.uri == requestedMode.styleUri) return
        loadingMode = requestedMode
        val styleStarted = Perf.start()
        val request = styleRequests.next()
        if (request > 1) {
            fitTrailPending = false
            trailCameraUntouched = false
        }
        style = null
        handler.removeCallbacks(tileTimeout)
        handler.postDelayed(tileTimeout, 30_000)
        ready.setStyle(Style.Builder().fromUri(requestedMode.styleUri)) { loaded ->
            if (!destroyed && (!styleRequests.isCurrent(request) || loaded.uri != requestedMode.styleUri)) {
                if (ready.style === loaded) {
                    loadingMode = null
                    loadStyle(mode)
                }
            } else if (!destroyed) {
                loadingMode = null
                style = loaded
                renderedStyle = null
                Perf.end("style_ready", styleStarted, "mode=${requestedMode.id} request=$request")
                if (request == 1) Perf.end("activity_to_style", activityStarted)
                Log.i(TAG, "Bundled USGS ${requestedMode.id} style loaded")
                mapErrorReported = false
                onError(null)
                updateLocationComponent()
                renderCoverage()
                renderTrails()
                if (request == 1) fitSelectedTrail()
                latest?.let(::showLocation)
            }
        }
    }

    fun retryStyle() {
        if (loadingMode != null) return
        style = null
        loadStyle(mode)
    }

    fun showTrail(trail: Trail, track: GpxTrack, fit: Boolean = true) {
        selectedTrail = trail to track
        browseVisible = false
        trailCameraUntouched = fit
        if (fit) {
            follow.suspendFollow()
            stopCameraTracking()
            centered = true
            fitTrailPending = true
        }
        renderTrails()
        fitSelectedTrail()
    }

    fun showBrowseTrails(trails: Map<String, GpxTrack>) {
        if (browseTrails.size == trails.size && trails.all { (id, track) -> browseTrails[id] === track }) return
        browseTrails = trails
        // Coalesce file completions into one preparation; the first available route is not held for the library.
        if (!overlayRefreshPosted) {
            overlayRefreshPosted = true
            handler.postDelayed(overlayRefresh, 50L)
        }
    }

    fun beginSelection() {
        selectedTrail = null
        browseVisible = false
        fitTrailPending = false
        trailCameraUntouched = false
        offlineTrailId = null
        renderTrails()
    }

    fun clearTrail() {
        selectedTrail = null
        browseVisible = true
        fitTrailPending = false
        trailCameraUntouched = false
        offlineTrailId = null
        renderTrails()
    }

    private fun renderTrails() {
        if (destroyed) return
        val browse = browseTrails
        val selected = selectedTrail?.second
        val visible = browseVisible
        val cached = preparedOverlay
        if (cached != null && preparedBrowse === browse && preparedSelected === selected && preparedVisible == visible) {
            applyOverlay(cached)
            return
        }
        if (overlayBusy) return // One running preparation; next pass uses only the newest state.
        overlayBusy = true
        overlayWorker.execute {
            val started = Perf.start()
            val data = overlayPreparation.prepare(browse, selected, visible)
            Perf.end("overlay_prepare", started,
                "browse=${browse.size} encodedRoutes=${overlayPreparation.encodedRoutes} " +
                    "chars=${data.browse.length} selected=${selected != null}")
            handler.post {
                if (!destroyed) {
                    overlayBusy = false
                    preparedBrowse = browse
                    preparedSelected = selected
                    preparedVisible = visible
                    preparedOverlay = data
                    // Publish a compatible partial library immediately, even if more files finished
                    // during preparation. Never restore removed routes or an obsolete selection.
                    if (selectedTrail?.second === selected && browseVisible == visible &&
                        browse.all { (id, track) -> browseTrails[id] === track }) applyOverlay(data)
                    // renderTrails compares identities before touching the current style.
                    renderTrails()
                }
            }
        }
    }

    private fun applyOverlay(data: OverlayGeometry) {
        val loaded = style ?: return
        val newStyle = renderedStyle !== loaded
        val old = renderedOverlay
        if (newStyle || old?.browse !== data.browse || old?.selected !== data.selected) {
            val started = Perf.start()
            TrailOverlay.render(loaded, data, newStyle || old?.browse !== data.browse,
                newStyle || old?.selected !== data.selected)
            renderedStyle = loaded
            renderedOverlay = data
            Perf.end("route_render", started, "selected=${selectedTrail != null} newStyle=$newStyle")
        }
    }

    /** Keep the initial view of a downloaded trail inside its saved zoom range.
     * A long trail may no longer fit entirely; start at its first point in that case.
     * Never override a user's gesture, recenter action, or restored camera position.
     */
    fun setOfflineTrail(id: String?) {
        offlineTrailId = id
        ensureOfflineOpeningZoom()
    }

    private fun ensureOfflineOpeningZoom() {
        val ready = map ?: return
        val selected = selectedTrail ?: return
        if (style == null || fitTrailPending || !trailCameraUntouched || selected.first.id != offlineTrailId) return
        if (ready.cameraPosition.zoom < com.trailrelay.app.offline.OfflineCoverage.MIN_ZOOM) {
            val first = selected.second.segments.firstOrNull { it.isNotEmpty() }?.first() ?: return
            ready.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(first.latitude, first.longitude),
                com.trailrelay.app.offline.OfflineCoverage.MIN_ZOOM.toDouble()))
        }
    }

    private fun fitSelectedTrail() {
        if (!fitTrailPending || style == null) return
        val ready = map ?: return
        val trail = selectedTrail?.first ?: return
        view.post {
            if (!destroyed && fitTrailPending && selectedTrail?.first?.id == trail.id) {
                fitTrailPending = false
                if (trail.minLatitude == trail.maxLatitude && trail.minLongitude == trail.maxLongitude) {
                    ready.moveCamera(CameraUpdateFactory.newLatLngZoom(
                        LatLng(trail.minLatitude, trail.minLongitude), INITIAL_ZOOM))
                } else {
                    val bounds = LatLngBounds.Builder()
                        .include(LatLng(trail.minLatitude, trail.minLongitude))
                        .include(LatLng(trail.maxLatitude, trail.maxLongitude)).build()
                    ready.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds,
                        (64 * context.resources.displayMetrics.density).toInt()))
                }
                ensureOfflineOpeningZoom()
            }
        }
    }

    fun setLocationAllowed(allowed: Boolean) {
        locationAllowed = allowed
        updateLocationComponent()
    }

    /** Returns true when a user selected Heading Up from a panned view. */
    fun setOrientation(orientation: MapOrientation, userInitiated: Boolean = false): Boolean {
        if (follow.selectOrientation(orientation, userInitiated)) {
            recenter()
            return true
        }
        if (follow.following) {
            applyCameraMode()
        } else if (orientation == MapOrientation.NORTH_UP) {
            map?.moveCamera(CameraUpdateFactory.bearingTo(0.0))
        }
        return false
    }

    fun resumeCompass() {
        compassActive = true
        subscribeCompass()
    }

    fun pauseCompass() {
        compassActive = false
        compassEngine?.removeCompassListener(compassListener)
        trueNorthCompass?.pause()
        compassEngine = null
        lastHeading = null
        headingTimeMillis = 0L
    }

    fun setHeadingOffset(degrees: Int) {
        headingOffset = degrees.coerceIn(-180, 180)
        trueNorthCompass?.setOffset(headingOffset)
    }

    fun setOfflineCoverageVisible(visible: Boolean) {
        if (coverageVisible == visible) return
        coverageVisible = visible
        if (!visible) {
            ++coverageDefinitionRequest
            ++coverageTilesRequest
            coverageSignature = ""
            coverageJson = "{\"type\":\"FeatureCollection\",\"features\":[]}"
            coverageRegions = emptyList()
            coverageCandidates = null
        }
        renderCoverage()
    }

    fun updateOfflineCoverage(packages: List<OfflineDownloads.Package>) {
        if (!coverageVisible) return
        val matching = packages.filter { it.metadata?.mapType == mode && !it.deleting }
        val signature = matching.joinToString("|") { "${it.region.id}:${it.complete}" }
        if (signature == coverageSignature) return
        coverageSignature = signature
        ++coverageTilesRequest // Any in-flight grid used the previous package snapshot.
        coverageCandidates = null
        coverageJson = "{\"type\":\"FeatureCollection\",\"features\":[]}"
        renderCoverage()
        val regions = matching.map { OfflineCoverageOverlay.Region(it.region.definition, it.complete) }
        val request = ++coverageDefinitionRequest
        coverageWorker.execute {
            if (request != coverageDefinitionRequest) return@execute
            val prepared = OfflineCoverageOverlay.prepare(regions)
            handler.post {
                if (!destroyed && request == coverageDefinitionRequest) {
                    coverageRegions = prepared
                    coverageCandidates = null
                    requestCoverageTiles()
                }
            }
        }
    }

    private fun requestCoverageTiles() {
        if (!coverageVisible) return
        val ready = map ?: return
        val bounds = ready.projection.visibleRegion.latLngBounds
        val viewport = GeoBox(bounds.getLatSouth(), bounds.getLonWest(),
            bounds.getLatNorth(), bounds.getLonEast())
        val zoom = ready.cameraPosition.zoom
        val tileZoom = OfflineTileGrid.zoom(zoom)
        val candidates = OfflineTileGrid.candidates(viewport, tileZoom)
        if (candidates == coverageCandidates) return
        coverageCandidates = candidates
        val regions = coverageRegions
        val request = ++coverageTilesRequest
        coverageWorker.execute {
            if (request != coverageTilesRequest) return@execute
            val tiles = OfflineTileGrid.covered(viewport, zoom, regions)
            val json = OfflineCoverageOverlay.geoJson(tiles)
            handler.post {
                if (!destroyed && coverageVisible && request == coverageTilesRequest) {
                    coverageJson = json
                    renderCoverage()
                }
            }
        }
    }

    private fun renderCoverage() {
        val loaded = style ?: return
        val json = if (coverageVisible) coverageJson else "{\"type\":\"FeatureCollection\",\"features\":[]}"
        val source = loaded.getSourceAs<GeoJsonSource>("offline-coverage")
        if (!coverageVisible && source == null) return
        if (source != null) { source.setGeoJson(json); return }
        loaded.addSource(GeoJsonSource("offline-coverage", json))
        fun below(layer: org.maplibre.android.style.layers.Layer) {
            val target = listOf("browse-trail-casing", "selected-trail-casing", "mapbox-location-foreground")
                .firstOrNull { loaded.getLayer(it) != null }
            if (target == null) loaded.addLayer(layer) else loaded.addLayerBelow(layer, target)
        }
        val completeFill = FillLayer("offline-coverage-complete-fill", "offline-coverage")
            .withProperties(fillColor(Color.rgb(0, 130, 110)), fillOpacity(0.08f))
        completeFill.setFilter(Expression.eq(Expression.get("complete"), Expression.literal(true)))
        below(completeFill)
        val complete = LineLayer("offline-coverage-complete", "offline-coverage").withProperties(
            lineColor(Color.rgb(0, 112, 95)), lineWidth(1.5f), lineOpacity(0.7f))
        complete.setFilter(Expression.eq(Expression.get("complete"), Expression.literal(true)))
        below(complete)
        val incomplete = LineLayer("offline-coverage-incomplete", "offline-coverage").withProperties(
            lineColor(Color.rgb(183, 103, 0)), lineWidth(1.5f), lineOpacity(0.8f),
            lineDasharray(arrayOf(2f, 2f)))
        incomplete.setFilter(Expression.eq(Expression.get("complete"), Expression.literal(false)))
        below(incomplete)
    }

    private fun subscribeCompass() {
        if (!compassActive) return
        val engine = map?.locationComponent?.takeIf { it.isLocationComponentActivated }?.compassEngine
        if (engine === compassEngine) return
        compassEngine?.removeCompassListener(compassListener)
        compassEngine = engine
        engine?.addCompassListener(compassListener)
    }

    private fun stopCameraTracking() {
        map?.locationComponent?.takeIf { it.isLocationComponentActivated }?.cameraMode = CameraMode.NONE
    }

    private fun applyCameraMode() {
        val component = map?.locationComponent ?: return
        if (!locationAllowed || !component.isLocationComponentActivated || !follow.following) return
        val fix = latest
        if (fix == null || !com.trailrelay.app.location.LocationFixQuality.follow(
                (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1_000_000,
                fix.accuracy)) {
            component.cameraMode = CameraMode.NONE
            return
        }
        component.cameraMode = if (follow.orientation == MapOrientation.HEADING_UP)
            CameraMode.TRACKING_COMPASS else CameraMode.TRACKING_GPS_NORTH
    }

    @SuppressLint("MissingPermission") // Activity supplies the current foreground permission state.
    private fun updateLocationComponent() {
        val ready = map ?: return
        val loaded = style ?: return
        try {
            val component = ready.locationComponent
            if (locationAllowed && locationStyle !== loaded) {
                component.activateLocationComponent(
                    LocationComponentActivationOptions.builder(context, loaded)
                        .useDefaultLocationEngine(false)
                        .locationComponentOptions(LocationComponentOptions.builder(context)
                            .foregroundTintColor(Color.rgb(0, 115, 255))
                            .backgroundTintColor(Color.WHITE)
                            .accuracyColor(Color.rgb(0, 115, 255))
                            .accuracyAlpha(0.2f)
                            .build())
                        .build()
                )
                locationStyle = loaded
                val engine = component.compassEngine
                if (engine !is TrueNorthCompass && engine != null) {
                    trueNorthCompass = TrueNorthCompass(engine).also { corrected ->
                        corrected.setOffset(headingOffset)
                        latest?.takeIf(ForegroundLocation::isUsable)?.let(corrected::updateLocation)
                        if (headingDiagnostics) corrected.onSample = ::logHeadingSample
                        component.compassEngine = corrected
                    }
                }
                if (!follow.following) component.cameraMode = CameraMode.NONE
                component.renderMode = RenderMode.COMPASS
            }
            if (component.isLocationComponentActivated) {
                component.isLocationComponentEnabled = locationAllowed
                subscribeCompass()
                if (locationAllowed && latest?.let(ForegroundLocation::isUsable) == true) applyCameraMode()
            }
        } catch (error: RuntimeException) {
            Log.e(TAG, "Location indicator initialization failed", error)
            onError(R.string.location_failed)
        }
    }

    fun showLocation(location: Location) {
        latest = Location(location)
        if (ForegroundLocation.isUsable(location)) trueNorthCompass?.updateLocation(location)
        if (trueNorthCompass?.decision?.source == HeadingSource.NONE) logHeadingSample()
        val ready = map ?: return
        if (style == null || !locationAllowed || !ForegroundLocation.isUsable(location)) return
        if (ready.locationComponent.isLocationComponentActivated) {
            if (!com.trailrelay.app.location.LocationFixQuality.follow(
                    (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000,
                    location.accuracy)) ready.locationComponent.cameraMode = CameraMode.NONE
            ready.locationComponent.forceLocationUpdate(location)
        }
        if (follow.following && ready.locationComponent.isLocationComponentActivated &&
            com.trailrelay.app.location.LocationFixQuality.follow(
                (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000,
                location.accuracy)) {
            val zoom = if (centered) ready.cameraPosition.zoom else INITIAL_ZOOM
            if (!centered) ready.moveCamera(CameraUpdateFactory.zoomTo(zoom))
            applyCameraMode()
            centered = true
        }
    }

    fun recenter(): Boolean {
        trailCameraUntouched = false
        fitTrailPending = false
        follow.recenter()
        val location = latest?.takeIf { ForegroundLocation.isUsable(it) &&
            com.trailrelay.app.location.LocationFixQuality.follow(
                (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000, it.accuracy) } ?: return false
        if (map == null || style == null) return false
        centered = false
        showLocation(location)
        return true
    }

    fun zoomBy(amount: Double) {
        val ready = map ?: return
        trailCameraUntouched = false
        fitTrailPending = false
        val zoom = (ready.cameraPosition.zoom + amount).coerceIn(1.0, 16.0)
        val component = ready.locationComponent
        if (component.isLocationComponentActivated && component.cameraMode != CameraMode.NONE) {
            component.zoomWhileTracking(zoom, 200L)
        } else ready.animateCamera(CameraUpdateFactory.zoomTo(zoom), 200)
    }

    fun saveState(state: Bundle) {
        state.putBoolean("fitTrailPending", fitTrailPending)
        state.putBoolean("following", follow.following)
        state.putBoolean("northResetPending", follow.northResetPending)
        state.putBoolean("centered", centered)
    }

    fun destroy() {
        pauseCompass()
        destroyed = true
        overlayWorker.shutdownNow()
        coverageWorker.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        map = null
        style = null
    }

    companion object {
        private const val TAG = "TrailRelayMap"
        private const val INITIAL_ZOOM = 16.0
    }
}
