package com.trailrelay.app.offline

/** Native isComplete alone can be true for an imprecise/empty initial status. */
fun offlineComplete(completed: Long, required: Long, precise: Boolean) =
    precise && required > 0 && completed >= required

fun offlinePercentage(completed: Long, required: Long, precise: Boolean): Int? =
    if (precise && required > 0) (completed.toDouble() / required * 100).toInt().coerceIn(0, 100) else null
