package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Receipts for the two issues mata raised in the Discord DM at
 * 12:07 PM 2026-05-11:
 *
 *   1. In-app reporting interval, pushable from a portal config — the
 *      ATAK feature he flagged as "almost not functional in
 *      iTAK/TAKaware". Implemented as the `pliIntervalSecs` user pref
 *      with ATAK-side aliases `dispatchLocationCotInterval` and
 *      `locationReportingInterval`. Either name in a configBundle
 *      payload routes to the same OmniTAK field.
 *
 *   2. TAK_TRACKER role on Meshtastic duplicates the operator's own
 *      PPLI on the TAK map. Toggle is `hideSelfFromMeshContacts`
 *      (default true). MeshtasticCoTBridge consults it before
 *      publishing each mesh node.
 */
class MataAsksTest {

    @Test fun pli_interval_via_omnitak_native_key_clamps_to_5_to_600() {
        val accepted = PreferenceUriApplier.apply(
            UserPrefs(),
            listOf(DeepLinkAction.PreferenceEntry("pliIntervalSecs", "int", "60")),
        )
        assertEquals(60, accepted.prefs.pliIntervalSecs)

        val tooSmall = PreferenceUriApplier.apply(
            UserPrefs(),
            listOf(DeepLinkAction.PreferenceEntry("pliIntervalSecs", "int", "1")),
        )
        assertEquals("rejected as out of range", 30, tooSmall.prefs.pliIntervalSecs)
        assertEquals(listOf("pliIntervalSecs"), tooSmall.ignored)

        val tooBig = PreferenceUriApplier.apply(
            UserPrefs(),
            listOf(DeepLinkAction.PreferenceEntry("pliIntervalSecs", "int", "99999")),
        )
        assertEquals(30, tooBig.prefs.pliIntervalSecs)
        assertEquals(listOf("pliIntervalSecs"), tooBig.ignored)
    }

    @Test fun pli_interval_atak_aliases_route_to_omnitak_field() {
        val dispatchAlias = PreferenceUriApplier.apply(
            UserPrefs(),
            listOf(DeepLinkAction.PreferenceEntry("dispatchLocationCotInterval", "int", "120")),
        )
        assertEquals(120, dispatchAlias.prefs.pliIntervalSecs)

        val reportingAlias = PreferenceUriApplier.apply(
            UserPrefs(),
            listOf(DeepLinkAction.PreferenceEntry("locationReportingInterval", "int", "300")),
        )
        assertEquals(300, reportingAlias.prefs.pliIntervalSecs)
    }

    @Test fun hide_self_from_mesh_contacts_default_is_true() {
        assertTrue(UserPrefs().hideSelfFromMeshContacts)
    }

    @Test fun hide_self_can_be_disabled_via_preference_link() {
        val result = PreferenceUriApplier.apply(
            UserPrefs(),
            listOf(DeepLinkAction.PreferenceEntry("hideSelfFromMeshContacts", "boolean", "false")),
        )
        assertFalse(result.prefs.hideSelfFromMeshContacts)
    }
}
