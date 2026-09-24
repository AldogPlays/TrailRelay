package com.trailrelay.app.map

import android.graphics.Color
import com.trailrelay.app.trails.GpxTrack
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.MultiPoint
import org.maplibre.geojson.Point

/** One shared source for the library and one for the selected route. Segments stay separate. */
object TrailOverlay {
    const val BROWSE_HIT_LAYER = "browse-trail-hit"
    const val TRAIL_ID = "trailId"
    private const val BROWSE_STYLE = "browseStyle"
    private const val BROWSE_SOURCE = "browse-trails"
    private const val SELECTED_SOURCE = "selected-trail"
    private const val POINTS_SOURCE = "selected-trail-points"

    fun render(style: Style, browse: Map<String, GpxTrack>, selected: GpxTrack?) {
        val features = browse.flatMap { (id, track) ->
            track.segments.filter { it.size >= 2 }.map { segment ->
                Feature.fromGeometry(LineString.fromLngLats(segment.map { Point.fromLngLat(it.longitude, it.latitude) }))
                    .apply {
                        addStringProperty(TRAIL_ID, id)
                        addNumberProperty(BROWSE_STYLE, BrowseTrailStyle.index(id))
                    }
            }
        }
        val browseSource = style.getSourceAs<GeoJsonSource>(BROWSE_SOURCE)
        if (browseSource == null) {
            style.addSource(GeoJsonSource(BROWSE_SOURCE, FeatureCollection.fromFeatures(features)))
            addBelowLocation(style, LineLayer("browse-trail-casing", BROWSE_SOURCE).withProperties(
                lineColor(Color.rgb(20, 28, 32)), lineWidth(4f), lineOpacity(0.55f), lineCap("round"), lineJoin("round")))
            val palette = intArrayOf(Color.rgb(255, 214, 102), Color.rgb(116, 211, 222),
                Color.rgb(248, 161, 187), Color.rgb(176, 222, 143))
            palette.forEachIndexed { index, color ->
                val layer = LineLayer("browse-trail-line-$index", BROWSE_SOURCE).withProperties(
                    lineColor(color), lineWidth(2.6f), lineOpacity(0.9f),
                    lineCap("round"), lineJoin("round"))
                layer.setFilter(Expression.eq(Expression.get(BROWSE_STYLE), Expression.literal(index)))
                if (index == 1 || index == 3) layer.setProperties(lineDasharray(arrayOf(2f, 1.2f)))
                addBelowLocation(style, layer)
            }
            addBelowLocation(style, LineLayer(BROWSE_HIT_LAYER, BROWSE_SOURCE).withProperties(
                lineColor(Color.WHITE), lineWidth(20f), lineOpacity(0.01f), lineCap("round")))
        } else browseSource.setGeoJson(FeatureCollection.fromFeatures(features))

        val geometry = MultiLineString.fromLngLats(selected?.segments.orEmpty().filter { it.size >= 2 }.map { segment ->
            segment.map { Point.fromLngLat(it.longitude, it.latitude) }
        })
        val isolated = MultiPoint.fromLngLats(selected?.segments.orEmpty().filter { it.size == 1 }.map {
            Point.fromLngLat(it[0].longitude, it[0].latitude)
        })
        val selectedSource = style.getSourceAs<GeoJsonSource>(SELECTED_SOURCE)
        if (selectedSource != null) {
            selectedSource.setGeoJson(geometry)
            style.getSourceAs<GeoJsonSource>(POINTS_SOURCE)?.setGeoJson(isolated)
            return
        }
        style.addSource(GeoJsonSource(SELECTED_SOURCE, geometry))
        style.addSource(GeoJsonSource(POINTS_SOURCE, isolated))
        addBelowLocation(style, LineLayer("selected-trail-casing", SELECTED_SOURCE).withProperties(
            lineColor(Color.rgb(20, 28, 32)), lineWidth(9f), lineCap("round"), lineJoin("round")))
        addBelowLocation(style, LineLayer("selected-trail-line", SELECTED_SOURCE).withProperties(
            lineColor(Color.rgb(255, 220, 0)), lineWidth(5f), lineCap("round"), lineJoin("round")))
        addBelowLocation(style, CircleLayer("selected-trail-isolated-points", POINTS_SOURCE).withProperties(
            circleColor(Color.rgb(255, 220, 0)), circleRadius(3f),
            circleStrokeColor(Color.rgb(25, 25, 25)), circleStrokeWidth(2f)))
    }

    private fun addBelowLocation(style: Style, layer: Layer) {
        val location = style.layers.firstOrNull {
            it.id.startsWith("mapbox-location") || it.id.startsWith("maplibre-location")
        }
        if (location == null) style.addLayer(layer) else style.addLayerBelow(layer, location.id)
    }
}
