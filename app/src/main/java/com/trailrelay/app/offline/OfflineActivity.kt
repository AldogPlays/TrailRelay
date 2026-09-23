package com.trailrelay.app.offline

import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.chip.Chip
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.trailrelay.app.R
import com.trailrelay.app.trails.AerialChipState
import com.trailrelay.app.trails.MyTrailsActivity
import com.trailrelay.app.trails.RouteChipState
import com.trailrelay.app.trails.Trail
import com.trailrelay.app.trails.TrailStore
import com.trailrelay.app.trails.fromLibrary
import com.trailrelay.app.trails.showAerialStatus
import com.trailrelay.app.trails.showRouteStatus
import org.maplibre.android.MapLibre
import java.util.concurrent.Executors

class OfflineActivity : AppCompatActivity() {
    private lateinit var downloads: OfflineDownloads
    private val worker = Executors.newSingleThreadExecutor()
    private var trails: Map<String, Trail> = emptyMap()
    private var availableRouteIds: Set<String> = emptySet()
    private var trailsLoaded = false
    private var trailsError = false
    private var selectedId: String? = null
    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_offline)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.offline_root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        downloads = OfflineDownloads.get(this)
        selectedId = intent.getStringExtra(MyTrailsActivity.EXTRA_TRAIL_ID)
        findViewById<Button>(R.id.offline_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.offline_retry).setOnClickListener {
            downloads.refresh()
            loadTrails()
        }
        render()
    }

    private fun loadTrails() {
        worker.execute {
            val result = runCatching {
                TrailStore(applicationContext).use { store ->
                    val listed = store.list()
                    listed.associateBy(Trail::id) to listed.filter(store::hasLocalGpx).map(Trail::id).toSet()
                }
            }
            runOnUiThread {
                if (!isDestroyed) {
                    result.onSuccess { (listed, availableIds) ->
                        trails = listed
                        availableRouteIds = availableIds
                        trailsError = false
                    }
                        .onFailure { trailsError = true }
                    trailsLoaded = true
                    render()
                }
            }
        }
    }

    private fun render() {
        val container = findViewById<LinearLayout>(R.id.offline_items)
        container.removeAllViews()
        val items = downloads.allPackages().sortedWith(
            compareByDescending<OfflineDownloads.Package> { it.metadata?.trailId == selectedId }
                .thenByDescending { it.metadata?.createdAt ?: 0L })
        val selected = selectedId?.let(trails::get)
        val withoutImagery = trails.values.filter { downloads.packageFor(it.id) == null }
            .sortedWith(compareByDescending<Trail> { it.id == selectedId }
                .thenByDescending { it.importedAt })
        if (selected != null && selected in withoutImagery) {
            addNewTrailRow(container, selected, downloads.isCreating(selected.id))
        }
        items.forEach { addRegionRow(container, it) }
        withoutImagery.filterNot { it.id == selectedId }.forEach {
            addNewTrailRow(container, it, downloads.isCreating(it.id))
        }
        findViewById<TextView>(R.id.offline_notice).apply {
            text = when {
                downloads.loadError != null -> downloads.loadError
                !downloads.loaded -> getString(R.string.offline_library_loading)
                trailsError -> getString(R.string.offline_trails_error)
                !trailsLoaded -> getString(R.string.offline_library_loading)
                selectedId != null && selected == null -> getString(R.string.offline_selected_missing)
                items.isEmpty() && withoutImagery.isEmpty() -> getString(R.string.offline_library_empty)
                else -> ""
            }
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
        findViewById<Button>(R.id.offline_retry).visibility =
            if (downloads.loadError != null || trailsError) View.VISIBLE else View.GONE
    }

    private fun addNewTrailRow(container: LinearLayout, trail: Trail, creating: Boolean) {
        val row = LayoutInflater.from(this).inflate(R.layout.offline_list_item, container, false)
        row.findViewById<TextView>(R.id.offline_item_name).text = trail.name
        row.findViewById<Chip>(R.id.offline_item_route_chip).showRouteStatus(
            if (trail.id in availableRouteIds) RouteChipState.SAVED else RouteChipState.MISSING)
        row.findViewById<Chip>(R.id.offline_item_aerial_chip).showAerialStatus(when {
            downloads.loadError != null -> AerialChipState.ERROR
            !downloads.loaded -> AerialChipState.CHECKING
            creating -> AerialChipState.PREPARING
            downloads.errorFor(trail.id) != null -> AerialChipState.ERROR
            else -> AerialChipState.NOT_OFFLINE
        })
        row.findViewById<TextView>(R.id.offline_item_details).text = getString(R.string.offline_coverage_details)
        row.findViewById<TextView>(R.id.offline_item_note).apply {
            text = downloads.errorFor(trail.id).orEmpty()
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
        row.findViewById<LinearProgressIndicator>(R.id.offline_item_progress).visibility =
            if (creating) View.VISIBLE else View.GONE
        row.findViewById<Button>(R.id.offline_item_resume).apply {
            text = getString(R.string.download_offline_map)
            isEnabled = downloads.loaded && downloads.loadError == null && !creating &&
                trail.id in availableRouteIds
            setOnClickListener { downloads.start(trail) }
        }
        row.findViewById<Button>(R.id.offline_item_pause).visibility = View.GONE
        row.findViewById<Button>(R.id.offline_item_delete).visibility = View.GONE
        container.addView(row)
    }

    private fun addRegionRow(container: LinearLayout, item: OfflineDownloads.Package) {
        val row = LayoutInflater.from(this).inflate(R.layout.offline_list_item, container, false)
        val metadata = item.metadata
        val orphan = isOrphanedOffline(metadata != null, metadata?.trailId, trails.keys)
        row.findViewById<TextView>(R.id.offline_item_name).text =
            metadata?.trailName ?: getString(R.string.offline_unknown_region, item.region.id)
        row.findViewById<Chip>(R.id.offline_item_route_chip).showRouteStatus(when {
            !trailsLoaded || trailsError -> RouteChipState.CHECKING
            metadata == null -> RouteChipState.UNKNOWN
            metadata.trailId !in availableRouteIds -> RouteChipState.MISSING
            else -> RouteChipState.SAVED
        })
        row.findViewById<Chip>(R.id.offline_item_aerial_chip).showAerialStatus(when {
            item.deleting -> AerialChipState.DELETING
            metadata == null && item.libraryState == OfflineLibraryState.DOWNLOADING ->
                AerialChipState.DOWNLOADING
            metadata == null && item.libraryState == OfflineLibraryState.FAILED ->
                AerialChipState.ERROR
            metadata == null -> AerialChipState.UNKNOWN
            else -> AerialChipState.fromLibrary(item.libraryState)
        })
        row.findViewById<TextView>(R.id.offline_item_details).text = buildString {
            metadata?.let {
                append(getString(R.string.offline_zoom_created, it.minZoom, it.maxZoom,
                    DateFormat.getDateFormat(this@OfflineActivity).format(it.createdAt)))
            }
            item.status?.let {
                if (isNotEmpty()) append("\n")
                append(getString(R.string.offline_tiles_downloaded, it.completedTileCount))
                if (it.completedResourceSize > 0) {
                    append(" · ")
                    append(getString(R.string.offline_bytes_downloaded,
                        Formatter.formatFileSize(this@OfflineActivity, it.completedResourceSize)))
                }
                if (it.isRequiredResourceCountPrecise && it.requiredResourceCount > 0) {
                    append("\n")
                    append(getString(R.string.offline_resources_downloaded,
                        it.completedResourceCount, it.requiredResourceCount))
                }
            }
        }
        row.findViewById<TextView>(R.id.offline_item_note).apply {
            text = listOfNotNull(
                when {
                    !trailsLoaded || trailsError -> null
                    metadata == null -> getString(R.string.offline_unrecognized_details)
                    orphan -> getString(R.string.offline_orphaned_details)
                    metadata.trailId !in availableRouteIds -> getString(R.string.route_file_missing)
                    else -> null
                },
                item.error,
            ).joinToString("\n")
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
        row.findViewById<LinearProgressIndicator>(R.id.offline_item_progress).visibility =
            if (item.libraryState == OfflineLibraryState.DOWNLOADING) View.VISIBLE else View.GONE
        row.findViewById<Button>(R.id.offline_item_resume).apply {
            visibility = if (canResumeOffline(item.libraryState, metadata != null) && !item.deleting)
                View.VISIBLE else View.GONE
            setOnClickListener { downloads.resume(item) }
        }
        row.findViewById<Button>(R.id.offline_item_pause).apply {
            visibility = if (item.libraryState == OfflineLibraryState.DOWNLOADING && !item.deleting)
                View.VISIBLE else View.GONE
            setOnClickListener { downloads.pause(item) }
        }
        row.findViewById<Button>(R.id.offline_item_delete).apply {
            isEnabled = !item.deleting
            setOnClickListener {
                MaterialAlertDialogBuilder(this@OfflineActivity)
                    .setTitle(R.string.remove_offline)
                    .setMessage(R.string.offline_delete_confirmation)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.remove_offline) { _, _ -> downloads.delete(item) }
                    .show()
            }
        }
        container.addView(row)
    }

    override fun onStart() {
        super.onStart()
        downloads.listeners.add(listener)
        downloads.refresh()
        loadTrails()
        render()
    }

    override fun onStop() {
        downloads.listeners.remove(listener)
        super.onStop()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}
