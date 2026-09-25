package com.trailrelay.app.trails

import com.trailrelay.app.trails.community.resolveCommunityDownload
import com.trailrelay.app.trails.community.resolveMapGeometry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrailStoreLogicTest {
    @get:Rule val files = TemporaryFolder()
    private val community = Trail("community-a", "A", null, TrailSource.COMMUNITY, "trail.gpx",
        100.0, 1.0, 2.0, 1.1, 2.1, 1L, "a")
    private val validGpx = """<gpx><trk><trkseg><trkpt lat="1" lon="2"/><trkpt lat="1.1" lon="2.1"/></trkseg></trk></gpx>"""

    @Test fun validSavedCommunityGpxIsUsedWithoutDownload() {
        val file = files.newFile("valid.gpx").apply { writeText(validGpx) }
        val local = readUsableGpx(file)
        assertNotNull(local)
        assertSame(local, resolveMapGeometry(TrailSource.COMMUNITY, local != null,
            { checkNotNull(local) }, { error("Valid local route must not fetch") }))
        assertSame(community, resolveCommunityDownload(community, { readUsableGpx(file) != null }) {
            error("Valid saved route must not be overwritten")
        })
    }

    @Test fun missingAndCorruptCommunityGpxAreEligibleForRepair() {
        val missing = files.root.resolve("missing.gpx")
        val corrupt = files.newFile("corrupt.gpx").apply { writeText("<gpx><trk>") }
        for (file in listOf(missing, corrupt)) {
            assertNull(readUsableGpx(file))
            var downloads = 0
            assertSame(community, resolveCommunityDownload(community,
                { readUsableGpx(file) != null }) { downloads++; community })
            assertEquals(1, downloads)
        }
    }

    @Test fun missingImportedGpxDoesNotFetchCommunityGeometry() {
        var fetched = false
        assertThrows(IllegalStateException::class.java) {
            resolveMapGeometry(TrailSource.IMPORTED, false, { error("Missing local GPX") }, {
                fetched = true
                error("Imported route must not fetch")
            })
        }
        assertFalse(fetched)
    }

    @Test fun routeRemovalDistinguishesPartialAndCompleteCleanup() {
        assertEquals(RouteRemovalResult.FAILED, routeRemovalResult(false, false))
        val file = files.newFile("route.gpx")
        assertEquals(RouteRemovalResult.COMPLETE, routeRemovalResult(true, removeStoredRouteFile(file)))
        assertFalse(file.exists())
        assertTrue(removeStoredRouteFile(file)) // A retry after completed cleanup is harmless.

        val nonEmptyDirectory = files.newFolder("unremovable")
        nonEmptyDirectory.resolve("child").writeText("data")
        assertEquals(RouteRemovalResult.FILE_CLEANUP_FAILED,
            routeRemovalResult(true, removeStoredRouteFile(nonEmptyDirectory)))
    }
}
