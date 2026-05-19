package soy.engindearing.omnitak.mobile.data

import android.util.Base64
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Quick Connect / CSR enrollment client for TAK Server (GAP-081).
 * Mirrors iOS CSREnrollmentService at functional parity.
 *
 * Flow:
 *  1. GET  /Marti/api/tls/config   — read server's `<nameEntries>` policy
 *  2. POST /Marti/api/tls/signClient/v2 — submit CSR, receive signed cert
 *  3. Bundle signed cert + private key + CA chain into a PKCS#12 keystore
 *  4. Save the .p12 via [CertVault]; return server config for ServerManager
 *
 * Wire format quirks worth knowing:
 *  - The CSR POST body is raw base64 of DER bytes (NO `-----BEGIN-----`
 *    headers). Content-Type is `text/plain; charset=utf-8`. This matches
 *    Flight Tactics TAKTracker and the iOS implementation.
 *  - The Subject DN must only contain RDNs the server's tlsConfig
 *    declared, plus the auth-username as `CN`. Adding unauthorized RDNs
 *    triggers BBN's `CertManagerService.java:143` "CSR validation failed!"
 *    — see iOS issue #31.
 */
class CSREnrollmentService(
    private val certVault: CertVault
) {
    // Password applied to the in-memory PKCS#12 we hand to CertVault.
    // The same value must be stored in TAKServer.certificatePassword so
    // TAKConnection's KeyManagerFactory can load it later.
    private val p12Password = "omnitak".toCharArray()

    /**
     * Bind everything the caller knows about the enrollment target. Most
     * fields are common to all TAK Servers; `trustSelfSigned` is the
     * escape hatch for ops running their own internal CA.
     */
    data class Config(
        val host: String,
        val enrollmentPort: Int = 8446,
        val username: String,
        val password: String,
        val trustSelfSigned: Boolean = false
    ) {
        init {
            require(host.isNotBlank()) { "host required" }
            require(username.isNotBlank()) { "username required" }
        }
        val baseUrl: String get() = "https://$host:$enrollmentPort"
        val configUrl: String get() = "$baseUrl/Marti/api/tls/config"
        fun csrUrl(clientUid: String, version: String): String =
            "$baseUrl/Marti/api/tls/signClient/v2" +
                "?clientUid=${java.net.URLEncoder.encode(clientUid, "UTF-8")}" +
                "&version=${java.net.URLEncoder.encode(version, "UTF-8")}"
    }

    /**
     * What the caller gets back after a successful enrollment — enough to
     * construct a [TAKServer] (the connection port is the caller's
     * problem; typically 8089).
     */
    data class Result(
        val certificateName: String,
        val certificatePassword: String
    )

    /**
     * Run the full enrollment. Throws [EnrollmentException] on any
     * recoverable failure (HTTP 4xx/5xx, parse error, IO). The caller
     * should surface the message verbatim — it already contains the
     * server-side reason where one was available.
     */
    suspend fun enroll(config: Config): Result {
        val nameEntries = fetchCAConfig(config)
        Log.i(TAG, "CA config: O=${nameEntries.organization} OU=${nameEntries.organizationalUnit} C=${nameEntries.country}")

        // Build the CSR with ONLY what the server declared, plus the CN.
        // See class doc comment on the C=US bug from iOS.
        val csrConfig = CSRGenerator.Config(
            commonName = config.username,
            organization = nameEntries.organization,
            organizationalUnit = nameEntries.organizationalUnit,
            country = nameEntries.country
        )
        val csr = CSRGenerator.generate(csrConfig)
        Log.i(TAG, "CSR generated (${csr.csrDer.size}B DER)")

        val response = submitCsr(config, csr.csrBase64)
        Log.i(TAG, "signed cert + ${response.caChain.size} CA cert(s) received")

        val p12Bytes = buildPkcs12(
            signedCert = response.signedCert,
            chain = response.caChain,
            keyPair = csr.keyPair
        )
        val certName = "${sanitize(config.host)}-${config.username}.p12"
        val saved = certVault.importBytes(certName, p12Bytes)
            ?: throw EnrollmentException("Failed to write enrolled cert to vault")
        return Result(certificateName = saved, certificatePassword = String(p12Password))
    }

    // ------------------------------------------------------------------
    // Step 1 — fetch /Marti/api/tls/config
    // ------------------------------------------------------------------

    internal data class NameEntries(
        val organization: String?,
        val organizationalUnit: String?,
        val country: String?
    )

    private fun fetchCAConfig(config: Config): NameEntries {
        val (code, body) = httpGet(config, URL(config.configUrl))
        if (code !in 200..299) {
            throw EnrollmentException(
                "Server config fetch failed (HTTP $code). Check username/password and that the enrollment port is correct."
            )
        }
        return parseNameEntries(body)
    }

    /**
     * Pull `<nameEntry name="X" value="Y"/>` pairs from the tlsConfig
     * XML body. Accepts attributes in either order (`name`-first or
     * `value`-first) — strict regex caught us out in early iOS testing.
     */
    internal fun parseNameEntries(xml: String): NameEntries {
        val pattern = Regex(
            "<nameEntry\\s+(?:name=\"([^\"]+)\"\\s+value=\"([^\"]+)\"|value=\"([^\"]+)\"\\s+name=\"([^\"]+)\")"
        )
        var org: String? = null
        var ou: String? = null
        var country: String? = null
        for (m in pattern.findAll(xml)) {
            val name = (m.groupValues[1].ifEmpty { m.groupValues[4] }).uppercase()
            val value = m.groupValues[2].ifEmpty { m.groupValues[3] }
            when (name) {
                "O" -> if (org == null) org = value
                "OU" -> if (ou == null) ou = value
                "C" -> if (country == null) country = value
            }
        }
        // Sensible fallback ONLY if the server returned nothing usable.
        // Almost every TAK Server config declares O=TAK / OU=TAK.
        return NameEntries(
            organization = org ?: "TAK",
            organizationalUnit = ou ?: "TAK",
            country = country  // intentionally nullable — see CSRGenerator class doc
        )
    }

    // ------------------------------------------------------------------
    // Step 2 — POST CSR to /Marti/api/tls/signClient/v2
    // ------------------------------------------------------------------

    internal data class SignResponse(
        val signedCert: X509Certificate,
        val caChain: List<X509Certificate>
    )

    private fun submitCsr(config: Config, csrBase64: String): SignResponse {
        val clientUid = java.util.UUID.randomUUID().toString()
        val (code, body) = httpPostText(
            config = config,
            url = URL(config.csrUrl(clientUid, "OmniTAK-Android-1.0")),
            body = csrBase64
        )
        if (code !in 200..299) {
            throw EnrollmentException(
                "CSR enrollment rejected by server (HTTP $code). Server response: ${body.take(400)}"
            )
        }
        return parseSignResponse(body)
    }

    internal fun parseSignResponse(json: String): SignResponse {
        val root = try {
            Json.parseToJsonElement(json) as? JsonObject
                ?: throw EnrollmentException("Server returned non-object JSON: ${json.take(120)}")
        } catch (t: Throwable) {
            throw EnrollmentException("Could not parse server response as JSON: ${t.message}")
        }

        val signedPem = root["signedCert"]?.jsonPrimitive?.contentOrNull
            ?: throw EnrollmentException("Server response missing 'signedCert'")
        val cf = CertificateFactory.getInstance("X.509")
        val signed = cf.generateCertificate(
            ByteArrayInputStream(pemToDer(signedPem))
        ) as X509Certificate

        // Server returns CAs as 'ca0', 'ca1', ... — order matters for the chain.
        val chain = root.keys.filter { it.startsWith("ca") }
            .sorted()
            .mapNotNull { key ->
                root[key]?.jsonPrimitive?.contentOrNull?.let { pem ->
                    try {
                        cf.generateCertificate(ByteArrayInputStream(pemToDer(pem))) as X509Certificate
                    } catch (t: Throwable) {
                        Log.w(TAG, "Skipping malformed CA '$key': ${t.message}")
                        null
                    }
                }
            }
        return SignResponse(signedCert = signed, caChain = chain)
    }

    // ------------------------------------------------------------------
    // Step 3 — bundle into a PKCS#12 keystore
    // ------------------------------------------------------------------

    internal fun buildPkcs12(
        signedCert: X509Certificate,
        chain: List<X509Certificate>,
        keyPair: java.security.KeyPair
    ): ByteArray {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        val fullChain: Array<java.security.cert.Certificate> =
            (listOf(signedCert) + chain).toTypedArray()
        ks.setKeyEntry(
            "omnitak-client",
            keyPair.private,
            p12Password,
            fullChain
        )
        val out = java.io.ByteArrayOutputStream()
        ks.store(out, p12Password)
        return out.toByteArray()
    }

    // ------------------------------------------------------------------
    // HTTP helpers (HttpURLConnection — no extra deps)
    // ------------------------------------------------------------------

    private fun httpGet(config: Config, url: URL): Pair<Int, String> =
        openConn(config, url).run {
            requestMethod = "GET"
            setRequestProperty("Authorization", basicAuth(config))
            connectAndRead(this)
        }

    private fun httpPostText(config: Config, url: URL, body: String): Pair<Int, String> =
        openConn(config, url).run {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", basicAuth(config))
            setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            OutputStreamWriter(outputStream, Charsets.UTF_8).use { it.write(body) }
            connectAndRead(this)
        }

    private fun openConn(config: Config, url: URL): HttpURLConnection {
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        if (config.trustSelfSigned && conn is HttpsURLConnection) {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(TrustAll), java.security.SecureRandom())
            conn.sslSocketFactory = ctx.socketFactory
            conn.hostnameVerifier = AcceptAllHostnames
        }
        return conn
    }

    private fun connectAndRead(conn: HttpURLConnection): Pair<Int, String> {
        return try {
            conn.connect()
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            code to text
        } catch (t: Throwable) {
            throw EnrollmentException("Network error: ${t.javaClass.simpleName}: ${t.message}", t)
        } finally {
            conn.disconnect()
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun basicAuth(config: Config): String {
        val raw = "${config.username}:${config.password}".toByteArray(Charsets.UTF_8)
        return "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(p0: Array<X509Certificate>?, p1: String?) {}
        override fun checkServerTrusted(p0: Array<X509Certificate>?, p1: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private object AcceptAllHostnames : javax.net.ssl.HostnameVerifier {
        override fun verify(hostname: String?, session: SSLSession?) = true
    }

    private fun pemToDer(pem: String): ByteArray {
        val base64 = pem.lineSequence()
            .filter { !it.startsWith("-----") }
            .joinToString("")
            .replace("\\s".toRegex(), "")
        return Base64.decode(base64, Base64.DEFAULT)
    }

    private fun sanitize(s: String): String =
        s.replace(Regex("[^A-Za-z0-9._-]"), "_")

    class EnrollmentException(message: String, cause: Throwable? = null) : Exception(message, cause)

    companion object {
        private const val TAG = "CSREnrollment"
    }
}
