package soy.engindearing.omnitak.mobile.data

import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.MessageDigest
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

    // The CN of an RFC 2253 subject, as X500Principal.name prints it: a value
    // runs to the next unescaped ',' or '+', and a backslash escapes one char.
    private val COMMON_NAME = Regex("""(?:^|[,+])CN=((?:\\.|[^,+\\])+)""")
    private val ESCAPED_CHAR = Regex("""\\(.)""")

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
     * The CAs built into the phone, which it already trusts for public
     * websites: the `system:` entries of the Android CA store, never the
     * `user:` ones a person added. Read once, on first use. Empty when the
     * store cannot be read, which is always so in JVM unit tests.
     */
    val builtInCas: List<X509Certificate> by lazy {
        runCatching {
            val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            store.aliases().toList()
                .filter { it.startsWith("system:") }
                .mapNotNull { store.getCertificate(it) as? X509Certificate }
        }.getOrDefault(emptyList())
    }

    /**
     * Decide what the Add Server screen does with a CA file the person picked
     * for manual pinning. [publicAnchors] are the CAs the phone already trusts
     * publicly ([builtInCas]); they come in as a parameter so this stays pure.
     *
     * A pinned CA replaces the host name check (see `TakTls`), so a CA the
     * phone trusts publicly must never be pinned: every certificate that CA
     * signs, for any name, would then pass for this server. One public CA among
     * the CAs kept from the file refuses the whole file.
     */
    fun evaluateManualPin(bytes: ByteArray, publicAnchors: List<X509Certificate>): ManualPinResult {
        val cas = parseCaFile(bytes).distinctBy { sha256Fingerprint(it) }
        if (cas.isEmpty()) return ManualPinResult.NoCa
        val firstPublic = publicCas(cas, publicAnchors).firstOrNull()
        if (firstPublic != null) return ManualPinResult.PublicCa(commonName(firstPublic))
        return ManualPinResult.Ok(
            cas = cas,
            fileName = manualPinFileName(cas),
            fingerprints = cas.map { CaFingerprint(commonName(it), sha256Fingerprint(it)) },
        )
    }

    /**
     * The certificates of [cas] that the phone already trusts publicly, in the
     * order of [cas]. A certificate is public when it is (a) a CA from
     * [anchors], (b) issued by one, or (c) issued by another certificate of
     * [cas] that is public. (c) repeats until nothing changes, so a chain of
     * intermediates under a built-in CA is public all the way down, whatever
     * order the file lists it in.
     */
    private fun publicCas(cas: List<X509Certificate>, anchors: List<X509Certificate>): List<X509Certificate> {
        val isPublic = BooleanArray(cas.size) { i ->
            anchors.any { isSameCa(cas[i], it) || isIssuedBy(cas[i], it) }
        }
        var changed = true
        while (changed) {
            changed = false
            for (i in cas.indices) {
                if (!isPublic[i] && cas.indices.any { j -> isPublic[j] && isIssuedBy(cas[i], cas[j]) }) {
                    isPublic[i] = true
                    changed = true
                }
            }
        }
        return cas.filterIndexed { i, _ -> isPublic[i] }
    }

    /** Same subject and same public key: the very same CA, however it is encoded. */
    private fun isSameCa(cert: X509Certificate, other: X509Certificate): Boolean =
        cert.subjectX500Principal == other.subjectX500Principal &&
            cert.publicKey.encoded.contentEquals(other.publicKey.encoded)

    /** [issuer] is named as the issuer of [cert] and its key checks the signature. */
    private fun isIssuedBy(cert: X509Certificate, issuer: X509Certificate): Boolean =
        cert.issuerX500Principal == issuer.subjectX500Principal &&
            runCatching { cert.verify(issuer.publicKey) }.isSuccess

    /**
     * Vault file name for a set of pinned CAs: `ca-manual-`, the first 16 hex
     * characters of the SHA-256 over the certificates' own SHA-256 fingerprints
     * (lowercase hex, sorted, one per line), and `.pem`. The name follows what
     * the file holds, not what it was called or how it orders its certificates,
     * so two different CA files that are both called `ca.pem` never share a
     * vault file. Only letters, digits, `-` and `.`, and far under the vault's
     * length limit, so [CertVault] keeps it as it is. [cas] has no duplicates.
     */
    private fun manualPinFileName(cas: List<X509Certificate>): String {
        val fingerprints = cas.map { sha256Hex(it.encoded) }.sorted()
        val set = sha256Hex(fingerprints.joinToString("\n").toByteArray(Charsets.US_ASCII))
        return "ca-manual-${set.take(16)}.pem"
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * The common name of [cert]'s subject, or the whole subject when it has
     * none. Android has no javax.naming, so this reads the RFC 2253 text.
     */
    private fun commonName(cert: X509Certificate): String {
        val subject = cert.subjectX500Principal.name
        val escaped = COMMON_NAME.find(subject)?.groupValues?.get(1) ?: return subject
        return ESCAPED_CHAR.replace(escaped) { it.groupValues[1] }
    }

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

/** What the Add Server screen does with a CA file picked for manual pinning. */
sealed interface ManualPinResult {
    /** The file holds no CA certificate. */
    data object NoCa : ManualPinResult

    /** The file holds a CA the phone already trusts publicly, named [commonName]. */
    data class PublicCa(val commonName: String) : ManualPinResult

    /**
     * The file holds only private CAs. Save [cas] in the vault as [fileName]
     * and show [fingerprints], one per CA, to compare with the server's admin.
     */
    data class Ok(
        val cas: List<X509Certificate>,
        val fileName: String,
        val fingerprints: List<CaFingerprint>,
    ) : ManualPinResult
}

/** One pinned CA: its common name and its SHA-256 fingerprint as [CaTrust.sha256Fingerprint] prints it. */
data class CaFingerprint(val commonName: String, val sha256: String)
