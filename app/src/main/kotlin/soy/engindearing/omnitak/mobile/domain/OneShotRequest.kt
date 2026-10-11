package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/**
 * A request one screen makes of another that must be acted on exactly once:
 * "open the Go to Coordinate sheet", "start a lasso", "enter toolbar edit
 * mode". The asker (the Tools launcher, Settings) and the screen that acts (the
 * map, the toolbar) are in different places, so the request has to wait for the
 * screen when it is not showing yet, and has to be gone once a screen has acted
 * on it.
 *
 * This replaces a counter that was only ever bumped. Each screen worked out for
 * itself whether the count was new, and got it wrong in opposite ways: the map
 * opened Go to Coordinate for any count above zero, so every new composition of
 * the map (back from another tab) saw the old count and opened the sheet again
 * (#286); the lasso compared the count with a note it seeded when the map was
 * built, so a lasso asked for from another tab, in the same frame as the switch
 * to the map, looked old and was lost. The count lasts as long as the process
 * and a screen's notes only as long as the screen; here the record of what has
 * been taken lives in the request itself, so it lasts as long as the request.
 *
 * Plain Kotlin and coroutines, no Android or Compose, so the rules are tested
 * on the JVM. Screens listen through `OnRequest` in ui/components.
 */
class OneShotRequest {

    /** [asked] counts the requests made, [taken] is that count when one was last taken. */
    private data class Tally(val asked: Long = 0, val taken: Long = 0)

    private val tally = MutableStateFlow(Tally())

    /** True while a request has been made that nobody has taken yet. */
    val pending: Boolean get() = tally.value.let { it.asked != it.taken }

    /**
     * Ask. Safe from any thread, and safe before anyone is listening: the
     * request waits. Asking again before it is taken is still one request.
     */
    fun request() {
        tally.update { it.copy(asked = it.asked + 1) }
    }

    /**
     * Take the pending request, if there is one. True for exactly one caller
     * per request, however many are listening, and false once it is taken.
     */
    fun consume(): Boolean {
        val before = tally.getAndUpdate { it.copy(taken = it.asked) }
        return before.asked != before.taken
    }

    /**
     * Calls [handle] once for each request, for as long as the caller stays
     * active (a screen's effect, which ends when the screen leaves). A request
     * made before the caller started is handled as soon as it starts. Never
     * returns; it ends when the caller is cancelled.
     */
    suspend fun handleRequests(handle: () -> Unit): Nothing {
        // The tally changes on every request and on every take, so this wakes
        // for both. A wake with nothing pending takes nothing and does nothing.
        tally.collect { if (consume()) handle() }
    }
}
