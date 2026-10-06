package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.ScreenRotation

/**
 * #214 - the Rotate shortcut on the bottom bar. It is a catalog command an operator adds from
 * the + palette. It is deliberately not in the default layout (the bar holds six and nobody's
 * bar changes under them), and a bar an operator already customised never gains it by itself.
 */
class RotateShortcutTest {

    private val rotate get() = ToolbarCatalog.item(ToolbarCatalog.ROTATION_ID)

    @Test fun the_catalog_has_a_rotate_shortcut_and_it_is_a_command() {
        val item = rotate
        assertNotNull(item)
        assertEquals("rotation", item!!.id)
        assertEquals("Rotate", item.label)
        val kind = item.kind
        assertTrue(kind is BarKind.Command)
        assertEquals(BarCommand.ROTATION, (kind as BarKind.Command).command)
    }

    @Test fun it_is_in_the_commands_so_the_add_palette_lists_it_under_tools() {
        assertTrue(ToolbarCatalog.commands.any { it.id == ToolbarCatalog.ROTATION_ID })
        assertTrue(ToolbarCatalog.all.any { it.id == ToolbarCatalog.ROTATION_ID })
        // The palette offers every catalog item that is not already on the bar.
        val onTheDefaultBar = ToolbarCatalog.defaultIds.toSet()
        assertTrue(ToolbarCatalog.all.filter { it.id !in onTheDefaultBar }.any { it.id == ToolbarCatalog.ROTATION_ID })
    }

    @Test fun the_default_bar_is_unchanged() {
        assertEquals(
            listOf("map", "chat", "servers", "mesh", "tools", "settings"),
            ToolbarCatalog.defaultIds,
        )
        assertFalse(ToolbarCatalog.ROTATION_ID in ToolbarCatalog.defaultIds)
        assertEquals(ToolbarCatalog.MAX_ITEMS, ToolbarCatalog.defaultIds.size)
    }

    @Test fun a_bar_an_operator_already_customised_does_not_gain_it() {
        val saved = listOf("map", "chat", "settings")
        assertEquals(saved, ToolbarCatalog.resolve(saved).map { it.id })
        // Nor does the fallback bar used when nothing is saved.
        assertNull(ToolbarCatalog.resolve(emptyList()).firstOrNull { it.id == ToolbarCatalog.ROTATION_ID })
    }

    @Test fun a_bar_that_has_it_shows_it() {
        val saved = listOf("map", "rotation", "settings")
        assertEquals(saved, ToolbarCatalog.resolve(saved).map { it.id })
    }

    // --- the bar draws the mode the screen is in ----------------------------

    @Test fun the_icon_is_different_for_each_mode() {
        val icons = ScreenRotation.entries.map { ToolbarCatalog.rotationIcon(it) }
        assertNotSame(icons[0], icons[1])
        assertNotSame(icons[1], icons[2])
        assertNotSame(icons[0], icons[2])
    }

    @Test fun the_shortcut_is_drawn_with_the_icon_of_the_current_mode() {
        for (mode in ScreenRotation.entries) {
            val shown = ToolbarCatalog.showing(rotate!!, mode)
            assertSame("icon for $mode", ToolbarCatalog.rotationIcon(mode), shown.icon)
        }
    }

    @Test fun the_accessibility_label_names_the_current_mode() {
        assertEquals("Screen rotation: Auto", ToolbarCatalog.showing(rotate!!, ScreenRotation.AUTO).contentDescription)
        assertEquals("Screen rotation: Portrait", ToolbarCatalog.showing(rotate!!, ScreenRotation.PORTRAIT).contentDescription)
        assertEquals("Screen rotation: Landscape", ToolbarCatalog.showing(rotate!!, ScreenRotation.LANDSCAPE).contentDescription)
    }

    @Test fun the_visible_label_stays_short() {
        for (mode in ScreenRotation.entries) assertEquals("Rotate", ToolbarCatalog.showing(rotate!!, mode).label)
    }

    @Test fun every_other_item_is_drawn_exactly_as_it_is() {
        for (item in ToolbarCatalog.all.filter { it.id != ToolbarCatalog.ROTATION_ID }) {
            for (mode in ScreenRotation.entries) {
                assertSame("${item.id} under $mode", item, ToolbarCatalog.showing(item, mode))
            }
            assertEquals("${item.id} is read out as its label", item.label, item.contentDescription)
        }
    }
}
