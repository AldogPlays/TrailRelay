package com.trailrelay.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/** Debug-build phase timings; never logs geometry, filenames or coordinates. */
object Perf {
    fun routeCache(phase: String, started: Long, detail: String) {
        if (!enabled) return
        val ms = (System.nanoTime() - started) / 1_000_000.0
        Log.d("TrailRelayPerf", "$phase ms=${"%.1f".format(java.util.Locale.ROOT, ms)} $detail")
    }
    var enabled = false
        private set
    fun configure(context: Context) {
        enabled = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }
    fun start() = if (enabled) SystemClock.elapsedRealtimeNanos() else 0L
    fun end(phase: String, start: Long, detail: String = "") {
        if (!enabled || start == 0L) return
        val ms = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        Log.d("TrailRelayPerf", "$phase ms=${"%.1f".format(java.util.Locale.ROOT, ms)} " +
            "thread=${if (Looper.myLooper() == Looper.getMainLooper()) "main" else "worker"} $detail")
    }
}
