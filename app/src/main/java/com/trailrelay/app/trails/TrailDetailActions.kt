package com.trailrelay.app.trails

enum class ImageryState { CHECKING, NONE, PREPARING, DOWNLOADING, INCOMPLETE, COMPLETE, FAILED }

data class TrailDetailActions(
    val downloadTrail: Boolean,
    val openMap: Boolean,
    val offlineAction: Boolean,
)

fun trailDetailActions(local: Boolean, downloadingTrail: Boolean, imagery: ImageryState): TrailDetailActions =
    TrailDetailActions(
        downloadTrail = !local && !downloadingTrail,
        openMap = local,
        offlineAction = local && imagery != ImageryState.CHECKING,
    )
