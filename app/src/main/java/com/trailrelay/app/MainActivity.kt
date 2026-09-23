package com.trailrelay.app

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.trailrelay.app.location.ForegroundLocation
import com.trailrelay.app.map.AerialMap
import com.trailrelay.app.offline.OfflineActivity
import com.trailrelay.app.offline.OfflineDownloads
import com.trailrelay.app.trails.MyTrailsActivity
import com.trailrelay.app.trails.TrailStore
import java.util.concurrent.Executors
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView

class MainActivity : AppCompatActivity() {
    private lateinit var mapView: MapView
    private lateinit var aerialMap: AerialMap
    private lateinit var location: ForegroundLocation
    private lateinit var status: TextView
    private val trailWorker = Executors.newSingleThreadExecutor()
    private var selectedTrailId: String? = null
    private var trailRequest = 0
    private var pendingTrailFit = false
    private lateinit var offline: OfflineDownloads
    private val offlineListener: () -> Unit = { renderOfflineAction() }
    private var openedTrailId: String? = null
    private val libraryRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringExtra(MyTrailsActivity.EXTRA_TRAIL_ID)?.let { openTrail(it, true) }
        }
    }
    private var resumed = false
    private var locationStatus: Int? = null
    private var mapStatus: Int? = null
    private var permissionRequested = false
    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (location.hasPermission()) {
            refreshLocation()
        } else {
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
        val statusTopMargin = (status.layoutParams as FrameLayout.LayoutParams).topMargin
        WindowInsetsControllerCompat(window, findViewById(R.id.main)).isAppearanceLightStatusBars = false
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, 0, bars.right, bars.bottom)
            status.layoutParams = (status.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = statusTopMargin + bars.top
            }
            insets
        }
        mapView = findViewById(R.id.map_view)
        mapView.onCreate(savedInstanceState)
        aerialMap = AerialMap(this, mapView, savedInstanceState) {
            mapStatus = it
            renderStatus()
        }
        location = ForegroundLocation(this, aerialMap::showLocation) {
            locationStatus = it
            renderStatus()
        }
        status.setOnClickListener { if (mapStatus != null) aerialMap.loadStyle() }
        findViewById<ImageButton>(R.id.recenter).setOnClickListener {
            if (!location.hasPermission()) {
                requestLocation()
            } else {
                val hasFix = aerialMap.recenter()
                location.stop()
                refreshLocation()
                if (!hasFix) Log.i("TrailRelay", "Recenter waiting for a fresh location")
            }
        }
        findViewById<Button>(R.id.my_trails).setOnClickListener {
            libraryRequest.launch(Intent(this, MyTrailsActivity::class.java))
        }
        findViewById<Button>(R.id.offline_library).setOnClickListener {
            startActivity(Intent(this, OfflineActivity::class.java))
        }
        findViewById<Button>(R.id.selected_offline).setOnClickListener {
            openedTrailId?.let { id ->
                startActivity(Intent(this, OfflineActivity::class.java).putExtra(MyTrailsActivity.EXTRA_TRAIL_ID, id))
            }
        }
        savedInstanceState?.getString("selectedTrailId")?.let { openTrail(it, savedInstanceState.getBoolean("pendingTrailFit")) }
        permissionRequested = savedInstanceState?.getBoolean("permissionRequested") ?: false
        if (!location.hasPermission() && !permissionRequested) requestLocation()
    }

    private fun openTrail(id: String, fit: Boolean) {
        val previousTrailId = selectedTrailId
        selectedTrailId = id
        pendingTrailFit = fit
        val request = ++trailRequest
        trailWorker.execute {
            val result = runCatching {
                TrailStore(applicationContext).use { store ->
                    val trail = store.get(id) ?: error("Trail record is missing")
                    trail to store.load(trail)
                }
            }
            runOnUiThread {
                if (!isDestroyed && request == trailRequest) {
                    result.onSuccess { (trail, track) ->
                        openedTrailId = trail.id
                        aerialMap.showTrail(trail, track, fit)
                        renderOfflineAction()
                        pendingTrailFit = false
                    }
                        .onFailure {
                            selectedTrailId = previousTrailId
                            pendingTrailFit = false
                            Toast.makeText(this, R.string.trail_open_failed, Toast.LENGTH_LONG).show()
                        }
                }
            }
        }
    }

    private fun renderOfflineAction() {
        val item = openedTrailId?.let(offline::packageFor)
        val available = item?.complete == true && offline.loadError == null
        aerialMap.setOfflineTrail(openedTrailId.takeIf { available })
        findViewById<Button>(R.id.selected_offline).apply {
            visibility = if (openedTrailId != null) View.VISIBLE else View.GONE
            text = getString(when {
                available -> R.string.available_offline
                item != null -> R.string.manage_offline
                else -> R.string.download_offline_map
            })
        }
    }

    private fun requestLocation() {
        permissionRequested = true
        permissionRequest.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ))
    }

    private fun refreshLocation() {
        val allowed = location.hasPermission()
        aerialMap.setLocationAllowed(allowed)
        if (!allowed) {
            location.stop()
            locationStatus = R.string.permission_needed
            renderStatus()
        } else if (resumed) {
            location.start()
        }
    }

    private fun renderStatus() {
        val messages = listOfNotNull(mapStatus, locationStatus).distinct()
        status.text = messages.joinToString("\n") { getString(it) }
        status.visibility = if (messages.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
        offline.listeners.add(offlineListener)
        offline.refresh()
        renderOfflineAction()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        resumed = true
        refreshLocation()
    }

    override fun onPause() {
        resumed = false
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
        outState.putString("selectedTrailId", selectedTrailId)
        outState.putBoolean("pendingTrailFit", pendingTrailFit)
        outState.putBoolean("permissionRequested", permissionRequested)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        trailWorker.shutdown()
        location.stop()
        aerialMap.destroy()
        mapView.onDestroy()
        super.onDestroy()
    }
}
