package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The map's location gate. It read the permission once when the map was composed and only changed
 * on the answer to its own prompt, so a permission granted in the system settings (the app in the
 * background, then brought back) went unnoticed: the map kept saying "Acquiring fix" and Center on
 * me stayed disabled until a tab switch built the screen again. The gate now reads the permission
 * again every time the app comes back to the foreground.
 */
class LocationPermissionRulesTest {

    private fun after(current: Boolean, event: LocationPermissionRules.Event) = LocationPermissionRules.next(current, event)

    // MARK: fine or coarse

    @Test fun either_permission_is_enough() {
        assertTrue(LocationPermissionRules.granted(fine = true, coarse = false))
        assertTrue(LocationPermissionRules.granted(fine = false, coarse = true))
        assertTrue(LocationPermissionRules.granted(fine = true, coarse = true))
    }

    @Test fun neither_permission_is_not_enough() {
        assertFalse(LocationPermissionRules.granted(fine = false, coarse = false))
    }

    // MARK: coming back to the foreground

    @Test fun a_permission_granted_in_the_system_settings_is_noticed_on_return() {
        assertTrue(after(false, LocationPermissionRules.Event.Resumed(grantedNow = true)))
    }

    @Test fun a_permission_still_not_granted_stays_not_granted_on_return() {
        assertFalse(after(false, LocationPermissionRules.Event.Resumed(grantedNow = false)))
    }

    @Test fun a_permission_taken_away_is_noticed_on_return_too() {
        // The system ends the process when a permission is revoked, but a gate must not keep saying
        // "granted" for as long as it lives if it is asked again.
        assertFalse(after(true, LocationPermissionRules.Event.Resumed(grantedNow = false)))
    }

    @Test fun a_permission_that_is_still_granted_stays_granted_on_return() {
        assertTrue(after(true, LocationPermissionRules.Event.Resumed(grantedNow = true)))
    }

    // MARK: the gate's own prompt

    @Test fun granting_the_prompt_grants() {
        assertTrue(after(false, LocationPermissionRules.Event.PromptAnswered(granted = true)))
    }

    @Test fun refusing_the_prompt_does_not_grant() {
        assertFalse(after(false, LocationPermissionRules.Event.PromptAnswered(granted = false)))
    }

    @Test fun refusing_a_prompt_does_not_take_back_a_permission_already_there() {
        assertTrue(after(true, LocationPermissionRules.Event.PromptAnswered(granted = false)))
    }

    @Test fun every_combination_ends_with_the_latest_news() {
        for (current in listOf(false, true)) {
            for (now in listOf(false, true)) {
                assertEquals("resume $now on $current", now, after(current, LocationPermissionRules.Event.Resumed(now)))
            }
        }
    }

    // MARK: the gate is wired to it

    private val gate: String by lazy {
        listOf(
            "src/main/kotlin/soy/engindearing/omnitak/mobile/ui/components/LocationPermissionGate.kt",
            "app/src/main/kotlin/soy/engindearing/omnitak/mobile/ui/components/LocationPermissionGate.kt",
        ).map(::File).first { it.exists() }.readText()
    }

    @Test fun the_gate_reads_the_permission_again_on_every_resume() {
        assertTrue(gate.contains("Lifecycle.Event.ON_RESUME"))
        assertTrue(gate.contains("LocationPermissionRules.Event.Resumed(hasLocationPermission(context))"))
        assertTrue("the observer is removed when the gate leaves", gate.contains("removeObserver(observer)"))
    }
}
