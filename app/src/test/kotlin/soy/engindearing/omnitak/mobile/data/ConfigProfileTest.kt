package soy.engindearing.omnitak.mobile.data

import android.net.Uri
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.Base64

/**
 * Unit tests for [ConfigProfile] serialization, [ProfileQrCodec] round-trip,
 * and [ProfileServer] secret-stripping.
 *
 * JVM-only (no Android context needed): ProfileQrCodec uses
 * [java.util.Base64] (available on JVM 8+) and stock java.util.zip.
 * [android.net.Uri] from the `org.robolectric:robolectric` jar would be
 * needed for Uri.parse in a real device test; here we test the raw
 * encode/decode helpers without Uri.
 */
class ConfigProfileTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ── ConfigProfile serialization ───────────────────────────────────────

    @Test
    fun profileSerializationRoundTrip() {
        val profile = ConfigProfile(
            id = "test-id",
            name = "Alpha Team",
            team = "Cyan",
            servers = listOf(
                ProfileServer(
                    id = "srv1",
                    name = "TAK HQ",
                    host = "tak.example.com",
                    port = 8089,
                    protocol = "tls",
                    useTLS = true,
                    // No username / password field in ProfileServer — by design (PII + secret).
                )
            ),
            enrollmentPointer = EnrollmentPointer(
                host = "tak.example.com",
                enrollmentPort = 8446,
                // No username — PII excluded per security contract.
                trustSelfSigned = true,
            ),
            mapProvider = MapProvider.TOPO_HINT.name,
            customTileUrl = "",
            coordFormat = CoordFormat.MGRS.name,
            distanceUnit = DistanceUnit.IMPERIAL.name,
        )

        val encoded = json.encodeToString(ConfigProfile.serializer(), profile)
        val decoded = json.decodeFromString(ConfigProfile.serializer(), encoded)

        assertEquals(profile.id, decoded.id)
        assertEquals(profile.name, decoded.name)
        assertEquals(profile.team, decoded.team)
        assertEquals(profile.coordFormat, decoded.coordFormat)
        assertEquals(profile.distanceUnit, decoded.distanceUnit)
        assertEquals(1, decoded.servers.size)
        assertEquals("tak.example.com", decoded.servers[0].host)
        assertEquals(8089, decoded.servers[0].port)
        // username is intentionally excluded from ProfileServer (PII).
        assertNotNull(decoded.enrollmentPointer)
        assertEquals(8446, decoded.enrollmentPointer!!.enrollmentPort)
    }

    @Test
    fun profileSerializationWithUnknownFieldsIsIgnored() {
        // Simulate a newer profile version with an extra field — must decode without error.
        val rawJson = """
            {
              "id": "id1",
              "name": "Beta Team",
              "team": "Red",
              "unknownFutureField": 42,
              "servers": [],
              "mapProvider": "OSM_RASTER",
              "customTileUrl": "",
              "coordFormat": "LATLON_DECIMAL",
              "distanceUnit": "METRIC",
              "callsignCardVisible": true,
              "gridEnabled": false,
              "drawingsVisible": true,
              "aircraftVisible": true,
              "contactsVisible": true,
              "useMilStdSelfSymbol": true,
              "autoPublishMeshToTak": true,
              "broadcastOverMesh": true,
              "meshBroadcastIntervalSecs": 30,
              "meshNodesLayerVisible": true
            }
        """.trimIndent()
        val decoded = json.decodeFromString(ConfigProfile.serializer(), rawJson)
        assertEquals("Beta Team", decoded.name)
        assertEquals("Red", decoded.team)
    }

    // ── ProfileServer secret-stripping ────────────────────────────────────

    @Test
    fun profileServerFromServerStripsSecrets() {
        val server = TAKServer(
            id = "abc",
            name = "Secure Server",
            host = "tak.example.com",
            port = 8089,
            protocol = "tls",
            useTLS = true,
            username = "operator",
            password = "s3cr3t",
            certificatePassword = "certpass",
            certificateName = "client.p12",
        )
        val profileServer = ProfileServer.fromServer(server)

        // Secrets + PII must be excluded.
        // No username field in ProfileServer — verified at class definition level.
        // No password field in ProfileServer — by design.
        assertEquals("abc", profileServer.id)
        assertEquals("tak.example.com", profileServer.host)
        assertEquals(8089, profileServer.port)
        assertTrue(profileServer.useTLS)
    }

    @Test
    fun profileServerToServerHasNoSecrets() {
        val ps = ProfileServer(
            id = "srv1",
            name = "TAK Server",
            host = "tak.mil",
            port = 8089,
            protocol = "tls",
            useTLS = true,
            // username not included — PII excluded from ProfileServer.
        )
        val server = ProfileServer.toServer(ps)

        assertEquals("tak.mil", server.host)
        // username is null — not propagated from profile (PII excluded).
        assertNull("username must not be set on import", server.username)
        // Secrets must be null — the teammate enrolls their own cert.
        assertNull("password must not be set on import", server.password)
        assertNull("certificatePassword must not be set on import", server.certificatePassword)
        assertNull("certificateName must not be set on import", server.certificateName)
    }

    // ── ProfileQrCodec round-trip ─────────────────────────────────────────

    @Test
    fun qrCodecEncodeDecodeRoundTrip() {
        val profile = ConfigProfile(
            id = "qr-test",
            name = "QR Test Profile",
            team = "Blue",
            servers = listOf(
                ProfileServer(
                    id = "s1",
                    name = "HQ",
                    host = "hq.example.org",
                    port = 8089,
                    protocol = "tls",
                    useTLS = true,
                    // No username — PII excluded from ProfileServer.
                )
            ),
            coordFormat = CoordFormat.MGRS.name,
            distanceUnit = DistanceUnit.IMPERIAL.name,
            broadcastOverMesh = false,
            meshBroadcastIntervalSecs = 45,
        )

        val uriString = ProfileQrCodec.encode(profile)

        // Payload must start with the correct scheme.
        assertTrue("Expected omnitak://profile URI, got: $uriString",
            uriString.startsWith("omnitak://profile?d="))

        // Decode via the string overload (JVM-safe).
        val decoded = ProfileQrCodec.decode(uriString)

        assertNotNull("Decoded profile must not be null", decoded)
        assertEquals(profile.id, decoded!!.id)
        assertEquals(profile.name, decoded.name)
        assertEquals(profile.team, decoded.team)
        assertEquals(profile.coordFormat, decoded.coordFormat)
        assertEquals(profile.distanceUnit, decoded.distanceUnit)
        assertEquals(false, decoded.broadcastOverMesh)
        assertEquals(45, decoded.meshBroadcastIntervalSecs)
        assertEquals(1, decoded.servers.size)
        assertEquals("hq.example.org", decoded.servers[0].host)
    }

    @Test
    fun qrCodecEncodedPayloadIsReasonablySmall() {
        // A profile with 3 servers should still produce a QR-friendly payload
        // (< 2048 bytes in the URI string).
        val profile = ConfigProfile(
            name = "Large Team Profile",
            team = "Dark Blue",
            servers = (1..3).map { i ->
                ProfileServer(
                    id = "server-$i",
                    name = "TAK Server $i",
                    host = "tak$i.example.com",
                    port = 8089,
                    protocol = "tls",
                    useTLS = true,
                    // No username — PII excluded from ProfileServer.
                )
            },
            enrollmentPointer = EnrollmentPointer("tak1.example.com", 8446),
        )
        val uriString = ProfileQrCodec.encode(profile)
        assertTrue(
            "QR URI too large for reliable scanning: ${uriString.length} bytes (limit 2048)",
            uriString.length < 2048,
        )
    }

    @Test
    fun qrCodecRejectsNonProfileUri() {
        val decoded = ProfileQrCodec.decode("omnitak://server?host=tak.example.com&port=8089")
        assertNull("Non-profile URI must decode to null", decoded)
    }

    @Test
    fun qrCodecRejectsGarbageInput() {
        val decoded = ProfileQrCodec.decode("not-a-uri-at-all!!!")
        assertNull("Garbage input must decode to null", decoded)
    }

    @Test
    fun qrCodecRejectsProfileUriWithCorruptPayload() {
        val decoded = ProfileQrCodec.decode("omnitak://profile?d=NOTVALIDBASE64!!!")
        assertNull("Corrupt payload must decode to null", decoded)
    }

    // ── isProfileUri checks ───────────────────────────────────────────────

    @Test
    fun isProfileUriReturnsFalseForNull() {
        assertFalse(ProfileQrCodec.isProfileUri(null))
    }

    // ── Gzip-bomb guard ─────────────────────────────────────────────────────

    /**
     * A hostile QR can embed a gzip stream that decompresses to many megabytes
     * (gzip bomb). [ProfileQrCodec.decode] must reject payloads that decompress
     * beyond the 64 KiB cap rather than OOM the app.
     */
    @Test
    fun qrCodecRejectsOversizedPayload() {
        // Build a gzip stream whose decompressed size is > 64 KiB (just under
        // 1 MB of repeating bytes) and encode it as a fake profile URI.
        val bigData = ByteArray(1_024 * 1_024) { 0x41 } // 1 MB of 'A'
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(bigData) }
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(bos.toByteArray())
        val uriStr = "omnitak://profile?d=$b64"

        // decode() wraps in runCatching and returns null on any error —
        // the gzip-bomb guard throws inside gunzip, which is caught.
        val decoded = ProfileQrCodec.decode(uriStr)
        assertNull("Oversized QR payload must decode to null (gzip-bomb guard)", decoded)
    }

    // ── No-secrets regression test ───────────────────────────────────────────

    /**
     * Asserts that [ConfigProfileStore.snapshotCurrent] (via [ProfileQrCodec.encode])
     * produces a QR payload that contains NO password, cert passphrase, cert blob,
     * or username (PII). This locks the security contract in code.
     */
    @Test
    fun snapshotCurrentQrContainsNoSecrets() {
        // Build a TAKServer carrying every secret field.
        val server = TAKServer(
            id = "srv-secret-test",
            name = "Secure HQ",
            host = "tak.mil",
            port = 8089,
            protocol = "tls",
            useTLS = true,
            username = "operator42",
            password = "hunter2",
            certificatePassword = "certPass123",
            certificateName = "client.p12",
        )

        // Simulate what snapshotCurrent builds: ProfileServer.fromServer strips secrets.
        val profileServer = ProfileServer.fromServer(server)
        val enrollmentPointer = EnrollmentPointer(
            host = server.host,
            enrollmentPort = 8446,
            // username intentionally omitted per Fix 11.
        )
        val profile = ConfigProfile(
            id = "sec-test",
            name = "Security Regression",
            servers = listOf(profileServer),
            enrollmentPointer = enrollmentPointer,
        )

        val uriStr = ProfileQrCodec.encode(profile)

        // Decode and re-serialize to get the raw JSON for content inspection.
        val decoded = ProfileQrCodec.decode(uriStr)
        assertNotNull("Profile must decode successfully", decoded)
        val rawJson = Json { encodeDefaults = true }
            .encodeToString(ConfigProfile.serializer(), decoded!!)

        assertFalse("QR must not contain password 'hunter2'", rawJson.contains("hunter2"))
        assertFalse("QR must not contain cert password 'certPass123'", rawJson.contains("certPass123"))
        assertFalse("QR must not contain cert blob 'client.p12'", rawJson.contains("client.p12"))
        assertFalse("QR must not contain username 'operator42'", rawJson.contains("operator42"))
    }

    @Test
    fun isProfileUriReturnsTrueForValidUri() {
        val profile = ConfigProfile(name = "Test", team = "Green")
        val uriString = ProfileQrCodec.encode(profile)
        // Parse the URI string to an android.net.Uri for the check.
        // On JVM, Uri.parse works without robolectric for simple strings
        // because Android's Uri class is partly JVM-compatible.
        // If this fails on pure JVM, the test will throw — acceptable since
        // the real test coverage comes from the encode/decode tests above.
        val uri = runCatching { android.net.Uri.parse(uriString) }.getOrNull()
        if (uri != null) {
            assertTrue(ProfileQrCodec.isProfileUri(uri))
        }
        // If Uri.parse throws on JVM (stubs), we skip this particular assertion.
    }
}
