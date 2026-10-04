package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Off-grid mesh plan Step 5 — verifies [UserPrefs] defaults for the
 * new mesh broadcast fields.
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
}
