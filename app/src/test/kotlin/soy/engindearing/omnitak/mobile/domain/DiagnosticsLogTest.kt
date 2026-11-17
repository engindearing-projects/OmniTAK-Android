package soy.engindearing.omnitak.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.TAKServer

/** iOS #169 parity — the in-app log export's header and rendering, with no secrets. */
class DiagnosticsLogTest {

    private val t0 = 1_791_500_000_000L

    @Test fun `header lists versions device and every server`() {
        val a = TAKServer(name = "A", host = "a.example", port = 8089, protocol = "tls", useTLS = true)
        val b = TAKServer(name = "B", host = "b.example", port = 8087, protocol = "tcp", useTLS = false, enabled = false)
        val header = DiagnosticsLog.header("0.46.0", 112, "16", 36, "Google Pixel 6a", listOf(a, b), now = t0)
        assertTrue(header, header.startsWith("OmniTAK diagnostics\n"))
        assertTrue(header, header.contains("App: 0.46.0 (112)"))
        assertTrue(header, header.contains("Android: 16 (API 36), device: Google Pixel 6a"))
        assertTrue(header, header.contains("Servers: 2"))
        assertTrue(header, header.contains("- A: a.example:8089 tls tls, enabled"))
        assertTrue(header, header.contains("- B: b.example:8087 tcp, disabled"))
    }

    @Test fun `server line carries trust facts but no secrets`() {
        val server = TAKServer(
            name = "ARDOS", host = "tak.example", port = 8089, protocol = "tls", useTLS = true,
            certificateName = "omnitak-cert-tak.example", caCertificateName = "ca-tak.example.pem",
            username = "vaclav", password = "hunter2", certificatePassword = "p12secret",
            allowUntrustedTls = true, enrollmentPort = 8446,
        )
        val line = DiagnosticsLog.describe(server)
        assertTrue(line, line.contains("enroll 8446"))
        assertTrue(line, line.contains("cert omnitak-cert-tak.example"))
        assertTrue(line, line.contains("truststore ca-tak.example.pem"))
        assertTrue(line, line.contains("trust-untrusted ON"))
        assertTrue(line, line.contains("credentials stored"))
        assertFalse(line, line.contains("hunter2"))
        assertFalse(line, line.contains("p12secret"))
        assertFalse(line, line.contains("vaclav"))
    }

    @Test fun `render starts with the header and counts lines`() {
        val text = DiagnosticsLog.render("HEADER", listOf("10-09 13:01:01.978 I/TakConnection: one", "10-09 13:01:02.000 W/TakRestApiClient: two"))
        assertTrue(text, text.startsWith("HEADER\n\nLog (2 lines)\n"))
        assertTrue(text, text.endsWith("W/TakRestApiClient: two"))
        assertEquals("H\n\nLog (0 lines)\n", DiagnosticsLog.render("H", emptyList()))
    }
}
