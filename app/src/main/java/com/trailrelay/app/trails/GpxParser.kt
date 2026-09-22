package com.trailrelay.app.trails

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.ext.DefaultHandler2
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory

class GpxException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Namespace-aware streaming parser. Only direct standard GPX track structures are consumed. */
object GpxParser {
    fun parse(input: InputStream): GpxTrack {
        val handler = TrackHandler()
        try {
            val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
                .newSAXParser().xmlReader
            reader.setFeature("http://xml.org/sax/features/external-general-entities", false)
            reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
            reader.contentHandler = handler
            reader.errorHandler = handler
            reader.entityResolver = handler
            reader.parse(InputSource(input))
        } catch (error: SAXException) {
            throw GpxException("Invalid GPX: ${error.message ?: "malformed XML"}", error)
        }
        if (handler.segments.none { it.size >= 2 }) {
            throw GpxException("This GPX has no track segment with at least two points. Routes and waypoints are not supported.")
        }
        return GpxTrack(handler.name ?: handler.metadataName,
            handler.description ?: handler.metadataDescription, handler.segments)
    }

    private class TrackHandler : DefaultHandler2() {
        val segments = mutableListOf<List<TrackPoint>>()
        var name: String? = null
        var description: String? = null
        var metadataName: String? = null
        var metadataDescription: String? = null
        private val path = mutableListOf<String>()
        private val text = StringBuilder()
        private var namespace = ""
        private var segment = mutableListOf<TrackPoint>()
        private var point: TrackPoint? = null
        private var pointCount = 0

        override fun startDTD(name: String?, publicId: String?, systemId: String?) {
            throw SAXException("DOCTYPE declarations are not supported.")
        }
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource {
            throw SAXException("External entities are not supported.")
        }
        override fun error(e: SAXParseException) { throw e }
        override fun fatalError(e: SAXParseException) { throw e }

        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            if (path.isEmpty()) {
                if (localName != "gpx" || uri !in setOf("", "http://www.topografix.com/GPX/1/0", "http://www.topografix.com/GPX/1/1")) {
                    throw SAXException("Expected a GPX document.")
                }
                namespace = uri
            }
            if (path.size >= 64) throw SAXException("XML nesting is too deep.")
            path.add(if (uri == namespace) localName else "#extension")
            text.setLength(0)
            when (path.joinToString("/")) {
                "gpx/trk/trkseg" -> segment = mutableListOf()
                "gpx/trk/trkseg/trkpt" -> {
                    if (++pointCount > 250_000) throw SAXException("Track exceeds the 250,000 point import limit.")
                    val lat = coordinate(attributes.getValue("lat"), -90.0, 90.0, "latitude")
                    val lon = coordinate(attributes.getValue("lon"), -180.0, 180.0, "longitude")
                    point = TrackPoint(lat, lon)
                }
            }
        }

        private fun coordinate(value: String?, min: Double, max: Double, label: String): Double =
            value?.toDoubleOrNull()?.takeIf { it.isFinite() && it in min..max }
                ?: throw SAXException("Missing or invalid track point $label.")

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (text.length + length > 65_536) throw SAXException("An XML text value is too long.")
            text.append(ch, start, length)
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            val value = text.toString().trim().takeIf { it.isNotEmpty() }
            when (path.joinToString("/")) {
                "gpx/metadata/name", "gpx/name" -> if (metadataName == null) metadataName = value
                "gpx/metadata/desc", "gpx/desc" -> if (metadataDescription == null) metadataDescription = value
                "gpx/trk/name" -> if (name == null) name = value
                "gpx/trk/desc" -> if (description == null) description = value
                "gpx/trk/trkseg/trkpt/ele" -> if (value != null) {
                    val elevation = value.toDoubleOrNull()?.takeIf { it.isFinite() }
                        ?: throw SAXException("Invalid track point elevation.")
                    point = point?.copy(elevation = elevation)
                }
                "gpx/trk/trkseg/trkpt" -> { segment.add(checkNotNull(point)); point = null }
                "gpx/trk/trkseg" -> if (segment.isNotEmpty()) segments.add(segment.toList())
            }
            path.removeAt(path.lastIndex)
            text.setLength(0)
        }
    }
}
