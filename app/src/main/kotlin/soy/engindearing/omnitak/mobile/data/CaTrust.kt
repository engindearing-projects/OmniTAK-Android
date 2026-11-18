package soy.engindearing.omnitak.mobile.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SignatureException
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

    /** A picked CA file is read up to this size: a CA certificate, even a long chain of them, is a few KB. */
    const val MAX_CA_FILE_BYTES = 1024 * 1024

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

    private var builtIn: List<X509Certificate>? = null

    /**
     * The CAs built into the phone, which it already trusts for public
     * websites: the `system:` entries of the Android CA store, never the
     * `user:` ones a person added. Null when the store cannot be read or has
     * no `system:` entry (always so in JVM unit tests): the public-CA check
     * cannot be done then. Only a successful read is kept, so a failed one is
     * tried again on the next call. Reads files: call it off the main thread.
     */
    @Synchronized
    fun builtInCas(): List<X509Certificate>? {
        builtIn?.let { return it }
        val loaded = runCatching {
            val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            store.aliases().toList()
                .filter { it.startsWith("system:") }
                .mapNotNull { store.getCertificate(it) as? X509Certificate }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
        if (loaded != null) builtIn = loaded
        return loaded
    }

    /**
     * [evaluateManualPin] for a picked file: at most [MAX_CA_FILE_BYTES] are
     * read, and a bigger file is [ManualPinResult.TooLarge].
     */
    fun evaluateManualPin(input: InputStream, publicAnchors: List<X509Certificate>?): ManualPinResult {
        val bytes = readCapped(input) ?: return ManualPinResult.TooLarge
        return evaluateManualPin(bytes, publicAnchors)
    }

    /**
     * Decide what the Add Server screen does with a CA file the person picked
     * for manual pinning. [publicAnchors] are the CAs the phone already trusts
     * publicly ([builtInCas]); they come in as a parameter so this stays pure.
     * Null means they could not be read: a file with a CA in it is then
     * [ManualPinResult.CannotCheck], never accepted unchecked.
     *
     * A pinned CA replaces the host name check (see `TakTls`), so a CA the
     * phone trusts publicly must never be pinned: every certificate that CA
     * signs, for any name, would then pass for this server. One public CA among
     * the CAs kept from the file refuses the whole file. A chain of CAs under a
     * built-in one always has a CA that is built in or was issued directly by
     * one, so it is enough to look at each CA of the file on its own.
     */
    fun evaluateManualPin(bytes: ByteArray, publicAnchors: List<X509Certificate>?): ManualPinResult {
        val cas = parseCaFile(bytes).distinctBy { sha256Fingerprint(it) }
        if (cas.isEmpty()) return ManualPinResult.NoCa
        if (publicAnchors == null) return ManualPinResult.CannotCheck
        val firstPublic = cas.firstOrNull { ca -> publicAnchors.any { isSameCa(ca, it) || isIssuedBy(ca, it) } }
        if (firstPublic != null) return ManualPinResult.PublicCa(commonName(firstPublic))
        return ManualPinResult.Ok(
            cas = cas,
            fileName = manualPinFileName(cas),
            fingerprints = cas.map { CaFingerprint(commonName(it), sha256Fingerprint(it)) },
        )
    }

    /** All of [input], or null when it holds more than [MAX_CA_FILE_BYTES]; never reads past that. */
    private fun readCapped(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > MAX_CA_FILE_BYTES) return null
            out.write(chunk, 0, n)
        }
    }

    /**
     * What the row under "Server CA certificate" shows for a pinned file: the
     * common name of its first CA, and how many more the file holds. Empty
     * when [fingerprints] is.
     */
    fun manualPinLabel(fingerprints: List<CaFingerprint>): String {
        val first = fingerprints.firstOrNull() ?: return ""
        val more = fingerprints.size - 1
        return if (more == 0) first.commonName else "${first.commonName} and $more more"
    }

    /** Same subject and same public key: the very same CA, however it is encoded or cross-signed. */
    private fun isSameCa(cert: X509Certificate, other: X509Certificate): Boolean =
        cert.subjectX500Principal == other.subjectX500Principal &&
            cert.publicKey.encoded.contentEquals(other.publicKey.encoded)

    /**
     * [issuer] is named as the issuer of [cert] and its key checks the
     * signature. A signature that does not match (or a key of the wrong kind)
     * means another CA used the name. A check that cannot be made at all, an
     * algorithm this phone does not support for instance, counts as issued: a
     * private CA refused by mistake costs a retry by QR code, a public one let
     * through is the hole this check closes.
     */
    private fun isIssuedBy(cert: X509Certificate, issuer: X509Certificate): Boolean {
        if (cert.issuerX500Principal != issuer.subjectX500Principal) return false
        return try {
            cert.verify(issuer.publicKey)
            true
        } catch (e: SignatureException) {
            false
        } catch (e: InvalidKeyException) {
            false
        } catch (e: Exception) {
            true
        }
    }

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
        return commonNameIn(subject)?.takeIf { it.isNotBlank() } ?: subject
    }

    /**
     * The value of the first CN attribute in the RFC 2253 name [name], or null
     * when it has none. Attributes end at a ',' '+' or ';' that is neither
     * escaped with a backslash nor inside quotes, so a "CN=" sitting inside the
     * value of another attribute is never taken for one. (The JDK prints '='
     * inside a value escaped and Android does not: both read the same here.)
     */
    internal fun commonNameIn(name: String): String? {
        val type = StringBuilder()
        val value = StringBuilder()
        var inValue = false
        var quoted = false
        var escaped = false

        fun isCommonName() = type.toString().trim().equals("CN", ignoreCase = true)

        for (c in name) {
            val target = if (inValue) value else type
            when {
                escaped -> {
                    target.append(c)
                    escaped = false
                }
                c == '\\' -> escaped = true
                c == '"' -> quoted = !quoted
                quoted -> target.append(c)
                c == '=' && !inValue -> inValue = true
                c == ',' || c == '+' || c == ';' -> {
                    if (isCommonName()) return value.toString()
                    type.setLength(0)
                    value.setLength(0)
                    inValue = false
                }
                else -> target.append(c)
            }
        }
        return if (isCommonName()) value.toString() else null
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

    /** The file is bigger than [CaTrust.MAX_CA_FILE_BYTES], so it cannot be a CA certificate. */
    data object TooLarge : ManualPinResult

    /** The phone's own CA list could not be read, so the file cannot be checked for a public CA. */
    data object CannotCheck : ManualPinResult

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
