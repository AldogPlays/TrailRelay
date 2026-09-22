package com.trailrelay.app.trails

import org.junit.Assert.*
import org.junit.Test

class GpxParserTest {
    private fun parse(xml: String) = xml.byteInputStream().use(GpxParser::parse)
    private fun gpx(body: String) = """<gpx xmlns="http://www.topografix.com/GPX/1/1">$body</gpx>"""
    private val segment = """<trkseg><trkpt lat="0" lon="0"><ele>123.5</ele></trkpt><trkpt lat="0" lon="1"/></trkseg>"""

    @Test fun deviceFixturesHaveExpectedGeometryAndFailure() {
        val track = javaClass.getResourceAsStream("/gpx/multi-segment.gpx")!!.use(GpxParser::parse)
        assertEquals("Milestone 2 test track", track.name)
        assertEquals(listOf(3, 2, 1), track.segments.map { it.size })
        assertTrue(track.distanceMeters in 750.0..900.0)
        assertInvalid(javaClass.getResource("/gpx/malformed.gpx")!!.readText())
    }

    @Test fun rejectsExcessiveNesting() {
        assertInvalid("<gpx>" + "<extensions>".repeat(70) + "</extensions>".repeat(70) + "</gpx>")
    }

    @Test fun readsTrackMetadataAndElevation() {
        val track = parse(gpx("<metadata><name>Fallback</name></metadata><trk><name> Ridge &amp; Creek </name><desc> A walk </desc>$segment</trk>"))
        assertEquals("Ridge & Creek", track.name)
        assertEquals("A walk", track.description)
        assertEquals(123.5, track.segments[0][0].elevation!!, 0.0)
        assertNull(track.segments[0][1].elevation)
    }

    @Test fun preservesSegmentsAndDoesNotMeasureGaps() {
        val track = parse(gpx("""<trk>$segment<trkseg><trkpt lat="40" lon="100"/><trkpt lat="40" lon="100"/></trkseg></trk><trk><trkseg><trkpt lat="50" lon="50"/></trkseg></trk>"""))
        assertEquals(listOf(2, 2, 1), track.segments.map { it.size })
        assertEquals(111_195.08, track.distanceMeters, 0.1)
    }

    @Test fun fallsBackToDocumentMetadata() {
        val track = parse(gpx("<metadata><name>Document</name><desc>Details</desc></metadata><trk>$segment</trk>"))
        assertEquals("Document", track.name)
        assertEquals("Details", track.description)
    }

    @Test fun supportsUnnamespacedAndGpx10Metadata() {
        assertEquals("Old trail", parse("<gpx><name>Old trail</name><trk>$segment</trk></gpx>").name)
        assertEquals(2, parse("""<gpx xmlns="http://www.topografix.com/GPX/1/0"><trk>$segment</trk></gpx>""").segments[0].size)
    }

    @Test fun supportsPrefixedNamespace() {
        val xml = """<g:gpx xmlns:g="http://www.topografix.com/GPX/1/1"><g:trk><g:trkseg><g:trkpt lat="1" lon="2"/><g:trkpt lat="2" lon="3"/></g:trkseg></g:trk></g:gpx>"""
        assertEquals(2, parse(xml).segments[0].size)
    }

    @Test fun ignoresExtensionsIncludingLookalikeTracks() {
        val track = parse(gpx("""<extensions><trk><name>Wrong</name>$segment</trk></extensions><trk xmlns:x="urn:vendor"><x:name>Wrong</x:name><name>Right</name><extensions><x:trkpt lat="bad"/></extensions>$segment</trk>"""))
        assertEquals("Right", track.name)
        assertEquals(1, track.segments.size)
    }

    @Test fun missingMetadataIsOptional() {
        val track = parse(gpx("<trk>$segment</trk>"))
        assertNull(track.name)
        assertNull(track.description)
    }

    @Test fun rejectsMalformedXmlAndNonGpx() {
        listOf("<gpx><trk>", "<html/>", "", gpx("<trk>$segment</trk>") + "garbage").forEach(::assertInvalid)
    }

    @Test fun rejectsInvalidCoordinatesAndElevation() {
        listOf("lat=\"91\" lon=\"0\"", "lat=\"0\" lon=\"181\"", "lon=\"1\"", "lat=\"NaN\" lon=\"0\"",
            "lat=\"0\" lon=\"Infinity\"").forEach {
            assertInvalid(gpx("<trk><trkseg><trkpt $it/><trkpt lat=\"0\" lon=\"0\"/></trkseg></trk>"))
        }
        assertInvalid(gpx("<trk>${segment.replace("123.5", "oops")}</trk>"))
    }

    @Test fun rejectsEmptyAndWaypointOrRouteOnlyDocuments() {
        listOf("", "<trk><trkseg/></trk>", "<wpt lat=\"1\" lon=\"2\"/>",
            "<rte><rtept lat=\"1\" lon=\"2\"/></rte>",
            "<trk><trkseg><trkpt lat=\"1\" lon=\"2\"/></trkseg></trk>").forEach { assertInvalid(gpx(it)) }
    }

    @Test fun rejectsDoctypeAndExternalEntities() {
        assertInvalid("""<!DOCTYPE gpx [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><gpx><trk><name>&xxe;</name>$segment</trk></gpx>""")
        assertInvalid("<!DOCTYPE gpx SYSTEM \"https://example.invalid/external.dtd\"><gpx><trk>$segment</trk></gpx>")
    }

    @Test fun distanceHandlesIdenticalPointsAndAntimeridian() {
        val p = TrackPoint(10.0, 20.0)
        assertEquals(0.0, distanceMeters(p, p), 0.0)
        assertEquals(222_390.16, distanceMeters(TrackPoint(0.0, 179.0), TrackPoint(0.0, -179.0)), 0.1)
        assertEquals(20_015_114.44, distanceMeters(TrackPoint(0.0, 0.0), TrackPoint(0.0, 180.0)), 0.1)
    }

    private fun assertInvalid(xml: String) {
        try {
            parse(xml)
            fail("Expected invalid GPX")
        } catch (error: GpxException) {
            assertFalse(error.message.isNullOrBlank())
        }
    }
}
