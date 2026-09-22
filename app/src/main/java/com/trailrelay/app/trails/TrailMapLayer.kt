package com.trailrelay.app.trails

import android.graphics.RectF
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.*
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection

/** One GeoJSON source; expressions change the selected segment's color and width. */
class TrailMapLayer(private val map: MapLibreMap, style: Style) {
    private val source = GeoJsonSource(SOURCE)
    private val casing = LineLayer("trail-casing", SOURCE).withProperties(
        lineColor("#263238"), lineCap("round"), lineJoin("round"), lineWidth(6f),
    )
    private val line = LineLayer(LINE, SOURCE).withProperties(
        lineColor("#66E5ED"), lineCap("round"), lineJoin("round"), lineWidth(3f),
    )
    private var data: FeatureCollection? = null
    private var byId = emptyMap<String, Trail>()

    init {
        style.addSource(source)
        style.addLayer(casing)
        style.addLayer(line)
    }

    fun display(collection: FeatureCollection, selectedId: String?) {
        if (data !== collection) {
            data = collection
            byId = collection.features().orEmpty().map(::Trail).associateBy { it.id }
            source.setGeoJson(collection)
        }
        val selected = eq(toString(get("objectid")), literal(selectedId ?: ""))
        line.setProperties(
            lineColor(switchCase(selected, literal("#FFD54F"), literal("#66E5ED"))),
            lineWidth(switchCase(selected, literal(5f), literal(3f))),
        )
        casing.setProperties(lineWidth(switchCase(selected, literal(8f), literal(6f))))
    }

    fun hit(point: LatLng, radius: Float): Trail? {
        val screen = map.projection.toScreenLocation(point)
        val bounds = RectF(screen.x - radius, screen.y - radius, screen.x + radius, screen.y + radius)
        return map.queryRenderedFeatures(bounds, LINE).firstOrNull()?.let {
            it.getProperty("objectid")?.asString?.let(byId::get)
        }
    }

    companion object {
        private const val SOURCE = "usgs-trails"
        private const val LINE = "trail-lines"
    }
}
