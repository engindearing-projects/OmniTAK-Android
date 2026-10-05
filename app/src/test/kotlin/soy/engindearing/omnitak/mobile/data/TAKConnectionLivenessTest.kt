package soy.engindearing.omnitak.mobile.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import soy.engindearing.omnitak.mobile.domain.ConnectionState
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * #233: a TAK server connection that is dead without the OS knowing must be
 * noticed and reported, and one that is only quiet must be left alone.
 *
 * These run against real sockets on the loopback interface with short windows.
 * Waits are long (they cost nothing when a test passes) and windows that a slow
 * machine could overrun are wide.
 */
class TAKConnectionLivenessTest {

    private val closeables = ArrayList<AutoCloseable>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @After
    fun tearDown() {
        scope.cancel()
        closeables.forEach { runCatching { it.close() } }
    }

    /** How the stand-in server reacts to a ping. */
    private enum class Answer { PONG, ECHO, NONE }

    /** Speaks just enough CoT: counts pings, answers them if asked to, records everything else. */
    private class FakeTakServer(private val answer: Answer) : AutoCloseable {
        private val listener = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val clients = CopyOnWriteArrayList<Socket>()
        val port: Int get() = listener.localPort
        val accepted = AtomicInteger(0)
        val pings = AtomicInteger(0)

        /** The type of every frame, in the order it arrived. */
        val types = CopyOnWriteArrayList<String>()

        /** While true the server keeps its sockets open and says nothing: the path is gone. */
        @Volatile var silent = false

        /** While true the server does not even read: the client's writes back up. */
        @Volatile var stalled = false
        @Volatile private var closed = false

        init {
            thread(isDaemon = true) {
                while (!listener.isClosed) {
                    val s = try {
                        listener.accept()
                    } catch (_: Exception) {
                        break
                    }
                    accepted.incrementAndGet()
                    clients += s
                    // close() may have walked the list just before this socket was added.
                    if (closed) runCatching { s.close() }
                    thread(isDaemon = true) { serve(s) }
                }
            }
        }

        private fun serve(s: Socket) {
            val reader = s.getInputStream().bufferedReader(Charsets.UTF_8)
            val sb = StringBuilder()
            try {
                while (true) {
                    while (stalled && !closed) Thread.sleep(10)
                    val c = reader.read()
                    if (c == -1) break
                    sb.append(c.toChar())
                    if (!sb.endsWith("</event>")) continue
                    val xml = sb.toString()
                    sb.clear()
                    if (silent) continue
                    val type = TAKConnection.eventType(xml) ?: "?"
                    types += type
                    if (type == TAKConnection.PING_TYPE) {
                        pings.incrementAndGet()
                        when (answer) {
                            Answer.PONG -> send(s, PONG)
                            Answer.ECHO -> send(s, xml) // what OpenTAKServer does
                            Answer.NONE -> Unit
                        }
                    }
                }
            } catch (_: Exception) {
                // client went away
            }
        }

        fun sendToAll(xml: String) = clients.forEach { send(it, xml) }

        private fun send(s: Socket, xml: String) {
            synchronized(s) {
                runCatching {
                    s.getOutputStream().write(xml.toByteArray(Charsets.UTF_8))
                    s.getOutputStream().flush()
                }
            }
        }

        override fun close() {
            closed = true
            runCatching { listener.close() }
            clients.forEach { runCatching { it.close() } }
        }
    }

    /** Accepts TCP connections and never says a word: a TLS handshake against it stalls. */
    private class StallServer : AutoCloseable {
        private val listener = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val clients = CopyOnWriteArrayList<Socket>()
        val port: Int get() = listener.localPort
        val accepted = AtomicInteger(0)

        init {
            thread(isDaemon = true) {
                while (!listener.isClosed) {
                    val s = try {
                        listener.accept()
                    } catch (_: Exception) {
                        break
                    }
                    accepted.incrementAndGet()
                    clients += s
                }
            }
        }

        override fun close() {
            runCatching { listener.close() }
            clients.forEach { runCatching { it.close() } }
        }
    }

    private fun fakeServer(answer: Answer) = FakeTakServer(answer).also { closeables += it }

    private fun timing(pingIdleMs: Long, pongWaitMs: Long, tickMs: Long = 20, connectTimeoutMs: Long = 5_000) =
        TAKConnection.Timing(connectTimeoutMs = connectTimeoutMs, pingIdleMs = pingIdleMs, pongWaitMs = pongWaitMs, tickMs = tickMs)

    private fun connection(
        port: Int,
        timing: TAKConnection.Timing,
        tls: Boolean = false,
        nanoTime: () -> Long = System::nanoTime,
    ): TAKConnection {
        val server = TAKServer(
            name = "test",
            host = "127.0.0.1",
            port = port,
            useTLS = tls,
            allowUntrustedTls = tls,
        )
        val conn = TAKConnection(server, certVault = null, timing = timing, pingUid = { "TEST-UID-ping" }, nanoTime = nanoTime)
        closeables += AutoCloseable { conn.disconnect() }
        return conn
    }

    private fun awaitState(
        conn: TAKConnection,
        timeoutMs: Long = 15_000,
        what: String,
        predicate: (ConnectionState) -> Boolean,
    ): ConnectionState {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            val s = conn.state.value
            if (predicate(s)) return s
            Thread.sleep(5)
        }
        fail("expected $what within $timeoutMs ms, state is ${conn.state.value}")
        throw AssertionError("unreachable")
    }

    private fun awaitTrue(timeoutMs: Long = 15_000, what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        fail("expected $what within $timeoutMs ms")
    }

    private fun connectAndArm(conn: TAKConnection) {
        conn.connect()
        awaitState(conn, what = "Connected") { it is ConnectionState.Connected }
        awaitTrue(what = "the server's first answer") { conn.answersPings }
    }

    // MARK: when to ping

    @Test
    fun theAppsOwnFirstEventReachesTheServerBeforeAnyPing() {
        val server = fakeServer(Answer.PONG)
        val conn = connection(server.port, timing(pingIdleMs = 1_500, pongWaitMs = 30_000))
        conn.connect()
        awaitState(conn, what = "Connected") { it is ConnectionState.Connected }
        assertTrue(runBlocking { conn.send(CONTACT) })

        awaitTrue(what = "a ping, later") { server.pings.get() >= 1 }
        assertEquals("the first frame on the wire is the app's", "a-f-G-U-C", server.types.first())
    }

    @Test
    fun aBusyStreamIsPingedUntilTheServerHasAnsweredOnceAndNotAfter() {
        val server = fakeServer(Answer.PONG)
        val conn = connection(server.port, timing(pingIdleMs = 300, pongWaitMs = 30_000))
        conn.connect()
        awaitState(conn, what = "Connected") { it is ConnectionState.Connected }
        // Traffic every 20 ms: the stream is never idle for 300 ms.
        val flooding = AtomicBoolean(true)
        val flood = thread(isDaemon = true) {
            while (flooding.get()) {
                server.sendToAll(CONTACT)
                Thread.sleep(20)
            }
        }
        try {
            awaitTrue(what = "the server to have answered a ping despite the traffic") { conn.answersPings }
            val pingsWhenArmed = server.pings.get()
            Thread.sleep(1_500) // five ping intervals
            // On a schedule there would be five more by now. One or two are allowed
            // for a moment when this machine did not get to run the traffic.
            assertTrue(
                "a stream that is not idle needs no more pings: ${server.pings.get()} after $pingsWhenArmed",
                server.pings.get() <= pingsWhenArmed + 2,
            )
            assertTrue(conn.state.value is ConnectionState.Connected)
        } finally {
            flooding.set(false)
            flood.join(2_000)
        }
    }

    // MARK: quiet versus dead

    @Test
    fun aQuietServerThatAnswersPingsStaysConnectedAcrossManyDropWindows() {
        val server = fakeServer(Answer.PONG)
        // One ping and its wait are 1.15 s; watch four of them go by.
        val conn = connection(server.port, timing(pingIdleMs = 150, pongWaitMs = 1_000))
        connectAndArm(conn)

        Thread.sleep(4_600)

        assertTrue("still connected, state is ${conn.state.value}", conn.state.value is ConnectionState.Connected)
        assertEquals("one connection, never re-dialed", 1, server.accepted.get())
        assertTrue("pinged throughout, pings = ${server.pings.get()}", server.pings.get() >= 10)
    }

    @Test
    fun aServerThatStopsAnsweringIsReportedFailed() {
        val server = fakeServer(Answer.PONG)
        val conn = connection(server.port, timing(pingIdleMs = 100, pongWaitMs = 300))
        connectAndArm(conn)

        server.silent = true

        val failed = awaitState(conn, what = "Failed") { it is ConnectionState.Failed } as ConnectionState.Failed
        assertEquals("No response from server", failed.reason)
        assertNull("no session socket after a failure", conn.sessionSocket)
    }

    @Test
    fun aServerThatSendsThePingBackCountsAsAnsweringAndIsDroppedWhenItStops() {
        val server = fakeServer(Answer.ECHO)
        val conn = connection(server.port, timing(pingIdleMs = 100, pongWaitMs = 300))
        val delivered = CopyOnWriteArrayList<String>()
        val subscribed = AtomicBoolean(false)
        scope.launch {
            conn.received.onSubscription { subscribed.set(true) }.collect { delivered += it }
        }
        awaitTrue(what = "a subscriber on received") { subscribed.get() }

        connectAndArm(conn)
        assertTrue("an echoed ping is not CoT for the app: $delivered", delivered.isEmpty())

        server.silent = true
        val failed = awaitState(conn, what = "Failed") { it is ConnectionState.Failed } as ConnectionState.Failed
        assertEquals("No response from server", failed.reason)
    }

    @Test
    fun anotherClientsPingRelayedByTheServerIsNotAnAnswer() {
        val server = fakeServer(Answer.NONE)
        val conn = connection(server.port, timing(pingIdleMs = 80, pongWaitMs = 200))
        conn.connect()
        awaitState(conn, what = "Connected") { it is ConnectionState.Connected }
        awaitTrue(what = "a ping at the server") { server.pings.get() >= 1 }

        server.sendToAll(PING_FROM_ANOTHER_CLIENT)
        Thread.sleep(1_000) // several drop windows

        assertFalse(conn.answersPings)
        assertTrue("still connected, state is ${conn.state.value}", conn.state.value is ConnectionState.Connected)
    }

    @Test
    fun aQuietServerThatNeverAnsweredAPingIsNotDropped() {
        val server = fakeServer(Answer.NONE)
        val conn = connection(server.port, timing(pingIdleMs = 80, pongWaitMs = 200))
        conn.connect()
        awaitState(conn, what = "Connected") { it is ConnectionState.Connected }

        Thread.sleep(1_500) // five drop windows

        assertTrue("still connected, state is ${conn.state.value}", conn.state.value is ConnectionState.Connected)
        assertFalse(conn.answersPings)
        assertTrue("it keeps asking, pings = ${server.pings.get()}", server.pings.get() >= 3)
        assertEquals(1, server.accepted.get())
    }

    @Test
    fun aLongSilenceOnTheClockAloneDoesNotDropTheLink() {
        // A process that was stalled wakes up to a clock that has moved on, with
        // nothing received in between. The server was never asked: it must be
        // asked before it is given up on.
        val skew = AtomicLong(0)
        val server = fakeServer(Answer.PONG)
        val conn = connection(
            server.port,
            timing(pingIdleMs = 100, pongWaitMs = 300),
            nanoTime = { System.nanoTime() + skew.get() },
        )
        connectAndArm(conn)
        val pingsBefore = server.pings.get()

        skew.addAndGet(10L * 60 * 1_000_000_000) // ten minutes pass at once

        awaitTrue(what = "a ping after the jump") { server.pings.get() > pingsBefore }
        Thread.sleep(1_000)
        assertTrue("still connected, state is ${conn.state.value}", conn.state.value is ConnectionState.Connected)
        assertEquals("never re-dialed", 1, server.accepted.get())
    }

    @Test
    fun aWriteStuckOnADeadPathDoesNotStopTheCheck() {
        val server = fakeServer(Answer.PONG)
        val conn = connection(server.port, timing(pingIdleMs = 100, pongWaitMs = 400))
        connectAndArm(conn)

        // The path dies: nothing is read at the far end and nothing comes back.
        server.silent = true
        server.stalled = true
        // A large send fills the socket buffers and blocks, holding the write lock.
        val megabyte = CONTACT.replace("<detail/>", "<detail><remarks>" + "x".repeat(1_000_000) + "</remarks></detail>")
        val writer = thread(isDaemon = true) {
            runBlocking { while (conn.send(megabyte)) Unit }
        }

        val failed = awaitState(conn, what = "Failed") { it is ConnectionState.Failed } as ConnectionState.Failed
        assertEquals("No response from server", failed.reason)
        writer.join(5_000)
        assertFalse("the blocked write was released when the socket closed", writer.isAlive)
    }

    // MARK: what the app sees

    @Test
    fun thePingExchangeIsNotDeliveredAsCot() {
        val server = fakeServer(Answer.PONG)
        val conn = connection(server.port, timing(pingIdleMs = 80, pongWaitMs = 30_000))
        val delivered = CopyOnWriteArrayList<String>()
        val subscribed = AtomicBoolean(false)
        scope.launch {
            conn.received.onSubscription { subscribed.set(true) }.collect { delivered += it }
        }
        awaitTrue(what = "a subscriber on received") { subscribed.get() }

        connectAndArm(conn)
        awaitTrue(what = "a few pongs") { server.pings.get() >= 3 }
        server.sendToAll(PING_FROM_ANOTHER_CLIENT)
        server.sendToAll(CONTACT)
        awaitTrue(what = "the contact event") { delivered.isNotEmpty() }
        Thread.sleep(200)

        assertEquals(listOf(CONTACT), delivered.toList())
    }

    @Test
    fun aFailedSendEndsTheConnection() {
        val server = fakeServer(Answer.NONE)
        val conn = connection(server.port, timing(pingIdleMs = 60_000, pongWaitMs = 60_000))
        conn.connect()
        awaitState(conn, what = "Connected") { it is ConnectionState.Connected }

        // Writes now fail while the read side stays open: the read loop alone would not notice.
        conn.sessionSocket!!.shutdownOutput()
        val sent = runBlocking { conn.send(CONTACT) }

        assertFalse(sent)
        val failed = awaitState(conn, what = "Failed") { it is ConnectionState.Failed } as ConnectionState.Failed
        assertTrue("reason was ${failed.reason}", failed.reason.startsWith("Send failed"))
    }

    @Test
    fun aServerThatClosesTheStreamIsReportedDisconnected() {
        val server = fakeServer(Answer.PONG)
        // No ping for a minute, so no write of ours can race the close.
        val conn = connection(server.port, timing(pingIdleMs = 60_000, pongWaitMs = 60_000))
        conn.connect()
        awaitState(conn, what = "Connected") { it is ConnectionState.Connected }

        server.close()

        awaitState(conn, what = "Disconnected") { it is ConnectionState.Disconnected }
    }

    // MARK: dialing

    @Test
    fun aRedialTheMomentItFailsIsNotLost() {
        val server = fakeServer(Answer.PONG)
        val conn = connection(server.port, timing(pingIdleMs = 100, pongWaitMs = 300))
        // The supervisor's first retry is immediate: dial again the moment it is down.
        scope.launch {
            conn.state.collect {
                if (it is ConnectionState.Failed || it is ConnectionState.Disconnected) conn.connect()
            }
        }
        repeat(5) { round ->
            awaitTrue(what = "a live, answered connection in round ${round + 1}") {
                conn.state.value is ConnectionState.Connected && conn.answersPings &&
                    (conn.idleMs ?: Long.MAX_VALUE) < 250
            }
            val dialsBefore = server.accepted.get()
            server.silent = true
            awaitTrue(what = "a redial after silence in round ${round + 1}") { server.accepted.get() > dialsBefore }
            server.silent = false
        }
        awaitState(conn, what = "Connected at the end") { it is ConnectionState.Connected }
    }

    @Test
    fun aStalledTlsHandshakeFailsInsteadOfStayingOnConnecting() {
        val stall = StallServer().also { closeables += it }
        val conn = connection(stall.port, timing(pingIdleMs = 60_000, pongWaitMs = 60_000, connectTimeoutMs = 400), tls = true)
        conn.connect()
        awaitState(conn, what = "Failed") { it is ConnectionState.Failed }
    }

    @Test
    fun disconnectDuringADialStaysDisconnectedAndADialAfterItStillRuns() {
        val stall = StallServer().also { closeables += it }
        val conn = connection(stall.port, timing(pingIdleMs = 60_000, pongWaitMs = 60_000, connectTimeoutMs = 30_000), tls = true)
        conn.connect()
        awaitTrue(what = "the first dial reaching the server") { stall.accepted.get() == 1 }
        assertTrue(conn.state.value is ConnectionState.Connecting)
        Thread.sleep(150) // let it block in the handshake

        conn.disconnect()
        assertEquals(ConnectionState.Disconnected, conn.state.value)
        Thread.sleep(500)
        assertEquals("the aborted dial must not report a failure", ConnectionState.Disconnected, conn.state.value)

        conn.connect()
        awaitTrue(what = "a second dial reaching the server") { stall.accepted.get() == 2 }
        assertTrue(conn.state.value is ConnectionState.Connecting)
        conn.disconnect()
        assertEquals(ConnectionState.Disconnected, conn.state.value)
    }

    // MARK: frames

    @Test
    fun eventTypeReadsTheStartTagOnly() {
        assertEquals("t-x-c-t-r", TAKConnection.eventType(PONG))
        assertEquals("a-f-G-U-C", TAKConnection.eventType(CONTACT))
        assertEquals("b-t-f", TAKConnection.eventType("<event version='2.0' type = 'b-t-f' uid='x'><detail/></event>"))
        assertEquals(
            "a-h-G",
            TAKConnection.eventType("<event subtype=\"t-x-c-t-r\" type=\"a-h-G\"><detail><link type=\"t-x-c-t\"/></detail></event>"),
        )
        assertNull(TAKConnection.eventType("<event uid=\"x\"><detail><link type=\"t-x-c-t-r\"/></detail></event>"))
        assertNull(TAKConnection.eventType("not xml"))
        // TAK Server writes its pong with single quotes.
        val takServerPong = "<event version='2.0' uid='takPong' type='t-x-c-t-r' how='h-g-i-g-o' time='t' start='t' stale='t'/>"
        assertEquals(TAKConnection.PONG_TYPE, TAKConnection.eventType(takServerPong))
        assertEquals("takPong", TAKConnection.eventUid(takServerPong))
        assertEquals("TEST-1", TAKConnection.eventUid(CONTACT))
        assertNull(TAKConnection.eventUid("<event type=\"a-f-G\"><detail><link uid=\"x\"/></detail></event>"))
    }

    @Test
    fun pingIsTheCotEveryTakServerAnswers() {
        val xml = TAKConnection.pingXml("ANDROID-<1>&2-ping", nowMs = 1_790_000_000_000L)
        assertEquals(TAKConnection.PING_TYPE, TAKConnection.eventType(xml))
        assertNotEquals(TAKConnection.PONG_TYPE, TAKConnection.eventType(xml))
        assertTrue(xml, xml.contains("uid=\"ANDROID-&lt;1&gt;&amp;2-ping\""))
        assertTrue(xml, xml.startsWith("<event version=\"2.0\""))
        assertTrue(xml, xml.endsWith("</event>"))
        assertTrue(xml, xml.contains("<point lat=\"0.0\" lon=\"0.0\""))
    }

    private companion object {
        const val PONG =
            "<event version=\"2.0\" uid=\"takPong\" type=\"t-x-c-t-r\" how=\"h-g-i-g-o\" " +
                "time=\"2026-01-01T00:00:00Z\" start=\"2026-01-01T00:00:00Z\" stale=\"2026-01-01T00:00:20Z\">" +
                "<point lat=\"0.0\" lon=\"0.0\" hae=\"0.0\" ce=\"9999999.0\" le=\"9999999.0\"/></event>"
        const val PING_FROM_ANOTHER_CLIENT =
            "<event version=\"2.0\" uid=\"OTHER-ping\" type=\"t-x-c-t\" how=\"m-g\" " +
                "time=\"2026-01-01T00:00:00Z\" start=\"2026-01-01T00:00:00Z\" stale=\"2026-01-01T00:00:10Z\">" +
                "<point lat=\"0.0\" lon=\"0.0\" hae=\"0.0\" ce=\"9999999.0\" le=\"9999999.0\"/><detail/></event>"
        const val CONTACT =
            "<event version=\"2.0\" uid=\"TEST-1\" type=\"a-f-G-U-C\" how=\"m-g\" " +
                "time=\"2026-01-01T00:00:00Z\" start=\"2026-01-01T00:00:00Z\" stale=\"2026-01-01T00:05:00Z\">" +
                "<point lat=\"1.0\" lon=\"2.0\" hae=\"0.0\" ce=\"10.0\" le=\"10.0\"/><detail/></event>"
    }
}
