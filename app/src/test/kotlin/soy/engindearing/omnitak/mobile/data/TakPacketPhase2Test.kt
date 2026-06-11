package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-2 golden-vector tests for Unishox2, TakPacketSerializer, TakPacketParser,
 * TakEnums, and format-detection.
 *
 * All golden vectors are embedded from /tmp/mesh_golden_vectors.json (produced by
 * unishox2-py3 1.0.0 + the reference gateway proto).
 *
 * Unishox2 byte-exactness is the pass/fail gate — the tests assert compressed hex
 * byte-for-byte against the Python reference output.
 */
class TakPacketPhase2Test {

    // -------------------------------------------------------------------------
    // (a) Unishox2 compress/decompress golden vectors
    // -------------------------------------------------------------------------

    private data class UnishoxVector(val text: String, val hexExpected: String)

    private val UNISHOX_VECTORS = listOf(
        UnishoxVector("ALPHA1",              "804f1e3b4814ff"),
        UnishoxVector("ANDROID-1234abcd",    "804e75babe80b4452091a55e69"),
        UnishoxVector("Moving to OBJ Bravo", "879afabcf65148283d07ea0f5b9faa"),
        UnishoxVector("",                    "97"),
        UnishoxVector("Overwatch-7",         "857d3dfbcc73d8b45dbf"),
        UnishoxVector("RTO actual, sitrep?", "86c20294f31de7824b571b7e07d8"),
    )

    @Test fun unishox2_compress_matches_golden_vectors() {
        for (v in UNISHOX_VECTORS) {
            val compressed = Unishox2.compress(v.text)
            val actualHex = compressed.toHex()
            assertEquals(
                "compress(\"${v.text}\") should equal golden vector",
                v.hexExpected,
                actualHex,
            )
        }
    }

    @Test fun unishox2_decompress_round_trips_golden_vectors() {
        for (v in UNISHOX_VECTORS) {
            val compressed = v.hexExpected.fromHex()
            val decompressed = Unishox2.decompress(compressed)
            assertEquals(
                "decompress(compress(\"${v.text}\")) should round-trip",
                v.text,
                decompressed,
            )
        }
    }

    @Test fun unishox2_compress_then_decompress_round_trips() {
        for (v in UNISHOX_VECTORS) {
            val compressed = Unishox2.compress(v.text)
            val decompressed = Unishox2.decompress(compressed)
            assertEquals(
                "compress→decompress should be identity for \"${v.text}\"",
                v.text,
                decompressed,
            )
        }
    }

    // -------------------------------------------------------------------------
    // (b) TakPacketParser.parse(PLI wire_hex) → CoTEvent lat≈47.6062 lon≈-122.3321
    //     callsign ALPHA1 team Cyan
    // -------------------------------------------------------------------------

    // Golden PLI wire_hex from mesh_golden_vectors.json:
    //   fields: callsign=ALPHA1, device_callsign=ANDROID-1234abcd, team=Cyan(10),
    //           role=TeamMember(1), battery=85, lat=47.6062, lon=-122.3321,
    //           altitude=56, speed=3, course=270, is_compressed=false
    private val PLI_WIRE_HEX = "121a0a06414c504841311210414e44524f49442d31323334616263641a040801100a220208552a110d3021601c15589a15b718382003288e02"

    @Test fun parse_pli_golden_vector() {
        val bytes = PLI_WIRE_HEX.fromHex()
        val event = TakPacketParser.parse(bytes)
        assertNotNull("PLI golden vector should parse", event)
        event!!

        assertEquals("a-f-G-U-C", event.type)
        assertEquals("ALPHA1", event.callsign)
        assertEquals(47.6062, event.lat, 1e-4)
        assertEquals(-122.3321, event.lon, 1e-4)
        assertEquals("Cyan", event.teamName)
        // uid = device_callsign
        assertTrue("uid should be device callsign", event.uid.contains("ANDROID-1234abcd"))
    }

    // -------------------------------------------------------------------------
    // (c) parse(GeoChat wire_hex) → b-t-f message "Moving to OBJ Bravo" team Red
    // -------------------------------------------------------------------------

    // Golden GeoChat wire_hex from mesh_golden_vectors.json:
    //   fields: callsign=ALPHA1(compressed), device_callsign=ANDROID-1234abcd(compressed),
    //           team=Red(5), role=TeamLead(2), battery=72,
    //           message=Moving to OBJ Bravo(compressed), to=All Chat Rooms(raw),
    //           is_compressed=true
    private val GEOCHAT_WIRE_HEX = "080112180a07804f1e3b4814ff120d804e75babe80b4452091a55e691a04080210052202084832210a0f879afabcf65148283d07ea0f5b9faa120e416c6c204368617420526f6f6d73"

    @Test fun parse_geochat_golden_vector() {
        val bytes = GEOCHAT_WIRE_HEX.fromHex()
        val event = TakPacketParser.parse(bytes)
        assertNotNull("GeoChat golden vector should parse", event)
        event!!

        assertEquals("b-t-f", event.type)
        assertEquals("Moving to OBJ Bravo", event.remarks)
        assertEquals("Red", event.teamName)
        assertEquals("ALPHA1", event.callsign)
    }

    // -------------------------------------------------------------------------
    // (d) TakPacketSerializer encode uncompressed PLI byte-matches golden PLI wire_hex
    // -------------------------------------------------------------------------

    @Test fun serialize_pli_matches_golden_wire_hex() {
        // Build the exact same CoTEvent the golden vector represents.
        val event = CoTEvent(
            uid = "ANDROID-1234abcd",
            type = "a-f-G-U-C",
            lat = 47.6062,
            lon = -122.3321,
            hae = 56.0,
            callsign = "ALPHA1",
            teamName = "Cyan",
            teamRole = "Team Member",
        )
        val actual = TakPacketSerializer.serializePli(
            event = event,
            callsign = "ALPHA1",
            deviceId = "ANDROID-1234abcd",
            battery = 85,
            speed = 3,
            course = 270,
        )
        val expected = PLI_WIRE_HEX.fromHex()

        // Compare byte by byte; print diff on failure
        if (!actual.contentEquals(expected)) {
            val actualHex = actual.toHex()
            assertEquals(
                "PLI serializer output should match golden wire_hex\nexpected: $PLI_WIRE_HEX\nactual  : $actualHex",
                PLI_WIRE_HEX,
                actualHex,
            )
        }
    }

    // -------------------------------------------------------------------------
    // (e) TakEnums name↔int both ways + normalization
    // -------------------------------------------------------------------------

    @Test fun team_enum_int_to_name_round_trip() {
        val cases = mapOf(
            0  to "Unspecifed_Color",
            1  to "White",
            5  to "Red",
            8  to "Dark_Blue",
            9  to "Blue",
            10 to "Cyan",
            13 to "Dark_Green",
            14 to "Brown",
        )
        for ((v, name) in cases) {
            val team = Team.fromValue(v)
            assertEquals("Team.fromValue($v).name", name, team.name)
            assertEquals("Team.fromValue($v).value", v, team.value)
        }
    }

    @Test fun team_cot_name_round_trip() {
        // Space form (CoT display)
        assertEquals(Team.Dark_Blue, Team.fromCotName("Dark Blue"))
        assertEquals(Team.Dark_Green, Team.fromCotName("Dark Green"))
        assertEquals(Team.Cyan, Team.fromCotName("Cyan"))
        assertEquals(Team.Red, Team.fromCotName("Red"))
        // Underscore form (proto enum name)
        assertEquals(Team.Dark_Blue, Team.fromCotName("Dark_Blue"))
        assertEquals(Team.Dark_Green, Team.fromCotName("Dark_Green"))
        // Case-insensitive
        assertEquals(Team.Cyan, Team.fromCotName("cyan"))
        assertEquals(Team.Red, Team.fromCotName("RED"))
    }

    @Test fun team_cot_name_output() {
        assertEquals("Dark Blue", Team.Dark_Blue.toCotName())
        assertEquals("Dark Green", Team.Dark_Green.toCotName())
        assertEquals("Cyan", Team.Cyan.toCotName())
    }

    @Test fun member_role_enum_int_to_name_round_trip() {
        val cases = mapOf(
            0 to "Unspecifed",
            1 to "TeamMember",
            2 to "TeamLead",
            6 to "ForwardObserver",
            8 to "K9",
        )
        for ((v, name) in cases) {
            val role = MemberRole.fromValue(v)
            assertEquals("MemberRole.fromValue($v).name", name, role.name)
            assertEquals("MemberRole.fromValue($v).value", v, role.value)
        }
    }

    @Test fun member_role_cot_name_round_trip() {
        assertEquals(MemberRole.TeamMember, MemberRole.fromCotName("Team Member"))
        assertEquals(MemberRole.TeamLead, MemberRole.fromCotName("Team Lead"))
        assertEquals(MemberRole.ForwardObserver, MemberRole.fromCotName("Forward Observer"))
        assertEquals(MemberRole.K9, MemberRole.fromCotName("K9"))
    }

    @Test fun member_role_cot_name_output() {
        assertEquals("Team Member", MemberRole.TeamMember.toCotName())
        assertEquals("Team Lead", MemberRole.TeamLead.toCotName())
        assertEquals("Forward Observer", MemberRole.ForwardObserver.toCotName())
    }

    // -------------------------------------------------------------------------
    // (f) Format detection: Phase-1 TAKMessage payload still decodes via fallback
    // -------------------------------------------------------------------------

    @Test fun phase1_takemessage_parses_via_fallback() {
        // Build a Phase-1 TAKMessage payload using AtakPluginSerializer (the
        // Phase-1 serializer). TakPacketParser.parse should return null,
        // letting the caller fall back to AtakPluginParser.parse.
        val event = CoTEvent(
            uid = "MESH-FALLBACK-01",
            type = "a-f-G-U-C",
            lat = 47.6,
            lon = -122.3,
            callsign = "FALLBACK",
        )
        val phase1Payload = AtakPluginSerializer.serialize(event)

        // TakPacketParser must return null for a TAKMessage payload
        val takResult = TakPacketParser.parse(phase1Payload)
        assertNull(
            "TakPacketParser should return null for Phase-1 TAKMessage payload (should fall back)",
            takResult,
        )

        // AtakPluginParser must still parse it
        val fallbackResult = AtakPluginParser.parse(phase1Payload)
        assertNotNull("AtakPluginParser should decode Phase-1 TAKMessage payload", fallbackResult)
        fallbackResult!!
        assertEquals("MESH-FALLBACK-01", fallbackResult.uid)
        assertEquals("a-f-G-U-C", fallbackResult.type)
        assertEquals(47.6, fallbackResult.lat, 1e-6)
    }

    // -------------------------------------------------------------------------
    // Additional: sfixed32 negative longitude round-trip
    // -------------------------------------------------------------------------

    @Test fun sfixed32_negative_longitude_round_trips() {
        // -122.3321 → latitude_i = -1223321000
        val lonI = (-122.3321 * 1e7).toLong().toInt()
        assertEquals(-1223321000, lonI)

        // Write as sfixed32 LE
        val out = java.io.ByteArrayOutputStream()
        out.write(lonI and 0xFF)
        out.write((lonI ushr 8) and 0xFF)
        out.write((lonI ushr 16) and 0xFF)
        out.write((lonI ushr 24) and 0xFF)
        val bytes = out.toByteArray()

        // Read back via MeshtasticProtoParser.readFixed32 + reinterpret as signed
        val (raw, _) = MeshtasticProtoParser.readFixed32(bytes, 0)!!
        val recovered = raw.toInt()
        assertEquals(-1223321000, recovered)
        val recoveredLon = recovered / 1e7
        assertEquals(-122.3321, recoveredLon, 1e-4)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun String.fromHex(): ByteArray {
        check(length % 2 == 0) { "Hex string must have even length" }
        return ByteArray(length / 2) { i ->
            substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
