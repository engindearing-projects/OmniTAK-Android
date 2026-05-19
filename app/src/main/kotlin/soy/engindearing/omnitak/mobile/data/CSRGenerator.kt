package soy.engindearing.omnitak.mobile.data

import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import java.security.KeyPair
import java.security.KeyPairGenerator

/**
 * PKCS#10 Certificate Signing Request generator for TAK Server Quick Connect
 * enrollment (GAP-081). Mirrors the iOS CSRGenerator in OmniTAK-iOS.
 *
 * **Subject DN policy — IMPORTANT:** BBN TAK Server's `CertManagerService`
 * (line 143) rejects CSRs whose Subject DN contains any RDN the server's
 * `<nameEntries>` policy did not declare. Most TAK Server configs only
 * declare `O` + `OU`. The corresponding iOS bug
 * (issue #31, PR #32 in OmniTAK-iOS, fix shipped in 2.23.0) was caused by
 * an unconditional `C=US` injection. We deliberately do NOT default `country`
 * here — the caller passes only what the server explicitly returned in its
 * tlsConfig response, plus the authenticated username as `CN`.
 */
object CSRGenerator {

    private const val KEY_ALGORITHM = "RSA"
    private const val KEY_SIZE = 2048
    private const val SIGNATURE_ALGORITHM = "SHA256WithRSA"

    /**
     * Configuration for one CSR. The fields are intentionally nullable so a
     * caller working from a server's `/Marti/api/tls/config` response can
     * pass `null` for any RDN the server did not declare.
     */
    data class Config(
        val commonName: String,
        val organization: String? = null,
        val organizationalUnit: String? = null,
        val country: String? = null,
        val email: String? = null,
        val keySize: Int = KEY_SIZE
    ) {
        init {
            require(commonName.isNotBlank()) { "Common Name (CN) is required" }
            country?.let {
                require(it.isBlank() || it.length == 2) {
                    "Country (C) must be a 2-letter code when provided"
                }
            }
            require(keySize == 2048 || keySize == 4096) {
                "Key size must be 2048 or 4096"
            }
        }
    }

    /** Result of one generation pass. */
    data class Result(
        val csrDer: ByteArray,
        val csrBase64: String,
        val keyPair: KeyPair
    )

    /**
     * Build an RSA key pair and a PKCS#10 CSR signed with it.
     *
     * The CSR body bytes the caller should POST to
     * `/Marti/api/tls/signClient/v2` is [Result.csrBase64] — raw base64 of
     * the DER, no PEM `-----BEGIN-----` headers, `Content-Type: text/plain;
     * charset=utf-8`. Matches TAKTracker / OmniTAK-iOS wire format.
     */
    fun generate(config: Config): Result {
        val keyPair = generateKeyPair(config.keySize)
        val subject = buildSubjectDN(config)
        val signer = JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
            .build(keyPair.private)
        val csr: PKCS10CertificationRequest =
            JcaPKCS10CertificationRequestBuilder(subject, keyPair.public)
                .build(signer)

        val der = csr.encoded
        val base64 = android.util.Base64.encodeToString(
            der,
            android.util.Base64.NO_WRAP
        )
        return Result(csrDer = der, csrBase64 = base64, keyPair = keyPair)
    }

    private fun generateKeyPair(keySize: Int): KeyPair {
        val gen = KeyPairGenerator.getInstance(KEY_ALGORITHM)
        gen.initialize(keySize)
        return gen.generateKeyPair()
    }

    /**
     * Build the Subject DN, emitting only RDNs the caller explicitly
     * provided. This is the heart of the iOS bug fix — see the class
     * doc comment.
     */
    internal fun buildSubjectDN(config: Config): org.bouncycastle.asn1.x500.X500Name {
        val builder = X500NameBuilder(BCStyle.INSTANCE)
        // Standard RDN order: C, O, OU, CN, emailAddress (RFC 5280 canonical).
        config.country?.takeIf { it.isNotBlank() }
            ?.let { builder.addRDN(BCStyle.C, it) }
        config.organization?.takeIf { it.isNotBlank() }
            ?.let { builder.addRDN(BCStyle.O, it) }
        config.organizationalUnit?.takeIf { it.isNotBlank() }
            ?.let { builder.addRDN(BCStyle.OU, it) }
        builder.addRDN(BCStyle.CN, config.commonName)
        config.email?.takeIf { it.isNotBlank() }
            ?.let { builder.addRDN(BCStyle.EmailAddress, it) }
        return builder.build()
    }
}
