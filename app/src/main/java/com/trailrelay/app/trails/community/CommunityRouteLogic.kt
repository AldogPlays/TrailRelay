package com.trailrelay.app.trails.community

import com.trailrelay.app.trails.GpxTrack
import com.trailrelay.app.trails.Trail
import com.trailrelay.app.trails.TrailSource

/** Select the only allowed map geometry source for a saved route or a Community preview. */
fun resolveMapGeometry(source: TrailSource, hasLocalGpx: Boolean,
                       loadLocal: () -> GpxTrack, fetchPreview: () -> GpxTrack): GpxTrack = when {
    hasLocalGpx -> loadLocal()
    source == TrailSource.IMPORTED -> error("Saved GPX file is missing")
    else -> fetchPreview()
}

/** Reuse the existing Community identity when its GPX is still present. */
fun resolveCommunityDownload(existing: Trail?, usable: (Trail) -> Boolean, download: () -> Trail): Trail =
    existing?.takeIf(usable) ?: download()
