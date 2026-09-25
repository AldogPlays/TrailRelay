package com.trailrelay.app.map

import org.junit.Assert.*
import org.junit.Test

class MapModeTest {
    @Test fun persistedIdsDefaultSafely() {
        assertEquals(MapMode.AERIAL, MapMode.fromId(null))
        assertEquals(MapMode.AERIAL, MapMode.fromId("unknown"))
        MapMode.entries.forEach { assertEquals(it, MapMode.fromId(it.id)) }
    }

    @Test fun onlyLatestStyleRequestIsCurrent() {
        val requests = MapModeRequests()
        val aerial = requests.next()
        val hybrid = requests.next()
        val topo = requests.next()
        assertFalse(requests.isCurrent(aerial))
        assertFalse(requests.isCurrent(hybrid))
        assertTrue(requests.isCurrent(topo))
    }
}
