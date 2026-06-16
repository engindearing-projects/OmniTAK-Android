package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.xmlpull.v1.XmlPullParserFactory

/**
 * JVM unit tests for [KmlGeoJsonConverter] — verifies that KML Point
 * placemarks survive the streaming parse and that the `name` property is
 * written correctly into the GeoJSON output (issue #124).
 *
 * Uses [XmlPullParserFactory] (standard Java/JVM) via the internal
 * [KmlGeoJsonConverter.convert] overload so the test runs without an
 * Android device or Robolectric.
 */
class KmlPlacemarkConverterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val TWO_PLACEMARK_KML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <kml xmlns="http://www.opengis.net/kml/2.2">
          <Document>
            <Placemark>
              <name>Alpha Base</name>
              <Point>
                <coordinates>121.543000,25.012000,0</coordinates>
              </Point>
            </Placemark>
            <Placemark>
              <name>Bravo OP</name>
              <Point>
                <coordinates>121.564000,25.034000,0</coordinates>
              </Point>
            </Placemark>
          </Document>
        </kml>
    """.trimIndent()

    private fun makeParser() = XmlPullParserFactory.newInstance().apply {
        isNamespaceAware = false
    }.newPullParser()

    @Test fun two_point_placemarks_yield_two_features() {
        val out = tmp.newFile("test.geojson")
        val result = KmlGeoJsonConverter.convert(
            TWO_PLACEMARK_KML.byteInputStream(Charsets.UTF_8),
            out,
            makeParser(),
        )
        assertEquals("expected 2 features", 2, result.featureCount)
    }

    @Test fun name_property_present_in_geojson_output() {
        val out = tmp.newFile("names.geojson")
        KmlGeoJsonConverter.convert(
            TWO_PLACEMARK_KML.byteInputStream(Charsets.UTF_8),
            out,
            makeParser(),
        )
        val json = out.readText()
        assertTrue(
            "GeoJSON should contain 'Alpha Base' in name property",
            json.contains("\"name\":\"Alpha Base\""),
        )
        assertTrue(
            "GeoJSON should contain 'Bravo OP' in name property",
            json.contains("\"name\":\"Bravo OP\""),
        )
    }

    @Test fun bounding_box_reflects_both_point_coords() {
        val out = tmp.newFile("bbox.geojson")
        val result = KmlGeoJsonConverter.convert(
            TWO_PLACEMARK_KML.byteInputStream(Charsets.UTF_8),
            out,
            makeParser(),
        )
        // Placemarks are at 25.012 and 25.034 lat, 121.543 and 121.564 lon.
        assertEquals(25.012, result.minLat, 1e-5)
        assertEquals(25.034, result.maxLat, 1e-5)
        assertEquals(121.543, result.minLon, 1e-5)
        assertEquals(121.564, result.maxLon, 1e-5)
    }

    @Test fun output_is_valid_feature_collection() {
        val out = tmp.newFile("fc.geojson")
        KmlGeoJsonConverter.convert(
            TWO_PLACEMARK_KML.byteInputStream(Charsets.UTF_8),
            out,
            makeParser(),
        )
        val json = out.readText()
        assertTrue("must open as FeatureCollection", json.startsWith("{\"type\":\"FeatureCollection\""))
        assertTrue("must contain Point geometry type", json.contains("\"type\":\"Point\""))
    }
}
