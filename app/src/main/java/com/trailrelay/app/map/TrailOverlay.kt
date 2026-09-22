package com.trailrelay.app.map

import android.graphics.Color
import com.trailrelay.app.trails.GpxTrack
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.MultiPoint
import org.maplibre.geojson.Point

/** A dark casing keeps the bright trail visible over both pale and dark aerial imagery. */
object TrailOverlay {
    fun render(style: Style, track: GpxTrack) {
        val geometry = MultiLineString.fromLngLats(track.segments.filter { it.size >= 2 }.map { segment ->
            segment.map { Point.fromLngLat(it.longitude, it.latitude) }
        })
        val source = style.getSourceAs<GeoJsonSource>("selected-trail")
        val isolated = MultiPoint.fromLngLats(track.segments.filter { it.size == 1 }.map {
            Point.fromLngLat(it[0].longitude, it[0].latitude)
        })
        if (source != null) {
            style.getSourceAs<GeoJsonSource>("selected-trail-points")?.setGeoJson(isolated)
            source.setGeoJson(geometry)
            return
        }
        style.addSource(GeoJsonSource("selected-trail", geometry))
        style.addSource(GeoJsonSource("selected-trail-points", isolated))
        val points = CircleLayer("selected-trail-isolated-points", "selected-trail-points").withProperties(
            circleColor(Color.rgb(255, 220, 0)), circleRadius(3f),
            circleStrokeColor(Color.rgb(25, 25, 25)), circleStrokeWidth(2f))
        val casing = LineLayer("selected-trail-casing", "selected-trail").withProperties(
            lineColor(Color.rgb(25, 25, 25)), lineWidth(8f), lineCap("round"), lineJoin("round"))
        val line = LineLayer("selected-trail-line", "selected-trail").withProperties(
            lineColor(Color.rgb(255, 220, 0)), lineWidth(4f), lineCap("round"), lineJoin("round"))
        // Insert before the location component so the current-location indicator stays visible.
        val locationLayer = style.layers.firstOrNull { it.id.startsWith("mapbox-location") || it.id.startsWith("maplibre-location") }
        if (locationLayer != null) {
            style.addLayerBelow(casing, locationLayer.id)
            style.addLayerBelow(line, locationLayer.id)
            style.addLayerBelow(points, locationLayer.id)
        } else {
            style.addLayer(casing)
            style.addLayer(line)
            style.addLayer(points)
        }
    }
}
