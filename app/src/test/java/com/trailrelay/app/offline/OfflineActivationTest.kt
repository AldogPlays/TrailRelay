package com.trailrelay.app.offline

import com.trailrelay.app.map.AERIAL_STYLE_URI
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class OfflineActivationTest {
    @Test fun newRegionSeedsExactAssetThenActivatesThenRequestsProgress() {
        val bytes = "{\"name\":\"USGS aerial imagery\"}".toByteArray()
        val events = mutableListOf<String>()
        activateOfflineRegion(false,
            readAsset = { path ->
                assertEquals("usgs_imagery.json", path)
                events.add("read")
                bytes
            },
            cacheResource = { uri, cached ->
                assertEquals(AERIAL_STYLE_URI, uri)
                assertSame(bytes, cached) // No alternate style, rewriting, or encoding conversion.
                events.add("cache")
            },
            activate = { events.add("active") },
            requestStatus = { events.add("status") })
        assertEquals(listOf("read", "cache", "active", "status"), events)
    }

    @Test fun restoredIncompleteRegionIsExplicitlyActivatedOnEachResume() {
        var activations = 0
        var queries = 0
        repeat(2) {
            activateOfflineRegion(false, { byteArrayOf(1) }, { _, _ -> },
                { activations++ }, { queries++ })
        }
        assertEquals(2, activations)
        assertEquals(2, queries)
    }

    @Test fun completedRegionIsNotReactivatedOrRewritten() {
        activateOfflineRegion(true,
            { error("Must not read asset") }, { _, _ -> error("Must not rewrite cache") },
            { error("Must not reactivate") }, { error("Must not request activation status") })
    }

    @Test fun unreadableAssetDoesNotStartAStalledDownload() {
        assertThrows(IOException::class.java) {
            activateOfflineRegion(false, { throw IOException("Asset unavailable") },
                { _, _ -> error("Must not cache") }, { error("Must not activate") }, { error("Must not query") })
        }
    }

    @Test fun cacheFailureDoesNotActivate() {
        assertThrows(IllegalStateException::class.java) {
            activateOfflineRegion(false, { byteArrayOf(1) }, { _, _ -> throw IllegalStateException("Cache failed") },
                { error("Must not activate") }, { error("Must not query") })
        }
    }
}
