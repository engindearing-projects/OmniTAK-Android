package soy.engindearing.omnitak.mobile.data

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date

/**
 * #38 / GAP-106 — locks the CA-pinning contract used by [TAKConnection]
 * to replace the dev-mode trust-all manager.
 *
 * Tests use BouncyCastle (already a runtime dep) to mint a tiny CA + a
 * server cert signed by it + an unrelated CA, so the trust manager's
 * accept/reject path is exercised against real X.509 material rather
 * than mocks.
 */
class CaTrustTest {

    @Test fun encode_decode_roundtrip_preserves_single_cert() {
        val ca = makeSelfSignedCa("CN=Test CA 1")
        val pem = CaTrust.encodePemChain(listOf(ca.cert))
        val pemStr = String(pem)
        assertTrue("must contain BEGIN", pemStr.contains("-----BEGIN CERTIFICATE-----"))
        assertTrue("must contain END", pemStr.contains("-----END CERTIFICATE-----"))

        val parsed = CaTrust.decodePemChain(pem)
        assertEquals(1, parsed.size)
        assertArrayEquals(ca.cert.encoded, parsed[0].encoded)
    }

    @Test fun encode_decode_roundtrip_preserves_chain_order() {
        val ca0 = makeSelfSignedCa("CN=Root 0")
        val ca1 = makeSelfSignedCa("CN=Root 1")
        val pem = CaTrust.encodePemChain(listOf(ca0.cert, ca1.cert))

        val parsed = CaTrust.decodePemChain(pem)
        assertEquals(2, parsed.size)
        assertArrayEquals(ca0.cert.encoded, parsed[0].encoded)
        assertArrayEquals(ca1.cert.encoded, parsed[1].encoded)
    }

    @Test fun decode_empty_input_returns_empty_list() {
        assertEquals(0, CaTrust.decodePemChain(ByteArray(0)).size)
    }

    @Test fun decode_garbage_returns_empty_list() {
        assertEquals(0, CaTrust.decodePemChain("not a pem file".toByteArray()).size)
    }

    @Test fun trustManagerFor_accepts_cert_signed_by_pinned_ca() {
        val ca = makeSelfSignedCa("CN=Pinned CA")
        val leaf = makeLeafCert("CN=tak.test.local", issuedBy = ca)

        val tm = CaTrust.trustManagerFor(listOf(ca.cert))
        // checkServerTrusted throws on rejection; success = no throw.
        tm.checkServerTrusted(arrayOf(leaf, ca.cert), "RSA")
    }

    @Test fun trustManagerFor_rejects_cert_signed_by_unrelated_ca() {
        val pinnedCa = makeSelfSignedCa("CN=Pinned CA")
        val attackerCa = makeSelfSignedCa("CN=Attacker CA")
        val attackerLeaf = makeLeafCert("CN=tak.test.local", issuedBy = attackerCa)

        val tm = CaTrust.trustManagerFor(listOf(pinnedCa.cert))
        try {
            tm.checkServerTrusted(arrayOf(attackerLeaf, attackerCa.cert), "RSA")
            fail("Trust manager must reject cert signed by an unpinned CA")
        } catch (expected: CertificateException) {
            // Path validation will raise CertPathValidatorException wrapped in
            // CertificateException — both shapes are acceptable receipts.
            val msg = generateSequence(expected as Throwable, Throwable::cause)
                .joinToString("; ") { "${it.javaClass.simpleName}: ${it.message}" }
            assertTrue(
                "Rejection should mention trust anchors / path / unable to find / signature, got: $msg",
                msg.contains("anchor", ignoreCase = true) ||
                    msg.contains("path", ignoreCase = true) ||
                    msg.contains("unable to find", ignoreCase = true) ||
                    msg.contains("signature", ignoreCase = true),
            )
        }
    }

    @Test fun systemTrustManager_is_nonnull_and_has_accepted_issuers() {
        val tm = CaTrust.systemTrustManager()
        assertNotNull(tm)
        // The JVM/Android system trust store always has at least the
        // baseline public CAs; an empty list would indicate the factory
        // didn't initialize properly.
        assertTrue(
            "system trust manager should have at least one accepted issuer",
            tm.acceptedIssuers.isNotEmpty(),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun trustManagerFor_empty_list_throws() {
        CaTrust.trustManagerFor(emptyList())
    }

    // ------------------------------------------------------------------
    // helpers — tiny self-signed CAs + leaves via BouncyCastle
    // ------------------------------------------------------------------

    private data class IssuedCert(val cert: X509Certificate, val keyPair: KeyPair)

    // Manual CA pin from the Add Server screen.

    @Test fun parseCaFile_accepts_pem_ca() {
        val ca = makeSelfSignedCa("CN=Manual Pin CA")
        val parsed = CaTrust.parseCaFile(CaTrust.encodePemChain(listOf(ca.cert)))
        assertEquals(1, parsed.size)
        assertArrayEquals(ca.cert.encoded, parsed[0].encoded)
    }

    @Test fun parseCaFile_accepts_der_ca() {
        val ca = makeSelfSignedCa("CN=Manual Pin CA DER")
        val parsed = CaTrust.parseCaFile(ca.cert.encoded)
        assertEquals(1, parsed.size)
        assertArrayEquals(ca.cert.encoded, parsed[0].encoded)
    }

    @Test fun parseCaFile_drops_leaf_certs() {
        val ca = makeSelfSignedCa("CN=Issuer")
        val leaf = makeLeafCert("CN=server", ca)
        assertEquals(0, CaTrust.parseCaFile(CaTrust.encodePemChain(listOf(leaf))).size)
        val mixed = CaTrust.parseCaFile(CaTrust.encodePemChain(listOf(leaf, ca.cert)))
        assertEquals(1, mixed.size)
        assertArrayEquals(ca.cert.encoded, mixed[0].encoded)
    }

    @Test fun parseCaFile_rejects_garbage() {
        assertEquals(0, CaTrust.parseCaFile("not a certificate".toByteArray()).size)
        assertEquals(0, CaTrust.parseCaFile(ByteArray(0)).size)
    }

    @Test fun sha256Fingerprint_matches_openssl_format() {
        val ca = makeSelfSignedCa("CN=Fingerprint CA")
        val fp = CaTrust.sha256Fingerprint(ca.cert)
        assertTrue(fp, Regex("^([0-9A-F]{2}:){31}[0-9A-F]{2}$").matches(fp))
    }

    private fun makeSelfSignedCa(dn: String): IssuedCert {
        val kp = newKeyPair()
        val now = Date()
        val later = Date(now.time + 365L * 24 * 60 * 60 * 1000)
        val builder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
            X500Name(dn),
            BigInteger.valueOf(System.nanoTime()),
            now, later,
            X500Name(dn),
            kp.public,
        ).addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        val signer = JcaContentSignerBuilder("SHA256WithRSA").build(kp.private)
        val holder = builder.build(signer)
        val cert = JcaX509CertificateConverter().getCertificate(holder)
        return IssuedCert(cert, kp)
    }

    private fun makeLeafCert(subjectDn: String, issuedBy: IssuedCert): X509Certificate {
        val kp = newKeyPair()
        val now = Date()
        val later = Date(now.time + 90L * 24 * 60 * 60 * 1000)
        val builder = JcaX509v3CertificateBuilder(
            issuedBy.cert,
            BigInteger.valueOf(System.nanoTime() + 1),
            now, later,
            X500Name(subjectDn),
            kp.public,
        )
        val signer = JcaContentSignerBuilder("SHA256WithRSA").build(issuedBy.keyPair.private)
        val holder = builder.build(signer)
        return JcaX509CertificateConverter().getCertificate(holder)
    }

    private fun newKeyPair(): KeyPair {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        return gen.generateKeyPair()
    }
}
