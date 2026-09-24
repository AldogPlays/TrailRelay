package com.trailrelay.app.trails

import android.content.Intent
import android.os.Bundle
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.trailrelay.app.ui.ContentShell
import com.trailrelay.app.ui.TrailRowsAdapter
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.chip.Chip
import com.trailrelay.app.R
import com.trailrelay.app.offline.OfflineDownloads
import org.maplibre.android.MapLibre
import java.util.Locale

class MyTrailsActivity : AppCompatActivity() {
    private lateinit var model: TrailLibraryModel
    private lateinit var downloads: OfflineDownloads
    private lateinit var trailAdapter: TrailRowsAdapter<Trail>
    private val offlineListener: () -> Unit = { render() }
    private val detail = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            setResult(RESULT_OK, result.data)
            finish()
        }
    }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::import)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        downloads = OfflineDownloads.get(this)
        ContentShell.install(this, R.string.my_trails, R.layout.activity_my_trails, R.id.trail_list)
        model = ViewModelProvider(this)[TrailLibraryModel::class.java]
        trailAdapter = TrailRowsAdapter(Trail::id, bind = { view, trail ->
            view.findViewById<TextView>(R.id.trail_row_name).text = trail.name
            view.findViewById<TextView>(R.id.trail_row_meta).text =
                "${getString(if (trail.source == TrailSource.COMMUNITY) R.string.source_community else R.string.source_imported)} · " +
                    String.format(Locale.getDefault(), "%.2f mi", trail.distanceMeters / 1609.344)
            view.findViewById<TextView>(R.id.trail_row_location).visibility = View.GONE
            view.findViewById<Chip>(R.id.trail_row_route_chip).showRouteStatus(
                if (trail.id in model.availableRouteIds) RouteChipState.SAVED else RouteChipState.MISSING)
            view.findViewById<Chip>(R.id.trail_row_aerial_chip).showAerialStatus(
                AerialChipState.fromImagery(imageryState(downloads, trail.id)))
        }, open = { trail ->
            if (!model.busy) detail.launch(Intent(this, TrailDetailActivity::class.java)
                .putExtra(TrailDetailActivity.EXTRA_LOCAL_ID, trail.id))
        })
        findViewById<RecyclerView>(R.id.trail_list).apply {
            layoutManager = LinearLayoutManager(this@MyTrailsActivity)
            adapter = trailAdapter
            itemAnimator = null
        }
        findViewById<Button>(R.id.import_gpx).setOnClickListener {
            picker.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream"))
        }
        model.onChange = ::render
        render()
    }

    private fun render() {
        findViewById<Button>(R.id.import_gpx).isEnabled = !model.busy
        findViewById<View>(R.id.library_progress).visibility = if (model.busy) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.library_message).apply {
            text = model.message ?: if (!model.busy && model.trails.isEmpty()) getString(R.string.no_trails) else ""
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
        trailAdapter.show(model.trails)
    }

    override fun onResume() {
        super.onResume()
        model.refresh()
    }

    override fun onStart() {
        super.onStart()
        downloads.listeners.add(offlineListener)
        downloads.refresh()
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

    companion object { const val EXTRA_TRAIL_ID = "trailId" }
}
