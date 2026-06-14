package soy.engindearing.omnitak.mobile.data

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Unit tests for the #100 enrollment-link parsing in [DeepLinkImport]:
 * the standard ATAK / TAK Server / ArgusTAK QR
 * `tak://com.atakmap.app/enroll?host=<host>[:port]&username=&token=`.
 *
 * The host:port split (the URL-encoded `host=argustak.com%3A8089` decode) is
 * pure-Kotlin and tested directly. The [Uri]-based detector/parser tests are
 * guarded with `assumeTrue` because `android.net.Uri.parse` returns stubs
 * under `unitTests.isReturnDefaultValues = true` (no Robolectric on the
 * classpath) — same convention as ConfigProfileTest.
 */
class DeepLinkEnrollTest {

    // ── splitHostPort (pure JVM, always runs) ─────────────────────────────

    @Test
    fun splitHostPort_hostAndPort() {
        val (host, port) = DeepLinkImport.splitHostPort("argustak.com:8089")
        assertEquals("argustak.com", host)
        assertEquals(8089, port)
    }

    @Test
    fun splitHostPort_bareHostHasNoPort() {
        val (host, port) = DeepLinkImport.splitHostPort("argustak.com")
        assertEquals("argustak.com", host)
        assertNull(port)
    }

    @Test
    fun splitHostPort_trailingColonHasNoPort() {
        val (host, port) = DeepLinkImport.splitHostPort("tak.example.com:")
        assertEquals("tak.example.com", host)
        assertNull(port)
    }

    @Test
    fun splitHostPort_nonNumericPortIsIgnored() {
        val (host, port) = DeepLinkImport.splitHostPort("tak.example.com:abc")
        assertEquals("tak.example.com", host)
        assertNull(port)
    }

    @Test
    fun splitHostPort_outOfRangePortIsIgnored() {
        val (host, port) = DeepLinkImport.splitHostPort("tak.example.com:99999")
        assertEquals("tak.example.com", host)
        assertNull(port)
    }

    @Test
    fun splitHostPort_ipv4WithPort() {
        val (host, port) = DeepLinkImport.splitHostPort("10.0.0.5:8089")
        assertEquals("10.0.0.5", host)
        assertEquals(8089, port)
    }

    @Test
    fun splitHostPort_ipv6Bracketed() {
        val (host, port) = DeepLinkImport.splitHostPort("[2001:db8::1]:8089")
        assertEquals("2001:db8::1", host)
        assertEquals(8089, port)
    }

    @Test
    fun splitHostPort_ipv6BracketedNoPort() {
        val (host, port) = DeepLinkImport.splitHostPort("[2001:db8::1]")
        assertEquals("2001:db8::1", host)
        assertNull(port)
    }

    // ── isEnrollLink / parseEnrollLink (need Uri.parse — skipped on stub) ──

    private fun parsed(uriString: String): Uri? =
        runCatching { Uri.parse(uriString) }.getOrNull()
            ?.takeIf { it.scheme != null }

    @Test
    fun isEnrollLink_acceptsStandardTakEnrollUri() {
        val uri = parsed(
            "tak://com.atakmap.app/enroll?host=argustak.com%3A8089&username=u&token=t",
        )
        assumeTrue("Uri.parse stubbed on JVM", uri != null)
        assertTrue(DeepLinkImport.isEnrollLink(uri))
    }

    @Test
    fun isEnrollLink_rejectsConnectForm() {
        val uri = parsed("atak://com.atakmap.app/connect?host=tak.example.com&port=8089")
        assumeTrue("Uri.parse stubbed on JVM", uri != null)
        assertFalse(DeepLinkImport.isEnrollLink(uri))
    }

    @Test
    fun isEnrollLink_rejectsHttpScheme() {
        val uri = parsed("https://evil.example.com/enroll?host=tak.example.com&username=u&token=t")
        assumeTrue("Uri.parse stubbed on JVM", uri != null)
        assertFalse(DeepLinkImport.isEnrollLink(uri))
    }

    @Test
    fun parseEnrollLink_extractsAllFieldsAndDefaults() {
        val uri = parsed(
            "tak://com.atakmap.app/enroll?host=argustak.com%3A8089&username=alpha&token=secret",
        )
        assumeTrue("Uri.parse stubbed on JVM", uri != null)
        val cfg = DeepLinkImport.parseEnrollLink(uri!!)
        assertTrue(cfg != null)
        cfg!!
        assertEquals("argustak.com", cfg.host)
        assertEquals(8089, cfg.port)            // connection port from host:port
        assertEquals("alpha", cfg.username)
        assertEquals("secret", cfg.password)    // token mapped to enroll secret
        assertEquals(8446, cfg.enrollmentPort)  // separate default enroll port
        assertTrue(cfg.useTLS)
        assertTrue(cfg.needsEnrollment)
    }

    @Test
    fun parseEnrollLink_bareHostDefaultsConnectPort8089() {
        val uri = parsed("tak://com.atakmap.app/enroll?host=tak.example.com&username=u&token=t")
        assumeTrue("Uri.parse stubbed on JVM", uri != null)
        val cfg = DeepLinkImport.parseEnrollLink(uri!!)
        assertTrue(cfg != null)
        assertEquals(8089, cfg!!.port)
    }

    @Test
    fun parseEnrollLink_overridableEnrollPort() {
        val uri = parsed(
            "tak://com.atakmap.app/enroll?host=tak.example.com%3A8089&username=u&token=t&enrollport=8443",
        )
        assumeTrue("Uri.parse stubbed on JVM", uri != null)
        val cfg = DeepLinkImport.parseEnrollLink(uri!!)
        assertTrue(cfg != null)
        assertEquals(8443, cfg!!.enrollmentPort)
    }

    @Test
    fun parseEnrollLink_missingTokenReturnsNull() {
        val uri = parsed("tak://com.atakmap.app/enroll?host=tak.example.com%3A8089&username=u")
        assumeTrue("Uri.parse stubbed on JVM", uri != null)
        assertNull(DeepLinkImport.parseEnrollLink(uri!!))
    }
}
