package soy.engindearing.omnitak.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.Drawing
import soy.engindearing.omnitak.mobile.data.DrawingKind

/**
 * Issue #76 — DrawingStore edit/delete used by the drawing edit sheet and the
 * move overlay.
 */
class DrawingStoreTest {

    private fun line(id: String) =
        Drawing(id = id, kind = DrawingKind.LINE, points = listOf(0.0 to 0.0, 1.0 to 1.0))

    @Test fun add_then_update_replaces_in_place() {
        val store = DrawingStore()
        store.add(line("d1"))
        store.update(line("d1").copy(name = "Alpha", colorHex = "#FF0000"))
        val list = store.drawings.value
        assertEquals(1, list.size)
        assertEquals("Alpha", list[0].name)
        assertEquals("#FF0000", list[0].colorHex)
    }

    @Test fun update_preserves_order() {
        val store = DrawingStore()
        store.add(line("a"))
        store.add(line("b"))
        store.add(line("c"))
        store.update(line("b").copy(name = "edited"))
        val ids = store.drawings.value.map { it.id }
        assertEquals(listOf("a", "b", "c"), ids)
        assertEquals("edited", store.drawings.value[1].name)
    }

    @Test fun update_unknown_id_is_noop() {
        val store = DrawingStore()
        store.add(line("a"))
        store.update(line("ghost").copy(name = "should-not-appear"))
        val list = store.drawings.value
        assertEquals(1, list.size)
        assertEquals("a", list[0].id)
        assertEquals("", list[0].name)
    }

    @Test fun remove_drops_only_matching_id() {
        val store = DrawingStore()
        store.add(line("a"))
        store.add(line("b"))
        store.remove("a")
        assertEquals(listOf("b"), store.drawings.value.map { it.id })
    }
}
