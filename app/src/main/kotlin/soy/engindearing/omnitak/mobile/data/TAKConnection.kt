package soy.engindearing.omnitak.mobile.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import soy.engindearing.omnitak.mobile.data.net.TakTls
import soy.engindearing.omnitak.mobile.domain.ConnectionState
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import kotlin.coroutines.coroutineContext

/**
 * One TAK server connection. Pure JVM networking — no JNI/Rust needed.
 *
 * Lifecycle:
 *   Disconnected → Connecting → Connected → (read loop) → Disconnected
 *                             ↳ Failed (timeout / socket error / no answer / failed send)
 *
 * Mirrors the iOS DirectTCPSender behavior shipped in the 2.11.0 batch:
 *   - 15s connect timeout so "Connecting…" never sticks forever (iOS #40)
 *   - Single-shot state transitions guarded by coroutine cancellation
 *
 * Liveness (#233). A socket whose path is gone without a FIN or RST (Wi-Fi to
 * LTE swap, NAT timeout, server host gone) looks connected to the OS for many
 * minutes: reads block and small writes are accepted into the send buffer. So
 * while connected this class sends the ping every TAK client uses (a CoT of
 * type `t-x-c-t`, which servers answer with `t-x-c-t-r`; OpenTAKServer sends
 * the ping itself back, which counts as an answer too):
 *   - Until the server has answered one, a ping goes out every
 *     [Timing.pingIdleMs], busy stream or not, to learn whether it answers.
 *   - After that, a ping goes out only when nothing has arrived for
 *     [Timing.pingIdleMs]. If nothing at all arrives for [Timing.pongWaitMs]
 *     after such a ping, the connection is reported [ConnectionState.Failed].
 *   - A server that has never answered a ping is not dropped for being quiet:
 *     a plain CoT listener that ignores pings would otherwise be reconnected
 *     in a loop.
 *   - The first ping waits [Timing.pingIdleMs] after the socket opens, so the
 *     app's own first event (its position report) reaches the server first. A
 *     server that takes a client's identity from the first event it sees must
 *     not get the ping for that.
 * A link is only given up on after a ping went unanswered, never for idle time
 * alone: a process that was stalled for a while finds a long silence on its
 * clock, and the server was never asked.
 * A failed write also ends the connection instead of leaving it Connected.
 * The reconnect supervisor in `ServerManager` re-dials on Failed and Disconnected.
 *
 * Each connect is one session with its own number. A session that has ended, or
 * has been replaced, can no longer change [state] or the socket of a newer one.
 */
class TAKConnection(
    private val server: TAKServer,
    private val certVault: CertVault? = null,
    private val timing: Timing = Timing(),
    /** UID for the ping CoT. ATAK uses its own UID with a "-ping" suffix. */
    private val pingUid: suspend () -> String = { DEFAULT_PING_UID },
    private val nanoTime: () -> Long = System::nanoTime,
) {

    /**
     * Timeouts. The liveness windows count time on the clock `delay` uses, so a
     * phone that slept is not charged for it.
     */
    data class Timing(
        val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
        /** Ask the server when nothing has arrived for this long. Also the wait before a connection's first ping. */
        val pingIdleMs: Long = 15_000L,
        /** How long a server that answers pings gets to send anything after one. */
        val pongWaitMs: Long = 25_000L,
        /** How often the watchdog looks. */
        val tickMs: Long = 5_000L,
    )

    private class Session(val number: Int, val sock: Socket)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Guards [sessionNumber], [connectJob], [dialing], [session] and every write to [_state]. */
    private val lock = Any()
    private var sessionNumber = 0
    private var connectJob: Job? = null
    /** The socket of a dial in flight, so [disconnect] can abort a blocked connect or handshake. */
    private var dialing: Session? = null
    @Volatile private var session: Session? = null

    /** One writer at a time: PPLI, chat and pings share the stream. */
    private val writeLock = Any()

    @Volatile private var lastRxNanos = 0L
    @Volatile private var lastPingNanos = 0L
    /**
     * When the first ping since the last received byte went out, or null when
     * no ping is waiting for an answer. The server is given up on from here.
     */
    @Volatile private var askedNanos: Long? = null
    private val pingInFlight = AtomicBoolean(false)
    /** The UID of the last ping sent, to recognise a server that answers by sending it back. */
    @Volatile private var sentPingUid: String? = null

    /**
     * True once this server has answered a ping. Kept across reconnects: it is
     * a fact about the server, and it is what allows a silent link to be dropped.
     */
    @Volatile var answersPings: Boolean = false
        private set

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    // Raw CoT-XML stream from the server. Parsing into CoT events
    // arrives in a later slice; for now callers can log/inspect.
    private val _received = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val received: SharedFlow<String> = _received.asSharedFlow()

    /** Milliseconds since the last byte arrived, or null when not connected. */
    val idleMs: Long?
        get() = if (session != null) (nanoTime() - lastRxNanos) / NANOS_PER_MS else null

    /** The live socket. For tests that need to break it from the outside. */
    internal val sessionSocket: Socket? get() = session?.sock

    /**
     * Dial. A no-op while a dial is in flight or the connection is up. The
     * state is Connecting by the time this returns, unless it was a no-op.
     */
    fun connect() {
        synchronized(lock) {
            when (_state.value) {
                is ConnectionState.Connecting, is ConnectionState.Connected -> return
                else -> Unit
            }
            val number = ++sessionNumber
            val previous = connectJob
            _state.value = ConnectionState.Connecting(server.name)
            connectJob = scope.launch {
                if (previous != null) {
                    // The session before this one may still be unwinding: its read loop
                    // returns a moment after the state leaves Connected, and the
                    // supervisor re-dials the instant it sees that. Give it a moment so
                    // two sessions do not overlap, but do not let a socket that is slow
                    // to close hold this dial up. A retired session cannot touch the
                    // state or this session's socket either way.
                    previous.cancel()
                    withTimeoutOrNull(PREVIOUS_SESSION_WAIT_MS) { previous.join() }
                }
                try {
                    runSession(number)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    // Nothing may escape: an uncaught exception in this scope takes the app down.
                    Log.w(TAG, "Session ended on ${t.javaClass.simpleName}: ${t.message}")
                    abandon(number, ConnectionState.Failed(t.message ?: t.javaClass.simpleName))
                }
            }
        }
    }

    fun disconnect() {
        val toClose = ArrayList<Socket>(2)
        synchronized(lock) {
            sessionNumber++ // whatever is running may no longer change the state
            connectJob?.cancel()
            dialing?.let { toClose += it.sock }
            dialing = null
            session?.let { toClose += it.sock }
            session = null
            _state.value = ConnectionState.Disconnected
        }
        toClose.forEach(::closeLater)
    }

    /**
     * Fire-and-forget CoT XML send. Returns false if no socket is open
     * or if the write fails. Suspends on [Dispatchers.IO] so a UI-thread
     * caller (eg. ChatScreen's send button) doesn't trip
     * NetworkOnMainThreadException.
     *
     * A write that fails ends the connection ([ConnectionState.Failed]), so the
     * supervisor re-dials instead of every later send failing on a dead socket.
     */
    suspend fun send(xml: String): Boolean {
        val current = session ?: return false
        if (_state.value !is ConnectionState.Connected) return false
        return write(current, xml)
    }

    private suspend fun runSession(number: Int) {
        val sock = try {
            withTimeout(timing.connectTimeoutMs) {
                withContext(Dispatchers.IO) {
                    if (server.useTLS) openTlsSocket(number) else openPlainSocket(number)
                }
            }
        } catch (_: TimeoutCancellationException) {
            Log.w(TAG, "Connect timed out after ${timing.connectTimeoutMs / 1000}s")
            abandon(number, ConnectionState.Failed("Connection timed out"))
            return
        } catch (t: Throwable) {
            Log.w(TAG, "Connect failed: ${t.javaClass.simpleName}: ${t.message}")
            abandon(number, ConnectionState.Failed(t.message ?: t.javaClass.simpleName))
            return
        }

        val current = Session(number, sock)
        val live = synchronized(lock) {
            if (dialing?.sock === sock) dialing = null
            if (number != sessionNumber) {
                false // disconnect() ran while the socket was opening
            } else {
                val now = nanoTime()
                lastRxNanos = now
                lastPingNanos = now // the first ping waits its turn behind the app's own first event
                askedNanos = null
                session = current
                _state.value = ConnectionState.Connected(server.name, server.useTLS)
                true
            }
        }
        if (!live) {
            closeLater(sock)
            return
        }
        Log.i(TAG, "Connected to ${server.host}:${server.port} (tls=${server.useTLS})")

        coroutineScope {
            val watchdog = launch { watch(current) }
            try {
                readLoop(current)
            } finally {
                watchdog.cancel()
            }
        }
        // The stream ended on its own: the server closed it, or the socket errored.
        end(current, ConnectionState.Disconnected)
    }

    private fun openPlainSocket(number: Int): Socket {
        val s = Socket()
        registerDial(number, s)
        s.tcpNoDelay = true
        s.keepAlive = true
        s.soTimeout = READ_TIMEOUT_MS
        s.connect(InetSocketAddress(server.host, server.port), timing.connectTimeoutMs.toInt())
        return s
    }

    /**
     * Mutual-TLS when the operator imported a `.p12` for this server, plain
     * TLS otherwise. TAK servers default to mTLS — without a client cert the
     * server closes the connection at handshake (PEER_DID_NOT_RETURN_A_CERTIFICATE).
     *
     * Server-side trust is resolved by [TakTls] — the same policy the Marti
     * REST plane uses (#38 / GAP-106):
     *   1. CA pin written during Quick Connect enrollment (preferred).
     *   2. System trust store + hostname verification — legacy `.p12`
     *      imports without a pin and publicly-trusted server certs.
     *   3. Trust-all ONLY when the operator explicitly set
     *      [TAKServer.allowUntrustedTls].
     */
    private fun openTlsSocket(number: Int): Socket {
        val keyManagers = TakTls.clientKeyManagers(
            server.certificateName, server.certificatePassword, certVault,
        )
        if (keyManagers == null) {
            Log.w(TAG, "⚠ TLS without client cert — server may reject. Import a .p12 if mTLS is required.")
        } else {
            Log.i(TAG, "TLS with client cert ${server.certificateName} (mTLS)")
        }
        val trust = TakTls.serverTrust(server, certVault)
        val ctx = TakTls.sslContext(keyManagers, trust.trustManager)
        val s = ctx.socketFactory.createSocket() as SSLSocket
        registerDial(number, s)
        s.tcpNoDelay = true
        s.keepAlive = true
        if (trust.verifiesHostname) {
            // System-trust path: verify the cert's SAN/CN against the host
            // we dialed, matching the REST plane's default verifier. The
            // pinned path skips this — the per-server pin IS the identity
            // (TAK servers are routinely dialed by IP / internal names).
            val params = s.sslParameters
            params.endpointIdentificationAlgorithm = "HTTPS"
            s.sslParameters = params
        }
        val timeoutMs = timing.connectTimeoutMs.toInt()
        s.connect(InetSocketAddress(server.host, server.port), timeoutMs)
        // Bound the handshake as well. With no read timeout, a server that accepts
        // the TCP connection and then says nothing leaves this on "Connecting":
        // cancelling the coroutine does not interrupt a blocked socket read.
        s.soTimeout = timeoutMs
        s.startHandshake()
        s.soTimeout = READ_TIMEOUT_MS
        return s
    }

    /** Record the socket of a dial before it blocks, so [disconnect] can close it. */
    private fun registerDial(number: Int, s: Socket) {
        synchronized(lock) {
            if (number != sessionNumber) {
                closeLater(s)
                throw SocketException("connect abandoned")
            }
            dialing = Session(number, s)
        }
    }

    private suspend fun readLoop(current: Session) {
        val buffer = StringBuilder()
        val chunk = CharArray(READ_CHUNK_CHARS)
        try {
            val reader = current.sock.getInputStream().bufferedReader(Charsets.UTF_8)
            while (coroutineContext.isActive) {
                val n = reader.read(chunk)
                if (n == -1) break
                lastRxNanos = nanoTime()
                for (i in 0 until n) {
                    val c = chunk[i]
                    buffer.append(c)
                    if ((c == '>' && buffer.endsWith(EVENT_CLOSE_TAG)) || buffer.length > 64 * 1024) {
                        deliver(buffer.toString())
                        buffer.clear()
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Read loop ended: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Pass a frame on, unless it is part of the ping exchange. */
    private fun deliver(xml: String) {
        when (eventType(xml)) {
            PONG_TYPE -> answersPings = true
            PING_TYPE -> {
                // Our own ping coming back is an answer (OpenTAKServer does this).
                // Another client's ping, relayed by a server that answers none, proves nothing.
                val ours = sentPingUid
                if (ours != null && eventUid(xml) == CotXml.escape(ours)) answersPings = true
            }
            else -> _received.tryEmit(xml)
        }
    }

    /**
     * Ping an idle server and give up on one that stops answering. This loop
     * never waits for a write: a write that blocks on a dead path must not stop
     * the check that would catch it.
     */
    private suspend fun watch(current: Session) {
        var suspected = false
        while (coroutineContext.isActive) {
            delay(timing.tickMs)
            val now = nanoTime()
            val lastRx = lastRxNanos
            val asked = askedNanos
            // A ping is waiting for an answer when nothing has arrived since it went out.
            val waiting = asked != null && lastRx - asked < 0

            if (answersPings && waiting && (now - asked!!) / NANOS_PER_MS >= timing.pongWaitMs) {
                // Look once more on the next tick before giving up. If this process was
                // stalled, the answer may already be in the socket buffer, unread.
                if (suspected) {
                    fail(current, "No response from server")
                    return
                }
                suspected = true
                continue
            }
            suspected = false

            val sincePing = (now - lastPingNanos) / NANOS_PER_MS
            val idle = (now - lastRx) / NANOS_PER_MS
            val due = sincePing >= timing.pingIdleMs && (!answersPings || idle >= timing.pingIdleMs)
            if (due) {
                lastPingNanos = now
                if (!waiting) askedNanos = now
                if (pingInFlight.compareAndSet(false, true)) {
                    scope.launch {
                        try {
                            ping(current)
                        } finally {
                            pingInFlight.set(false)
                        }
                    }
                }
            }
        }
    }

    private suspend fun ping(current: Session): Boolean {
        val uid = try {
            pingUid()
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            DEFAULT_PING_UID
        }
        sentPingUid = uid
        return write(current, pingXml(uid, System.currentTimeMillis()))
    }

    private suspend fun write(current: Session, xml: String): Boolean = withContext(Dispatchers.IO) {
        try {
            synchronized(writeLock) {
                val out = current.sock.getOutputStream()
                out.write(xml.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "send failed: ${t.javaClass.simpleName}: ${t.message}")
            fail(current, "Send failed: ${t.message ?: t.javaClass.simpleName}")
            false
        }
    }

    /** End [current] as failed. Does nothing if it already ended or was replaced. */
    private fun fail(current: Session, reason: String) {
        synchronized(lock) {
            if (current.number != sessionNumber) return
            sessionNumber++ // its read loop is about to return; it may not touch the state
            session = null
            _state.value = ConnectionState.Failed(reason)
        }
        Log.w(TAG, "Connection to ${server.host}:${server.port} dropped: $reason")
        closeLater(current.sock)
    }

    /** The read loop of [current] returned. */
    private fun end(current: Session, newState: ConnectionState) {
        synchronized(lock) {
            if (current.number == sessionNumber) {
                sessionNumber++
                session = null
                _state.value = newState
            }
        }
        closeLater(current.sock)
    }

    /** Session [number] is over without a live socket: a dial that failed, or an error nobody expected. */
    private fun abandon(number: Int, newState: ConnectionState) {
        val toClose = ArrayList<Socket>(2)
        synchronized(lock) {
            dialing?.takeIf { it.number == number }?.let {
                toClose += it.sock
                dialing = null
            }
            session?.takeIf { it.number == number }?.let {
                toClose += it.sock
                session = null
            }
            if (number == sessionNumber) {
                sessionNumber++
                _state.value = newState
            }
        }
        toClose.forEach(::closeLater)
    }

    /**
     * Close off the caller's thread. Closing a TLS socket writes a close_notify,
     * and on a dead path with a full send buffer that write can block for as
     * long as the kernel takes to give up. Nothing here may wait for that.
     */
    private fun closeLater(s: Socket) {
        scope.launch { runCatching { s.close() } }
    }

    companion object {
        private const val TAG = "TAKConnection"
        const val CONNECT_TIMEOUT_MS = 15_000L
        private const val READ_TIMEOUT_MS = 0 // 0 = no read timeout; the watchdog decides when a stream is dead
        private const val READ_CHUNK_CHARS = 4096
        private const val EVENT_CLOSE_TAG = "</event>"
        private const val NANOS_PER_MS = 1_000_000L
        private const val PING_STALE_MS = 10_000L
        private const val PREVIOUS_SESSION_WAIT_MS = 2_000L
        const val PING_TYPE = "t-x-c-t"
        const val PONG_TYPE = "t-x-c-t-r"
        const val DEFAULT_PING_UID = "OmniTAK-ping"

        private val EVENT_TYPE = Regex("""<event\b[^>]*?\btype\s*=\s*["']([^"']*)["']""")
        private val EVENT_UID = Regex("""<event\b[^>]*?\buid\s*=\s*["']([^"']*)["']""")

        /** The `type` attribute of a frame's `<event>` start tag, or null. */
        internal fun eventType(xml: String): String? = EVENT_TYPE.find(xml)?.groupValues?.get(1)

        /** The `uid` attribute of a frame's `<event>` start tag, as written (still escaped), or null. */
        internal fun eventUid(xml: String): String? = EVENT_UID.find(xml)?.groupValues?.get(1)

        /** The ping every TAK client sends: type `t-x-c-t`, answered with `t-x-c-t-r`. */
        internal fun pingXml(uid: String, nowMs: Long): String {
            val time = CotXml.isoMillis(nowMs)
            val stale = CotXml.isoMillis(nowMs + PING_STALE_MS)
            return "<event version=\"2.0\" uid=\"${CotXml.escape(uid)}\" type=\"$PING_TYPE\" how=\"m-g\" " +
                "time=\"$time\" start=\"$time\" stale=\"$stale\">" +
                "<point lat=\"0.0\" lon=\"0.0\" hae=\"0.0\" ce=\"9999999.0\" le=\"9999999.0\"/>" +
                "<detail/></event>"
        }
    }
}
