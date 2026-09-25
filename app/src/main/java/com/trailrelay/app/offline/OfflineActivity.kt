package com.trailrelay.app.offline

import android.os.Bundle
import com.trailrelay.app.ui.ContentShell
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.text.format.Formatter
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.chip.Chip
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.trailrelay.app.R
import com.trailrelay.app.map.MapMode
import com.trailrelay.app.trails.AerialChipState
import com.trailrelay.app.trails.MyTrailsActivity
import com.trailrelay.app.trails.RouteChipState
import com.trailrelay.app.trails.Trail
import com.trailrelay.app.trails.TrailStore
import com.trailrelay.app.trails.RouteRemovalResult
import com.trailrelay.app.trails.fromLibrary
import com.trailrelay.app.trails.showAerialStatus
import com.trailrelay.app.trails.showRouteStatus
import org.maplibre.android.MapLibre
import java.util.concurrent.Executors
import android.widget.Toast

class OfflineActivity : AppCompatActivity() {
    private lateinit var downloads: OfflineDownloads
    private val worker = Executors.newSingleThreadExecutor()
    private var trails: Map<String, Trail> = emptyMap()
    private var availableRouteIds: Set<String> = emptySet()
    private var routeSizes: Map<String, Long> = emptyMap()
    private var trailsLoaded = false
    private var trailsError = false
    private var selectedId: String? = null
    private val listener: () -> Unit = { render() }
    private val mapMode get() = MapMode.selected(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        ContentShell.install(this, R.string.offline_library, R.layout.activity_offline, R.id.offline_root)
        downloads = OfflineDownloads.get(this)
        selectedId = intent.getStringExtra(MyTrailsActivity.EXTRA_TRAIL_ID)
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
                    val local = listed.filter(store::hasLocalGpx)
                    Triple(listed.associateBy(Trail::id), local.map(Trail::id).toSet(),
                        local.mapNotNull { trail -> store.localGpxSize(trail)?.let { trail.id to it } }.toMap())
                }
            }
            runOnUiThread {
                if (!isDestroyed) {
                    result.onSuccess { (listed, availableIds, sizes) ->
                        trails = listed
                        availableRouteIds = availableIds
                        routeSizes = sizes
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
        val withoutImagery = trails.values.filter { downloads.packageFor(it.id, mapMode) == null }
            .sortedWith(compareByDescending<Trail> { it.id == selectedId }
                .thenByDescending { it.importedAt })
        if (selected != null && selected in withoutImagery) {
            addNewTrailRow(container, selected, downloads.isCreating(selected.id, mapMode))
        }
        items.forEach { addRegionRow(container, it) }
        withoutImagery.filterNot { it.id == selectedId }.forEach {
            addNewTrailRow(container, it, downloads.isCreating(it.id, mapMode))
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
        renderStorageSummary(items)
    }

    private fun renderStorageSummary(items: List<OfflineDownloads.Package>) {
        val routeBytes = routeSizes.values.sum()
        val mapBytes = items.sumOf { it.status?.completedResourceSize?.coerceAtLeast(0L) ?: 0L }
        val parts = listOfNotNull(
            routeBytes.takeIf { it > 0 }?.let { "Routes: ${Formatter.formatFileSize(this, it)}" },
            mapBytes.takeIf { it > 0 }?.let { "Downloaded map resources: ${Formatter.formatFileSize(this, it)}" })
        findViewById<TextView>(R.id.offline_summary).text = if (parts.isEmpty())
            getString(R.string.offline_library_intro) else parts.joinToString(" · ")
    }

    private fun confirmRemoveRoute(trail: Trail) {
        val message = if (trail.source == com.trailrelay.app.trails.TrailSource.IMPORTED)
            getString(R.string.remove_imported_route_confirmation, trail.name)
        else getString(R.string.remove_community_route_confirmation, trail.name)
        MaterialAlertDialogBuilder(this).setTitle(R.string.remove_route).setMessage(message)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.remove_route) { _, _ ->
                worker.execute {
                    val result = runCatching { TrailStore(applicationContext).use { it.removeLocalRoute(trail) } }
                        .getOrDefault(RouteRemovalResult.FAILED)
                    runOnUiThread { if (!isDestroyed) {
                        loadTrails()
                        when (result) {
                            RouteRemovalResult.COMPLETE -> Unit
                            RouteRemovalResult.FILE_CLEANUP_FAILED -> showRouteCleanupFailure(trail)
                            RouteRemovalResult.FAILED -> Toast.makeText(this,
                                R.string.route_removal_failed, Toast.LENGTH_LONG).show()
                        }
                    } }
                }
            }.show()
    }

    private fun showRouteCleanupFailure(trail: Trail) {
        MaterialAlertDialogBuilder(this).setTitle(R.string.route_cleanup_failed_title)
            .setMessage(R.string.route_cleanup_failed_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.retry_cleanup) { _, _ ->
                worker.execute {
                    val cleaned = runCatching { TrailStore(applicationContext).use {
                        it.retryRouteFileCleanup(trail)
                    } }.getOrDefault(false)
                    runOnUiThread { if (!isDestroyed) {
                        Toast.makeText(this, if (cleaned) R.string.route_cleanup_complete
                            else R.string.route_cleanup_still_failed, Toast.LENGTH_LONG).show()
                    } }
                }
            }.show()
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
            downloads.errorFor(trail.id, mapMode) != null -> AerialChipState.ERROR
            else -> AerialChipState.NOT_OFFLINE
        }, mapMode)
        row.findViewById<TextView>(R.id.offline_item_details).text = buildString {
            routeSizes[trail.id]?.let { append(getString(R.string.route_size,
                Formatter.formatFileSize(this@OfflineActivity, it))) }
            if (isNotEmpty()) append("\n")
            append(getString(R.string.offline_coverage_details))
        }
        row.findViewById<TextView>(R.id.offline_item_note).apply {
            text = downloads.errorFor(trail.id, mapMode).orEmpty()
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
        val state = offlineOperationState(creating, false,
            when {
                downloads.loadError != null || downloads.errorFor(trail.id, mapMode) != null -> OfflineLibraryState.FAILED
                !downloads.loaded -> OfflineLibraryState.CHECKING
                else -> null
            })
        row.findViewById<TextView>(R.id.offline_item_state).setText(state.label())
        row.findViewById<LinearProgressIndicator>(R.id.offline_item_progress)
            .showOfflineProgress(state, null)
        row.findViewById<Button>(R.id.offline_item_resume).apply {
            text = getString(if (downloads.errorFor(trail.id, mapMode) != null) R.string.retry_offline
                else R.string.download_offline_map)
            isEnabled = downloads.loaded && downloads.loadError == null && !creating &&
                trail.id in availableRouteIds
            setOnClickListener { downloads.start(trail, mapMode) }
        }
        row.findViewById<Button>(R.id.offline_item_pause).visibility = View.GONE
        row.findViewById<Button>(R.id.offline_item_delete).visibility = View.GONE
        row.findViewById<Button>(R.id.offline_item_remove_route).apply {
            visibility = if (trail.id in availableRouteIds) View.VISIBLE else View.GONE
            setOnClickListener { confirmRemoveRoute(trail) }
        }
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
        }, metadata?.mapType ?: MapMode.AERIAL)
        row.findViewById<TextView>(R.id.offline_item_details).text = buildString {
            fun line(value: String) { if (isNotEmpty()) append("\n"); append(value) }
            val routeSize = metadata?.trailId?.let(routeSizes::get)
            routeSize?.let { line(getString(R.string.route_size, Formatter.formatFileSize(this@OfflineActivity, it))) }
            item.status?.completedResourceSize?.takeIf { it > 0 }?.let {
                line("${getString(metadata?.mapType?.label ?: R.string.map_title)} map resources · ${Formatter.formatFileSize(this@OfflineActivity, it)}")
            }
            metadata?.let {
                line(getString(R.string.offline_zoom_created, it.minZoom, it.maxZoom,
                    DateFormat.getDateFormat(this@OfflineActivity).format(it.createdAt)))
            }
            offlineProgressMetrics(this@OfflineActivity, item.status).takeIf(String::isNotBlank)?.let {
                line(it)
            }
        }
        row.findViewById<TextView>(R.id.offline_item_note).apply {
            text = listOfNotNull(
                when {
                    !trailsLoaded || trailsError -> null
                    metadata == null -> getString(R.string.offline_unrecognized_details)
                    orphan -> getString(R.string.offline_orphaned_details)
                    metadata.trailId !in trails -> getString(R.string.route_not_saved_aerial_kept)
                    metadata.trailId !in availableRouteIds -> getString(R.string.route_file_missing)
                    else -> null
                },
                item.error,
            ).joinToString("\n")
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
        val operation = offlineOperationState(false, item.deleting, item.libraryState)
        row.findViewById<TextView>(R.id.offline_item_state).setText(operation.label())
        row.findViewById<LinearProgressIndicator>(R.id.offline_item_progress)
            .showOfflineProgress(operation, item.status)
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
                    .setMessage(getString(R.string.offline_delete_confirmation,
                        getString(metadata?.mapType?.label ?: R.string.map_title)))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.remove_offline) { _, _ -> downloads.delete(item) }
                    .show()
            }
        }
        row.findViewById<Button>(R.id.offline_item_remove_route).apply {
            val trail = metadata?.trailId?.let(trails::get)?.takeIf { it.id in availableRouteIds }
            visibility = if (trail != null) View.VISIBLE else View.GONE
            setOnClickListener { trail?.let(::confirmRemoveRoute) }
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
