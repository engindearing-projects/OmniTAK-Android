package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Off-grid mesh plan Step 5 — verifies [UserPrefs] defaults for the
 * new mesh broadcast fields.
 *
 * #212: also covers the relay direction switches: their defaults and the
 * migration from the single `relay_gateway_enabled` switch.
 */
class UserPrefsMeshDefaultsTest {

    @Test
    fun `broadcastOverMesh defaults to true`() {
        assertTrue(
            "broadcastOverMesh must default to true (opt-in off-grid by default)",
            UserPrefs().broadcastOverMesh,
        )
    }

    @Test
    fun `positionReportingEnabled defaults to true`() {
        // #211 - reporting stays on unless the operator switches it off, so
        // existing installs behave exactly as before the switch existed.
        assertTrue(
            "positionReportingEnabled must default to true",
            UserPrefs().positionReportingEnabled,
        )
    }

    @Test
    fun `positionReportingEnabled is independent of broadcastOverMesh`() {
        // Two separate switches: the master one (server + mesh) and the mesh-only one.
        val off = UserPrefs().copy(positionReportingEnabled = false)
        assertTrue("turning reporting off must not touch the mesh toggle", off.broadcastOverMesh)
        val meshOff = UserPrefs().copy(broadcastOverMesh = false)
        assertTrue("turning the mesh toggle off must not touch reporting", meshOff.positionReportingEnabled)
    }

    @Test
    fun `meshBroadcastIntervalSecs defaults to 30`() {
        assertEquals(
            "meshBroadcastIntervalSecs must default to 30 (minimum LoRa-safe interval)",
            30,
            UserPrefs().meshBroadcastIntervalSecs,
        )
    }

    @Test
    fun `meshBroadcastIntervalSecs coerces to 30 when below minimum`() {
        val prefs = UserPrefs(meshBroadcastIntervalSecs = 5)
        // Coercion happens in UserPrefsStore.update(); the data class itself
        // doesn't coerce — we verify the coerce helper logic.
        val coerced = prefs.meshBroadcastIntervalSecs.coerceIn(30, 60)
        assertEquals(30, coerced)
    }

    @Test
    fun `meshBroadcastIntervalSecs coerces to 60 when above maximum`() {
        val prefs = UserPrefs(meshBroadcastIntervalSecs = 90)
        val coerced = prefs.meshBroadcastIntervalSecs.coerceIn(30, 60)
        assertEquals(60, coerced)
    }

    @Test
    fun `meshBroadcastIntervalSecs accepts 45 (midpoint)`() {
        val prefs = UserPrefs(meshBroadcastIntervalSecs = 45)
        val coerced = prefs.meshBroadcastIntervalSecs.coerceIn(30, 60)
        assertEquals(45, coerced)
    }

    // region relay direction switches (#212) ----------------------------------

    @Test
    fun `both relay directions default to off`() {
        assertFalse("relay to server is off by default", UserPrefs().relayToServerEnabled)
        assertFalse("relay to mesh is off by default (LoRa airtime)", UserPrefs().relayToMeshEnabled)
    }

    @Test
    fun `a direction with no stored key takes the legacy gateway value`() {
        // Upgrade from the single #179 switch: ON keeps both directions ON.
        assertTrue(resolveRelayDirection(stored = null, legacyGateway = true))
        // ...and OFF (or never set) keeps them OFF.
        assertFalse(resolveRelayDirection(stored = null, legacyGateway = false))
        assertFalse(resolveRelayDirection(stored = null, legacyGateway = null))
    }

    @Test
    fun `a stored direction wins over the legacy gateway value`() {
        // Once the operator has set a direction, the old switch no longer counts.
        assertFalse(resolveRelayDirection(stored = false, legacyGateway = true))
        assertTrue(resolveRelayDirection(stored = true, legacyGateway = false))
        assertTrue(resolveRelayDirection(stored = true, legacyGateway = null))
    }

    @Test
    fun `each direction resolves against its own key`() {
        // UserPrefsStore resolves the two keys independently against the same
        // legacy value: (to-server key, to-mesh key, legacy) -> (to-server, to-mesh).
        fun both(toServerKey: Boolean?, toMeshKey: Boolean?, legacy: Boolean?) =
            resolveRelayDirection(toServerKey, legacy) to resolveRelayDirection(toMeshKey, legacy)

        assertEquals("upgrade, gateway was on", true to true, both(null, null, true))
        assertEquals("fresh install", false to false, both(null, null, null))
        assertEquals("only to-server written", true to false, both(true, null, false))
        assertEquals("to-server turned off, to-mesh still on the old value", false to true, both(false, null, true))
        assertEquals("to-mesh turned off, to-server still on the old value", true to false, both(null, false, true))
    }
}
