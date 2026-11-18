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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
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

    // What is decided about a picked CA file before it is saved and pinned.
    // The anchors stand in for the phone's built-in CAs, which a JVM does not have.

    @get:Rule val tempDir = TemporaryFolder()

    private fun evaluate(
        certs: List<X509Certificate>,
        anchors: List<X509Certificate> = emptyList(),
    ): ManualPinResult = CaTrust.evaluateManualPin(CaTrust.encodePemChain(certs), anchors)

    private fun okOf(result: ManualPinResult): ManualPinResult.Ok =
        result as? ManualPinResult.Ok ?: throw AssertionError("expected Ok, got $result")

    @Test fun evaluateManualPin_names_the_file_after_what_it_holds() {
        val ca = makeSelfSignedCa("CN=ca")

        val fromPem = okOf(evaluate(listOf(ca.cert)))
        val again = okOf(evaluate(listOf(ca.cert)))
        val fromDer = okOf(CaTrust.evaluateManualPin(ca.cert.encoded, emptyList()))

        assertTrue(fromPem.fileName, Regex("^ca-manual-[0-9a-f]{16}\\.pem$").matches(fromPem.fileName))
        assertEquals("the same CA gives the same name", fromPem.fileName, again.fileName)
        assertEquals("PEM and DER of one CA give the same name", fromPem.fileName, fromDer.fileName)
    }

    @Test fun evaluateManualPin_names_two_different_cas_differently() {
        // Two servers, each on its own CA, both handed over as ca.pem with the same subject.
        val first = makeSelfSignedCa("CN=ca")
        val second = makeSelfSignedCa("CN=ca")

        val firstName = okOf(evaluate(listOf(first.cert))).fileName
        val secondName = okOf(evaluate(listOf(second.cert))).fileName

        assertNotEquals(firstName, secondName)
    }

    @Test fun evaluateManualPin_names_a_set_of_cas_the_same_in_any_order() {
        val a = makeSelfSignedCa("CN=Root A")
        val b = makeSelfSignedCa("CN=Root B")

        val ab = okOf(evaluate(listOf(a.cert, b.cert)))
        val ba = okOf(evaluate(listOf(b.cert, a.cert)))
        val onlyA = okOf(evaluate(listOf(a.cert)))

        assertEquals(ab.fileName, ba.fileName)
        assertNotEquals("one CA is not the same set as two", ab.fileName, onlyA.fileName)
    }

    @Test fun evaluateManualPin_counts_a_ca_listed_twice_once() {
        val a = makeSelfSignedCa("CN=Root A")

        val once = okOf(evaluate(listOf(a.cert)))
        val twice = okOf(evaluate(listOf(a.cert, a.cert)))

        assertEquals(once.fileName, twice.fileName)
        assertEquals(1, twice.cas.size)
        assertEquals(1, twice.fingerprints.size)
    }

    @Test fun evaluateManualPin_file_name_passes_through_the_vault_unchanged() {
        val ok = okOf(evaluate(listOf(makeSelfSignedCa("CN=ca").cert)))
        val vault = object : CertVault(tempDir.newFolder()) {}
        val pem = CaTrust.encodePemChain(ok.cas)

        val saved = vault.importBytes(ok.fileName, pem)

        assertEquals("the vault must keep the name as it is", ok.fileName, saved)
        assertArrayEquals(pem, vault.read(ok.fileName))
    }

    @Test fun evaluateManualPin_refuses_a_ca_the_phone_already_trusts() {
        val root = makeSelfSignedCa("CN=Public Root,O=Big Authority,C=US")

        val result = evaluate(listOf(root.cert), anchors = listOf(root.cert))

        assertEquals(ManualPinResult.PublicCa("Public Root"), result)
    }

    @Test fun evaluateManualPin_refuses_a_ca_issued_by_a_built_in_ca() {
        val root = makeSelfSignedCa("CN=Public Root")
        val intermediate = makeIntermediateCa("CN=Public Intermediate", issuedBy = root)

        val result = evaluate(listOf(intermediate.cert), anchors = listOf(root.cert))

        assertEquals(ManualPinResult.PublicCa("Public Intermediate"), result)
    }

    @Test fun evaluateManualPin_refuses_a_ca_issued_by_a_public_ca_in_the_same_file() {
        val root = makeSelfSignedCa("CN=Public Root")
        val middle = makeIntermediateCa("CN=Public Middle", issuedBy = root)
        val lower = makeIntermediateCa("CN=Public Lower", issuedBy = middle)
        val bottom = makeIntermediateCa("CN=Public Bottom", issuedBy = lower)

        // Deepest first, and only the middle CA was issued by a built-in one. The others are public through
        // the CAs above them in the file, which takes more than one look over the file when it lists them like this.
        val result = evaluate(listOf(bottom.cert, lower.cert, middle.cert), anchors = listOf(root.cert))

        assertEquals(ManualPinResult.PublicCa("Public Bottom"), result)
        // Without the middle CA nothing names the lower CA's issuer, so the middle CA is what made the rest public.
        assertTrue(evaluate(listOf(bottom.cert, lower.cert), anchors = listOf(root.cert)) is ManualPinResult.Ok)
    }

    @Test fun evaluateManualPin_accepts_a_private_ca_that_only_shares_a_built_in_subject() {
        val builtIn = makeSelfSignedCa("CN=Public Root")
        val lookalike = makeSelfSignedCa("CN=Public Root")

        val result = evaluate(listOf(lookalike.cert), anchors = listOf(builtIn.cert))

        assertTrue("got $result", result is ManualPinResult.Ok)
    }

    @Test fun evaluateManualPin_refuses_the_whole_file_when_one_ca_is_public() {
        val root = makeSelfSignedCa("CN=Public Root")
        val own = makeSelfSignedCa("CN=Own CA")
        val expected = ManualPinResult.PublicCa("Public Root")

        assertEquals(expected, evaluate(listOf(own.cert, root.cert), anchors = listOf(root.cert)))
        assertEquals(expected, evaluate(listOf(root.cert, own.cert), anchors = listOf(root.cert)))
    }

    @Test fun evaluateManualPin_finds_no_ca_in_a_file_without_one() {
        val ca = makeSelfSignedCa("CN=Issuer")
        val leaf = makeLeafCert("CN=server", ca)

        assertEquals(ManualPinResult.NoCa, evaluate(listOf(leaf)))
        assertEquals(ManualPinResult.NoCa, CaTrust.evaluateManualPin("not a certificate".toByteArray(), emptyList()))
        assertEquals(ManualPinResult.NoCa, CaTrust.evaluateManualPin(ByteArray(0), emptyList()))
    }

    @Test fun evaluateManualPin_lists_one_fingerprint_per_ca() {
        val a = makeSelfSignedCa("CN=Root A")
        val b = makeSelfSignedCa("CN=Root B")

        val ok = okOf(evaluate(listOf(a.cert, b.cert)))

        assertEquals(2, ok.cas.size)
        assertEquals(
            listOf(
                CaFingerprint("Root A", CaTrust.sha256Fingerprint(a.cert)),
                CaFingerprint("Root B", CaTrust.sha256Fingerprint(b.cert)),
            ),
            ok.fingerprints,
        )
    }

    @Test fun manualPinLabel_is_the_common_name_when_the_file_holds_one_ca() {
        val a = makeSelfSignedCa("CN=Root A")

        val ok = okOf(evaluate(listOf(a.cert)))

        assertEquals("Root A", CaTrust.manualPinLabel(ok.fingerprints))
    }

    @Test fun manualPinLabel_adds_how_many_more_when_the_file_holds_several() {
        val a = makeSelfSignedCa("CN=Root A")
        val b = makeSelfSignedCa("CN=Root B")
        val c = makeSelfSignedCa("CN=Root C")

        val two = okOf(evaluate(listOf(a.cert, b.cert)))
        val three = okOf(evaluate(listOf(b.cert, a.cert, c.cert)))

        assertEquals("Root A and 1 more", CaTrust.manualPinLabel(two.fingerprints))
        assertEquals("Root B and 2 more", CaTrust.manualPinLabel(three.fingerprints))
    }

    @Test fun manualPinLabel_is_empty_when_nothing_is_pinned() {
        assertEquals("", CaTrust.manualPinLabel(emptyList()))
    }

    @Test fun evaluateManualPin_reads_a_common_name_with_an_escaped_comma() {
        val ca = makeSelfSignedCa("CN=Acme\\, Inc Root,O=Acme,C=US")

        val ok = okOf(evaluate(listOf(ca.cert)))

        assertEquals("Acme, Inc Root", ok.fingerprints.single().commonName)
    }

    @Test fun evaluateManualPin_falls_back_to_the_whole_subject_without_a_common_name() {
        val ca = makeSelfSignedCa("O=Plain Org,C=US")

        val ok = okOf(evaluate(listOf(ca.cert)))

        assertEquals(ca.cert.subjectX500Principal.name, ok.fingerprints.single().commonName)
    }

    @Test fun builtInCas_is_empty_where_the_android_store_does_not_exist() {
        // Only a phone has "AndroidCAStore". On the JVM the lookup throws, and the list is empty instead.
        assertEquals(emptyList<X509Certificate>(), CaTrust.builtInCas)
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

    /** A CA certificate for [subjectDn] signed by [issuedBy], with a key of its own so it can sign in turn. */
    private fun makeIntermediateCa(subjectDn: String, issuedBy: IssuedCert): IssuedCert {
        val kp = newKeyPair()
        val now = Date()
        val later = Date(now.time + 365L * 24 * 60 * 60 * 1000)
        val builder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
            issuedBy.cert,
            BigInteger.valueOf(System.nanoTime() + 2),
            now, later,
            X500Name(subjectDn),
            kp.public,
        ).addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        val signer = JcaContentSignerBuilder("SHA256WithRSA").build(issuedBy.keyPair.private)
        val holder = builder.build(signer)
        return IssuedCert(JcaX509CertificateConverter().getCertificate(holder), kp)
    }

    private fun newKeyPair(): KeyPair {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        return gen.generateKeyPair()
    }
}
