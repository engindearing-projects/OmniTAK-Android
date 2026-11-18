package soy.engindearing.omnitak.mobile.data

import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * CA-pinning helpers for TAK Server connections.
 *
 * Replaces the dev-mode `TrustAllX509Manager` we shipped through 0.9.x.
 * When the operator enrolls via Quick Connect, the server hands back the
 * signed client cert plus its CA chain (`ca0`/`ca1`/…) — those CA certs
 * are the right pin to validate every subsequent connection against,
 * since they're the same CA that signed the client cert the server is
 * already happy with.
 *
 * Storage shape is a concatenated PEM file (one BEGIN/END CERTIFICATE
 * block per CA cert). [CertVault] keeps it next to the `.p12`. Loader
 * here is forgiving about whitespace inside the base64 body.
 *
 * Closes #38 (GAP-106).
 */
object CaTrust {

    private const val PEM_BEGIN = "-----BEGIN CERTIFICATE-----"
    private const val PEM_END = "-----END CERTIFICATE-----"

    /**
     * Serialize a CA chain to a single PEM file — one CERTIFICATE block
     * per cert, in chain order (ca0 first, ca1 next, …).
     */
    fun encodePemChain(chain: List<X509Certificate>): ByteArray {
        val encoder = Base64.getMimeEncoder(64, "\n".toByteArray(Charsets.US_ASCII))
        return buildString {
            chain.forEach { cert ->
                append(PEM_BEGIN).append('\n')
                append(encoder.encodeToString(cert.encoded)).append('\n')
                append(PEM_END).append('\n')
            }
        }.toByteArray(Charsets.US_ASCII)
    }

    /**
     * Parse zero-or-more PEM CERTIFICATE blocks out of [pem]. Returns
     * an empty list when nothing parses — caller decides whether that's
     * a fall-back-to-system-trust signal or a hard failure.
     */
    fun decodePemChain(pem: ByteArray): List<X509Certificate> {
        val text = String(pem, Charsets.US_ASCII)
        val cf = CertificateFactory.getInstance("X.509")
        val out = mutableListOf<X509Certificate>()
        var cursor = 0
        while (true) {
            val begin = text.indexOf(PEM_BEGIN, cursor)
            if (begin < 0) break
            val end = text.indexOf(PEM_END, begin + PEM_BEGIN.length)
            if (end < 0) break
            val body = text.substring(begin + PEM_BEGIN.length, end)
                .replace(Regex("\\s+"), "")
            cursor = end + PEM_END.length
            val der = runCatching { Base64.getDecoder().decode(body) }.getOrNull() ?: continue
            val cert = runCatching {
                cf.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            }.getOrNull() ?: continue
            out += cert
        }
        return out
    }

    /**
     * Parse an operator-supplied CA file (PEM chain or a single DER cert)
     * for manual pinning on the Add Server screen. Only certificates that
     * are CAs (basicConstraints cA=TRUE) are returned: pinning a leaf would
     * silently stop working at the server's next cert rotation, and pinning
     * the wrong file type should fail loudly rather than fall back.
     */
    fun parseCaFile(bytes: ByteArray): List<X509Certificate> {
        val certs = decodePemChain(bytes).ifEmpty {
            runCatching {
                val cf = CertificateFactory.getInstance("X.509")
                listOf(cf.generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate)
            }.getOrDefault(emptyList())
        }
        return certs.filter { it.basicConstraints >= 0 }
    }

    /** Colon-separated uppercase SHA-256 fingerprint, as `openssl x509 -fingerprint -sha256` prints it. */
    fun sha256Fingerprint(cert: X509Certificate): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString(":") { "%02X".format(it) }

    /**
     * Build a trust manager that accepts certs anchored to any cert in
     * [pinnedCAs]. Standard Android pattern: keystore with the CA(s),
     * fed through [TrustManagerFactory]. Path validation (expiry, chain
     * walk, basic constraints) is handled by the platform code.
     */
    fun trustManagerFor(pinnedCAs: List<X509Certificate>): X509TrustManager {
        require(pinnedCAs.isNotEmpty()) { "pinnedCAs must contain at least one certificate" }
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        pinnedCAs.forEachIndexed { i, cert -> ks.setCertificateEntry("ca-$i", cert) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /**
     * System default trust manager (Android's bundled CA store). Used
     * when no pin exists for a server — legacy `.p12` imports from
     * before Quick Connect existed, or when the pin file is missing.
     */
    fun systemTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}
