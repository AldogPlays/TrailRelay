package com.trailrelay.app.trails.community

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.trailrelay.app.ui.ContentShell
import com.trailrelay.app.ui.TrailRowsAdapter
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import com.trailrelay.app.R
import com.trailrelay.app.MainActivity
import com.trailrelay.app.offline.OfflineDownloads
import com.trailrelay.app.trails.TrailDetailActivity
import com.trailrelay.app.trails.AerialChipState
import com.trailrelay.app.trails.RouteChipState
import com.trailrelay.app.trails.fromImagery
import com.trailrelay.app.trails.imageryState
import com.trailrelay.app.trails.showAerialStatus
import com.trailrelay.app.trails.showRouteStatus
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.chip.Chip
import com.google.android.material.button.MaterialButton
import org.maplibre.android.MapLibre

class CommunityActivity : AppCompatActivity() {
    private lateinit var model: CommunityModel
    private lateinit var downloads: OfflineDownloads
    private val offlineListener: () -> Unit = { renderList() }
    private var visibleEntries = emptyList<CatalogEntry>()
    private lateinit var communityAdapter: TrailRowsAdapter<CatalogEntry>
    private val detail = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            setResult(RESULT_OK, result.data)
            finish()
        } else {
            model.refreshSaved()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        downloads = OfflineDownloads.get(this)
        ContentShell.install(this, R.string.community, R.layout.activity_community,
            R.id.community_list, R.layout.community_chrome)
        model = ViewModelProvider(this)[CommunityModel::class.java]
        communityAdapter = TrailRowsAdapter(CatalogEntry::id, bind = { view, entry ->
            view.apply {
                findViewById<TextView>(R.id.trail_row_name).text = entry.name
                findViewById<TextView>(R.id.trail_row_meta).text =
                    listOfNotNull(entry.distanceMiles?.let { "%.1f mi".format(it) },
                        entry.difficulty).joinToString(" · ")
                findViewById<TextView>(R.id.trail_row_location).apply {
                    text = listOfNotNull(entry.state, entry.region).joinToString(" · ")
                    visibility = if (text.isBlank()) View.GONE else View.VISIBLE
                }
                findViewById<Chip>(R.id.trail_row_route_chip).showRouteStatus(when {
                    model.savedError -> RouteChipState.ERROR
                    !model.savedLoaded -> RouteChipState.CHECKING
                    entry.id in model.savedRemoteIds -> RouteChipState.SAVED
                    else -> RouteChipState.NOT_SAVED
                })
                findViewById<Chip>(R.id.trail_row_aerial_chip).showAerialStatus(
                    AerialChipState.fromImagery(imageryState(downloads, communityTrailId(entry.id))))
                findViewById<Button>(R.id.trail_row_preview).apply {
                    visibility = View.VISIBLE
                    setOnClickListener {
                        setResult(RESULT_OK, Intent().putExtra(MainActivity.EXTRA_PREVIEW_ID, entry.id))
                        finish()
                    }
                }
            }
        }, open = { entry ->
            detail.launch(Intent(this, TrailDetailActivity::class.java)
                .putExtra(TrailDetailActivity.EXTRA_CATALOG_ID, entry.id))
        })

        findViewById<RecyclerView>(R.id.community_list).apply {
            layoutManager = LinearLayoutManager(this@CommunityActivity)
            adapter = communityAdapter
            itemAnimator = null
        }
        findViewById<Button>(R.id.community_filters).setOnClickListener {
            if (supportFragmentManager.findFragmentByTag("filters") == null)
                CommunityFiltersSheet().show(supportFragmentManager, "filters")
        }
        supportFragmentManager.setFragmentResultListener(CommunityFiltersSheet.RESULT, this) { _, _ -> renderList() }
        findViewById<EditText>(R.id.community_search).apply {
            setText(model.query)
            doAfterTextChanged { model.query = it.toString(); renderList() }
            setOnEditorActionListener { view, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) {
                    WindowInsetsControllerCompat(window, view).hide(WindowInsetsCompat.Type.ime())
                    view.clearFocus()
                    true
                } else false
            }
        }
        model.onChange = ::render
        render()
    }

    private fun render() {
        findViewById<LinearProgressIndicator>(R.id.community_progress).visibility =
            if (model.refreshing) View.VISIBLE else View.GONE
        renderList()
    }

    private fun renderList() {
        visibleEntries = CommunityCatalog.filter(model.entries, model.query, model.state, model.difficulty, model.vehicle)
        findViewById<TextView>(R.id.community_message).text = model.message + when {
            model.refreshing && visibleEntries.isEmpty() -> ""
            visibleEntries.isEmpty() && model.entries.isNotEmpty() -> "\n${getString(R.string.no_matching_trails)}"
            visibleEntries.isEmpty() -> "\n${getString(R.string.no_community_trails)}"
            else -> " · ${resources.getQuantityString(R.plurals.trail_count, visibleEntries.size, visibleEntries.size)}"
        }
        communityAdapter.show(visibleEntries)
        val active = listOfNotNull(model.state, model.difficulty, model.vehicle)
        findViewById<MaterialButton>(R.id.community_filters).apply {
            text = if (active.isEmpty()) "" else active.size.toString()
            contentDescription = if (active.isEmpty()) getString(R.string.filters)
                else getString(R.string.filters_count, active.size)
        }
    }

    override fun onStart() {
        super.onStart()
        downloads.listeners.add(offlineListener)
        downloads.refresh()
        model.refreshSaved()
        renderList()
    }

    override fun onStop() {
        downloads.listeners.remove(offlineListener)
        super.onStop()
    }

    override fun onDestroy() {
        model.onChange = null
        super.onDestroy()
    }
}
