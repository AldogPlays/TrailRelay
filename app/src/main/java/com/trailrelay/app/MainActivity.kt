package com.trailrelay.app

import android.Manifest
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.ApplicationInfo
import android.net.Uri
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
import com.trailrelay.app.location.ForegroundLocation
import com.trailrelay.app.location.SpeedEstimator
import com.trailrelay.app.location.SpeedFix
import com.trailrelay.app.map.AerialMap
import com.trailrelay.app.map.MapOrientation
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
    private lateinit var aerialMap: AerialMap
    private lateinit var location: ForegroundLocation
    private lateinit var status: TextView
    private lateinit var mapShell: MapShell
    private lateinit var offline: OfflineDownloads
    private val worker = Executors.newSingleThreadExecutor()
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
    private val offlineListener: () -> Unit = { renderCard() }
    private var resumed = false
    private var locationStatus: Int? = null
    private var mapStatus: Int? = null
    private var permissionRequested = false
    private var orientation = MapOrientation.NORTH_UP
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
        aerialMap = AerialMap(this, mapView, savedInstanceState) {
            mapStatus = it
            renderStatus()
        }
        aerialMap.setOrientation(orientation)
        renderOrientation()
        aerialMap.onTrailTap = ::chooseMapHit
        location = ForegroundLocation(this, { fix ->
            aerialMap.showLocation(fix)
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
        }) {
            locationStatus = it
            if (it == R.string.location_disabled || it == R.string.permission_needed ||
                it == R.string.location_failed) clearSpeed()
            renderStatus()
        }
        findViewById<ImageButton>(R.id.orientation).setOnClickListener {
            orientation = if (orientation == MapOrientation.NORTH_UP) MapOrientation.HEADING_UP
                else MapOrientation.NORTH_UP
            getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE).edit {
                putString(ORIENTATION_PREFERENCE, orientation.name)
            }
            aerialMap.setOrientation(orientation)
            renderOrientation()
        }
        status.setOnClickListener { if (mapStatus != null) aerialMap.loadStyle() }
        findViewById<ImageButton>(R.id.recenter).setOnClickListener {
            if (!location.hasPermission()) requestLocation() else {
                val hasFix = aerialMap.recenter()
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
                    val item = offline.packageFor(trail.id)
                    when (offlineOperationState(offline.isCreating(trail.id), item?.deleting == true,
                        item?.libraryState)) {
                        OfflineOperationState.NONE -> offline.start(trail)
                        OfflineOperationState.DOWNLOADING -> item?.let(offline::pause)
                        OfflineOperationState.PAUSED, OfflineOperationState.FAILED ->
                            if (item == null) offline.start(trail) else offline.resume(item)
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

    private fun refreshBrowse() {
        val request = ++browseRequest
        worker.execute {
            val result = runCatching {
                val routes = TrailStore(applicationContext).use { store ->
                    store.list().filter(store::hasLocalGpx).mapNotNull { trail ->
                        runCatching { trail to store.load(trail) }.getOrNull()
                    }
                }
                routes to CommunityClient(applicationContext).cached().orEmpty()
            }
            runOnUiThread {
                if (!isDestroyed && request == browseRequest) result.onSuccess { (routes, entries) ->
                    browseTracks = routes.associate { it.first.id to it.second }
                    browseInfo = routes.associate { it.first.id to it.first }
                    catalogInfo = entries.associateBy(CatalogEntry::id)
                    aerialMap.showBrowseTrails(browseTracks)
                    val current = selection.selection
                    if (current is MapSelection.Preview) {
                        routes.firstOrNull { it.first.id == communityTrailId(current.catalogId) }?.let { (trail, track) ->
                            selection.saved(trail.id)
                            selectedTrail = trail
                            selectedTrack = track
                            previewMessage = null
                            aerialMap.showTrail(trail, track, false)
                            renderCard()
                        }
                    }
                }.onFailure { Toast.makeText(this, R.string.library_load_failed, Toast.LENGTH_LONG).show() }
            }
        }
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
            content.addView(MaterialButton(this).apply {
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
        aerialMap.beginSelection()
        renderCard()
        val request = ++trailRequest
        worker.execute {
            val result = runCatching {
                TrailStore(applicationContext).use { store ->
                    val trail = store.get(id) ?: error("Trail record is missing")
                    val hasLocal = store.hasLocalGpx(trail)
                    val track = resolveMapGeometry(trail.source, hasLocal, { store.load(trail) },
                        { error("Local route geometry is unavailable") })
                    Triple(trail, track, CommunityClient(applicationContext).cached()
                        ?.firstOrNull { it.id == trail.remoteId })
                }
            }
            runOnUiThread {
                if (!isDestroyed && request == trailRequest) {
                    busy = false
                    routeOperation = RouteOperation.NONE
                    result.onSuccess { (trail, track, entry) ->
                        selectedTrail = trail
                        selectedEntry = entry
                        selectedTrack = track
                        aerialMap.showTrail(trail, track, fit)
                        renderCard()
                    }.onFailure {
                        clearSelection()
                        Toast.makeText(this, R.string.trail_open_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
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
        aerialMap.beginSelection()
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
                    val hasLocal = saved?.let(store::hasLocalGpx) == true
                    val geometry = resolveMapGeometry(TrailSource.COMMUNITY, hasLocal,
                        { store.load(checkNotNull(saved)) }, {
                            val bytes = ByteArrayOutputStream().also {
                                CommunityClient.download(entry.gpxUrl, it)
                            }.toByteArray()
                            bytes.inputStream().use(GpxParser::parse)
                        })
                    (saved?.takeIf { hasLocal }?.let { it to geometry }) to geometry
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
                        aerialMap.showTrail(trail, track, fit)
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
                store.downloadIfMissing(entry).let { it to store.load(it) }
            } }
            runOnUiThread {
                if (!isDestroyed && request == trailRequest) {
                    busy = false
                    routeOperation = RouteOperation.NONE
                    result.onSuccess { (trail, savedTrack) ->
                        selection.saved(trail.id)
                        selectedTrail = trail
                        selectedTrack = savedTrack
                        aerialMap.showTrail(trail, savedTrack, false)
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
        aerialMap.clearTrail()
        aerialMap.showBrowseTrails(browseTracks)
        renderCard()
    }

    private fun renderCard() {
        val current = selection.selection
        selectionBack.isEnabled = current != null
        mapShell.setSelectionVisible(current != null)
        if (current == null) return
        val preview = current is MapSelection.Preview
        val trail = selectedTrail
        val entry = selectedEntry
        val offlineItem = trail?.takeUnless { preview }?.let { offline.packageFor(it.id) }
        val offlineState = offlineOperationState(trail?.let { !preview && offline.isCreating(it.id) } == true,
            offlineItem?.deleting == true, offlineItem?.libraryState ?: when {
                trail != null && !preview && (offline.loadError != null || offline.errorFor(trail.id) != null) ->
                    OfflineLibraryState.FAILED
                trail != null && !preview && !offline.loaded -> OfflineLibraryState.CHECKING
                else -> null
            })
        aerialMap.setOfflineTrail(trail?.id?.takeIf {
            !preview && offline.loadError == null && offline.packageFor(it)?.complete == true
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
            text = listOfNotNull(entry?.state, entry?.region,
                entry?.vehicleTypes?.takeIf { it.isNotEmpty() }?.joinToString(", "),
                if (preview) getString(R.string.community_preview) else null
            ).joinToString(" · ")
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.selection_source).text = getString(when {
            trail?.source == TrailSource.IMPORTED -> R.string.source_imported
            entry != null || trail?.source == TrailSource.COMMUNITY -> R.string.source_community
            else -> R.string.community_preview
        })
        findViewById<TextView>(R.id.selection_description).apply {
            text = entry?.description ?: trail?.description
            visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        findViewById<Chip>(R.id.selection_route_chip).showRouteStatus(when {
            busy -> RouteChipState.CHECKING
            preview -> RouteChipState.NOT_SAVED
            trail != null -> RouteChipState.SAVED
            else -> RouteChipState.CHECKING
        })
        findViewById<Chip>(R.id.selection_aerial_chip).showAerialStatus(
            if (preview) AerialChipState.NOT_OFFLINE else if (offlineItem?.deleting == true)
                AerialChipState.DELETING else AerialChipState.fromImagery(
                trail?.let { imageryState(offline, it.id) } ?: com.trailrelay.app.trails.ImageryState.CHECKING))
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
                ?: trail?.id?.let(offline::errorFor) ?: offline.loadError else null
            text = if (preview || trail == null || offlineState == OfflineOperationState.NONE) "" else
                getString(offlineState.label()) + metrics.takeIf(String::isNotBlank)?.let { "\n$it" }.orEmpty() +
                    error?.let { "\n$it" }.orEmpty()
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
        findViewById<Button>(R.id.selection_details).isEnabled = trail != null || entry != null
        findViewById<Button>(R.id.selection_open_maps).visibility =
            if (selectedTrack?.endpoints() != null) View.VISIBLE else View.GONE
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
    }

    private fun requestLocation() {
        permissionRequested = true
        permissionRequest.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun refreshLocation() {
        val allowed = location.hasPermission()
        aerialMap.setLocationAllowed(allowed)
        if (!allowed) {
            location.stop()
            clearSpeed()
            locationStatus = R.string.permission_needed
            renderStatus()
        } else if (resumed) location.start()
    }

    private fun renderStatus() {
        val messages = listOfNotNull(mapStatus, locationStatus).distinct()
        status.text = messages.joinToString("\n") { getString(it) }
        status.visibility = if (messages.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderOrientation() {
        findViewById<ImageButton>(R.id.orientation).apply {
            setImageResource(if (orientation == MapOrientation.NORTH_UP) R.drawable.ic_north_up
                else R.drawable.ic_heading_up)
            contentDescription = getString(if (orientation == MapOrientation.NORTH_UP)
                R.string.orientation_north_up else R.string.orientation_heading_up)
            isActivated = orientation == MapOrientation.HEADING_UP
        }
    }

    private fun clearSpeed() {
        speedEstimator.reset()
        renderSpeed()
    }

    private fun renderSpeed() {
        val mph = speedEstimator.reading(SystemClock.elapsedRealtimeNanos()).mph
        findViewById<TextView>(R.id.speed_hud).text = if (mph == null)
            getString(R.string.speed_unavailable) else getString(R.string.speed_mph, mph)
    }

    private fun openSelectedTrailInMaps() {
        val endpoints = selectedTrack?.endpoints() ?: return
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

    private fun openMapPoint(point: com.trailrelay.app.trails.TrackPoint) {
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
        aerialMap.resumeCompass()
        refreshLocation()
        speedHandler.removeCallbacks(speedRefresh)
        speedHandler.post(speedRefresh)
    }
    override fun onPause() {
        resumed = false
        aerialMap.pauseCompass()
        speedHandler.removeCallbacks(speedRefresh)
        clearSpeed()
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        location.stop()
        mapView.onPause()
        super.onPause()
    }
    override fun onStop() {
        offline.listeners.remove(offlineListener)
        mapView.onStop()
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
        aerialMap.saveState(outState)
        (selection.selection as? MapSelection.Saved)?.let { outState.putString("selectedTrailId", it.trailId) }
        (selection.selection as? MapSelection.Preview)?.let { outState.putString("previewCatalogId", it.catalogId) }
        outState.putBoolean("permissionRequested", permissionRequested)
    }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroy() {
        worker.shutdown()
        location.stop()
        aerialMap.destroy()
        mapView.onDestroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PREVIEW_ID = "previewCatalogId"
        private const val ORIENTATION_PREFERENCE = "map_orientation"
    }
}
