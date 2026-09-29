package com.trailrelay.app.map

import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource

/** One shared source for the library and one for the selected route. Segments stay separate. */
object TrailOverlay {
    const val BROWSE_HIT_LAYER = "browse-trail-hit"
    const val TRAIL_ID = "trailId"
    private const val BROWSE_STYLE = "browseStyle"
    private const val BROWSE_SOURCE = "browse-trails"
    private const val SELECTED_SOURCE = "selected-trail"
    private const val POINTS_SOURCE = "selected-trail-points"

    fun render(style: Style, data: OverlayGeometry, updateBrowse: Boolean, updateSelected: Boolean) {
        val browseSource = style.getSourceAs<GeoJsonSource>(BROWSE_SOURCE)
        if (browseSource == null) {
            style.addSource(GeoJsonSource(BROWSE_SOURCE, data.browse))
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
        } else if (updateBrowse) browseSource.setGeoJson(data.browse)

        if (!updateSelected && style.getSource(SELECTED_SOURCE) != null) return
        val geometry = data.selected.lines
        val isolated = data.selected.isolated
        val selectedSource = style.getSourceAs<GeoJsonSource>(SELECTED_SOURCE)
        if (selectedSource != null) {
            selectedSource.setGeoJson(geometry)
            style.getSourceAs<GeoJsonSource>(POINTS_SOURCE)?.setGeoJson(isolated)
            renderEndpoints(style, data.selected.endpoints)
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
        renderEndpoints(style, data.selected.endpoints)
    }

    private fun renderEndpoints(style: Style, collection: String) {
        val source = style.getSourceAs<GeoJsonSource>("trail-endpoints")
        if (source != null) { source.setGeoJson(collection); return }
        style.addSource(GeoJsonSource("trail-endpoints", collection))
        // Local bitmaps avoid a remote glyph dependency, including after offline style reloads.
        for (label in listOf("A", "B", "A/B")) {
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            bitmap.density = 160 // Stable logical marker size on every screen density.
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.rgb(25, 25, 25)
            canvas.drawCircle(32f, 32f, 31f, paint)
            paint.color = Color.WHITE
            canvas.drawCircle(32f, 32f, 26f, paint)
            paint.color = Color.rgb(25, 25, 25)
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = if (label.length == 1) 38f else 26f
            canvas.drawText(label, 32f, 32f - (paint.ascent() + paint.descent()) / 2f, paint)
            style.addImage("trail-endpoint-$label", bitmap)
        }
        addBelowLocation(style, SymbolLayer("trail-endpoint-markers", "trail-endpoints").withProperties(
            iconImage(Expression.get("endpoint")), iconSize(0.45f), iconAllowOverlap(true),
            iconIgnorePlacement(true)))
    }

    private fun addBelowLocation(style: Style, layer: Layer) {
        val location = style.layers.firstOrNull {
            it.id.startsWith("mapbox-location") || it.id.startsWith("maplibre-location")
        }
        if (location == null) style.addLayer(layer) else style.addLayerBelow(layer, location.id)
    }
}
