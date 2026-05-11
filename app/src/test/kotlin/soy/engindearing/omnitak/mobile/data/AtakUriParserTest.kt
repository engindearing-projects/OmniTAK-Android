package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks down the ATAK URL-scheme parser so QR codes and onboarding portals
 * that already speak `tak://com.atakmap.app/<verb>?…` work against OmniTAK
 * the same way they work against ATAK / iTAK / TAKaware.
 */
class AtakUriParserTest {

    @Test fun import_subpath_yields_ImportFromUrl() {
        val result = AtakUriParser.parse(
            scheme = "tak",
            path = "/import",
            query = mapOf("url" to "https://download.osgeo.org/geotiff/samples/misc/tjpeg.tif"),
        )
        assertTrue("expected ImportFromUrl, was $result", result is DeepLinkAction.ImportFromUrl)
        assertEquals(
            "https://download.osgeo.org/geotiff/samples/misc/tjpeg.tif",
            (result as DeepLinkAction.ImportFromUrl).url,
        )
    }

    @Test fun import_rejects_non_http_schemes() {
        val result = AtakUriParser.parse(
            scheme = "tak",
            path = "/import",
            query = mapOf("url" to "file:///etc/passwd"),
        )
        assertEquals(DeepLinkAction.Unknown, result)
    }

    @Test fun preference_indexed_groups_parse_in_order() {
        val result = AtakUriParser.parse(
            scheme = "tak",
            path = "/preference",
            query = mapOf(
                "key1" to "displayRed", "type1" to "boolean", "value1" to "true",
                "key2" to "displayGreen", "type2" to "boolean", "value2" to "false",
            ),
        )
        assertTrue(result is DeepLinkAction.SetPreferences)
        val entries = (result as DeepLinkAction.SetPreferences).entries
        assertEquals(2, entries.size)
        assertEquals("displayRed", entries[0].key)
        assertEquals("boolean", entries[0].type)
        assertEquals("true", entries[0].value)
        assertEquals("displayGreen", entries[1].key)
        assertEquals("false", entries[1].value)
    }

    @Test fun preference_stops_walking_at_first_missing_index() {
        val result = AtakUriParser.parse(
            scheme = "tak",
            path = "/preference",
            // Skips index 2 — parser must stop, not return 3.
            query = mapOf(
                "key1" to "callsign", "type1" to "string", "value1" to "ENGIE-1",
                "key3" to "team", "type3" to "string", "value3" to "CYAN",
            ),
        )
        val entries = (result as DeepLinkAction.SetPreferences).entries
        assertEquals(1, entries.size)
        assertEquals("callsign", entries[0].key)
    }

    @Test fun preference_defaults_type_to_string_when_missing() {
        val result = AtakUriParser.parse(
            scheme = "tak",
            path = "/preference",
            query = mapOf("key1" to "callsign", "value1" to "ENGIE-1"),
        )
        val entry = (result as DeepLinkAction.SetPreferences).entries.first()
        assertEquals("string", entry.type)
    }

    @Test fun preference_empty_yields_Unknown() {
        val result = AtakUriParser.parse(
            scheme = "tak",
            path = "/preference",
            query = emptyMap(),
        )
        assertEquals(DeepLinkAction.Unknown, result)
    }

    @Test fun enroll_full_payload_parses_with_default_port_8446() {
        val result = AtakUriParser.parse(
            scheme = "tak",
            path = "/enroll",
            query = mapOf(
                "host" to "takserver.com",
                "username" to "foo",
                "token" to "user_token",
            ),
        )
        assertTrue(result is DeepLinkAction.Enroll)
        val e = result as DeepLinkAction.Enroll
        assertEquals("takserver.com", e.host)
        assertEquals("foo", e.username)
        assertEquals("user_token", e.token)
        assertEquals(8446, e.port)
    }

    @Test fun enroll_honours_explicit_enrollmentport() {
        val result = AtakUriParser.parse(
            scheme = "tak",
            path = "/enroll",
            query = mapOf(
                "host" to "takserver.com",
                "username" to "foo",
                "token" to "t",
                "enrollmentport" to "9446",
            ),
        )
        assertEquals(9446, (result as DeepLinkAction.Enroll).port)
    }

    @Test fun enroll_missing_token_yields_Unknown() {
        val result = AtakUriParser.parse(
            scheme = "tak",
            path = "/enroll",
            query = mapOf("host" to "takserver.com", "username" to "foo"),
        )
        assertEquals(DeepLinkAction.Unknown, result)
    }

    @Test fun connect_subpath_parses_as_AddServer() {
        val result = AtakUriParser.parse(
            scheme = "tak",
            path = "/connect",
            query = mapOf("host" to "tak.example.com", "port" to "8089", "proto" to "tls"),
        )
        assertTrue(result is DeepLinkAction.AddServer)
        val cfg = (result as DeepLinkAction.AddServer).config
        assertEquals("tak.example.com", cfg.host)
        assertEquals(8089, cfg.port)
        assertTrue(cfg.useTLS)
    }

    @Test fun root_path_with_host_still_parses_as_AddServer_for_back_compat() {
        // omnitak://server?host=…&port=…&tls=true legacy path
        val result = AtakUriParser.parse(
            scheme = "omnitak",
            path = "",
            query = mapOf("host" to "tak.engindearing.soy", "port" to "8089", "tls" to "true"),
        )
        assertTrue(result is DeepLinkAction.AddServer)
    }

    @Test fun unknown_scheme_yields_Unknown() {
        val result = AtakUriParser.parse(
            scheme = "mailto",
            path = "/foo",
            query = emptyMap(),
        )
        assertEquals(DeepLinkAction.Unknown, result)
    }
}
