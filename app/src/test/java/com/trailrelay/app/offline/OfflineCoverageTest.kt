package com.trailrelay.app.offline

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos

class OfflineCoverageTest {
    @Test fun paddingAddsAtLeastFifteenHundredMetersOnEverySide() {
        for (latitude in listOf(0.0, 40.0, 65.0, -65.0)) {
            val original = CoverageBounds(latitude, -110.0, latitude + 0.1, -109.9)
            val result = OfflineCoverage.padded(original)
            val metersPerDegree = Math.PI * 6_371_008.8 / 180
            assertEquals(1500.0, (original.south - result.south) * metersPerDegree, 0.001)
            assertEquals(1500.0, (result.north - original.north) * metersPerDegree, 0.001)
            for (edge in listOf(original.south, original.north)) {
                assertTrue((original.west - result.west) * metersPerDegree * cos(Math.toRadians(edge)) >= 1500)
                assertTrue((result.east - original.east) * metersPerDegree * cos(Math.toRadians(edge)) >= 1500)
            }
        }
    }

    @Test fun countsKnownWebMercatorTilesAndSumsZooms() {
        // Northeast quadrant, deliberately away from tile boundaries: one tile at z1, four at z2.
        val bounds = CoverageBounds(1.0, 1.0, 80.0, 179.0)
        assertEquals(1L, OfflineCoverage.estimateTiles(bounds, 1, 1))
        assertEquals(4L, OfflineCoverage.estimateTiles(bounds, 2, 2))
        assertEquals(5L, OfflineCoverage.estimateTiles(bounds, 1, 2))
        assertEquals(5L, OfflineCoverage.estimateTiles(CoverageBounds(40.0, -110.0, 40.0, -110.0)))
    }

    @Test fun boundaryTilesAreConservativeAndWorldEdgesDoNotOverflow() {
        // Eastern and southern edges lie exactly on z1 boundaries and include their neighbors.
        assertEquals(4L, OfflineCoverage.estimateTiles(CoverageBounds(0.0, -1.0, 1.0, 0.0), 1, 1))
        assertEquals(16L, OfflineCoverage.estimateTiles(CoverageBounds(-85.0, -180.0, 85.0, 180.0), 2, 2))
    }

    @Test fun rejectsLargeAreasAndThresholdItself() {
        val bounds = OfflineCoverage.padded(CoverageBounds(35.0, -120.0, 45.0, -110.0))
        assertTrue(OfflineCoverage.isTooLarge(OfflineCoverage.estimateTiles(bounds)))
        assertFalse(OfflineCoverage.isTooLarge(5499))
        assertTrue(OfflineCoverage.isTooLarge(5500))
        assertTrue(OfflineCoverage.isTooLarge(5501))
        assertFalse(OfflineCoverage.isTooLarge(OfflineCoverage.estimateTiles(
            OfflineCoverage.padded(CoverageBounds(40.0, -110.0, 40.01, -109.99)))))
    }

    @Test fun rejectsInvalidAndUnsupportedBounds() {
        listOf(CoverageBounds(Double.NaN, 0.0, 1.0, 1.0),
            CoverageBounds(5.0, 0.0, 1.0, 1.0), CoverageBounds(0.0, 5.0, 1.0, 1.0),
            CoverageBounds(-90.0, 0.0, 0.0, 1.0), CoverageBounds(0.0, 0.0, 86.0, 1.0),
            CoverageBounds(0.0, -180.0, 1.0, -179.0), CoverageBounds(0.0, 179.0, 1.0, 180.0),
            CoverageBounds(0.0, Double.NEGATIVE_INFINITY, 1.0, 0.0)).forEach { bounds ->
            assertThrows(IllegalArgumentException::class.java) { OfflineCoverage.padded(bounds) }
        }
    }
}
