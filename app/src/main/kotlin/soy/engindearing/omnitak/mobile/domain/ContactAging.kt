package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import soy.engindearing.omnitak.mobile.data.ContactMaxAge
import soy.engindearing.omnitak.mobile.data.ContactMaxAge.Decision

/**
 * #215: runs [ContactMaxAge] against the live [ContactStore].
 *
 * One collector decides, and everything that shows contacts reads that one decision: the map
 * drops the uids in [State.hiddenUids], and the Teams list shows the same uids in its
 * "Not heard from" section. Contacts that reach twice the max age are removed from the store here.
 *
 * It evaluates on three triggers: every change of the contact store (a new report makes its
 * contact fresh at once), every change of the setting, and a timer every [TICK_MS], so a contact
 * that goes quiet is hidden without anything arriving.
 *
 * [maxAgeMinutes] should not emit until the saved setting has been read: nothing is hidden or
 * removed before the first value, so a slow read of the preferences cannot apply a default the
 * operator did not choose. [selfUid] is read on each evaluation because the uid is minted later
 * than app start on a first run.
 */
class ContactAging(
    private val store: ContactStore,
    private val maxAgeMinutes: Flow<Int>,
    private val selfUid: () -> String = { "" },
    private val clock: () -> Long = System::currentTimeMillis,
    private val tickMs: Long = TICK_MS,
) {
    /** The decision both the map and the list read. Only a change of it is published. */
    data class State(
        /** The setting it was decided with, so a list can title its section with the same value. */
        val maxAgeMinutes: Int = ContactMaxAge.NEVER,
        /** Contacts to keep off the map and list as not heard from. Still in the store. */
        val hiddenUids: Set<String> = emptySet(),
    ) {
        fun isHidden(uid: String): Boolean = uid in hiddenUids
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _nowMs = MutableStateFlow(clock())

    /** The clock reading of the latest evaluation, for the age shown beside a hidden contact. */
    val nowMs: StateFlow<Long> = _nowMs.asStateFlow()

    /** Evaluate until cancelled. Launch it once on the application scope. */
    suspend fun run() {
        combine(store.contacts, maxAgeMinutes, ticks()) { _, max, _ -> max }
            .collect { max -> evaluate(max) }
    }

    private fun ticks(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(tickMs)
        }
    }

    private fun evaluate(maxAgeMinutes: Int) {
        val now = clock()
        val self = selfUid()
        store.removeIf { ContactMaxAge.decide(it, now, maxAgeMinutes, self) == Decision.REMOVE }
        val hidden = store.contacts.value.values
            .filter { ContactMaxAge.decide(it, now, maxAgeMinutes, self) == Decision.HIDDEN }
            .mapTo(HashSet()) { it.uid }
        _state.value = State(maxAgeMinutes, hidden)
        _nowMs.value = now
    }

    companion object {
        /** How often the rule is re-evaluated with nothing arriving. */
        const val TICK_MS = 30_000L
    }
}
