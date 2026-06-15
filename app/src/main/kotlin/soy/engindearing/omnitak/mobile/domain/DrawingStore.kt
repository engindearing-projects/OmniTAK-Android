package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import soy.engindearing.omnitak.mobile.data.Drawing

/**
 * In-memory roster of shapes the operator has drawn on the map. Kept
 * application-scoped so screens observe reactively; persistence to
 * DataStore is a follow-up once the format stabilizes.
 */
class DrawingStore {
    private val _drawings = MutableStateFlow<List<Drawing>>(emptyList())
    val drawings: StateFlow<List<Drawing>> = _drawings.asStateFlow()

    fun add(drawing: Drawing) {
        _drawings.value = _drawings.value + drawing
    }

    /**
     * Issue #76 — replace a drawing in place (rename / recolor / move).
     * Matched by [Drawing.id]; a no-op if the id isn't present so a stale
     * edit (drawing deleted underneath the open sheet) can't resurrect it.
     */
    fun update(drawing: Drawing) {
        _drawings.value = _drawings.value.map { if (it.id == drawing.id) drawing else it }
    }

    fun remove(id: String) {
        _drawings.value = _drawings.value.filterNot { it.id == id }
    }

    fun clear() {
        _drawings.value = emptyList()
    }
}
