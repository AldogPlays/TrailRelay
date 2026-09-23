package com.trailrelay.app.trails

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.chip.Chip
import com.trailrelay.app.R
import com.trailrelay.app.offline.OfflineDownloads
import com.trailrelay.app.trails.TrailSource
import org.maplibre.android.MapLibre
import java.util.Locale

class MyTrailsActivity : AppCompatActivity() {
    private lateinit var model: TrailLibraryModel
    private lateinit var downloads: OfflineDownloads
    private lateinit var trailAdapter: ArrayAdapter<Trail>
    private val offlineListener: () -> Unit = { render() }
    private val detail = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            setResult(RESULT_OK, result.data)
            finish()
        }
    }
    private val community = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
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
        enableEdgeToEdge()
        MapLibre.getInstance(this)
        downloads = OfflineDownloads.get(this)
        setContentView(R.layout.activity_my_trails)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.library)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        model = ViewModelProvider(this)[TrailLibraryModel::class.java]
        trailAdapter = object : ArrayAdapter<Trail>(this, R.layout.trail_list_item,
            R.id.trail_row_name, mutableListOf()) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                return super.getView(position, convertView, parent).apply {
                    val trail = getItem(position)!!
                    findViewById<TextView>(R.id.trail_row_name).text = trail.name
                    findViewById<TextView>(R.id.trail_row_meta).text =
                        "${getString(if (trail.source == TrailSource.COMMUNITY) R.string.source_community else R.string.source_imported)} · " +
                            String.format(Locale.getDefault(), "%.2f mi", trail.distanceMeters / 1609.344)
                    findViewById<TextView>(R.id.trail_row_location).visibility = View.GONE
                    findViewById<Chip>(R.id.trail_row_route_chip).showRouteStatus(
                        if (trail.id in model.availableRouteIds) RouteChipState.SAVED else RouteChipState.MISSING)
                    findViewById<Chip>(R.id.trail_row_aerial_chip).showAerialStatus(
                        AerialChipState.fromImagery(imageryState(downloads, trail.id)))
                }
            }
        }
        findViewById<ListView>(R.id.trail_list).adapter = trailAdapter
        findViewById<Button>(R.id.back_to_map).setOnClickListener { finish() }
        findViewById<Button>(R.id.import_gpx).setOnClickListener {
            picker.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream"))
        }
        findViewById<ListView>(R.id.trail_list).setOnItemClickListener { _, _, position, _ ->
            detail.launch(Intent(this, TrailDetailActivity::class.java)
                .putExtra(TrailDetailActivity.EXTRA_LOCAL_ID, model.trails[position].id))
        }
        findViewById<Button>(R.id.community).setOnClickListener {
            community.launch(Intent(this, com.trailrelay.app.trails.community.CommunityActivity::class.java))
        }
        model.onChange = ::render
        render()
    }

    private fun render() {
        findViewById<Button>(R.id.import_gpx).isEnabled = !model.busy
        findViewById<View>(R.id.library_progress).visibility = if (model.busy) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.library_message).text = model.message
            ?: if (!model.busy && model.trails.isEmpty()) getString(R.string.no_trails) else ""
        findViewById<ListView>(R.id.trail_list).apply {
            isEnabled = !model.busy
            if ((0 until trailAdapter.count).map { trailAdapter.getItem(it) } != model.trails) {
                trailAdapter.clear()
                trailAdapter.addAll(model.trails)
            } else {
                trailAdapter.notifyDataSetChanged()
            }
        }
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
