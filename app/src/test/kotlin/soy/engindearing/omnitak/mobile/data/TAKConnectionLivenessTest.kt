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
import kotlin.concurrent.thread

/**
 * #233: a TAK server connection that is dead without the OS knowing must be
 * noticed and reported, and one that is only quiet must be left alone.
 *
 * These run against real sockets on the loopback interface with short windows.
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
        val frames = CopyOnWriteArrayList<String>()

        /** While true the server keeps its sockets open and says nothing: the path is gone. */
        @Volatile var silent = false

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
                    thread(isDaemon = true) { serve(s) }
                }
            }
        }

        private fun serve(s: Socket) {
            val reader = s.getInputStream().bufferedReader(Charsets.UTF_8)
            val sb = StringBuilder()
            try {
                while (true) {
                    val c = reader.read()
                    if (c == -1) break
                    sb.append(c.toChar())
                    if (!sb.endsWith("</event>")) continue
                    val xml = sb.toString()
                    sb.clear()
                    if (silent) continue
                    if (TAKConnection.eventType(xml) == TAKConnection.PING_TYPE) {
                        pings.incrementAndGet()
                        when (answer) {
                            Answer.PONG -> send(s, PONG)
                            Answer.ECHO -> send(s, xml) // what OpenTAKServer does
                            Answer.NONE -> Unit
                        }
                    } else {
                        frames += xml
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

    private fun fakeServer(answerPings: Boolean) =
        fakeServer(if (answerPings) Answer.PONG else Answer.NONE)

    private fun fakeServer(answer: Answer) = FakeTakServer(answer).also { closeables += it }

    private fun connection(port: Int, timing: TAKConnection.Timing, tls: Boolean = false): TAKConnection {
        val server = TAKServer(
            name = "test",
            host = "127.0.0.1",
            port = port,
            useTLS = tls,
            allowUntrustedTls = tls,
        )
        val conn = TAKConnection(server, certVault = null, timing = timing, pingUid = { "TEST-UID-ping" })
        closeables += AutoCloseable { conn.disconnect() }
        return conn
    }

    private fun awaitState(
        conn: TAKConnection,
        timeoutMs: Long = 5_000,
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

    private fun awaitTrue(timeoutMs: Long = 5_000, what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        fail("expected $what within $timeoutMs ms")
    }

    @Test
    fun aQuietServerThatAnswersPingsStaysConnected() {
        val server = fakeServer(answerPings = true)
        // The drop window is far away: this test is about pings going out and coming back.
        val conn = connection(server.port, TAKConnection.Timing(3_000, pingIdleMs = 100, deadIdleMs = 30_000, tickMs = 25))
        conn.connect()
        awaitState(conn, what = "Connected") { it is ConnectionState.Connected }
        awaitTrue(what = "four pings at the server") { server.pings.get() >= 4 }
        assertTrue("the server's pong must be recognised", conn.answersPings)
        assertTrue(conn.state.value is ConnectionState.Connected)
        assertEquals(1, server.accepted.get())
    }

    @Test
    fun aServerThatStopsAnsweringIsReportedFailed() {
        val server = fakeServer(answerPings = true)
        val conn = connection(server.port, TAKConnection.Timing(3_000, pingIdleMs = 100, deadIdleMs = 500, tickMs = 25))
        conn.connect()
        awaitState(conn, what = "Connected") { it is ConnectionState.Connected }
        awaitTrue(what = "a first pong") { conn.answersPings }

        server.silent = true

        val failed = awaitState(conn, what = "Failed") { it is ConnectionState.Failed } as ConnectionState.Failed
        assertEquals("No response from server", failed.reason)
        assertNull("no session socket after a failure", conn.sessionSocket)
    }

    @Test
    fun aServerThatSendsThePingBackCountsAsAnsweringAndIsDroppedWhenItStops() {
        val server = fakeServer(Answer.ECHO)
        val conn = connection(server.port, TAKConnection.Timing(3_000, pingIdleMs = 100, deadIdleMs = 500, tickMs = 25))
        val delivered = CopyOnWriteArrayList<String>()
        val subscribed = AtomicBoolean(false)
        scope.launch {
            conn.received.onSubscription { subscribed.set(true) }.collect { delivered += it }
        }
        awaitTrue(what = "a subscriber on received") { subscribed.get() }

        conn.connect()
        awaitState(conn, what = "Connected") { it is ConnectionState.Connected }
        awaitTrue(what = "the echo to be taken as an answer") { conn.answersPings }
        assertTrue("an echoed ping is not CoT for the app: $delivered", delivered.isEmpty())

        server.silent = true
        val failed = awaitState(conn, what = "Failed") { it is ConnectionState.Failed } as ConnectionState.Failed
        assertEquals("No response from server", failed.reason)
    }

    @Test
    fun anotherClientsPingRelayedByTheServerIsNotAnAnswer() {
        val server = fakeServer(Answer.NONE)
        val conn = connection(server.port, TAKConnection.Timing(3_000, pingIdleMs = 80, deadIdleMs = 250, tickMs = 20))
        conn.connect()
        awaitState(conn, what = "Connected") { it is ConnectionState.Connected }
        awaitTrue(what = "a ping at the server") { server.pings.get() >= 1 }

        server.sendToAll(PING_FROM_ANOTHER_CLIENT)
        Thread.sleep(1_000) // four drop windows

        assertFalse(conn.answersPings)
        assertTrue("still connected, state is ${conn.state.value}", conn.state.value is ConnectionState.Connected)
    }

    @Test
    fun aQuietServerThatNeverAnsweredAPingIsNotDropped() {
        val server = fakeServer(answerPings = false)
        val conn = connection(server.port, TAKConnection.Timing(3_000, pingIdleMs = 80, deadIdleMs = 250, tickMs = 20))
        conn.connect()
        awaitState(conn, what = "Connected") { it is ConnectionState.Connected }

        Thread.sleep(1_500) // six drop windows

        assertTrue("still connected, state is ${conn.state.value}", conn.state.value is ConnectionState.Connected)
        assertFalse(conn.answersPings)
        assertTrue("it keeps asking, pings = ${server.pings.get()}", server.pings.get() >= 3)
        assertEquals(1, server.accepted.get())
    }

    @Test
    fun thePingExchangeIsNotDeliveredAsCot() {
        val server = fakeServer(answerPings = true)
        val conn = connection(server.port, TAKConnection.Timing(3_000, pingIdleMs = 80, deadIdleMs = 30_000, tickMs = 20))
        val delivered = CopyOnWriteArrayList<String>()
        val subscribed = AtomicBoolean(false)
        scope.launch {
            conn.received.onSubscription { subscribed.set(true) }.collect { delivered += it }
        }
        awaitTrue(what = "a subscriber on received") { subscribed.get() }

        conn.connect()
        awaitState(conn, what = "Connected") { it is ConnectionState.Connected }
        awaitTrue(what = "a few pongs") { server.pings.get() >= 3 && conn.answersPings }
        server.sendToAll(PING_FROM_ANOTHER_CLIENT)
        server.sendToAll(CONTACT)
        awaitTrue(what = "the contact event") { delivered.isNotEmpty() }
        Thread.sleep(200)

        assertEquals(listOf(CONTACT), delivered.toList())
    }

    @Test
    fun aFailedSendEndsTheConnection() {
        val server = fakeServer(answerPings = false)
        val conn = connection(server.port, TAKConnection.Timing(3_000, pingIdleMs = 60_000, deadIdleMs = 120_000, tickMs = 50))
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
    fun aRedialTheMomentItFailsIsNotLost() {
        val server = fakeServer(answerPings = true)
        val conn = connection(
            server.port,
            TAKConnection.Timing(3_000, pingIdleMs = 60, deadIdleMs = 300, tickMs = 15, firstReplyMs = 300),
        )
        // What ServerManager's supervisor does on its first retry: dial again at once.
        scope.launch {
            conn.state.collect {
                if (it is ConnectionState.Failed || it is ConnectionState.Disconnected) conn.connect()
            }
        }
        repeat(5) { round ->
            // Up, and this session has heard from the server a moment ago.
            awaitTrue(what = "a live connection in round ${round + 1}") {
                conn.state.value is ConnectionState.Connected && conn.answersPings &&
                    (conn.idleMs ?: Long.MAX_VALUE) < 150
            }
            val dialsBefore = server.accepted.get()
            server.silent = true
            awaitTrue(what = "a redial after silence in round ${round + 1}") { server.accepted.get() > dialsBefore }
            server.silent = false
        }
        awaitState(conn, what = "Connected at the end") { it is ConnectionState.Connected }
    }

    @Test
    fun aKnownServerThatAcceptsButSaysNothingIsNotReportedConnected() {
        val server = fakeServer(answerPings = true)
        val conn = connection(
            server.port,
            TAKConnection.Timing(3_000, pingIdleMs = 60, deadIdleMs = 300, tickMs = 15, firstReplyMs = 400),
        )
        conn.connect()
        awaitState(conn, what = "Connected") { it is ConnectionState.Connected }
        awaitTrue(what = "a first pong") { conn.answersPings }
        server.silent = true
        awaitState(conn, what = "Failed") { it is ConnectionState.Failed }

        // The path now accepts TCP connections and answers nothing.
        val seen = CopyOnWriteArrayList<ConnectionState>()
        val watcher = scope.launch { conn.state.collect { seen += it } }
        val dialsBefore = server.accepted.get()
        conn.connect()
        awaitTrue(what = "the dial reaching the server") { server.accepted.get() > dialsBefore }
        Thread.sleep(150)
        assertTrue("still proving, state is ${conn.state.value}", conn.state.value is ConnectionState.Connecting)
        assertFalse("nothing can be sent before the link is proven", runBlocking { conn.send(CONTACT) })
        awaitState(conn, what = "Failed again") { it is ConnectionState.Failed }
        watcher.cancel()
        assertTrue("states were $seen", seen.none { it is ConnectionState.Connected })

        // And when the path answers again, the next dial comes up.
        server.silent = false
        conn.connect()
        awaitState(conn, what = "Connected once the server answers") { it is ConnectionState.Connected }
    }

    @Test
    fun aServerThatClosesTheStreamIsReportedDisconnected() {
        val server = fakeServer(answerPings = true)
        val conn = connection(server.port, TAKConnection.Timing(3_000, pingIdleMs = 60_000, deadIdleMs = 120_000, tickMs = 50))
        conn.connect()
        awaitState(conn, what = "Connected") { it is ConnectionState.Connected }

        server.close()

        awaitState(conn, what = "Disconnected") { it is ConnectionState.Disconnected }
    }

    @Test
    fun aStalledTlsHandshakeFailsInsteadOfStayingOnConnecting() {
        val stall = StallServer().also { closeables += it }
        val conn = connection(stall.port, TAKConnection.Timing(connectTimeoutMs = 400), tls = true)
        conn.connect()
        awaitState(conn, timeoutMs = 5_000, what = "Failed") { it is ConnectionState.Failed }
    }

    @Test
    fun disconnectDuringADialStaysDisconnectedAndADialAfterItStillRuns() {
        val stall = StallServer().also { closeables += it }
        val conn = connection(stall.port, TAKConnection.Timing(connectTimeoutMs = 20_000), tls = true)
        conn.connect()
        awaitState(conn, what = "Connecting") { it is ConnectionState.Connecting }
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
