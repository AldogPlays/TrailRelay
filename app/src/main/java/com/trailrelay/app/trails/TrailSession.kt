package com.trailrelay.app.trails

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.ViewModel
import com.trailrelay.app.R
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.geojson.FeatureCollection
import java.util.concurrent.Executors

/** In-memory results and one request survive rotation; nothing is saved to disk. */
class TrailSession : ViewModel() {
    private val client = UsgsTrailsClient()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    var onChanged: (() -> Unit)? = null
    var trails: FeatureCollection = FeatureCollection.fromFeatures(emptyList())
        private set
    var selectedId: String? = null
    var loading = false
        private set
    var message: Int? = null
        private set

    fun load(bounds: LatLngBounds) {
        if (loading || closed) return
        loading = true
        message = null
        onChanged?.invoke()
        worker.execute {
            val result = runCatching {
                client.query(bounds.longitudeWest, bounds.latitudeSouth, bounds.longitudeEast, bounds.latitudeNorth)
            }
            result.exceptionOrNull()?.let { Log.w("TrailRelayTrails", "Viewport query failed", it) }
            main.post {
                if (!closed) {
                    loading = false
                    result.fold(onSuccess = {
                        trails = it
                        selectedId = null
                        message = if (it.features().isNullOrEmpty()) R.string.trails_empty else null
                        Log.i("TrailRelayTrails", "Loaded ${it.features()?.size} Terra Trail segments")
                    }, onFailure = {
                        message = if (it is ZoomInRequired) R.string.trails_zoom_in else R.string.trails_failed
                    })
                    onChanged?.invoke()
                }
            }
        }
    }

    override fun onCleared() {
        closed = true
        onChanged = null
        worker.shutdownNow()
        client.close()
        main.removeCallbacksAndMessages(null)
    }
}
