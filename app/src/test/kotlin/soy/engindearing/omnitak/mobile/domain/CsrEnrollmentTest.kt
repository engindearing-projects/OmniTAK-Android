package soy.engindearing.omnitak.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM receipts for the GAP-081 Android CSR enrollment path. Covers
 * the parser helpers; the network round-trip + KeyStore assembly run on
 * a real device.
 */
class CsrEnrollmentTest {

    @Test fun parseCaConfig_extracts_O_OU_DC_in_order() {
        val xml = """
            <certificateConfig>
              <nameEntry name="O" value="TAK" />
              <nameEntry name="OU" value="OmniTAK" />
              <nameEntry name="DC" value="engindearing" />
              <nameEntry name="DC" value="soy" />
            </certificateConfig>
        """.trimIndent()
        val ca = CsrEnrollment.parseCaConfig(xml)
        assertEquals(listOf("TAK"), ca.o)
        assertEquals(listOf("OmniTAK"), ca.ou)
        assertEquals(listOf("engindearing", "soy"), ca.dc)
    }

    @Test fun parseCaConfig_tolerates_self_closing_or_separate_tags() {
        val xml = """<nameEntry name="O" value="TAK"></nameEntry><nameEntry name="DC" value="local"/>"""
        val ca = CsrEnrollment.parseCaConfig(xml)
        assertEquals(listOf("TAK"), ca.o)
        assertEquals(listOf("local"), ca.dc)
    }

    @Test fun parseSignedResponse_collects_signedCert_and_ordered_ca_chain() {
        val json = """
            {
              "signedCert": "-----BEGIN CERTIFICATE-----\nABC\n-----END CERTIFICATE-----",
              "ca0": "-----BEGIN CERTIFICATE-----\nXYZ\n-----END CERTIFICATE-----",
              "ca1": "-----BEGIN CERTIFICATE-----\nROOT\n-----END CERTIFICATE-----",
              "caPassword": "ignored"
            }
        """.trimIndent()
        val signed = CsrEnrollment.parseSignedResponse(json)
        assertTrue(signed.signedCertPem.contains("ABC"))
        assertEquals(2, signed.caChainPem.size)
        assertTrue(signed.caChainPem[0].contains("XYZ"))
        assertTrue(signed.caChainPem[1].contains("ROOT"))
    }

    @Test(expected = IllegalStateException::class)
    fun parseSignedResponse_throws_when_signedCert_missing() {
        CsrEnrollment.parseSignedResponse("""{"ca0":"x"}""")
    }
}
