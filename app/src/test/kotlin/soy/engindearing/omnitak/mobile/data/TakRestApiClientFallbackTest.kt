package soy.engindearing.omnitak.mobile.data

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.net.InetAddress
import java.net.Socket
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

/**
 * iOS #169 parity — when the certificate port refuses the handshake and the
 * entry has a username and password, the REST client reaches the Marti API
 * through the enrollment port: OAuth password grant first (TAK Server), HTTP
 * Basic second (OpenTAKServer). Two loopback HTTPS servers play the ports.
 */
class TakRestApiClientFallbackTest {

    private val servers = mutableListOf<Loopback>()

    @After fun stopServers() {
        servers.forEach { it.stop() }
        servers.clear()
    }

    // ── Loopback connectors ──────────────────────────────────────────────

    sealed interface Auth {
        data object Open : Auth
        data class Bearer(val token: String, val username: String, val password: String) : Auth
        data class Basic(val username: String, val password: String) : Auth
    }

    class Recorded(val method: String, val path: String, val authorization: String?)

    /**
     * A minimal HTTPS/1.1 server on a raw TLS server socket (the Android unit
     * test classpath has no JDK HTTP server). One instance plays one
     * connector; `needClientCert` makes the handshake demand a client
     * certificate the way a TAK Server's 8443 does.
     */
    private inner class Loopback(needClientCert: Boolean, private val auth: Auth, private val oauth: Boolean = true) {
        val requests = CopyOnWriteArrayList<Recorded>()
        val port: Int
        private val serverSocket: SSLServerSocket
        @Volatile private var running = true

        init {
            val ctx = serverSslContext()
            serverSocket = (ctx.serverSocketFactory.createServerSocket(0, 16, InetAddress.getLoopbackAddress()) as SSLServerSocket)
                .apply { needClientAuth = needClientCert }
            port = serverSocket.localPort
            servers += this
            Thread({ acceptLoop() }, "loopback-marti-$port").apply { isDaemon = true }.start()
        }

        fun stop() {
            running = false
            runCatching { serverSocket.close() }
        }

        private fun acceptLoop() {
            while (running) {
                val socket = try { serverSocket.accept() } catch (_: Exception) { break }
                Thread({ serve(socket) }, "loopback-marti-peer").apply { isDaemon = true }.start()
            }
        }

        private fun serve(socket: Socket) {
            try {
                socket.soTimeout = 5000
                // The first read runs the handshake; without an acceptable
                // client certificate it throws here and the socket closes.
                val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val requestLine = input.readLine() ?: return
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                var remaining = contentLength
                while (remaining > 0) {
                    val skipped = input.skip(remaining.toLong())
                    if (skipped <= 0) break
                    remaining -= skipped.toInt()
                }
                val parts = requestLine.split(" ")
                val method = parts[0]
                val target = parts.getOrElse(1) { "/" }
                val path = target.substringBefore("?")
                val query = target.substringAfter("?", "").split("&").filter { it.isNotBlank() }.associate { kv ->
                    val p = kv.split("=", limit = 2)
                    URLDecoder.decode(p[0], "UTF-8") to (p.getOrNull(1)?.let { URLDecoder.decode(it, "UTF-8") } ?: "")
                }
                val authorization = headers["authorization"]
                requests += Recorded(method, path, authorization)
                val (code, body) = respond(method, path, query, authorization)
                val bytes = body.toByteArray(Charsets.UTF_8)
                val reason = when (code) { 200 -> "OK"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Error" }
                val head = "HTTP/1.1 $code $reason\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().apply {
                    write(head.toByteArray(Charsets.ISO_8859_1))
                    write(bytes)
                    flush()
                }
            } catch (_: Exception) {
                // Handshake refused or client went away: nothing to answer.
            } finally {
                runCatching { socket.close() }
            }
        }

        private fun authorized(header: String?): Boolean = when (val a = auth) {
            Auth.Open -> true
            is Auth.Bearer -> header == "Bearer ${a.token}"
            is Auth.Basic -> header == "Basic " + java.util.Base64.getEncoder().encodeToString("${a.username}:${a.password}".toByteArray())
        }

        private fun respond(method: String, path: String, query: Map<String, String>, header: String?): Pair<Int, String> = when {
            method == "POST" && path == "/oauth/token" -> {
                val a = auth
                if (!oauth || a !is Auth.Bearer) 404 to ""
                else if (query["grant_type"] == "password" && query["username"] == a.username && query["password"] == a.password) {
                    200 to """{"access_token":"${a.token}","token_type":"bearer","expires_in":7200}"""
                } else 401 to """{"error":"invalid_grant"}"""
            }
            path == "/Marti/api/version/config" ->
                if (authorized(header)) 200 to """{"version":"3","type":"ServerConfig","data":{"version":"loopback","api":"3"}}""" else 403 to ""
            path == "/Marti/api/missions" ->
                if (authorized(header)) 200 to """{"version":"3","type":"Mission","data":[{"name":"loopback-mission","description":"from the test server"}]}""" else 403 to ""
            path == "/Marti/api/sync/search" ->
                if (authorized(header)) 200 to """{"version":"3","type":"Metadata","data":[]}""" else 403 to ""
            else -> 404 to ""
        }
    }

    /** Self-signed RSA server identity; the client runs with trust-untrusted on. */
    private fun serverSslContext(): SSLContext {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = Date()
        val later = Date(now.time + 24L * 60 * 60 * 1000)
        val dn = X500Name("CN=127.0.0.1, O=OmniTAK tests")
        val builder = JcaX509v3CertificateBuilder(dn, BigInteger.valueOf(System.nanoTime()), now, later, dn, kp.public)
            .addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        val signer = JcaContentSignerBuilder("SHA256WithRSA").build(kp.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("server", kp.private, "pw".toCharArray(), arrayOf(cert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "pw".toCharArray()) }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }

    private fun entry(apiPort: Int, enrollPort: Int, certificateName: String?, username: String?, password: String?) = TAKServer(
        name = "Loopback",
        host = "127.0.0.1",
        port = 8089,
        protocol = ConnectionProtocol.TLS.wire,
        useTLS = true,
        certificateName = certificateName,
        username = username,
        password = password,
        allowUntrustedTls = true,
        enrollmentPort = enrollPort,
        secureApiPort = apiPort,
    )

    // ── Fallback paths ───────────────────────────────────────────────────

    @Test fun `certificate port refused then OAuth on the enrollment port`() {
        val api = Loopback(needClientCert = true, auth = Auth.Open)
        val enroll = Loopback(needClientCert = false, auth = Auth.Bearer("tok-1", "vaclav", "pw"))
        val client = TakRestApiClient(entry(api.port, enroll.port, "omnitak-cert-missing", "vaclav", "pw"), certVault = null)

        val route = client.connect()

        assertEquals(TakRestAuthRoute.Bearer(enroll.port), route)
        assertEquals(listOf("loopback-mission"), client.getMissions().map { it.name })
        val apiCalls = enroll.requests.filter { it.path.startsWith("/Marti/") }
        assertTrue(apiCalls.isNotEmpty())
        assertTrue(apiCalls.all { it.authorization == "Bearer tok-1" })
        assertTrue("the certificate port never saw an HTTP request", api.requests.isEmpty())
    }

    @Test fun `no certificate goes straight to the credentials`() {
        val api = Loopback(needClientCert = true, auth = Auth.Open)
        val enroll = Loopback(needClientCert = false, auth = Auth.Bearer("tok-2", "vaclav", "pw"))
        val client = TakRestApiClient(entry(api.port, enroll.port, null, "vaclav", "pw"), certVault = null)

        assertEquals(TakRestAuthRoute.Bearer(enroll.port), client.connect())
        assertNull(client.lastFallbackNote)
    }

    @Test fun `Basic auth when the server has no OAuth`() {
        val api = Loopback(needClientCert = true, auth = Auth.Open)
        val enroll = Loopback(needClientCert = false, auth = Auth.Basic("vaclav", "pw"), oauth = false)
        val client = TakRestApiClient(entry(api.port, enroll.port, null, "vaclav", "pw"), certVault = null)

        assertEquals(TakRestAuthRoute.Basic(enroll.port), client.connect())
        client.checkReachability()
        val expected = "Basic " + java.util.Base64.getEncoder().encodeToString("vaclav:pw".toByteArray())
        assertTrue(enroll.requests.filter { it.path.startsWith("/Marti/") }.all { it.authorization == expected })
    }

    // ── Failures stay explained ──────────────────────────────────────────

    @Test fun `wrong credentials keep the certificate failure and note the fallback`() {
        val api = Loopback(needClientCert = true, auth = Auth.Open)
        val enroll = Loopback(needClientCert = false, auth = Auth.Bearer("tok-3", "vaclav", "right"))
        val client = TakRestApiClient(entry(api.port, enroll.port, "omnitak-cert-missing", "vaclav", "wrong"), certVault = null)

        try {
            client.connect()
            fail("wrong credentials must not connect")
        } catch (e: TakRestApiClient.ApiException) {
            val message = e.message ?: ""
            assertTrue(message, message.contains("client certificate"))
            assertTrue(message, message.contains("Username and password did not work either"))
            assertTrue(message, message.contains("127.0.0.1:${enroll.port}"))
            assertNotNull("detail expected", e.detail)
            val detail = e.detail!!
            assertTrue(detail, detail.contains("Fallback:"))
            assertEquals(TakRestAuthRoute.ClientCertificate, client.route)
        }
    }

    @Test fun `no credentials means no fallback`() {
        val api = Loopback(needClientCert = true, auth = Auth.Open)
        val enroll = Loopback(needClientCert = false, auth = Auth.Bearer("tok-4", "vaclav", "pw"))
        val client = TakRestApiClient(entry(api.port, enroll.port, "omnitak-cert-missing", null, null), certVault = null)

        try {
            client.connect()
            fail("unreachable without a certificate or credentials")
        } catch (e: TakRestApiClient.ApiException) {
            val message = e.message ?: ""
            assertTrue(message, message.contains("client certificate \"omnitak-cert-missing\"") || message.contains("client certificate"))
            assertNotNull(e.detail)
        }
        assertTrue(enroll.requests.isEmpty())
    }

    /** The enrollment port itself not answering is reported as that, not as rejected credentials. */
    @Test fun `no certificate and an enrollment port that does not answer names that port`() {
        val api = Loopback(needClientCert = true, auth = Auth.Open)
        val closed = Loopback(needClientCert = false, auth = Auth.Open)
        val closedPort = closed.port
        closed.stop()
        val client = TakRestApiClient(entry(api.port, closedPort, null, "vaclav", "pw"), certVault = null)
        try {
            client.connect()
            fail("a closed enrollment port cannot connect")
        } catch (e: TakRestApiClient.ApiException) {
            val message = e.message ?: ""
            assertTrue(message, message.contains("127.0.0.1:$closedPort refused the connection"))
            assertTrue(message, !message.contains("did not accept the stored username"))
            assertTrue(e.detail ?: "", (e.detail ?: "").contains("Fallback:"))
        }
        assertTrue(api.requests.isEmpty())
    }

    @Test fun `neither certificate nor credentials is refused up front`() {
        val api = Loopback(needClientCert = true, auth = Auth.Open)
        val client = TakRestApiClient(entry(api.port, 8446, null, null, null), certVault = null)
        try {
            client.connect()
            fail("nothing to authenticate with")
        } catch (e: TakRestApiClient.ApiException) {
            assertTrue(e.message ?: "", (e.message ?: "").contains("has neither"))
        }
        assertTrue(api.requests.isEmpty())
    }
}
