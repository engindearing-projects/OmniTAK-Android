package soy.engindearing.omnitak.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Ditto wire schema is the cross-platform contract: iOS writes these
 * documents with `CoTWireMeta` and Android must produce byte-identical keys
 * and fields, or two phones stand next to each other and silently never
 * merge pictures. Every decision here is pure — no SDK, no clock, no I/O.
 */
class DittoWireTest {

    private val pli = """
        <event version="2.0" uid="ANDROID-abc123" type="a-f-G-U-C" how="m-g"
               time="2026-08-24T10:00:00.000Z" start="2026-08-24T10:00:00.000Z"
               stale="2026-08-24T10:03:00.000Z">
          <point lat="47.6588" lon="-117.4260" hae="562.0" ce="10.0" le="9999999.0"/>
          <detail><contact callsign="ENGIE"/><__group name="Cyan" role="Team Member"/></detail>
        </event>
    """.trimIndent()

    private val chat = """
        <event version="2.0" uid="GeoChat.ANDROID-abc123.All Chat Rooms.msg-1" type="b-t-f"
               time="2026-08-24T10:00:00Z" start="2026-08-24T10:00:00Z" stale="2026-08-24T10:03:00Z">
          <point lat="0" lon="0" hae="0" ce="9999999" le="9999999"/>
          <detail><remarks>on my way</remarks></detail>
        </event>
    """.trimIndent()

    private val now = 1_756_000_000_000L

    // ---- meta extraction ---------------------------------------------------

    @Test fun `meta pulls uid type point and stale from a PLI`() {
        val m = DittoWire.meta(pli, now)!!
        assertEquals("ANDROID-abc123", m.uid)
        assertEquals("a-f-G-U-C", m.type)
        assertEquals(47.6588, m.lat, 1e-9)
        assertEquals(-117.4260, m.lon, 1e-9)
        assertEquals(now, m.sentAt)
        // 2026-08-24T10:03:00.000Z
        assertEquals(1_787_565_780_000L, m.staleAt)
    }

    @Test fun `meta requires uid and type`() {
        assertNull(DittoWire.meta("<event type=\"a-f-G\"/>", now))
        assertNull(DittoWire.meta("<event uid=\"X\"/>", now))
        assertNull(DittoWire.meta("<event uid=\"\" type=\"a-f-G\"/>", now))
    }

    @Test fun `meta tolerates whole-second and fractional stale timestamps`() {
        val m = DittoWire.meta(chat, now)!!
        assertEquals(1_787_565_780_000L, m.staleAt)
    }

    @Test fun `unparseable or missing stale means never-expires zero`() {
        assertEquals(0L, DittoWire.parseIsoMs(null))
        assertEquals(0L, DittoWire.parseIsoMs(""))
        assertEquals(0L, DittoWire.parseIsoMs("not-a-date"))
        val noStale = "<event uid=\"X\" type=\"a-f-G\"/>"
        assertEquals(0L, DittoWire.meta(noStale, now)!!.staleAt)
    }

    @Test fun `attr scans only the first match and stops at the closing quote`() {
        assertEquals("A", DittoWire.attr("uid", "<event uid=\"A\"><link uid=\"B\"/></event>"))
        assertNull(DittoWire.attr("uid", "<event type=\"a\"/>"))
    }

    // ---- document keying ---------------------------------------------------

    @Test fun `state events key per uid so updates upsert in place`() {
        val m = DittoWire.meta(pli, now)!!
        assertEquals("omnitak|ANDROID-abc123", DittoWire.docId("omnitak", m))
        // Same uid a minute later — same key, so the marker moves instead of duplicating.
        val later = DittoWire.meta(pli, now + 60_000)!!
        assertEquals(DittoWire.docId("omnitak", m), DittoWire.docId("omnitak", later))
    }

    @Test fun `chat events key per message so history survives`() {
        val first = DittoWire.meta(chat, now)!!
        val second = DittoWire.meta(chat, now + 1)!!
        assertTrue(DittoWire.docId("omnitak", first) != DittoWire.docId("omnitak", second))
        assertEquals("omnitak|${first.uid}|$now", DittoWire.docId("omnitak", first))
    }

    @Test fun `channel prefixes the key so rooms never collide`() {
        val m = DittoWire.meta(pli, now)!!
        assertTrue(DittoWire.docId("alpha", m) != DittoWire.docId("bravo", m))
    }

    // ---- document body -----------------------------------------------------

    @Test fun `buildDoc carries the full field set at the current schema version`() {
        val m = DittoWire.meta(pli, now)!!
        val doc = DittoWire.buildDoc("omnitak", "peer-1", pli, m)
        assertEquals(DittoWire.SCHEMA_VERSION, doc["v"])
        assertEquals("omnitak|ANDROID-abc123", doc["_id"])
        assertEquals("ANDROID-abc123", doc["uid"])
        assertEquals("a-f-G-U-C", doc["type"])
        assertEquals("omnitak", doc["channel"])
        assertEquals("peer-1", doc["peer"])
        assertEquals(pli, doc["xml"])
        assertEquals(now, doc["sentAt"])
        assertEquals(
            setOf("_id", "v", "uid", "type", "channel", "peer", "xml", "lat", "lon", "sentAt", "staleAt"),
            doc.keys,
        )
    }

    // ---- admission ---------------------------------------------------------

    @Test fun `future schema versions are skipped not guessed at`() {
        assertTrue(DittoWire.admitVersion(1))
        assertFalse(DittoWire.admitVersion(2))
    }

    @Test fun `expiry drops dead tracks but keeps live and never-expiring ones`() {
        assertTrue(DittoWire.isExpired(staleAt = now - 1, nowMs = now))
        assertFalse(DittoWire.isExpired(staleAt = now + 60_000, nowMs = now))
        assertFalse(DittoWire.isExpired(staleAt = 0, nowMs = now)) // 0 = never expires
    }
}
