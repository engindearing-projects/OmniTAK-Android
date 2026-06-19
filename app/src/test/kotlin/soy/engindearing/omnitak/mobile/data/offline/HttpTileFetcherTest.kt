package soy.engindearing.omnitak.mobile.data.offline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #139 — OSM serves its "Access blocked" placeholder tile as HTTP 200 with an
 * `X-Blocked` header (not a 4xx), so the offline downloader must reject it by
 * marker, not by status code; otherwise a bulk region pull caches the
 * placeholder PNG as if it were real map data. These lock the
 * [HttpTileFetcher.isBlocked] decision against the exact headers observed from
 * tile.openstreetmap.org.
 */
class HttpTileFetcherTest {

    /** Mirror HttpURLConnection.getHeaderField: case-insensitive name lookup. */
    private fun headers(vararg pairs: Pair<String, String>): (String) -> String? =
        { name -> pairs.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second }

    @Test fun blocked_when_xblocked_header_present() {
        // Exact response observed from tile.openstreetmap.org for a generic UA.
        val h = headers(
            "Content-Type" to "image/png",
            "Cache-Control" to "no-cache",
            "X-Blocked" to "Access denied. See https://operations.osmfoundation.org/policies/tiles/",
        )
        assertTrue(HttpTileFetcher.isBlocked(h))
    }

    @Test fun blocked_detection_is_case_insensitive() {
        // HTTP/2 lower-cases header names; getHeaderField looks up case-insensitively.
        assertTrue(HttpTileFetcher.isBlocked(headers("x-blocked" to "Access denied.")))
    }

    @Test fun not_blocked_for_real_tile_headers() {
        // Exact response observed for a real tile (OmniTAK UA): no X-Blocked.
        val h = headers(
            "Content-Type" to "image/png",
            "X-Tilerender" to "orm.openstreetmap.org",
            "ETag" to "\"8445651b6e17b9905333a90831cb3557\"",
        )
        assertFalse(HttpTileFetcher.isBlocked(h))
    }

    @Test fun not_blocked_when_no_headers() {
        assertFalse(HttpTileFetcher.isBlocked { null })
    }
}
