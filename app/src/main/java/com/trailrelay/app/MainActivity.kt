package com.trailrelay.app

import android.Manifest
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.trailrelay.app.trails.TrailSession
import com.trailrelay.app.trails.TrailsOverlay
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.trailrelay.app.location.ForegroundLocation
import com.trailrelay.app.map.AerialMap
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView

class MainActivity : AppCompatActivity() {
    private lateinit var mapView: MapView
    private lateinit var aerialMap: AerialMap
    private lateinit var location: ForegroundLocation
    private lateinit var status: TextView
    private lateinit var trails: TrailsOverlay
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
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        status = findViewById(R.id.status)
        mapView = findViewById(R.id.map_view)
        mapView.onCreate(savedInstanceState)
        trails = TrailsOverlay(findViewById(R.id.main), ViewModelProvider(this)[TrailSession::class.java])
        aerialMap = AerialMap(this, mapView, savedInstanceState, trails::attach) {
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
        permissionRequested = savedInstanceState?.getBoolean("permissionRequested") ?: false
        if (!location.hasPermission() && !permissionRequested) requestLocation()
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
        mapView.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
        aerialMap.saveState(outState)
        outState.putBoolean("permissionRequested", permissionRequested)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        location.stop()
        trails.destroy()
        aerialMap.destroy()
        mapView.onDestroy()
        super.onDestroy()
    }
}
