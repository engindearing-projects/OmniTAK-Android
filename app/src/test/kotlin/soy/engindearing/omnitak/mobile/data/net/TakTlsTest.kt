package soy.engindearing.omnitak.mobile.data.net

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.CaTrust
import soy.engindearing.omnitak.mobile.data.CertVault
import soy.engindearing.omnitak.mobile.data.TAKServer
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Closes #45 — locks the server-trust policy enforced by [TakTls.serverTrust].
 *
 * Both [soy.engindearing.omnitak.mobile.data.TakRestApiClient] and
 * [soy.engindearing.omnitak.mobile.data.TAKConnection] delegate to
 * [TakTls.serverTrust] for server certificate validation, so a single
 * policy test covers both connection planes. Three trust resolutions:
 *
 *  1. **Pinned CA** — caCertificateName set + vault has the PEM →
 *     CA-pinned trust manager. A cert from an unrelated CA must be
 *     rejected — the MITM-prevention core of #38/#45.
 *  2. **System trust** — no pin available → platform CA trust +
 *     hostname verification.
 *  3. **Explicit bypass** — allowUntrustedTls=true → trust-all
 *     (operator opt-out, default false).
 *
 * [MapCertVault] provides an in-memory [CertVault] for pure-JVM testing.
 * [CertVault.read] is open so the stub can override file I/O without
 * Robolectric. BouncyCastle (already a runtime dep) supplies real X.509
 * material.
 */
class TakTlsTest {

    // ── 1. Pinned-CA path ─────────────────────────────────────────────────

    @Test
    fun `serverTrust with pinned CA accepts cert signed by that CA`() {
        val ca = makeSelfSignedCa("CN=TAK Pinned CA")
        val leaf = makeLeafCert("CN=tak.example.local", issuedBy = ca)
        val vault = mapVault(CA_NAME to CaTrust.encodePemChain(listOf(ca.cert)))
        val server = serverWithPin(CA_NAME)

        val trust = TakTls.serverTrust(server, vault)

        assertTrue("expected pinned=true for CA-pinned trust", trust.pinned)
        assertFalse("expected untrusted=false for CA-pinned trust", trust.untrusted)
        // No throw = accepted.
        trust.trustManager.checkServerTrusted(arrayOf(leaf, ca.cert), "RSA")
    }

    @Test
    fun `serverTrust with pinned CA rejects cert signed by unrelated CA`() {
        val pinnedCa = makeSelfSignedCa("CN=TAK Pinned CA")
        val attackerCa = makeSelfSignedCa("CN=Attacker CA")
        val attackerLeaf = makeLeafCert("CN=tak.example.local", issuedBy = attackerCa)
        val vault = mapVault(CA_NAME to CaTrust.encodePemChain(listOf(pinnedCa.cert)))
        val server = serverWithPin(CA_NAME)

        val trust = TakTls.serverTrust(server, vault)

        assertTrue("expected pinned=true", trust.pinned)
        try {
            trust.trustManager.checkServerTrusted(arrayOf(attackerLeaf, attackerCa.cert), "RSA")
            fail(
                "Pinned trust manager MUST reject a cert issued by an unpinned CA. " +
                    "This is the core MITM-prevention requirement from #38/#45: " +
                    "a tampered server cert from outside the pinned CA chain must " +
                    "fail the TLS handshake on both the CoT socket and the REST plane.",
            )
        } catch (_: CertificateException) {
            // Expected: trust anchor not found / path validation failure.
        }
    }

    @Test
    fun `serverTrust with pinned CA does not require hostname verification`() {
        // TAK servers are routinely addressed by IP or internal names that
        // do not appear in the cert's SAN/CN — the per-server pin IS the
        // server identity, making hostname verification redundant.
        val ca = makeSelfSignedCa("CN=TAK CA")
        val vault = mapVault(CA_NAME to CaTrust.encodePemChain(listOf(ca.cert)))

        val trust = TakTls.serverTrust(serverWithPin(CA_NAME), vault)

        assertFalse("pinned path must not require hostname verification", trust.verifiesHostname)
    }

    // ── 2. System-trust path (fall-through when no pin available) ────────

    @Test
    fun `serverTrust falls through to system trust when vault is null`() {
        val trust = TakTls.serverTrust(serverWithPin(CA_NAME), certVault = null)

        assertFalse("no pin available → pinned must be false", trust.pinned)
        assertFalse("no opt-out → untrusted must be false", trust.untrusted)
        assertTrue("system trust path must verify hostname", trust.verifiesHostname)
        assertNotNull(trust.trustManager)
    }

    @Test
    fun `serverTrust falls through to system trust when caCertificateName is null`() {
        val server = TAKServer(name = "t", host = "tak.local", port = 8089, caCertificateName = null)

        val trust = TakTls.serverTrust(server, mapVault())

        assertFalse("no CA name → pinned must be false", trust.pinned)
        assertFalse("no opt-out → untrusted must be false", trust.untrusted)
        assertTrue("system path verifies hostname", trust.verifiesHostname)
    }

    @Test
    fun `serverTrust falls through to system trust when CA file is absent from vault`() {
        val trust = TakTls.serverTrust(serverWithPin("ca-missing.pem"), mapVault())

        assertFalse("missing CA file → pinned must be false", trust.pinned)
        assertFalse("no opt-out → untrusted must be false", trust.untrusted)
        assertTrue("system path verifies hostname", trust.verifiesHostname)
    }

    @Test
    fun `serverTrust falls through to system trust when CA file is corrupt`() {
        val vault = mapVault(CA_NAME to "not a pem file".toByteArray())

        val trust = TakTls.serverTrust(serverWithPin(CA_NAME), vault)

        assertFalse("corrupt CA file → pinned must be false", trust.pinned)
        assertFalse("no opt-out → untrusted must be false", trust.untrusted)
    }

    // ── 3. Explicit bypass path (allowUntrustedTls=true) ─────────────────

    @Test
    fun `serverTrust with allowUntrustedTls=true is untrusted and skips hostname check`() {
        val ca = makeSelfSignedCa("CN=TAK CA")
        val vault = mapVault(CA_NAME to CaTrust.encodePemChain(listOf(ca.cert)))
        val server = TAKServer(
            name = "Lab",
            host = "10.0.0.5",
            port = 8089,
            allowUntrustedTls = true,
            caCertificateName = CA_NAME,   // bypass wins even when a pin exists
        )

        val trust = TakTls.serverTrust(server, vault)

        assertTrue("allowUntrustedTls=true → untrusted flag must be set", trust.untrusted)
        assertFalse("explicit bypass must not set pinned", trust.pinned)
        assertFalse("explicit bypass skips hostname verification", trust.verifiesHostname)
        // Trust-all accepts literally anything — that's the documented opt-out.
        val anyCa = makeSelfSignedCa("CN=Arbitrary CA")
        trust.trustManager.checkServerTrusted(arrayOf(anyCa.cert), "RSA")
    }

    @Test
    fun `TAKServer allowUntrustedTls defaults to false`() {
        // Regression guard: default must be validated TLS so pre-0.36 persisted
        // JSON blobs (without the field) stay on validated TLS after upgrade.
        assertFalse(
            "allowUntrustedTls must default to false",
            TAKServer(name = "d", host = "tak.local", port = 8089).allowUntrustedTls,
        )
    }

    // ------------------------------------------------------------------
    // Test infrastructure — in-memory CertVault stub
    // ------------------------------------------------------------------

    /**
     * In-memory [CertVault] stub for pure-JVM unit tests.
     *
     * [CertVault.read] is `open` and the protected `File`-based constructor
     * is used so no Android [android.content.Context] is ever touched in
     * these tests. The storage directory is a throwaway temp dir — it is
     * irrelevant because [read] is overridden to serve from an in-memory map.
     */
    private class MapCertVault(
        private val files: Map<String, ByteArray>,
    ) : CertVault(java.io.File(System.getProperty("java.io.tmpdir"), "omnitak-test-vault")) {

        override fun read(name: String): ByteArray? = files[name]
    }

    /** Build a [MapCertVault] with zero or more pre-loaded entries. */
    private fun mapVault(vararg entries: Pair<String, ByteArray>): MapCertVault =
        MapCertVault(mapOf(*entries))

    private fun serverWithPin(caName: String) = TAKServer(
        name = "Test Server",
        host = "tak.example.local",
        port = 8089,
        caCertificateName = caName,
    )

    // ------------------------------------------------------------------
    // BouncyCastle helpers — real self-signed CAs + leaf certs
    // ------------------------------------------------------------------

    private data class IssuedCert(val cert: X509Certificate, val keyPair: KeyPair)

    private fun makeSelfSignedCa(dn: String): IssuedCert {
        val kp = newKeyPair()
        val now = Date()
        val later = Date(now.time + 365L * 24 * 60 * 60 * 1000)
        val builder = JcaX509v3CertificateBuilder(
            X500Name(dn), BigInteger.valueOf(System.nanoTime()),
            now, later, X500Name(dn), kp.public,
        ).addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        val signer = JcaContentSignerBuilder("SHA256WithRSA").build(kp.private)
        return IssuedCert(JcaX509CertificateConverter().getCertificate(builder.build(signer)), kp)
    }

    private fun makeLeafCert(subjectDn: String, issuedBy: IssuedCert): X509Certificate {
        val kp = newKeyPair()
        val now = Date()
        val later = Date(now.time + 90L * 24 * 60 * 60 * 1000)
        val builder = JcaX509v3CertificateBuilder(
            issuedBy.cert, BigInteger.valueOf(System.nanoTime() + 1),
            now, later, X500Name(subjectDn), kp.public,
        )
        val signer = JcaContentSignerBuilder("SHA256WithRSA").build(issuedBy.keyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    companion object {
        private const val CA_NAME = "ca-tak.example.local.pem"
    }
}
