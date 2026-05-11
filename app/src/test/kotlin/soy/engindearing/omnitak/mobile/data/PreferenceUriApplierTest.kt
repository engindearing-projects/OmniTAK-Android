package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceUriApplierTest {

    @Test fun applies_callsign_and_returns_key_in_applied_list() {
        val result = PreferenceUriApplier.apply(
            UserPrefs(),
            listOf(DeepLinkAction.PreferenceEntry("callsign", "string", "ENGIE-1")),
        )
        assertEquals("ENGIE-1", result.prefs.callsign)
        assertEquals(listOf("callsign"), result.applied)
        assertTrue(result.ignored.isEmpty())
    }

    @Test fun team_is_validated_against_canonical_14_colors() {
        val ok = PreferenceUriApplier.apply(
            UserPrefs(),
            listOf(DeepLinkAction.PreferenceEntry("team", "string", "cyan")),
        )
        assertEquals("CYAN", ok.prefs.team)

        val bad = PreferenceUriApplier.apply(
            UserPrefs(),
            listOf(DeepLinkAction.PreferenceEntry("team", "string", "pink")),
        )
        assertEquals("CYAN", bad.prefs.team) // unchanged from default
        assertEquals(listOf("team"), bad.ignored)
    }

    @Test fun coordFormat_accepts_MGRS() {
        val result = PreferenceUriApplier.apply(
            UserPrefs(),
            listOf(DeepLinkAction.PreferenceEntry("coordFormat", "string", "MGRS")),
        )
        assertEquals(CoordFormat.MGRS, result.prefs.coordFormat)
    }

    @Test fun boolean_coercion_supports_common_truthy_values() {
        val result = PreferenceUriApplier.apply(
            UserPrefs(callsignCardVisible = false, gridEnabled = false),
            listOf(
                DeepLinkAction.PreferenceEntry("callsignCardVisible", "boolean", "true"),
                DeepLinkAction.PreferenceEntry("gridEnabled", "boolean", "1"),
            ),
        )
        assertTrue(result.prefs.callsignCardVisible)
        assertTrue(result.prefs.gridEnabled)
    }

    @Test fun unknown_keys_are_dropped_into_ignored_list() {
        val result = PreferenceUriApplier.apply(
            UserPrefs(),
            listOf(DeepLinkAction.PreferenceEntry("displayRed", "boolean", "true")),
        )
        assertEquals(emptyList<String>(), result.applied)
        assertEquals(listOf("displayRed"), result.ignored)
    }

    @Test fun atak_aliases_route_to_omnitak_fields() {
        val result = PreferenceUriApplier.apply(
            UserPrefs(),
            listOf(
                DeepLinkAction.PreferenceEntry("locationCallsign", "string", "ATAK-1"),
                DeepLinkAction.PreferenceEntry("locationTeam", "string", "GREEN"),
                DeepLinkAction.PreferenceEntry("coord_display_format", "string", "MGRS"),
            ),
        )
        assertEquals("ATAK-1", result.prefs.callsign)
        assertEquals("GREEN", result.prefs.team)
        assertEquals(CoordFormat.MGRS, result.prefs.coordFormat)
    }
}
