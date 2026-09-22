package com.trailrelay.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.trailrelay.app.R

/** Platform-only foreground updates. No Activity or location work survives stop(). */
class ForegroundLocation(
    private val context: Context,
    private val onLocation: (Location) -> Unit,
    private val onStatus: (Int?) -> Unit,
) : LocationListener {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var latest: Location? = null
    private val waiting = Runnable { onStatus(R.string.location_waiting) }
    private val providersChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (started) subscribe()
        }
    }

    fun hasPermission(): Boolean = has(Manifest.permission.ACCESS_COARSE_LOCATION) ||
        has(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun has(permission: String) = ContextCompat.checkSelfPermission(context, permission) ==
        PackageManager.PERMISSION_GRANTED

    fun start() {
        if (started || !hasPermission()) return
        started = true
        ContextCompat.registerReceiver(context, providersChanged,
            IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        subscribe()
    }

    @SuppressLint("MissingPermission") // Checked at start and rechecked after provider changes.
    private fun subscribe() {
        manager.removeUpdates(this)
        handler.removeCallbacks(waiting)
        if (!hasPermission()) {
            onStatus(R.string.permission_needed)
            return
        }
        try {
            val enabled = manager.getProviders(true)
            val fine = has(Manifest.permission.ACCESS_FINE_LOCATION)
            val providers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                LocationManager.FUSED_PROVIDER in enabled) {
                listOf(LocationManager.FUSED_PROVIDER)
            } else {
                listOfNotNull(
                    LocationManager.GPS_PROVIDER.takeIf { fine && it in enabled },
                    LocationManager.NETWORK_PROVIDER.takeIf { it in enabled },
                )
            }
            if (providers.isEmpty()) {
                Log.w(TAG, "No enabled foreground location provider (precise=$fine)")
                onStatus(R.string.location_disabled)
                return
            }
            onStatus(R.string.location_waiting)
            handler.postDelayed(waiting, 30_000)
            for (provider in providers) {
                manager.requestLocationUpdates(provider, 2_000L, 1f, this, Looper.getMainLooper())
            }
            Log.i(TAG, "Foreground providers: $providers")
            providers.mapNotNull { manager.getLastKnownLocation(it) }
                .filter(::isUsable).sortedByDescending { it.accuracy }
                .forEach(::onLocationChanged)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to subscribe to platform location", error)
            handler.removeCallbacks(waiting)
            onStatus(R.string.location_failed)
        }
    }

    override fun onLocationChanged(location: Location) {
        if (!started || !isUsable(location)) return
        val previous = latest
        // Prevent a coarse network fix from displacing a recent, more accurate GPS fix.
        if (previous != null && location.elapsedRealtimeNanos < previous.elapsedRealtimeNanos) return
        if (previous != null && ageMillis(previous) < 15_000 &&
            location.accuracy > previous.accuracy * 2) return
        latest = Location(location)
        handler.removeCallbacks(waiting)
        onStatus(null)
        onLocation(Location(location))
    }

    override fun onProviderEnabled(provider: String) {
        Log.i(TAG, "Provider enabled: $provider")
    }

    override fun onProviderDisabled(provider: String) {
        Log.w(TAG, "Provider disabled: $provider")
        // The PROVIDERS_CHANGED broadcast resubscribes to the available providers.
    }

    @Deprecated("Required for older Android versions")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        Log.i(TAG, "Provider status: $provider, $status")
    }

    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacks(waiting)
        manager.removeUpdates(this)
        context.unregisterReceiver(providersChanged)
    }

    companion object {
        private const val TAG = "TrailRelayLocation"
        private fun ageMillis(location: Location) =
            (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000

        fun isUsable(location: Location): Boolean =
            location.latitude.isFinite() && location.latitude in -90.0..90.0 &&
                location.longitude.isFinite() && location.longitude in -180.0..180.0 &&
                location.hasAccuracy() && location.accuracy.isFinite() && location.accuracy > 0 &&
                ageMillis(location) in 0..120_000
    }
}
