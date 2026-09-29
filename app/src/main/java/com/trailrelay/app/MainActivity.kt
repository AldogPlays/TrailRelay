package com.trailrelay.app

import android.location.Location
import com.trailrelay.app.trails.PreparedRoute
import com.trailrelay.app.trails.RoutePosition
import com.trailrelay.app.trails.TrailPositionQuality
import com.trailrelay.app.trails.TrackPoint
import android.Manifest
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.trailrelay.app.ui.MapShell
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.trailrelay.app.location.ForegroundLocation
import com.trailrelay.app.location.SpeedEstimator
import com.trailrelay.app.location.SpeedFix
import com.trailrelay.app.map.TrailMap
import com.trailrelay.app.map.MapOrientation
import com.trailrelay.app.map.MapMode
import com.trailrelay.app.map.MapSelection
import com.trailrelay.app.map.MapSelectionState
import com.trailrelay.app.map.MapTapDecision
import com.trailrelay.app.map.mapTapDecision
import com.trailrelay.app.offline.OfflineActivity
import com.trailrelay.app.offline.OfflineDownloads
import com.trailrelay.app.offline.OfflineLibraryState
import com.trailrelay.app.offline.OfflineOperationState
import com.trailrelay.app.offline.offlineOperationState
import com.trailrelay.app.offline.offlineProgressMetrics
import com.trailrelay.app.offline.showOfflineProgress
import com.trailrelay.app.offline.label
import com.trailrelay.app.trails.AerialChipState
import com.trailrelay.app.trails.GpxParser
import com.trailrelay.app.trails.GpxTrack
import com.trailrelay.app.trails.MyTrailsActivity
import com.trailrelay.app.trails.RouteChipState
import com.trailrelay.app.trails.Trail
import com.trailrelay.app.trails.TrailDetailActivity
import com.trailrelay.app.trails.TrailSource
import com.trailrelay.app.trails.TrailStore
import com.trailrelay.app.trails.fromImagery
import com.trailrelay.app.trails.endpoints
import com.trailrelay.app.trails.imageryState
import com.trailrelay.app.trails.showAerialStatus
import com.trailrelay.app.trails.showRouteStatus
import com.trailrelay.app.trails.community.CatalogEntry
import com.trailrelay.app.trails.community.CommunityActivity
import com.trailrelay.app.trails.community.CommunityClient
import com.trailrelay.app.trails.community.communityTrailId
import com.trailrelay.app.trails.community.resolveMapGeometry
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView

class MainActivity : AppCompatActivity() {
    private enum class RouteOperation { NONE, OPENING, PREVIEW, DOWNLOAD }
    private lateinit var mapView: MapView
    private lateinit var trailMap: TrailMap
    private lateinit var location: ForegroundLocation
    private lateinit var status: TextView
    private lateinit var mapShell: MapShell
    private lateinit var offline: OfflineDownloads
    private val worker = Executors.newSingleThreadExecutor()
    private val browseWorker = Executors.newSingleThreadExecutor()
    private val contextWorker = Executors.newSingleThreadExecutor()
    private var contextBusy = false
    private var browseLoading = false
    private var browseAgain = false
    private lateinit var geometryCache: com.trailrelay.app.trails.SavedGeometryCache
    private var selectedEndpoints: com.trailrelay.app.trails.TrailEndpoints? = null
    private var lastLocationTiming = 0L
    private val selection = MapSelectionState()
    private var selectedTrail: Trail? = null
    private var selectedEntry: CatalogEntry? = null
    private var selectedTrack: GpxTrack? = null
    private var previewMessage: String? = null
    private var busy = false
    private var routeOperation = RouteOperation.NONE
    private var downloadFailed = false
    private var trailRequest = 0
    private var browseRequest = 0
    private var browseTracks: Map<String, GpxTrack> = emptyMap()
    private var browseInfo: Map<String, Trail> = emptyMap()
    private var catalogInfo: Map<String, CatalogEntry> = emptyMap()
    private lateinit var selectionBack: OnBackPressedCallback
    private var offlineRenderPending = false
    private val offlineRender = Runnable {
        offlineRenderPending = false
        if (!isDestroyed) renderCard()
    }
    private val offlineListener: () -> Unit = {
        if (::trailMap.isInitialized) trailMap.updateOfflineCoverage(offline.allPackages())
        if (selection.selection != null && !offlineRenderPending) {
            offlineRenderPending = true
            speedHandler.postDelayed(offlineRender, 250L)
        }
    }
    private var resumed = false
    private var locationStatus: Int? = null
    private var mapStatus: Int? = null
    private var permissionRequested = false
    private var orientation = MapOrientation.NORTH_UP
    private var mapMode = MapMode.AERIAL
    private var contextFix: Location? = null
    private var preparedTrack: GpxTrack? = null
    private var preparedRoute: PreparedRoute? = null
    private var projectedFix: Location? = null
    private var routePosition: RoutePosition? = null
    private val speedEstimator = SpeedEstimator()
    private val speedDiagnosticsEnabled by lazy {
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
    private val speedHandler = Handler(Looper.getMainLooper())
    private val speedRefresh = object : Runnable {
        override fun run() {
            renderSpeed()
            if (resumed) speedHandler.postDelayed(this, 5_000L)
        }
    }

    private val destination = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringExtra(MyTrailsActivity.EXTRA_TRAIL_ID)?.let { selectLocal(it) }
                ?: result.data?.getStringExtra(EXTRA_PREVIEW_ID)?.let { selectPreview(it) }
        }
        refreshBrowse()
    }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        worker.execute {
            val result = runCatching { TrailStore(applicationContext).use { it.import(uri) } }
            runOnUiThread {
                if (!isDestroyed) {
                    result.onSuccess {
                        Toast.makeText(this, getString(R.string.imported_trail, it.name), Toast.LENGTH_LONG).show()
                        refreshBrowse()
                    }.onFailure {
                        Toast.makeText(this, it.message ?: getString(R.string.import_failed), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (location.hasPermission()) refreshLocation() else {
            Log.w("TrailRelay", "Foreground location permission denied")
            locationStatus = R.string.permission_needed
            renderStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Perf.configure(this)
        val activityStarted = Perf.start()
        geometryCache = com.trailrelay.app.trails.SavedGeometryCache(filesDir,
            com.trailrelay.app.trails.DerivedRouteCache(filesDir, report = Perf::routeCache)) { trail, file ->
            val started = Perf.start()
            val result = runCatching { file.inputStream().use(GpxParser::parse) }
            Perf.end("gpx_parse", started, "trail=${trail.id.hashCode()} success=${result.isSuccess}")
            result.getOrThrow()
        }
        enableEdgeToEdge()
        MapLibre.getInstance(this)
        offline = OfflineDownloads.get(this)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        mapShell = MapShell(this)
        findViewById<Button>(R.id.explore).setOnClickListener {
            if (supportFragmentManager.findFragmentByTag("explore") == null)
                ExploreSheet().show(supportFragmentManager, "explore")
        }
        mapView = findViewById(R.id.map_view)
        mapView.onCreate(savedInstanceState)
        orientation = MapOrientation.fromPreference(getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
            .getString(ORIENTATION_PREFERENCE, null))
        mapMode = MapMode.selected(this)
        trailMap = TrailMap(this, mapView, savedInstanceState, activityStarted, mapMode) {
            mapStatus = it
            renderStatus()
        }
        trailMap.setOrientation(orientation)
        trailMap.loadStyle(mapMode)
        renderOrientation()
        findViewById<ImageButton>(R.id.map_layers).setOnClickListener { showMapChooser() }
        renderModeControl()
        trailMap.onTrailTap = ::chooseMapHit
        trailMap.onPan = ::renderOrientation
        location = ForegroundLocation(this, { fix ->
            val measureLocation = SystemClock.elapsedRealtime() - lastLocationTiming >= 5_000L
            val locationStarted = if (measureLocation) Perf.start() else 0L
            if (measureLocation) lastLocationTiming = SystemClock.elapsedRealtime()
            contextFix = Location(fix)
            trailMap.showLocation(fix)
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            val reading = speedEstimator.accept(SpeedFix(fix.latitude, fix.longitude,
                fix.accuracy, fix.elapsedRealtimeNanos, fix.speed.takeIf { fix.hasSpeed() }), nowNanos)
            if (speedDiagnosticsEnabled) {
                val ageMillis = (nowNanos - fix.elapsedRealtimeNanos) / 1_000_000L
                Log.d("TrailRelaySpeed", "provider=${fix.provider ?: "unknown"} " +
                    "hasSpeed=${fix.hasSpeed()} rawMps=${if (fix.hasSpeed()) fix.speed else "absent"} " +
                    "mph=${reading.mph ?: "unavailable"} ageMs=$ageMillis " +
                    "accuracyM=${if (fix.hasAccuracy()) fix.accuracy else "absent"} " +
                    "state=${reading.source.name.lowercase()}")
            }
            renderSpeed()
            Perf.end("location_ui", locationStarted)
        }) {
            locationStatus = it
            if (it == R.string.location_disabled || it == R.string.permission_needed ||
                it == R.string.location_failed) clearSpeed()
            renderStatus()
        }
        findViewById<ImageButton>(R.id.zoom_in).setOnClickListener { trailMap.zoomBy(1.0) }
        findViewById<ImageButton>(R.id.zoom_out).setOnClickListener { trailMap.zoomBy(-1.0) }
        findViewById<ImageButton>(R.id.orientation).setOnClickListener {
            orientation = trailMap.nextOrientationPress()
            getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE).edit {
                putString(ORIENTATION_PREFERENCE, orientation.name)
            }
            val resumedFollow = trailMap.setOrientation(orientation, userInitiated = true)
            if (resumedFollow) {
                if (!location.hasPermission()) requestLocation() else {
                    location.stop()
                    refreshLocation()
                }
            }
            renderOrientation()
        }
        findViewById<View>(R.id.network_status).setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.map_source_status).setMessage(R.string.map_source_unavailable)
                .setPositiveButton(R.string.retry_map) { _, _ -> trailMap.retryStyle() }
                .setNegativeButton(android.R.string.ok, null).show()
        }
        findViewById<ImageButton>(R.id.recenter).setOnClickListener {
            if (!location.hasPermission()) requestLocation() else {
                val hasFix = trailMap.recenter()
                renderOrientation()
                location.stop()
                refreshLocation()
                if (!hasFix) Log.i("TrailRelay", "Recenter waiting for a fresh location")
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { clearSelection() }
        }.also { selectionBack = it })
        findViewById<Button>(R.id.selection_clear).setOnClickListener { clearSelection() }
        findViewById<Button>(R.id.selection_open_maps).setOnClickListener { openSelectedTrailInMaps() }
        findViewById<View>(R.id.selection_summary_content).setOnClickListener { mapShell.toggleExpanded() }
        findViewById<ImageButton>(R.id.selection_expand).setOnClickListener { mapShell.toggleExpanded() }
        findViewById<Button>(R.id.selection_details).setOnClickListener {
            val intent = Intent(this, TrailDetailActivity::class.java)
            when (val current = selection.selection) {
                is MapSelection.Saved -> intent.putExtra(TrailDetailActivity.EXTRA_LOCAL_ID, current.trailId)
                is MapSelection.Preview -> intent.putExtra(TrailDetailActivity.EXTRA_CATALOG_ID, current.catalogId)
                null -> return@setOnClickListener
            }
            destination.launch(intent)
        }
        findViewById<Button>(R.id.selection_action).setOnClickListener {
            when (selection.selection) {
                is MapSelection.Preview -> if (selectedTrack == null) loadPreview() else downloadPreview()
                is MapSelection.Saved -> selectedTrail?.let { trail ->
                    val item = offline.packageFor(trail.id, mapMode)
                    when (offlineOperationState(offline.isCreating(trail.id, mapMode), item?.deleting == true,
                        item?.libraryState)) {
                        OfflineOperationState.NONE -> offline.start(trail, mapMode)
                        OfflineOperationState.DOWNLOADING -> item?.let(offline::pause)
                        OfflineOperationState.PAUSED, OfflineOperationState.FAILED ->
                            if (item == null) offline.start(trail, mapMode) else offline.resume(item)
                        OfflineOperationState.COMPLETE -> destination.launch(Intent(this, OfflineActivity::class.java)
                            .putExtra(MyTrailsActivity.EXTRA_TRAIL_ID, trail.id))
                        else -> Unit
                    }
                }
                null -> Unit
            }
        }
        savedInstanceState?.getString("selectedTrailId")?.let { selectLocal(it, false) }
        savedInstanceState?.getString("previewCatalogId")?.let { selectPreview(it, false) }
        permissionRequested = savedInstanceState?.getBoolean("permissionRequested") ?: false
        if (!location.hasPermission() && !permissionRequested) requestLocation()
    }

    fun openTopDestination(target: TopDestination?) {
        when (target) {
            TopDestination.MY_TRAILS -> destination.launch(Intent(this, MyTrailsActivity::class.java))
            TopDestination.COMMUNITY -> destination.launch(Intent(this, CommunityActivity::class.java))
            TopDestination.OFFLINE -> destination.launch(Intent(this, OfflineActivity::class.java))
            TopDestination.SETTINGS -> destination.launch(Intent(this, SettingsActivity::class.java))
            null -> picker.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream"))
        }
    }

    private fun showMapChooser() {
        val padding = resources.getDimensionPixelSize(R.dimen.space_content)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, padding)
        }
        val choices = RadioGroup(this)
        content.addView(choices)
        val credit = TextView(this).apply {
            setText(R.string.map_credit_line)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            minHeight = resources.getDimensionPixelSize(R.dimen.touch_target)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(resources.getDimensionPixelSize(R.dimen.space_related), 0, 0, 0)
            contentDescription = getString(R.string.map_attribution_description)
            setOnClickListener {
                MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.map_attribution_short)
                    .setMessage(getString(R.string.map_attribution_details, getString(mapMode.credit)))
                    .setPositiveButton(android.R.string.ok, null).show()
            }
        }
        content.addView(credit)
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.map_title).setView(content)
            .setNegativeButton(android.R.string.cancel, null).create()
        MapMode.entries.forEach { mode ->
            choices.addView(MaterialRadioButton(this).apply {
                setText(mode.label)
                isChecked = mode == mapMode
                minHeight = resources.getDimensionPixelSize(R.dimen.touch_target)
                setOnClickListener {
                    dialog.dismiss()
                    if (mode != mapMode) {
                        mapMode = mode
                        getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE).edit {
                            putString(MapMode.PREFERENCE, mode.id)
                        }
                        trailMap.loadStyle(mode)
                        trailMap.updateOfflineCoverage(offline.allPackages())
                        renderModeControl()
                        renderCard()
                        renderStatus()
                    }
                }
            })
        }
        dialog.show()
    }

    private fun renderModeControl() {
        findViewById<ImageButton>(R.id.map_layers).apply {
            contentDescription = getString(R.string.map_selector_current, getString(mapMode.label))
            tooltipText = contentDescription
        }
    }

    private fun refreshBrowse() {
        if (browseLoading) { browseAgain = true; return }
        browseLoading = true
        val request = ++browseRequest
        val started = Perf.start()
        browseWorker.execute {
            val result = runCatching {
                val dbStarted = Perf.start()
                val trails = TrailStore(applicationContext).use { it.list() }
                Perf.end("browse_db", dbStarted, "routes=${trails.size}")
                geometryCache.retain(trails.map { it.id }.toSet())
                runOnUiThread {
                    if (!isDestroyed && request == browseRequest) {
                        val ids = trails.map { it.id }.toSet()
                        browseInfo = trails.associateBy(Trail::id)
                        browseTracks = browseTracks.filterKeys { it in ids }
                        trailMap.showBrowseTrails(browseTracks)
                    }
                }
                // Each completion is published independently; a slow/broken GPX cannot gate the library.
                var remaining = trails.size // Read/written only by the main-thread completion callbacks.
                var published = false
                if (trails.isEmpty()) runOnUiThread { finishBrowse(request, started) }
                trails.forEach { trail ->
                    geometryCache.request(trail).whenComplete { track, error ->
                        runOnUiThread {
                            if (!isDestroyed && request == browseRequest) {
                                if (error == null) {
                                    if (!published) {
                                        published = true
                                        Perf.end("browse_first_available", started)
                                    }
                                    if (browseTracks[trail.id] !== track) {
                                        browseTracks = browseTracks + (trail.id to track)
                                        trailMap.showBrowseTrails(browseTracks)
                                    }
                                    val current = selection.selection
                                    if (current is MapSelection.Preview && trail.id == communityTrailId(current.catalogId)) {
                                        selection.saved(trail.id)
                                        selectedTrail = trail
                                        selectedTrack = track
                                        previewMessage = null
                                        trailMap.showTrail(trail, track, false)
                                        renderCard()
                                    }
                                } else {
                                    browseTracks = browseTracks - trail.id
                                    trailMap.showBrowseTrails(browseTracks)
                                }
                                remaining--
                                if (remaining == 0) finishBrowse(request, started)
                            }
                        }
                    }
                }
                val entries = CommunityClient(applicationContext).cached().orEmpty()
                runOnUiThread {
                    if (!isDestroyed && request == browseRequest) catalogInfo = entries.associateBy(CatalogEntry::id)
                }
            }
            result.onFailure {
                runOnUiThread {
                    if (!isDestroyed && request == browseRequest) {
                        Toast.makeText(this, R.string.library_load_failed, Toast.LENGTH_LONG).show()
                        finishBrowse(request, started)
                    }
                }
            }
        }
    }

    private fun finishBrowse(request: Int, started: Long) {
        if (isDestroyed || request != browseRequest || !browseLoading) return
        Perf.end("browse_available", started, "routes=${browseTracks.size}")
        Perf.end("route_cache", started, geometryCache.diskSummary())
        browseLoading = false
        if (browseAgain) { browseAgain = false; refreshBrowse() }
    }

    private fun chooseMapHit(ids: List<String>) {
        val candidates = ids.mapNotNull(browseInfo::get)
        when (val decision = mapTapDecision(candidates.map(Trail::id))) {
            MapTapDecision.None -> return
            is MapTapDecision.Select -> { selectLocal(decision.trailId); return }
            is MapTapDecision.Choose -> Unit
        }
        val dialog = BottomSheetDialog(this, R.style.ThemeOverlay_TrailRelay_BottomSheet)
        val padding = resources.getDimensionPixelSize(R.dimen.space_content)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.choose_trail)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
        })
        candidates.forEach { trail ->
            val detail = listOfNotNull(String.format(Locale.getDefault(), "%.1f mi", trail.distanceMeters / 1609.344),
                trail.remoteId?.let(catalogInfo::get)?.difficulty,
                getString(if (trail.source == TrailSource.IMPORTED) R.string.source_imported else R.string.source_community))
                .joinToString(" · ")
            content.addView(layoutInflater.inflate(R.layout.chooser_action, content, false).apply {
                this as MaterialButton
                text = "${trail.name}\n$detail"
                isAllCaps = false
                setOnClickListener { dialog.dismiss(); selectLocal(trail.id) }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        dialog.setContentView(androidx.core.widget.NestedScrollView(this).apply { addView(content) })
        dialog.show()
    }

    private fun selectLocal(id: String, fit: Boolean = true) {
        selection.select(MapSelection.Saved(id))
        mapShell.setSelectionVisible(true, resetCollapsed = true)
        selectedTrail = null
        selectedEntry = null
        selectedTrack = null
        previewMessage = null
        busy = true
        routeOperation = RouteOperation.OPENING
        trailMap.beginSelection()
        renderCard()
        val request = ++trailRequest
        fun publish(result: Result<Triple<Trail, GpxTrack, CatalogEntry?>>) {
            runOnUiThread {
                if (!isDestroyed && request == trailRequest) {
                    busy = false
                    routeOperation = RouteOperation.NONE
                    result.onSuccess { (trail, track, entry) ->
                        selectedTrail = trail
                        selectedEntry = entry
                        selectedTrack = track
                        trailMap.showTrail(trail, track, fit)
                        renderCard()
                    }.onFailure {
                        clearSelection()
                        Toast.makeText(this, R.string.trail_open_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        // Metadata reads are independent of Community networking; GPX completion never blocks this lane.
        browseWorker.execute {
            runCatching {
                val trail = TrailStore(applicationContext).use { store ->
                    val saved = store.get(id) ?: error("Trail record is missing")
                    check(store.hasLocalGpx(saved)) { "Local route geometry is unavailable" }
                    saved
                }
                val entry = CommunityClient(applicationContext).cached()?.firstOrNull { it.id == trail.remoteId }
                geometryCache.request(trail).whenComplete { track, error ->
                    publish(if (error == null) Result.success(Triple(trail, track, entry)) else Result.failure(error))
                }
            }.onFailure { publish(Result.failure(it)) }
        }
    }

    private fun selectPreview(id: String, fit: Boolean = true) {
        selection.select(MapSelection.Preview(id))
        mapShell.setSelectionVisible(true, resetCollapsed = true)
        selectedTrail = null
        selectedTrack = null
        selectedEntry = null
        previewMessage = null
        downloadFailed = false
        trailMap.beginSelection()
        renderCard()
        loadPreview(fit)
    }

    private fun loadPreview(fit: Boolean = true) {
        val current = selection.selection as? MapSelection.Preview ?: return
        val request = ++trailRequest
        busy = true
        routeOperation = RouteOperation.PREVIEW
        previewMessage = null
        renderCard()
        worker.execute {
            val result = runCatching {
                val entry = CommunityClient(applicationContext).cached()?.firstOrNull { it.id == current.catalogId }
                    ?: error("Community trail information is unavailable. Open Community to refresh its catalog.")
                runOnUiThread {
                    if (!isDestroyed && request == trailRequest) { selectedEntry = entry; renderCard() }
                }
                val (local, track) = TrailStore(applicationContext).use { store ->
                    val saved = store.get(communityTrailId(entry.id))
                    val localTrack = saved?.let { runCatching { geometryCache.request(it).get() }.getOrNull() }
                    val geometry = resolveMapGeometry(TrailSource.COMMUNITY, localTrack != null,
                        { checkNotNull(localTrack) }, {
                            val bytes = ByteArrayOutputStream().also {
                                CommunityClient.download(entry.gpxUrl, it)
                            }.toByteArray()
                            bytes.inputStream().use(GpxParser::parse)
                        })
                    (saved?.takeIf { localTrack != null }?.let { it to geometry }) to geometry
                }
                val trail = local?.first ?: run {
                    val parsed = track
                    val points = parsed.segments.flatten()
                    Trail(communityTrailId(entry.id), entry.name, entry.description, TrailSource.COMMUNITY,
                        "", parsed.distanceMeters, points.minOf { it.latitude }, points.minOf { it.longitude },
                        points.maxOf { it.latitude }, points.maxOf { it.longitude }, 0L, entry.id)
                }
                Triple(entry, trail, track) to (local != null)
            }
            runOnUiThread {
                if (!isDestroyed && request == trailRequest) {
                    busy = false
                    routeOperation = RouteOperation.NONE
                    result.onSuccess { (data, saved) ->
                        val (entry, trail, track) = data
                        selectedEntry = entry
                        selectedTrail = trail
                        selectedTrack = track
                        if (saved) selection.saved(trail.id)
                        trailMap.showTrail(trail, track, fit)
                        renderCard()
                    }.onFailure {
                        previewMessage = getString(R.string.preview_failed, it.message ?: getString(R.string.check_connection))
                        renderCard()
                    }
                }
            }
        }
    }

    private fun downloadPreview() {
        val entry = selectedEntry ?: return
        selectedTrack ?: return
        val request = ++trailRequest
        busy = true
        routeOperation = RouteOperation.DOWNLOAD
        downloadFailed = false
        previewMessage = null
        renderCard()
        worker.execute {
            val result = runCatching { TrailStore(applicationContext).use { store ->
                store.downloadIfMissing(entry).let { it to geometryCache.request(it).get() }
            } }
            runOnUiThread {
                if (!isDestroyed && request == trailRequest) {
                    busy = false
                    routeOperation = RouteOperation.NONE
                    result.onSuccess { (trail, savedTrack) ->
                        selection.saved(trail.id)
                        selectedTrail = trail
                        selectedTrack = savedTrack
                        trailMap.showTrail(trail, savedTrack, false)
                        refreshBrowse()
                        renderCard()
                        Snackbar.make(findViewById(R.id.main), R.string.route_saved_snackbar,
                            Snackbar.LENGTH_SHORT).show()
                    }.onFailure {
                        downloadFailed = true
                        previewMessage = getString(R.string.download_failed, it.message ?: getString(R.string.check_connection))
                        renderCard()
                    }
                }
            }
        }
    }

    private fun clearSelection() {
        ++trailRequest
        selection.clear()
        selectedTrail = null
        selectedTrack = null
        selectedEntry = null
        previewMessage = null
        busy = false
        routeOperation = RouteOperation.NONE
        downloadFailed = false
        trailMap.clearTrail()
        trailMap.showBrowseTrails(browseTracks)
        renderCard()
    }

    private fun renderCard() {
        renderTrailPosition()
        renderOrientation()
        val current = selection.selection
        selectionBack.isEnabled = current != null
        mapShell.setSelectionVisible(current != null)
        if (current == null) return
        val preview = current is MapSelection.Preview
        val trail = selectedTrail
        val entry = selectedEntry
        val offlineItem = trail?.takeUnless { preview }?.let { offline.packageFor(it.id, mapMode) }
        val offlineState = offlineOperationState(trail?.let { !preview && offline.isCreating(it.id, mapMode) } == true,
            offlineItem?.deleting == true, offlineItem?.libraryState ?: when {
                trail != null && !preview && (offline.loadError != null || offline.errorFor(trail.id, mapMode) != null) ->
                    OfflineLibraryState.FAILED
                trail != null && !preview && !offline.loaded -> OfflineLibraryState.CHECKING
                else -> null
            })
        trailMap.setOfflineTrail(trail?.id?.takeIf {
            !preview && offline.loadError == null && offline.packageFor(it, mapMode)?.complete == true
        })
        findViewById<TextView>(R.id.selection_name).text = entry?.name ?: trail?.name
            ?: getString(if (preview) R.string.community_preview else R.string.loading_trail)
        val distance = (entry?.distanceMiles ?: trail?.distanceMeters?.div(1609.344))?.let {
                String.format(Locale.getDefault(), "%.1f mi", it)
            }
        findViewById<TextView>(R.id.selection_summary_meta).text = listOfNotNull(distance, entry?.difficulty)
            .joinToString(" · ")
        findViewById<TextView>(R.id.selection_summary_status).apply {
            text = when {
                preview -> getString(R.string.preview_not_saved_short)
                entry != null && trail != null -> getString(R.string.route_saved_summary)
                routeOperation == RouteOperation.OPENING -> getString(R.string.loading_trail)
                else -> ""
            }
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.selection_meta).apply {
            text = listOfNotNull(entry?.region, entry?.state).joinToString(" · ")
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.selection_vehicles).text = entry?.vehicleTypes?.joinToString(", ")
        findViewById<View>(R.id.selection_vehicles_row).visibility =
            if (entry?.vehicleTypes.isNullOrEmpty()) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.selection_source).text = getString(when {
            trail?.source == TrailSource.IMPORTED -> R.string.source_imported
            entry != null || trail?.source == TrailSource.COMMUNITY -> R.string.source_community
            else -> R.string.community_preview
        })
        findViewById<TextView>(R.id.selection_description).apply {
            text = entry?.description ?: trail?.description
            visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        findViewById<View>(R.id.selection_description_section).visibility =
            findViewById<TextView>(R.id.selection_description).visibility
        findViewById<Chip>(R.id.selection_route_chip).showRouteStatus(when {
            busy -> RouteChipState.CHECKING
            preview -> RouteChipState.NOT_SAVED
            trail != null -> RouteChipState.SAVED
            else -> RouteChipState.CHECKING
        })
        findViewById<Chip>(R.id.selection_aerial_chip).showAerialStatus(
            if (preview) AerialChipState.NOT_OFFLINE else if (offlineItem?.deleting == true)
                AerialChipState.DELETING else AerialChipState.fromImagery(
                trail?.let { imageryState(offline, it.id, mapMode) } ?: com.trailrelay.app.trails.ImageryState.CHECKING), mapMode)
        findViewById<TextView>(R.id.selection_message).apply {
            text = when (routeOperation) {
                RouteOperation.OPENING -> getString(R.string.loading_trail)
                RouteOperation.PREVIEW -> getString(R.string.loading_preview)
                RouteOperation.DOWNLOAD -> getString(R.string.downloading_trail)
                RouteOperation.NONE -> previewMessage ?: if (preview) getString(R.string.preview_not_saved) else ""
            }
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
        findViewById<LinearProgressIndicator>(R.id.selection_progress).apply {
            if (busy) {
                isIndeterminate = true
                visibility = View.VISIBLE
            } else showOfflineProgress(if (preview) OfflineOperationState.NONE else offlineState,
                offlineItem?.status)
        }
        findViewById<TextView>(R.id.selection_offline_state).apply {
            val metrics = if (offlineState == OfflineOperationState.DOWNLOADING ||
                offlineState == OfflineOperationState.PAUSED) offlineProgressMetrics(this@MainActivity,
                offlineItem?.status) else ""
            val error = if (offlineState == OfflineOperationState.FAILED) offlineItem?.error
                ?: trail?.id?.let { offline.errorFor(it, mapMode) } ?: offline.loadError else null
            text = if (preview || trail == null) "" else
                getString(mapMode.label) + " · " + getString(offlineState.label()) +
                    metrics.takeIf(String::isNotBlank)?.let { "\n$it" }.orEmpty() +
                    error?.let { "\n$it" }.orEmpty()
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
        findViewById<Button>(R.id.selection_details).isEnabled = trail != null || entry != null
        findViewById<Button>(R.id.selection_open_maps).visibility =
            if (selectedEndpoints != null) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.selection_action).apply {
            visibility = if (preview || trail != null) View.VISIBLE else View.GONE
            text = getString(when {
                routeOperation == RouteOperation.DOWNLOAD -> R.string.downloading_route
                preview && selectedTrack == null -> R.string.retry_preview
                preview && downloadFailed -> R.string.retry_download
                preview -> R.string.download_trail
                offlineState == OfflineOperationState.DOWNLOADING -> R.string.pause_offline
                offlineState == OfflineOperationState.PAUSED || offlineState == OfflineOperationState.FAILED ->
                    R.string.resume_offline
                offlineState == OfflineOperationState.COMPLETE -> R.string.manage_offline
                else -> R.string.download_offline_map
            })
            isEnabled = !busy && (preview || offline.loadError == null) && offlineState != OfflineOperationState.PREPARING &&
                offlineState != OfflineOperationState.DELETING && offlineState != OfflineOperationState.CHECKING
        }
        mapShell.refreshContentHeight()
        renderStatus()
    }

    private fun requestLocation() {
        permissionRequested = true
        permissionRequest.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun refreshLocation() {
        val allowed = location.hasPermission()
        trailMap.setLocationAllowed(allowed)
        if (!allowed) {
            location.stop()
            clearSpeed()
            locationStatus = R.string.permission_needed
            renderStatus()
        } else if (resumed) location.start()
    }

    private fun renderStatus() {
        val network = getSystemService(ConnectivityManager::class.java)
        val hasInternet = network.getNetworkCapabilities(network.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val coverageWarning = selectedTrail?.takeIf { !hasInternet &&
            offline.loaded && offline.packageFor(it.id, mapMode)?.complete != true }
            ?.let { getString(R.string.map_coverage_unavailable, getString(mapMode.label)) }
        status.text = listOfNotNull(locationStatus).map(::getString).joinToString("\n")
        status.visibility = if (status.text.isBlank()) View.GONE else View.VISIBLE
        findViewById<View>(R.id.network_status).visibility = if (mapStatus != null || coverageWarning != null)
            View.VISIBLE else View.GONE
    }

    private fun renderOrientation() {
        findViewById<ImageButton>(R.id.orientation).apply {
            setImageResource(if (trailMap.northResetPending || orientation == MapOrientation.NORTH_UP) R.drawable.ic_north_up
                else R.drawable.ic_heading_up)
            contentDescription = getString(if (trailMap.northResetPending) R.string.orientation_reset_north
                else if (orientation == MapOrientation.NORTH_UP)
                R.string.orientation_north_up else R.string.orientation_heading_up)
            tooltipText = contentDescription
            isActivated = orientation == MapOrientation.HEADING_UP && !trailMap.northResetPending
        }
    }

    private fun clearSpeed() {
        contextFix = null
        speedEstimator.reset()
        renderSpeed()
    }

    private fun renderSpeed() {
        renderTrailPosition()
        val mph = speedEstimator.reading(SystemClock.elapsedRealtimeNanos()).mph
        findViewById<TextView>(R.id.speed_hud).text = if (mph == null)
            getString(R.string.speed_unavailable) else getString(R.string.speed_mph, mph)
    }

    private fun renderTrailPosition() {
        val view = findViewById<View>(R.id.trail_position)
        if (preparedTrack !== selectedTrack) {
            preparedTrack = selectedTrack
            preparedRoute = null
            selectedEndpoints = null
            projectedFix = null
            routePosition = null
        }
        val fix = contextFix
        val quality = TrailPositionQuality
        val age = fix?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000L }
        val usable = fix != null && age != null && quality.usable(age, fix.accuracy)
        val track = selectedTrack
        if (!contextBusy && track != null && (preparedRoute == null || usable && projectedFix !== fix)) {
            contextBusy = true
            val retained = preparedRoute
            val inputFix = fix.takeIf { usable }
            contextWorker.execute {
                val prepareStarted = Perf.start()
                val route = retained ?: PreparedRoute(track)
                val endpoints = if (retained == null) track.endpoints() else null
                if (retained == null) Perf.end("context_prepare", prepareStarted)
                val projectionStarted = Perf.start()
                val position = inputFix?.let { route.nearest(TrackPoint(it.latitude, it.longitude)) }
                if (inputFix != null) Perf.end("context_project", projectionStarted)
                runOnUiThread {
                    if (!isDestroyed) {
                        contextBusy = false
                        if (selectedTrack === track) {
                            preparedRoute = route
                            if (retained == null) selectedEndpoints = endpoints
                            projectedFix = inputFix
                            routePosition = position
                        }
                        if (selectedTrack === track && retained == null) renderCard() else renderTrailPosition()
                    }
                }
            }
        }
        // Keep the last still-fresh result visible while the newest fix is being projected.
        // Its own timestamp and accuracy control display; never relabel old analysis as a new fix.
        val positionFix = projectedFix
        val position = routePosition.takeIf { usable && positionFix != null && quality.usable(
            (SystemClock.elapsedRealtimeNanos() - positionFix.elapsedRealtimeNanos) / 1_000_000L,
            positionFix.accuracy) }
        var changed = false
        fun show(target: View, visible: Boolean) {
            val visibility = if (visible) View.VISIBLE else View.GONE
            if (target.visibility != visibility) { target.visibility = visibility; changed = true }
        }
        fun metric(id: Int, text: String, description: String) {
            findViewById<TextView>(id).apply {
                if (this.text.toString() != text) { this.text = text; changed = true }
                contentDescription = description
            }
        }
        show(view, preparedRoute?.isValid == true)
        show(findViewById(R.id.position_unavailable), position == null)
        show(findViewById(R.id.position_metrics), position != null)
        val loop = selectedEndpoints?.end == null && selectedEndpoints != null
        show(findViewById(R.id.position_loop), loop && position != null)
        show(findViewById(R.id.position_a_group), !loop)
        show(findViewById(R.id.position_b_group), !loop)
        if (position != null) {
            val accuracy = positionFix!!.accuracy
            fun distance(meters: Double): String = when {
                meters / 0.3048 < quality.roundingFeet(accuracy) ->
                    getString(R.string.trail_position_distance_within, quality.roundingFeet(accuracy))
                meters < 160.9344 ->
                    getString(R.string.trail_position_distance_feet, quality.roundedFeet(meters, accuracy))
                else -> getString(R.string.trail_position_distance_miles, meters / 1609.344)
            }
            for ((id, label, meters) in listOf(Triple(R.id.position_a, "A", position.toAMeters),
                    Triple(R.id.position_b, "B", position.toBMeters))) {
                val value = meters?.let(::distance) ?: getString(R.string.position_gap_short)
                metric(id, value, if (meters == null) getString(R.string.trail_position_gap, label)
                    else getString(R.string.trail_position_endpoint, label, value))
            }
            val from = when {
                quality.nearTrail(position.fromRouteMeters, accuracy) -> getString(R.string.trail_position_near)
                position.fromRouteMeters < 160.9344 -> getString(R.string.position_feet_short,
                    quality.roundedFeet(position.fromRouteMeters, accuracy))
                else -> getString(R.string.position_miles_short, position.fromRouteMeters / 1609.344)
            }
            metric(R.id.position_from, from, getString(R.string.from_trail_label) + ": " + from)
        }
        if (changed) mapShell.refreshContentHeight()
    }

    private fun openSelectedTrailInMaps() {
        val endpoints = selectedEndpoints ?: return
        val end = endpoints.end
        if (end == null) {
            openMapPoint(endpoints.start)
        } else {
            val choices = layoutInflater.inflate(R.layout.dialog_trail_endpoint, null)
            val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.open_in_maps)
                .setView(choices).create()
            choices.findViewById<Button>(R.id.open_start_point).setOnClickListener {
                dialog.dismiss()
                openMapPoint(endpoints.start)
            }
            choices.findViewById<Button>(R.id.open_end_point).setOnClickListener {
                dialog.dismiss()
                openMapPoint(end)
            }
            dialog.show()
        }
    }

    private fun openMapPoint(point: TrackPoint) {
        val coordinates = "${point.latitude},${point.longitude}"
        val uri = Uri.parse("geo:$coordinates?q=${Uri.encode(coordinates)}")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_maps_app, Toast.LENGTH_LONG).show()
        }
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
        offline.listeners.add(offlineListener)
        offline.refresh()
        refreshBrowse()
        renderCard()
    }
    override fun onResume() {
        super.onResume()
        mapView.onResume()
        if (getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
                .getBoolean(SettingsActivity.KEEP_SCREEN_AWAKE, false))
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        resumed = true
        val settings = getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE)
        trailMap.setHeadingOffset(settings.getInt(SettingsActivity.HEADING_OFFSET, 0))
        trailMap.setOfflineCoverageVisible(settings.getBoolean(SettingsActivity.SHOW_OFFLINE_COVERAGE, false))
        trailMap.updateOfflineCoverage(offline.allPackages())
        trailMap.resumeCompass()
        refreshLocation()
        speedHandler.removeCallbacks(speedRefresh)
        speedHandler.post(speedRefresh)
    }
    override fun onPause() {
        resumed = false
        trailMap.pauseCompass()
        speedHandler.removeCallbacks(speedRefresh)
        clearSpeed()
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        location.stop()
        mapView.onPause()
        super.onPause()
    }
    override fun onStop() {
        offline.listeners.remove(offlineListener)
        speedHandler.removeCallbacks(offlineRender)
        offlineRenderPending = false
        mapView.onStop()
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
        trailMap.saveState(outState)
        (selection.selection as? MapSelection.Saved)?.let { outState.putString("selectedTrailId", it.trailId) }
        (selection.selection as? MapSelection.Preview)?.let { outState.putString("previewCatalogId", it.catalogId) }
        outState.putBoolean("permissionRequested", permissionRequested)
    }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroy() {
        speedHandler.removeCallbacks(offlineRender)
        ++browseRequest
        ++trailRequest
        geometryCache.close()
        browseWorker.shutdownNow()
        contextWorker.shutdownNow()
        worker.shutdown()
        location.stop()
        trailMap.destroy()
        mapView.onDestroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PREVIEW_ID = "previewCatalogId"
        private const val ORIENTATION_PREFERENCE = "map_orientation"
    }
}
