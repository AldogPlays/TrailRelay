package com.trailrelay.app.trails

import android.view.View
import android.widget.Button
import android.widget.TextView
import com.trailrelay.app.R
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

/** Small Views UI binding, detached on Activity destruction. */
class TrailsOverlay(private val root: View, private val session: TrailSession) {
    private val button = root.findViewById<Button>(R.id.trails_button)
    private val status = root.findViewById<TextView>(R.id.trails_status)
    private val panel = root.findViewById<View>(R.id.trail_panel)
    private val name = root.findViewById<TextView>(R.id.trail_name)
    private val details = root.findViewById<TextView>(R.id.trail_details)
    private var map: MapLibreMap? = null
    private var layer: TrailMapLayer? = null
    private val click = MapLibreMap.OnMapClickListener { point ->
        if (map?.style?.isFullyLoaded == true) {
            val trail = layer?.hit(point, 14f * root.resources.displayMetrics.density)
            session.selectedId = trail?.id
            render()
            trail != null
        } else false
    }

    init {
        button.setOnClickListener {
            map?.takeIf { it.style?.isFullyLoaded == true }?.let {
                session.load(it.projection.visibleRegion.latLngBounds)
            }
        }
        session.onChanged = ::render
        render()
    }

    fun attach(ready: MapLibreMap, style: Style) {
        map?.removeOnMapClickListener(click)
        map = ready
        layer = TrailMapLayer(ready, style)
        ready.addOnMapClickListener(click)
        render()
    }

    private fun render() {
        button.isEnabled = map != null && !session.loading
        button.setText(if (session.loading) R.string.trails_loading else R.string.trails)
        status.visibility = if (session.message == null) View.GONE else View.VISIBLE
        session.message?.let(status::setText)
        if (map?.style?.isFullyLoaded == true) layer?.display(session.trails, session.selectedId)
        val selected = session.trails.features().orEmpty().firstOrNull {
            it.getProperty("objectid")?.asString == session.selectedId
        }?.let(::Trail)
        panel.visibility = if (selected == null) View.GONE else View.VISIBLE
        name.text = selected?.name
        details.text = selected?.details()
    }

    fun destroy() {
        session.onChanged = null
        map?.removeOnMapClickListener(click)
        layer = null
        map = null
    }
}
