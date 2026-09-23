package com.trailrelay.app.trails

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import com.trailrelay.app.R
import com.trailrelay.app.offline.OfflineActivity
import com.trailrelay.app.offline.OfflineDownloads
import com.trailrelay.app.offline.OfflineLibraryState
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.chip.Chip
import com.trailrelay.app.trails.community.communityTrailId
import org.maplibre.android.MapLibre
import java.util.Locale

class TrailDetailActivity : AppCompatActivity() {
    private lateinit var model: TrailDetailModel
    private lateinit var downloads: OfflineDownloads
    private lateinit var trailStore: TrailStore
    private val offlineListener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_trail_detail)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.detail_root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        downloads = OfflineDownloads.get(this)
        trailStore = TrailStore(applicationContext)
        model = ViewModelProvider(this)[TrailDetailModel::class.java]
        model.initialize(intent.getStringExtra(EXTRA_LOCAL_ID), intent.getStringExtra(EXTRA_CATALOG_ID))
        findViewById<Button>(R.id.detail_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.detail_primary).setOnClickListener {
            model.trail?.let {
                setResult(RESULT_OK, Intent().putExtra(MyTrailsActivity.EXTRA_TRAIL_ID, it.id))
                finish()
            } ?: model.download()
        }
        findViewById<Button>(R.id.detail_offline_action).setOnClickListener {
            model.trail?.let { trail ->
                val item = downloads.packageFor(trail.id)
                when {
                    downloads.loadError != null -> startActivity(Intent(this, OfflineActivity::class.java)
                        .putExtra(MyTrailsActivity.EXTRA_TRAIL_ID, trail.id))
                    item == null -> downloads.start(trail)
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
            trail != null && trailStore.hasLocalGpx(trail) -> RouteChipState.SAVED
            trail != null -> RouteChipState.MISSING
            entry != null -> RouteChipState.NOT_SAVED
            else -> RouteChipState.ERROR
        })
        val statusTrailId = trail?.id ?: entry?.id?.let(::communityTrailId)
        findViewById<Chip>(R.id.detail_aerial_chip).showAerialStatus(
            AerialChipState.fromImagery(statusTrailId?.let { imageryState(downloads, it) }
                ?: ImageryState.CHECKING))
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
        val imagery = trail?.let { imageryState(downloads, it.id) } ?: ImageryState.CHECKING
        val actions = trailDetailActions(trail != null, model.downloading, imagery)
        findViewById<Button>(R.id.detail_primary).apply {
            text = getString(if (trail != null) R.string.open_map else R.string.download_trail)
            visibility = if (actions.openMap || actions.downloadTrail || model.downloading) View.VISIBLE else View.GONE
            isEnabled = !model.downloading && !model.loading
        }
        findViewById<TextView>(R.id.detail_offline_state).apply {
            text = if (trail == null) "" else when (imagery) {
                ImageryState.CHECKING, ImageryState.PREPARING -> ""
                ImageryState.NONE -> getString(R.string.detail_aerial_absent)
                ImageryState.DOWNLOADING -> getString(R.string.detail_aerial_downloading)
                ImageryState.INCOMPLETE -> getString(R.string.detail_aerial_incomplete)
                ImageryState.COMPLETE -> getString(R.string.detail_aerial_complete)
                ImageryState.FAILED -> downloads.packageFor(trail.id)?.error ?: downloads.errorFor(trail.id)
                    ?: downloads.loadError ?: getString(R.string.offline_failed)
            }
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
        findViewById<View>(R.id.detail_offline_card).visibility = if (trail == null) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.detail_offline_action).apply {
            visibility = if (actions.offlineAction) View.VISIBLE else View.GONE
            text = getString(when (imagery) {
                ImageryState.COMPLETE, ImageryState.DOWNLOADING, ImageryState.PREPARING -> R.string.manage_offline
                ImageryState.INCOMPLETE -> R.string.resume_offline
                ImageryState.FAILED -> if (downloads.loadError != null) R.string.manage_offline
                    else if (trail?.let { downloads.packageFor(it.id) } == null)
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
            setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
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
        trailStore.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_LOCAL_ID = "detailLocalId"
        const val EXTRA_CATALOG_ID = "detailCatalogId"
    }
}
