package soy.engindearing.omnitak.mobile.domain

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import soy.engindearing.omnitak.mobile.data.CertVault
import soy.engindearing.omnitak.mobile.data.ConnectionProtocol
import soy.engindearing.omnitak.mobile.data.TAKServer
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.io.ByteArrayOutputStream

/**
 * GAP-081 — Android side of the TAK Server / OpenTAKserver CSR enrollment
 * flow. Mirrors the iOS [DeepLinkHandler.enrollWithToken] path:
 *
 *   1. GET https://<host>:<enrollPort>/Marti/api/tls/config  (Bearer <token>)
 *      to fetch the server's CA configuration (Subject DN components used
 *      when we sign the CSR).
 *   2. Generate an RSA-2048 key pair locally.
 *   3. Build a PKCS#10 CSR with the operator's username as CN, signed by
 *      the new private key.
 *   4. POST that CSR base64-encoded to
 *      https://<host>:<enrollPort>/Marti/api/tls/signClient/v2 with the
 *      same Bearer token (plus the Basic-auth + token-query fallbacks the
 *      iOS path uses, since OpenTAKserver builds vary).
 *   5. The response is a JSON object with `signedCert` plus `ca0` / `ca1` /
 *      … CA chain entries (PEM-encoded).
 *   6. Assemble a PKCS#12 keystore containing the new private key, the
 *      signed leaf cert, and the CA chain. Save it through [CertVault] so
 *      the existing [TAKConnection] mTLS code path picks it up unchanged.
 *   7. Build a [TAKServer] entry pointing at the new .p12, return it so
 *      the caller can hand it to [ServerManager.addServer] (which
 *      auto-connects).
 *
 * Network calls run on [Dispatchers.IO]. Trust is permissive on the
 * enrollment socket (self-signed TAK CAs are the norm) — that's bounded
 * to this single request and doesn't taint the streaming TLS socket
 * which uses the just-imported truststore.
 */
class CsrEnrollment(
    private val context: Context,
    private val certVault: CertVault,
) {
    /**
     * Run the full enrollment dance. Throws on any failure so the caller
     * can surface a single toast rather than a multi-step error tree.
     */
    suspend fun enroll(
        host: String,
        username: String,
        token: String,
        enrollmentPort: Int = 8446,
        streamingPort: Int = 8089,
    ): TAKServer = withContext(Dispatchers.IO) {
        Log.i(TAG, "CSR enrollment start: $username@$host:$enrollmentPort")

        val caConfig = fetchCaConfig(host, enrollmentPort, token)
        Log.i(TAG, "CA config: O=${caConfig.o}, OU=${caConfig.ou}, DC=${caConfig.dc}")

        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048, SecureRandom())
        }.generateKeyPair()

        val subjectDn = buildSubjectDn(username, caConfig)
        val csrBytes = buildCsr(subjectDn, keyPair)
        val csrB64 = Base64.getEncoder().encodeToString(csrBytes)
        Log.i(TAG, "Generated CSR ${csrBytes.size} bytes for $subjectDn")

        val signedJson = postCsr(host, enrollmentPort, token, username, csrB64)
        val signed = parseSignedResponse(signedJson)

        val leaf = parseX509(signed.signedCertPem)
        val chain = signed.caChainPem.map { parseX509(it) }
        Log.i(TAG, "Server signed cert: subject=${leaf.subjectX500Principal.name}")

        val pkcs12Name = "omnitak-${host.replace(Regex("[^A-Za-z0-9._-]"), "_")}.p12"
        val pkcs12Bytes = buildPkcs12(
            alias = "omnitak",
            password = PKCS12_PASSWORD,
            privateKey = keyPair.private,
            leaf = leaf,
            chain = chain,
        )
        val storedName = certVault.importBytes(pkcs12Name, pkcs12Bytes)
            ?: error("CertVault rejected ${pkcs12Bytes.size}-byte PKCS#12")

        TAKServer(
            name = "$host ($username)",
            host = host,
            port = streamingPort,
            protocol = if (streamingPort == 8088) ConnectionProtocol.TCP.wire else ConnectionProtocol.TLS.wire,
            useTLS = streamingPort != 8088,
            username = username,
            certificateName = storedName,
            certificatePassword = PKCS12_PASSWORD,
        )
    }

    // ---------- step 1: CA config ----------

    private fun fetchCaConfig(host: String, port: Int, token: String): CaConfig {
        val url = URL("https://$host:$port/Marti/api/tls/config")
        val conn = openTrustingHttps(url).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/xml,text/xml,*/*")
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            val body = readErrorBody(conn)
            conn.disconnect()
            error("CA config HTTP $code: $body")
        }
        val xml = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        return parseCaConfig(xml)
    }

    /** Instance entry to the pure parser; kept for back-compat readability. */
    internal fun parseCaConfig(xml: String): CaConfig = Companion.parseCaConfig(xml)

    // ---------- step 3: CSR ----------

    private fun buildSubjectDn(username: String, ca: CaConfig): String {
        val parts = mutableListOf<String>()
        parts += "CN=$username"
        if (ca.o.isNotEmpty()) parts += "O=${ca.o.first()}"
        if (ca.ou.isNotEmpty()) parts += "OU=${ca.ou.first()}"
        // Domain components reversed: DC entries are listed root → leaf in
        // TAK config but RFC 4514 expects leaf → root in the DN string.
        for (d in ca.dc.asReversed()) parts += "DC=$d"
        return parts.joinToString(",")
    }

    private fun buildCsr(subjectDn: String, kp: java.security.KeyPair): ByteArray {
        val signer = JcaContentSignerBuilder("SHA256WithRSA").build(kp.private)
        val csr = JcaPKCS10CertificationRequestBuilder(X500Name(subjectDn), kp.public).build(signer)
        return csr.encoded
    }

    // ---------- step 4: POST CSR ----------

    private fun postCsr(
        host: String,
        port: Int,
        token: String,
        username: String,
        csrB64: String,
    ): String {
        val baseQuery = "clientUid=${urlEncode("OMNITAK-${System.currentTimeMillis()}")}&version=${urlEncode(USER_AGENT)}"
        val csrUrl = URL("https://$host:$port/Marti/api/tls/signClient/v2?$baseQuery")
        val basicAuthFallback = "Basic " + Base64.getEncoder()
            .encodeToString("$username:$token".toByteArray(Charsets.UTF_8))

        // Three auth attempts, same as iOS: Bearer, Basic, token-query.
        val attempts = listOf(
            "Bearer $token" to csrUrl,
            basicAuthFallback to csrUrl,
            null to URL("https://$host:$port/Marti/api/tls/signClient/v2?$baseQuery&token=${urlEncode(token)}"),
        )

        var lastBody = ""
        var lastCode = 0
        for ((authHeader, attemptUrl) in attempts) {
            val conn = openTrustingHttps(attemptUrl).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                setRequestProperty("Accept", "application/json,text/plain,*/*")
                if (authHeader != null) setRequestProperty("Authorization", authHeader)
            }
            conn.outputStream.use { it.write(csrB64.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            lastCode = code
            if (code in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                return body
            }
            lastBody = readErrorBody(conn)
            conn.disconnect()
            if (code != 401) break // only 401 should trigger the auth fallback chain
        }
        error("CSR submit failed HTTP $lastCode: $lastBody")
    }

    internal fun parseSignedResponse(body: String): SignedCert =
        Companion.parseSignedResponse(body)

    // ---------- step 5/6: PKCS#12 assembly ----------

    private fun parseX509(pem: String): X509Certificate {
        // Strip PEM headers if present; otherwise treat as base64 of DER.
        val cleaned = pem
            .lineSequence()
            .filterNot { it.contains("-----") || it.isBlank() }
            .joinToString("")
        val der = runCatching { Base64.getMimeDecoder().decode(cleaned) }
            .getOrElse { error("invalid PEM: ${it.message}") }
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    private fun buildPkcs12(
        alias: String,
        password: String,
        privateKey: java.security.PrivateKey,
        leaf: X509Certificate,
        chain: List<X509Certificate>,
    ): ByteArray {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, password.toCharArray())
        val fullChain = (listOf(leaf) + chain).toTypedArray<java.security.cert.Certificate>()
        ks.setKeyEntry(alias, privateKey, password.toCharArray(), fullChain)
        // Also store CA chain as trusted entries so the truststore side is
        // self-contained for ConnectionManager's loadTrustStore code path.
        chain.forEachIndexed { i, c -> ks.setCertificateEntry("ca-$i", c) }
        val out = ByteArrayOutputStream()
        ks.store(out, password.toCharArray())
        return out.toByteArray()
    }

    // ---------- HTTPS helpers ----------

    private fun openTrustingHttps(url: URL): HttpsURLConnection {
        val sc = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(PermissiveTrust), SecureRandom())
        }
        val conn = (url.openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = sc.socketFactory
            hostnameVerifier = HostnameVerifier { _, _ -> true }
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return conn
    }

    private fun readErrorBody(conn: HttpURLConnection): String =
        runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty() }
            .getOrDefault("")

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    private object PermissiveTrust : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    data class CaConfig(val o: List<String>, val ou: List<String>, val dc: List<String>)
    data class SignedCert(val signedCertPem: String, val caChainPem: List<String>)

    companion object {
        private const val TAG = "CsrEnrollment"
        private const val USER_AGENT = "OmniTAK-Android/0.3.0"
        // ATAK / iTAK convention: the on-device PKCS#12 password is a known
        // string ("omnitak" here, mirroring iOS) — the cert never leaves
        // the device so this is for at-rest archiving only.
        private const val PKCS12_PASSWORD = "omnitak"

        /**
         * Pure parser exposed for unit tests. TAK Server / OpenTAKserver
         * returns a flat XML of `<nameEntry name="O|OU|DC" value="…"/>`
         * tuples — regex is cheaper than a pull-parser dependency.
         */
        @JvmStatic
        fun parseCaConfig(xml: String): CaConfig {
            val o = mutableListOf<String>()
            val ou = mutableListOf<String>()
            val dc = mutableListOf<String>()
            val regex = Regex("""<nameEntry\s+name="([^"]+)"\s+value="([^"]+)"\s*/?>""")
            for (m in regex.findAll(xml)) {
                val name = m.groupValues[1].uppercase()
                val value = m.groupValues[2]
                when (name) {
                    "O" -> o += value
                    "OU" -> ou += value
                    "DC" -> dc += value
                }
            }
            return CaConfig(o = o, ou = ou, dc = dc)
        }

        /**
         * Pure parser for the signClient/v2 response. Pulls out signedCert
         * + every `ca…` PEM entry while skipping `caPassword`. Uses
         * kotlinx.serialization (already a dep) so JVM unit tests work
         * without Robolectric or Android's stubbed `org.json`.
         */
        @JvmStatic
        fun parseSignedResponse(body: String): SignedCert {
            val obj = Json.parseToJsonElement(body) as? JsonObject
                ?: error("response is not a JSON object")
            val signedCert = (obj["signedCert"] as? JsonPrimitive)?.contentOrNull
                .takeUnless { it.isNullOrBlank() }
                ?: error("response missing signedCert")
            // Collect ca0 / ca1 / … in numeric order so the chain reaches the
            // root in the right direction when we feed it to the keystore.
            val caChain = obj.entries
                .filter { it.key.startsWith("ca", ignoreCase = true) && it.key != "caPassword" }
                .sortedBy { it.key }
                .mapNotNull { (it.value as? JsonPrimitive)?.contentOrNull }
                .filter { it.isNotBlank() }
            return SignedCert(signedCertPem = signedCert, caChainPem = caChain)
        }
    }
}
