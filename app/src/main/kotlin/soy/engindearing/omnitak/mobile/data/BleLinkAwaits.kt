package soy.engindearing.omnitak.mobile.data

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Awaits for callback-style BLE requests, bound to the life of one link
 * (#203).
 *
 * The Nordic library drops queued requests without calling their done/fail
 * callbacks when a link goes down (`taskQueue.clear()` in its disconnect,
 * cancel and close paths). A coroutine suspended on one of those callbacks
 * would then wait forever, and whatever awaits it inline stops for good:
 * the auto-reconnect loop awaits the handshake write after every connect,
 * so one write dropped that way ended reconnecting until the app was
 * force-stopped.
 *
 * Every await made through this class ends when its request finishes or
 * when [close] is called, whichever comes first.
 */
internal class BleLinkAwaits {

    private val lock = Any()
    private var closed = false
    private val pending = LinkedHashSet<Waiter<*>>()

    private inner class Waiter<T>(
        private val cont: CancellableContinuation<T>,
        private val onClosed: T,
    ) {
        private val done = AtomicBoolean(false)

        fun finish(value: T) {
            if (!done.compareAndSet(false, true)) return
            synchronized(lock) { pending.remove(this) }
            cont.resume(value)
        }

        fun abort() = finish(onClosed)

        fun forget() {
            done.set(true)
            synchronized(lock) { pending.remove(this) }
        }
    }

    val isClosed: Boolean get() = synchronized(lock) { closed }

    /** Awaits still waiting on a callback. */
    val pendingCount: Int get() = synchronized(lock) { pending.size }

    /**
     * Runs [start] with a completion function and suspends until that
     * function is called, or until [close], which resumes with [onClosed].
     * Calls after the first completion are ignored. Once closed, [start] is
     * not run at all and [onClosed] comes straight back.
     */
    suspend fun <T> await(onClosed: T, start: (complete: (T) -> Unit) -> Unit): T =
        suspendCancellableCoroutine { cont ->
            val waiter = Waiter(cont, onClosed)
            val accepted = synchronized(lock) {
                if (closed) false else pending.add(waiter)
            }
            if (!accepted) {
                waiter.abort()
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation { waiter.forget() }
            try {
                start(waiter::finish)
            } catch (t: Throwable) {
                waiter.forget()
                throw t
            }
        }

    /** Ends every pending await with its `onClosed` value and refuses new ones. */
    fun close() {
        val toAbort = synchronized(lock) {
            if (closed) return
            closed = true
            pending.toList()
        }
        toAbort.forEach { it.abort() }
    }
}
