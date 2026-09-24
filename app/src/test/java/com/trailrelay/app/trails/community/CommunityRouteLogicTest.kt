package com.trailrelay.app.trails.community

import com.trailrelay.app.trails.GpxTrack
import com.trailrelay.app.trails.TrackPoint
import com.trailrelay.app.trails.Trail
import com.trailrelay.app.trails.TrailSource
import org.junit.Assert.*
import org.junit.Test

class CommunityRouteLogicTest {
    private val localTrack = GpxTrack("Local", null, listOf(listOf(TrackPoint(1.0, 2.0), TrackPoint(1.1, 2.1))))
    private val remoteTrack = GpxTrack("Remote", null, listOf(listOf(TrackPoint(3.0, 4.0), TrackPoint(3.1, 4.1))))
    private val trail = Trail("community-a", "A", null, TrailSource.COMMUNITY, "trails/a.gpx",
        100.0, 1.0, 2.0, 1.1, 2.1, 1L, "a")

    @Test fun savedCommunityDownloadKeepsExistingRecord() {
        assertSame(trail, resolveCommunityDownload(trail, { true }) { error("Duplicate download") })
        var downloads = 0
        assertSame(trail, resolveCommunityDownload(trail, { false }) { downloads++; trail })
        assertEquals(1, downloads)
    }

    @Test fun savedCommunityAndImportedRoutesUseLocalGeometry() {
        assertSame(localTrack, resolveMapGeometry(TrailSource.COMMUNITY, true,
            { localTrack }, { error("Saved Community route must not fetch") }))
        assertSame(localTrack, resolveMapGeometry(TrailSource.IMPORTED, true,
            { localTrack }, { error("Imported route must not fetch") }))
    }

    @Test fun unsavedCommunityRouteUsesPreviewFetch() {
        assertSame(remoteTrack, resolveMapGeometry(TrailSource.COMMUNITY, false,
            { error("No local route") }, { remoteTrack }))
    }
}
