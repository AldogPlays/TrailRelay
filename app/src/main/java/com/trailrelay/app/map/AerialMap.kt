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

/** Map styling, location display, and deliberately simple camera following. */
class AerialMap(
    private val context: Context,
    private val view: MapView,
    state: Bundle?,
    private val onError: (Int?) -> Unit,
) {
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var locationAllowed = false
    private var latest: Location? = null
    private var compassEngine: CompassEngine? = null
    private var compassActive = false
    private var lastHeading: Float? = null
    private var headingTimeMillis = 0L
    private val compassListener = object : CompassListener {
        override fun onCompassChanged(heading: Float) {
            if (heading.isFinite()) {
                lastHeading = heading
                headingTimeMillis = SystemClock.elapsedRealtime()
            }
        }
        override fun onCompassAccuracyChange(status: Int) = Unit
    }
    private val follow = FollowState(MapOrientation.NORTH_UP,
        state?.getBoolean("following", true) ?: true)
    private var centered = state?.getBoolean("centered", false) ?: false
    private var selectedTrail: Pair<Trail, GpxTrack>? = null
    private var browseTrails: Map<String, GpxTrack> = emptyMap()
    private var browseVisible = true
    var onTrailTap: ((List<String>) -> Unit)? = null
    private var fitTrailPending = state?.getBoolean("fitTrailPending") ?: false
    private var destroyed = false
    private var offlineTrailId: String? = null
    private var trailCameraUntouched = false
    private val handler = Handler(Looper.getMainLooper())
    private val imageryTimeout = Runnable {
        Log.w(TAG, "No USGS raster tile loaded within 30 seconds")
        onError(R.string.map_failed)
    }

    init {
        view.addOnRenderErrorListener {
            Log.e(TAG, "MapLibre renderer reported an error")
            reportMapError()
        }
        view.addOnTileActionListener { operation, x, y, z, _, _, source ->
            if (source == "usgs-imagery") {
                if (operation == TileOperation.Error) {
                    Log.e(TAG, "USGS raster tile failed: z=$z y=$y x=$x")
                    reportMapError()
                } else if (operation == TileOperation.LoadFromNetwork ||
                    operation == TileOperation.LoadFromCache) {
                    handler.post {
                        if (!destroyed) {
                            handler.removeCallbacks(imageryTimeout)
                        }
                    }
                }
            }
        }
        view.addOnDidFinishLoadingMapListener {
            if (!destroyed) {
                handler.removeCallbacks(imageryTimeout)
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
                ready.setMaxZoomPreference(19.0)
                if (state == null) {
                    ready.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(39.5, -98.35), 3.0))
                }
                ready.addOnCameraMoveStartedListener { reason ->
                    if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                        follow.pan()
                        if (ready.locationComponent.isLocationComponentActivated) {
                            ready.locationComponent.cameraMode = CameraMode.NONE
                        }
                        fitTrailPending = false
                        trailCameraUntouched = false
                    }
                }
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
        handler.post { if (!destroyed) onError(R.string.map_failed) }
    }

    fun loadStyle() {
        val ready = map ?: return
        style = null
        handler.removeCallbacks(imageryTimeout)
        handler.postDelayed(imageryTimeout, 30_000)
        ready.setStyle(Style.Builder().fromUri(AERIAL_STYLE_URI)) { loaded ->
            if (!destroyed) {
                style = loaded
                Log.i(TAG, "Bundled USGS raster style loaded")
                onError(null)
                updateLocationComponent()
                renderTrails()
                fitSelectedTrail()
                latest?.let(::showLocation)
            }
        }
    }

    fun showTrail(trail: Trail, track: GpxTrack, fit: Boolean = true) {
        selectedTrail = trail to track
        browseVisible = false
        trailCameraUntouched = fit
        if (fit) {
            follow.pan()
            stopCameraTracking()
            centered = true
            fitTrailPending = true
        }
        renderTrails()
        fitSelectedTrail()
    }

    fun showBrowseTrails(trails: Map<String, GpxTrack>) {
        browseTrails = trails
        renderTrails()
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
        style?.let { TrailOverlay.render(it,
            if (browseVisible) browseTrails else emptyMap(), selectedTrail?.second) }
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
            if (!destroyed && fitTrailPending) {
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
        compassEngine = null
        lastHeading = null
        headingTimeMillis = 0L
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
        component.cameraMode = if (follow.orientation == MapOrientation.HEADING_UP)
            CameraMode.TRACKING_COMPASS else CameraMode.TRACKING_GPS_NORTH
    }

    @SuppressLint("MissingPermission") // Activity supplies the current foreground permission state.
    private fun updateLocationComponent() {
        val ready = map ?: return
        val loaded = style ?: return
        try {
            val component = ready.locationComponent
            if (locationAllowed && !component.isLocationComponentActivated) {
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
                component.cameraMode = CameraMode.NONE
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
        val ready = map ?: return
        if (style == null || !locationAllowed || !ForegroundLocation.isUsable(location)) return
        if (ready.locationComponent.isLocationComponentActivated) {
            ready.locationComponent.forceLocationUpdate(location)
        }
        if (follow.following && ready.locationComponent.isLocationComponentActivated) {
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
        val location = latest?.takeIf(ForegroundLocation::isUsable) ?: return false
        if (map == null || style == null) return false
        centered = false
        showLocation(location)
        return true
    }

    fun saveState(state: Bundle) {
        state.putBoolean("fitTrailPending", fitTrailPending)
        state.putBoolean("following", follow.following)
        state.putBoolean("centered", centered)
    }

    fun destroy() {
        pauseCompass()
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        map = null
        style = null
    }

    companion object {
        private const val TAG = "TrailRelayMap"
        private const val INITIAL_ZOOM = 16.0
    }
}
