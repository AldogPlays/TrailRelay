package com.trailrelay.app.map

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RasterStyleConfigTest {
    @Test fun bundledUsgsStylesUseNativeTileDimensionsAndSupportedDisplayLevel() {
        for (name in listOf("usgs_imagery", "usgs_hybrid", "usgs_topo")) {
            val style = JSONObject(File("src/main/assets/$name.json").readText())
            val sources = style.getJSONObject("sources")
            assertEquals(1, sources.length())
            val source = sources.getJSONObject(sources.keys().next())
            assertEquals("raster", source.getString("type"))
            assertEquals(256, source.getInt("tileSize"))
            assertEquals(16, source.getInt("maxzoom"))
            assertTrue(source.getJSONArray("tiles").getString(0).contains("basemap.nationalmap.gov"))
        }
    }
}
