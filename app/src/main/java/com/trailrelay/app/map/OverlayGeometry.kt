package com.trailrelay.app.map

import com.trailrelay.app.trails.GpxTrack
import com.trailrelay.app.trails.endpointMarkers
import org.maplibre.geojson.*

data class SelectedOverlay(val lines: String, val isolated: String, val endpoints: String)
data class OverlayGeometry(val browse: String, val selected: SelectedOverlay)

/** Single render-worker ownership. Retains only the current library and selected geometry.
 * Coordinates and GeoJSON serialization never run in a style callback on the UI thread.
 */
class OverlayPreparation {
    private val browseCache = mutableMapOf<String, Pair<GpxTrack, List<String>>>()
    var encodedRoutes: Int = 0
        private set
    private var previousBrowse: Map<String, GpxTrack>? = null
    private var browseJson = FeatureCollection.fromFeatures(emptyList()).toJson()
    private var previousSelected: GpxTrack? = null
    private var selected = selectedGeometry(null)
    private val emptyBrowse = FeatureCollection.fromFeatures(emptyList()).toJson()

    fun prepare(browse: Map<String, GpxTrack>, track: GpxTrack?, visible: Boolean = true): OverlayGeometry {
        encodedRoutes = 0
        browseCache.keys.retainAll(browse.keys)
        if (visible && previousBrowse !== browse) {
            val features = browse.flatMap { (id, geometry) ->
                browseCache[id]?.takeIf { it.first === geometry }?.second ?: geometry.segments
                    .filter { it.size >= 2 }.map { segment ->
                        Feature.fromGeometry(LineString.fromLngLats(segment.map { Point.fromLngLat(it.longitude, it.latitude) }))
                            .apply {
                                addStringProperty(TrailOverlay.TRAIL_ID, id)
                                addNumberProperty("browseStyle", BrowseTrailStyle.index(id))
                            }.toJson()
                    }.also {
                        browseCache[id] = geometry to it
                        encodedRoutes++
                    }
            }
            // Encode coordinates/properties once per changed route, not once per growing library
            // snapshot. Only the fixed collection envelope is assembled here; MapLibre's encoder
            // still handles every feature, including property escaping and coordinate precision.
            browseJson = features.joinToString(",", "{\"type\":\"FeatureCollection\",\"features\":[", "]}")
            previousBrowse = browse
        }
        if (previousSelected !== track) {
            selected = selectedGeometry(track)
            previousSelected = track
        }
        return OverlayGeometry(if (visible) browseJson else emptyBrowse, selected)
    }

    private fun selectedGeometry(track: GpxTrack?): SelectedOverlay = SelectedOverlay(
        MultiLineString.fromLngLats(track?.segments.orEmpty().filter { it.size >= 2 }.map { segment ->
            segment.map { Point.fromLngLat(it.longitude, it.latitude) }
        }).toJson(),
        MultiPoint.fromLngLats(track?.segments.orEmpty().filter { it.size == 1 }.map {
            Point.fromLngLat(it[0].longitude, it[0].latitude)
        }).toJson(),
        FeatureCollection.fromFeatures(track?.endpointMarkers().orEmpty().map { (point, label) ->
            Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude)).apply {
                addStringProperty("endpoint", "trail-endpoint-$label")
            }
        }).toJson(),
    )
}
