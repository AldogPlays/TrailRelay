package com.trailrelay.app.trails

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.trailrelay.app.ui.ContentShell
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.trailrelay.app.R
import com.trailrelay.app.MainActivity
import com.trailrelay.app.map.MapMode
import com.trailrelay.app.offline.OfflineActivity
import com.trailrelay.app.offline.OfflineDownloads
import com.trailrelay.app.offline.OfflineLibraryState
import com.trailrelay.app.offline.OfflineOperationState
import com.trailrelay.app.offline.offlineOperationState
import com.trailrelay.app.offline.offlineProgressMetrics
import com.trailrelay.app.offline.showOfflineProgress
import com.trailrelay.app.offline.label
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.chip.Chip
import com.trailrelay.app.trails.community.communityTrailId
import org.maplibre.android.MapLibre
import java.util.Locale

class TrailDetailActivity : AppCompatActivity() {
    private lateinit var model: TrailDetailModel
    private lateinit var downloads: OfflineDownloads
    private var wasDownloadingRoute = false
    private val offlineListener: () -> Unit = { render() }
    private val mapMode get() = MapMode.selected(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        ContentShell.install(this, R.string.trail_details, R.layout.activity_trail_detail, R.id.detail_root)
        downloads = OfflineDownloads.get(this)
        model = ViewModelProvider(this)[TrailDetailModel::class.java]
        model.initialize(intent.getStringExtra(EXTRA_LOCAL_ID), intent.getStringExtra(EXTRA_CATALOG_ID))
        findViewById<Button>(R.id.detail_primary).setOnClickListener {
            model.trail?.takeIf { model.localUsable }?.let {
                setResult(RESULT_OK, Intent().putExtra(MyTrailsActivity.EXTRA_TRAIL_ID, it.id))
                finish()
            } ?: model.download()
        }
        findViewById<Button>(R.id.detail_preview).setOnClickListener {
            model.entry?.let { entry ->
                setResult(RESULT_OK, Intent().putExtra(MainActivity.EXTRA_PREVIEW_ID, entry.id))
                finish()
            }
        }
        findViewById<Button>(R.id.detail_offline_action).setOnClickListener {
            model.trail?.let { trail ->
                val item = downloads.packageFor(trail.id, mapMode)
                when {
                    downloads.loadError != null -> startActivity(Intent(this, OfflineActivity::class.java)
                        .putExtra(MyTrailsActivity.EXTRA_TRAIL_ID, trail.id))
                    item == null -> downloads.start(trail, mapMode)
                    item.libraryState == OfflineLibraryState.DOWNLOADING -> downloads.pause(item)
                    item.libraryState == OfflineLibraryState.INCOMPLETE ||
                        item.libraryState == OfflineLibraryState.FAILED ->
                        downloads.resume(item)
                    else -> startActivity(Intent(this, OfflineActivity::class.java)
                        .putExtra(MyTrailsActivity.EXTRA_TRAIL_ID, trail.id))
                }
            }
        }
        findViewById<Button>(R.id.detail_source_action).setOnClickListener {
            model.entry?.sourceUrl?.let { url ->
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                catch (_: ActivityNotFoundException) {
                    findViewById<TextView>(R.id.detail_message).text = getString(R.string.no_browser)
                }
            }
        }
        model.onChange = ::render
        render()
    }

    private fun render() {
        val trail = model.trail
        val entry = model.entry
        val routeReady = trail != null && model.localUsable
        val canDownload = entry != null && (trail == null ||
            (trail.source == TrailSource.COMMUNITY && !model.localUsable))
        if (wasDownloadingRoute && !model.downloading && trail != null) {
            Snackbar.make(findViewById(R.id.detail_root), R.string.route_saved_snackbar, Snackbar.LENGTH_SHORT).show()
        }
        wasDownloadingRoute = model.downloading
        val name = entry?.name ?: trail?.name
        findViewById<TextView>(R.id.detail_name).text = name ?: getString(R.string.trail_details)
        findViewById<TextView>(R.id.detail_source).text = when {
            trail?.source == TrailSource.IMPORTED -> getString(R.string.source_imported)
            entry != null || trail?.source == TrailSource.COMMUNITY -> getString(R.string.source_community)
            else -> ""
        }
        findViewById<TextView>(R.id.detail_status).text = when {
            model.downloading -> getString(R.string.downloading_trail)
            model.loading -> getString(R.string.loading_trail)
            else -> ""
        }
        findViewById<TextView>(R.id.detail_status).visibility =
            if (model.downloading || model.loading) View.VISIBLE else View.GONE
        findViewById<View>(R.id.detail_status_chips).visibility =
            if (trail != null || entry != null) View.VISIBLE else View.GONE
        findViewById<Chip>(R.id.detail_route_chip).showRouteStatus(when {
            model.downloading || model.loading -> RouteChipState.CHECKING
            routeReady -> RouteChipState.SAVED
            trail != null -> RouteChipState.MISSING
            entry != null -> RouteChipState.NOT_SAVED
            else -> RouteChipState.ERROR
        })
        val statusTrailId = trail?.id ?: entry?.id?.let(::communityTrailId)
        findViewById<Chip>(R.id.detail_aerial_chip).showAerialStatus(
            if (trail?.let { downloads.packageFor(it.id, mapMode)?.deleting } == true) AerialChipState.DELETING
            else AerialChipState.fromImagery(statusTrailId?.let { imageryState(downloads, it, mapMode) }
                ?: ImageryState.CHECKING), mapMode)
        findViewById<TextView>(R.id.detail_description).apply {
            text = entry?.description ?: trail?.description
            visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        val details = findViewById<LinearLayout>(R.id.detail_fields)
        details.removeAllViews()
        val distance = entry?.distanceMiles ?: trail?.distanceMeters?.div(1609.344)
        distance?.let { field(details, getString(R.string.distance_label), String.format(Locale.getDefault(), "%.1f mi", it)) }
        entry?.difficulty?.let { field(details, getString(R.string.difficulty_label), it) }
        entry?.state?.let { field(details, getString(R.string.state_label), it) }
        entry?.region?.let { field(details, getString(R.string.region_label), it) }
        entry?.vehicleTypes?.takeIf { it.isNotEmpty() }?.let {
            field(details, getString(R.string.vehicle_types_label), it.joinToString(", "))
        }
        findViewById<View>(R.id.detail_fields_card).visibility = if (details.childCount == 0) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.detail_message).apply {
            text = model.message.orEmpty()
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
        findViewById<LinearProgressIndicator>(R.id.detail_progress).visibility =
            if (model.loading || model.downloading) View.VISIBLE else View.GONE
        val imagery = trail?.let { imageryState(downloads, it.id, mapMode) } ?: ImageryState.CHECKING
        val packageItem = trail?.let { downloads.packageFor(it.id, mapMode) }
        val operation = offlineOperationState(trail?.let { downloads.isCreating(it.id, mapMode) } == true,
            packageItem?.deleting == true, packageItem?.libraryState ?: when {
                downloads.loadError != null || trail?.let { downloads.errorFor(it.id, mapMode) } != null -> OfflineLibraryState.FAILED
                !downloads.loaded -> OfflineLibraryState.CHECKING
                else -> null
            })
        val actions = trailDetailActions(routeReady, model.downloading, imagery)
        findViewById<Button>(R.id.detail_primary).apply {
            text = getString(when {
                routeReady -> R.string.open_map
                model.downloading -> R.string.downloading_route
                trail != null -> R.string.retry_download
                model.message?.startsWith("Download failed:") == true -> R.string.retry_download
                else -> R.string.download_trail
            })
            visibility = if (actions.openMap || canDownload || model.downloading) View.VISIBLE else View.GONE
            isEnabled = !model.downloading && !model.loading
        }
        findViewById<Button>(R.id.detail_preview).apply {
            visibility = if (entry != null && trail == null) View.VISIBLE else View.GONE
            isEnabled = !model.loading && !model.downloading
        }
        findViewById<TextView>(R.id.detail_offline_state).apply {
            val error = if (operation == OfflineOperationState.FAILED) packageItem?.error
                ?: trail?.id?.let { downloads.errorFor(it, mapMode) } ?: downloads.loadError else null
            text = if (trail == null) "" else getString(mapMode.label) + " · " + getString(operation.label()) +
                (error?.let { "\n$it" } ?: "")
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
        findViewById<LinearProgressIndicator>(R.id.detail_offline_progress)
            .showOfflineProgress(operation, packageItem?.status)
        findViewById<TextView>(R.id.detail_offline_metrics).apply {
            text = offlineProgressMetrics(this@TrailDetailActivity, packageItem?.status)
            visibility = if (text.isBlank() || operation == OfflineOperationState.NONE) View.GONE else View.VISIBLE
        }
        findViewById<View>(R.id.detail_offline_card).visibility = if (trail == null) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.detail_offline_action).apply {
            visibility = if (actions.offlineAction && operation !in setOf(
                    OfflineOperationState.DELETING, OfflineOperationState.PREPARING))
                View.VISIBLE else View.GONE
            text = getString(when (imagery) {
                ImageryState.DOWNLOADING -> R.string.pause_offline
                ImageryState.COMPLETE, ImageryState.PREPARING -> R.string.manage_offline
                ImageryState.INCOMPLETE -> R.string.resume_offline
                ImageryState.FAILED -> if (downloads.loadError != null) R.string.manage_offline
                    else if (trail?.let { downloads.packageFor(it.id, mapMode) } == null)
                    R.string.download_offline_map else R.string.resume_offline
                else -> R.string.download_offline_map
            })
        }
        findViewById<Button>(R.id.detail_source_action).visibility =
            if (entry?.sourceUrl != null) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.detail_source_url).apply {
            text = entry?.sourceUrl.orEmpty()
            visibility = if (entry?.sourceUrl != null) View.VISIBLE else View.GONE
        }
    }

    private fun field(parent: LinearLayout, label: String, value: String) {
        val row = TextView(this).apply {
            text = "$label\n$value"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            setPadding(0, 0, 0, resources.getDimensionPixelSize(R.dimen.space_compact))
        }
        parent.addView(row)
    }

    override fun onStart() {
        super.onStart()
        downloads.listeners.add(offlineListener)
        downloads.refresh()
        model.refresh()
        render()
    }
    override fun onStop() {
        downloads.listeners.remove(offlineListener)
        super.onStop()
    }
    override fun onDestroy() {
        model.onChange = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_LOCAL_ID = "detailLocalId"
        const val EXTRA_CATALOG_ID = "detailCatalogId"
    }
}
