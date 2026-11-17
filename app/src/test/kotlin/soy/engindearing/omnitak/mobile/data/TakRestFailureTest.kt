package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * iOS #169 parity — a Marti REST request that never got an HTTP answer is
 * explained from the exception and what the entry has configured, not from
 * the platform's one-line message.
 */
class TakRestFailureTest {

    private fun describe(t: Throwable, cert: String? = null) =
        TakRestFailure.describe(t, "tak.example", 8443, cert)

    @Test fun `certificate alert without a configured certificate says enroll`() {
        val text = describe(SSLHandshakeException("Received fatal alert: certificate_required"))
        assertTrue(text, text.startsWith("tak.example:8443 refused the TLS handshake."))
        assertTrue(text, text.contains("requires a client certificate and this server has none"))
        assertTrue(text, text.contains("Enroll"))
    }

    @Test fun `certificate alert with a configured certificate names it`() {
        val text = describe(SSLHandshakeException("Received fatal alert: bad_certificate"), cert = "omnitak-cert-x")
        assertTrue(text, text.contains("did not accept the client certificate \"omnitak-cert-x\""))
    }

    @Test fun `a close right after the handshake is the certificate story too`() {
        // A TAK Server's Tomcat completes the handshake and closes the connection.
        val reset = describe(SSLException("Connection reset"))
        assertTrue(reset, reset.contains("closed the connection right after the TLS handshake"))
        assertTrue(reset, reset.contains("requires a client certificate"))
        val eof = describe(EOFException("\\n not found: limit=0 content=…"), cert = "omnitak-cert-x")
        assertTrue(eof, eof.contains("did not accept the client certificate \"omnitak-cert-x\""))
    }

    @Test fun `untrusted server certificate says install the CA or trust untrusted`() {
        val text = describe(
            SSLHandshakeException("java.security.cert.CertPathValidatorException: Trust anchor for certification path not found."),
        )
        assertTrue(text, text.contains("not trusted"))
        assertTrue(text, text.contains("Trust untrusted certificates"))
        val peer = describe(SSLPeerUnverifiedException("Hostname tak.example not verified"))
        assertTrue(peer, peer.contains("does not match its host name"))
    }

    @Test fun `other handshake failures keep the platform text`() {
        val text = describe(SSLHandshakeException("No appropriate protocol"))
        assertTrue(text, text.startsWith("TLS handshake with tak.example:8443 failed before authentication"))
        assertTrue(text, text.contains("No appropriate protocol"))
    }

    @Test fun `network failures name the endpoint and what to check`() {
        assertEquals(
            "tak.example:8443 refused the connection. Check the Marti API port (usually 8443).",
            describe(ConnectException("Connection refused")),
        )
        assertEquals("Could not resolve tak.example.", describe(UnknownHostException("tak.example")))
        assertEquals("tak.example:8443 did not answer in time.", describe(SocketTimeoutException("connect timed out")))
        assertEquals("tak.example:8443: IllegalStateException: boom", describe(IllegalStateException("boom")))
    }

    @Test fun `credential fallback applies to TLS and TCP layer failures only`() {
        assertTrue(TakRestFailure.allowsCredentialFallback(SSLHandshakeException("x")))
        assertTrue(TakRestFailure.allowsCredentialFallback(SSLException("Connection reset")))
        assertTrue(TakRestFailure.allowsCredentialFallback(EOFException()))
        assertTrue(TakRestFailure.allowsCredentialFallback(SocketException("Connection reset")))
        assertTrue(TakRestFailure.allowsCredentialFallback(ConnectException("refused")))
        assertTrue(TakRestFailure.allowsCredentialFallback(CertificateException("bad")))
        assertFalse(TakRestFailure.allowsCredentialFallback(UnknownHostException("x")))
        assertFalse(TakRestFailure.allowsCredentialFallback(SocketTimeoutException("x")))
        assertFalse(TakRestFailure.allowsCredentialFallback(IllegalStateException("x")))
    }

    @Test fun `detail carries the facts and never a password`() {
        val t = SSLException("Connection reset", CertificateException("inner"))
        val detail = TakRestFailure.detail(
            t, "tak.example", 8443, TakRestAuthRoute.ClientCertificate,
            clientCertificateName = "omnitak-cert-x", caCertificateName = "omnitak-ca.pem", allowUntrustedTls = false,
        )
        assertTrue(detail, detail.contains("Server: tak.example:8443"))
        assertTrue(detail, detail.contains("Route: client certificate"))
        assertTrue(detail, detail.contains("System error: javax.net.ssl.SSLException: Connection reset"))
        assertTrue(detail, detail.contains("Caused by: java.security.cert.CertificateException: inner"))
        assertTrue(detail, detail.contains("Trust policy: pinned CA \"omnitak-ca.pem\""))
        assertTrue(detail, detail.contains("Client certificate: omnitak-cert-x"))
        val untrusted = TakRestFailure.detail(t, "h", 1, TakRestAuthRoute.Basic(8446), null, null, allowUntrustedTls = true)
        assertTrue(untrusted, untrusted.contains("trust untrusted certificates (on)"))
        assertTrue(untrusted, untrusted.contains("Client certificate: none configured"))
    }

    @Test fun `auth routes build their headers and labels`() {
        assertNull(TakRestAuthRoute.ClientCertificate.authorizationHeader("u", "p", null))
        assertEquals("Bearer t", TakRestAuthRoute.Bearer(8446).authorizationHeader("u", "p", "t"))
        assertNull(TakRestAuthRoute.Bearer(8446).authorizationHeader("u", "p", null))
        assertEquals(
            "Basic " + java.util.Base64.getEncoder().encodeToString("u:p".toByteArray()),
            TakRestAuthRoute.Basic(8446).authorizationHeader("u", "p", null),
        )
        assertNull(TakRestAuthRoute.Basic(8446).authorizationHeader("u", "", null))
        assertEquals("client certificate", TakRestAuthRoute.ClientCertificate.label)
        assertEquals("username and password (8446)", TakRestAuthRoute.Bearer(8446).label)
        assertEquals("username and password (8446)", TakRestAuthRoute.Basic(8446).label)
        assertFalse(TakRestAuthRoute.ClientCertificate.usesCredentials)
        assertTrue(TakRestAuthRoute.Bearer(1).usesCredentials)
    }
}
