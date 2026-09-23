package com.trailrelay.app.trails

import org.junit.Assert.*
import org.junit.Test

class TrailDetailActionsTest {
    @Test fun remoteCommunityOnlyOffersDownload() {
        assertEquals(TrailDetailActions(true, false, false),
            trailDetailActions(false, false, ImageryState.NONE))
        assertEquals(TrailDetailActions(false, false, false),
            trailDetailActions(false, true, ImageryState.CHECKING))
    }

    @Test fun localTrailOpensMapAndCanManageImagery() {
        for (state in listOf(ImageryState.NONE, ImageryState.DOWNLOADING,
            ImageryState.INCOMPLETE, ImageryState.COMPLETE, ImageryState.FAILED)) {
            assertEquals(TrailDetailActions(false, true, true),
                trailDetailActions(true, false, state))
        }
        assertEquals(TrailDetailActions(false, true, false),
            trailDetailActions(true, false, ImageryState.CHECKING))
    }
}
