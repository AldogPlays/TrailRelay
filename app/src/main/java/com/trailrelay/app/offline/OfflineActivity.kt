package com.trailrelay.app.offline

import android.os.Bundle
import android.util.Log
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.trailrelay.app.R
import com.trailrelay.app.trails.MyTrailsActivity
import com.trailrelay.app.trails.Trail
import com.trailrelay.app.trails.TrailStore
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.maplibre.android.MapLibre
import java.util.concurrent.Executors

class OfflineActivity : AppCompatActivity() {
    private lateinit var downloads: OfflineDownloads
    private var trail: Trail? = null
    private var estimate: Long? = null
    private var trailError: String? = null
    private var areaError: String? = null
    private var dialog: AlertDialog? = null
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
        findViewById<Button>(R.id.offline_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.offline_download).setOnClickListener {
            trail?.let { downloads.start(it) }
        }
        findViewById<Button>(R.id.offline_pause).setOnClickListener {
            trail?.let { downloads.packageFor(it.id) }?.let(downloads::pause)
        }
        findViewById<Button>(R.id.offline_retry).setOnClickListener { downloads.refresh() }
        findViewById<Button>(R.id.offline_delete).setOnClickListener {
            val item = trail?.let { downloads.packageFor(it.id) } ?: return@setOnClickListener
            dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.remove_offline)
                .setMessage("Remove this trail’s downloaded aerial coverage? Your trail and GPX will stay in My Trails.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.remove_offline) { _, _ -> downloads.delete(item) }.show()
        }
        val id = intent.getStringExtra(MyTrailsActivity.EXTRA_TRAIL_ID)
        if (id.isNullOrBlank()) {
            trailError = "Select a locally stored trail in My Trails first."
            render()
            return
        }
        val worker = Executors.newSingleThreadExecutor()
        worker.execute {
            val result = runCatching { TrailStore(applicationContext).use { it.get(id) ?: error("Missing trail $id") } }
            runOnUiThread {
                if (!isDestroyed) {
                    result.onSuccess {
                        trail = it
                        try {
                            val bounds = OfflineCoverage.padded(CoverageBounds(it.minLatitude, it.minLongitude, it.maxLatitude, it.maxLongitude))
                            estimate = OfflineCoverage.estimateTiles(bounds)
                            if (OfflineCoverage.isTooLarge(estimate!!)) areaError = "This trail area is too large for offline maps in this version (limit: fewer than 5,500 tiles)."
                        } catch (error: IllegalArgumentException) { areaError = error.message }
                    }.onFailure {
                        Log.e("TrailRelayOffline", "Loading trail $id", it)
                        trailError = "Unable to load this trail. Open a trail from My Trails and try again."
                    }
                    render()
                }
            }
        }
        worker.shutdown()
        render()
    }

    private fun render() {
        val selected = trail
        val item = selected?.let { downloads.packageFor(it.id) }
        val status = item?.status
        val creating = selected?.let { downloads.isCreating(it.id) } == true
        val busy = creating || item?.active == true || item?.deleting == true
        findViewById<TextView>(R.id.offline_title).text = selected?.name ?: getString(R.string.offline_maps)
        findViewById<TextView>(R.id.offline_details).text = buildString {
            append("Offline aerial coverage\nZooms 12–16\nAbout 1.5 km around the trail bounds")
            estimate?.let { append("\nApproximate tile count: %,d".format(it)) }
        }
        findViewById<TextView>(R.id.offline_state).text = when {
                trailError != null -> trailError
                selected == null -> "Loading trail…"
                downloads.loadError != null -> downloads.loadError
                !downloads.loaded -> "Checking saved offline maps…"
                item?.deleting == true -> "Removing offline coverage…"
                creating -> "Preparing download…"
                item?.complete == true -> listOfNotNull("Available Offline", item.error).joinToString("\n")
                item?.error != null -> item.error
                item?.active == true -> "Downloading aerial coverage…"
                item != null && status == null -> "Checking download progress…"
                item != null -> "Download incomplete or paused. Resume to finish."
                else -> "Ready to download."
            }
        findViewById<TextView>(R.id.offline_message).text = buildString {
            status?.let {
                append("%,d tiles downloaded".format(it.completedTileCount))
                append("\n${Formatter.formatFileSize(this@OfflineActivity, it.completedResourceSize)} downloaded")
                if (it.isRequiredResourceCountPrecise && it.requiredResourceCount > 0) {
                    append("\n%,d / %,d resources".format(it.completedResourceCount, it.requiredResourceCount))
                    append(" · ${offlinePercentage(it.completedResourceCount, it.requiredResourceCount, true)}%")
                }
            }
            listOfNotNull(areaError, selected?.let { downloads.errorFor(it.id) },
                downloads.metadataWarning).forEach {
                if (isNotEmpty()) append("\n\n")
                append(it)
            }
            if (isNotEmpty()) append("\n\n")
            append("Downloaded imagery is available inside this area at zooms 12–16. You can leave this screen during download. If Android stops the app, return here to resume.")
        }
        findViewById<LinearProgressIndicator>(R.id.offline_progress).apply {
            val indeterminate = status?.isRequiredResourceCountPrecise != true || status.requiredResourceCount <= 0
            if (isIndeterminate != indeterminate) {
                visibility = View.GONE
                isIndeterminate = indeterminate
            }
            if (!indeterminate) {
                setProgressCompat(offlinePercentage(status.completedResourceCount, status.requiredResourceCount, true) ?: 0, true)
            }
            visibility = if (busy) View.VISIBLE else View.GONE
        }
        findViewById<Button>(R.id.offline_download).apply {
            text = when {
                item?.complete == true -> getString(R.string.available_offline)
                item != null -> getString(R.string.resume_offline)
                else -> getString(R.string.download_offline)
            }
            isEnabled = selected != null && areaError == null && downloads.loaded && !busy &&
                item?.complete != true && (item == null || status != null)
        }
        findViewById<Button>(R.id.offline_pause).visibility = if (item?.active == true) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.offline_delete).apply {
            visibility = if (item != null) View.VISIBLE else View.GONE
            isEnabled = !busy
        }
        findViewById<Button>(R.id.offline_retry).visibility =
            if (downloads.loadError != null || (item?.error != null && status == null)) View.VISIBLE else View.GONE
    }

    override fun onStart() {
        super.onStart()
        downloads.listeners.add(listener)
        downloads.refresh()
        render()
    }

    override fun onStop() {
        downloads.listeners.remove(listener)
        super.onStop()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        super.onDestroy()
    }
}
