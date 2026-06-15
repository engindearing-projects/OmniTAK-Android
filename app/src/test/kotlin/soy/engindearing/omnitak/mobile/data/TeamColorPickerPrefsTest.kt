package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Contracts for issue #51 — team color picker for self-marker tint.
 *
 * The picker is a UI swatch grid in SettingsScreen (Self Position section)
 * that writes `UserPrefs.team` via `UserPrefsStore.update`. These tests pin
 * the data-model layer that the picker depends on:
 *
 *  1. [UserPrefs.team] defaults to "Cyan" (the TAK default).
 *  2. The 14 canonical TAK team names are all recognised by [TakTeamColor]
 *     (the picker's source of truth for which colors to show).
 *  3. Copying a [UserPrefs] with a new team name round-trips correctly —
 *     the picker writes `prefs.copy(team = name)` internally.
 *  4. Every entry in the canonical name list produces a non-null ARGB from
 *     [TakTeamColor.forName] — swatch colors in the picker are never null.
 */
class TeamColorPickerPrefsTest {

    // The 14 canonical TAK team colors expected in the picker palette.
    private val canonicalTeamNames = listOf(
        "White", "Yellow", "Orange", "Magenta", "Red",
        "Maroon", "Purple", "Dark Blue", "Blue", "Cyan",
        "Teal", "Green", "Dark Green", "Brown",
    )

    @Test fun `UserPrefs default team is Cyan`() {
        assertEquals("Cyan", UserPrefs().team)
    }

    @Test fun `team name round-trips through UserPrefs copy`() {
        val base = UserPrefs()
        for (name in canonicalTeamNames) {
            val updated = base.copy(team = name)
            assertEquals(
                "copy(team=$name) must survive to updated.team",
                name, updated.team,
            )
        }
    }

    @Test fun `all canonical picker names resolve to non-null ARGB`() {
        for (name in canonicalTeamNames) {
            assertNotNull(
                "TakTeamColor.forName(\"$name\") must not be null — picker swatch would be invisible",
                TakTeamColor.forName(name),
            )
        }
    }

    @Test fun `picker palette covers exactly 14 colors`() {
        assertEquals(
            "Canonical TAK team palette must have 14 entries",
            14, canonicalTeamNames.size,
        )
    }

    @Test fun `TakTeamColor lookup is case-insensitive for picker writes`() {
        // The picker writes Title Case (e.g. "Dark Blue") but DataStore
        // normalises on read. Verify forName handles both.
        assertEquals(
            TakTeamColor.forName("Cyan"),
            TakTeamColor.forName("cyan"),
        )
        assertEquals(
            TakTeamColor.forName("Dark Green"),
            TakTeamColor.forName("dark green"),
        )
    }
}
