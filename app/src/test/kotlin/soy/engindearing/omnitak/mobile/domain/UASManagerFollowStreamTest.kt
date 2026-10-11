package soy.engindearing.omnitak.mobile.domain

import io.dronefleet.mavlink.common.CommandLong
import io.dronefleet.mavlink.common.FollowTarget
import io.dronefleet.mavlink.common.GlobalPositionInt
import io.dronefleet.mavlink.common.MavCmd
import io.dronefleet.mavlink.minimal.MavAutopilot
import io.dronefleet.mavlink.minimal.MavType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.SelfFix
import soy.engindearing.omnitak.mobile.data.uas.MavlinkConnection
import soy.engindearing.omnitak.mobile.data.uas.TerrainSampler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import io.dronefleet.mavlink.MavlinkConnection as DroneFleetConnection

/**
 * #249 end to end, without a vehicle: the real [UASManager], its real MAVLink
 * link and its real 1 Hz stream, talking over loopback UDP to a socket that
 * decodes everything the app sends. It shows what goes on the wire when the
 * fix stops updating, not just what the rules decide.
 *
 * The limits are shortened (stale after 1 s, stop after 1.5 s of holding) so a
 * test takes seconds, not the 40 s a real stall takes. The stream ticks once a
 * second in real time, so each test waits on the clock for a few ticks.
 */
class UASManagerFollowStreamTest {

    private val limits = FollowLimits(maxTargetAgeMs = 1_000, maxHoldMs = 1_500)

    private class Frame(val atNanos: Long, val payload: Any)

    /** Plays the vehicle: a UDP socket that records every MAVLink frame it gets. */
    private class FakeVehicle {
        private val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 100 }
        private val frames = CopyOnWriteArrayList<Frame>()
        private val open = AtomicBoolean(true)
        val port: Int get() = socket.localPort

        private val reader = thread(isDaemon = true, name = "fake-vehicle") {
            val buf = ByteArray(512)
            while (open.get()) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    decode(buf.copyOf(packet.length))?.let { frames += Frame(System.nanoTime(), it) }
                } catch (_: SocketTimeoutException) {
                    // look at `open` again
                } catch (_: IOException) {
                    return@thread
                }
            }
        }

        private fun decode(bytes: ByteArray): Any? = try {
            DroneFleetConnection.create(ByteArrayInputStream(bytes), ByteArrayOutputStream()).next()?.payload
        } catch (_: Throwable) {
            null
        }

        /** What a PX4 is fed: FOLLOW_TARGET. */
        fun followTargets() = frames.filter { it.payload is FollowTarget }

        /** What an ArduPilot Copter is fed: GLOBAL_POSITION_INT standing in for the target. */
        fun targetPositions() = frames.filter { it.payload is GlobalPositionInt }

        private fun modeCommands(main: Float, sub: Float) = frames.filter {
            val c = it.payload as? CommandLong ?: return@filter false
            c.command().entry() == MavCmd.MAV_CMD_DO_SET_MODE && c.param2() == main && c.param3() == sub
        }

        /** PX4 AUTO.FOLLOW_TARGET (main 4, sub 8). */
        fun followModeCommands() = modeCommands(4f, 8f)

        /** PX4 AUTO.LOITER (main 4, sub 3). */
        fun loiterCommands() = modeCommands(4f, 3f)

        /** ArduPilot Copter FOLLOW (custom mode 23). */
        fun arduFollowModeCommands() = modeCommands(23f, 0f)

        fun close() {
            open.set(false)
            socket.close()
            reader.join(1_000)
        }
    }

    private class StubTerrain(private val meters: Double?) : TerrainSampler() {
        override suspend fun sampleMeters(latDeg: Double, lonDeg: Double): Double? = meters
        override suspend fun sample(latDeg: Double, lonDeg: Double): TerrainSampler.Sample? =
            meters?.let { TerrainSampler.Sample(it, zoomUsed = 12) }
    }

    private lateinit var vehicle: FakeVehicle
    private var uas: UASManager? = null

    @Before
    fun openTheFakeVehicle() {
        vehicle = FakeVehicle()
    }

    @After
    fun tearDown() {
        uas?.disconnect()
        vehicle.close()
    }

    /** The GPS: live (time = now) until [freeze], then stuck at the moment it froze. */
    private class Gps {
        private val frozenAt = AtomicLong(0)
        @Volatile var hasAltitude = true

        fun freeze() = frozenAt.set(System.currentTimeMillis())
        fun resume() = frozenAt.set(0)

        fun fix() = SelfFix(
            lat = 47.6,
            lon = -117.4,
            altitudeM = if (hasAltitude) 600.0 else 0.0,
            speedKmh = 0.0,
            accuracyM = 5f,
            timeMs = frozenAt.get().takeIf { it != 0L } ?: System.currentTimeMillis(),
            hasAltitude = hasAltitude,
        )
    }

    /** A connected PX4 on the other end of loopback, and a manager wired to it. */
    private fun connectedManager(
        gps: Gps,
        terrain: TerrainSampler = StubTerrain(null),
        followLimits: FollowLimits = limits,
        autopilot: MavAutopilot = MavAutopilot.MAV_AUTOPILOT_PX4,
        vehicleType: MavType? = null,
    ): UASManager {
        val link = MavlinkConnection()
        val manager = UASManager(
            sendCoT = { true },
            terrain = terrain,
            operatorFix = { gps.fix() },
            mavlink = link,
            followLimits = followLimits,
        )
        uas = manager
        manager.connect("127.0.0.1", vehicle.port)
        // connect() starts from a clean state, so the vehicle's heartbeat goes
        // in afterwards. Dated ahead so it outlasts the test.
        link.injectStateUpdate {
            it.copy(
                lastHeartbeat = System.currentTimeMillis() + 120_000,
                autopilot = autopilot.name,
                vehicleType = vehicleType?.name,
            )
        }
        return manager
    }

    private fun awaitTrue(timeoutMs: Long, what: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(20)
        }
        fail("timed out after $timeoutMs ms waiting for $what")
    }

    private fun UASManager.awaitHealth(timeoutMs: Long, what: String, predicate: (FollowHealth) -> Boolean) =
        awaitTrue(timeoutMs, what) { predicate(followHealth.value) }

    private fun Frame.msSince(earlier: Frame) = (atNanos - earlier.atNanos) / 1_000_000

    // ------------------------------------------------------------------ tests

    @Test
    fun aFixThatStopsUpdatingGoesQuietAndThenTheVehicleIsToldToLoiter() {
        val gps = Gps()
        val manager = connectedManager(gps)

        assertEquals(UASManager.FollowMeResult.Started, runBlocking { manager.startFollowMe() })
        awaitTrue(5_000, "the first targets") { vehicle.followTargets().size >= 2 }
        assertEquals(1, vehicle.followModeCommands().size)
        assertEquals(FollowHealth.Streaming, manager.followHealth.value)
        assertTrue(vehicle.loiterCommands().isEmpty())

        // GPS stops updating.
        gps.freeze()
        manager.awaitHealth(8_000, "the stream to hold") { it is FollowHealth.Holding }
        assertEquals(
            FollowHoldReason.StaleFix,
            (manager.followHealth.value as FollowHealth.Holding).reason,
        )
        assertTrue("still following while it holds", manager.followMeActive.value)

        // After the hold limit it gives up on its own.
        manager.awaitHealth(10_000, "the stream to stop") { it is FollowHealth.Stopped }
        val stopped = manager.followHealth.value as FollowHealth.Stopped
        assertEquals(FollowHoldReason.StaleFix, stopped.reason)
        assertFalse(manager.followMeActive.value)
        assertNull(manager.followingWhat.value)

        // The vehicle was told to loiter, once...
        awaitTrue(3_000, "the LOITER command to arrive") { vehicle.loiterCommands().isNotEmpty() }
        assertEquals(1, vehicle.loiterCommands().size)

        // ...and no target was sent for at least the length of the hold before it.
        val lastTarget = vehicle.followTargets().last()
        val loiter = vehicle.loiterCommands().first()
        assertTrue(
            "the wire was quiet for ${loiter.msSince(lastTarget)} ms before LOITER",
            loiter.msSince(lastTarget) >= limits.maxHoldMs,
        )

        // The stream is over: nothing more arrives.
        val targets = vehicle.followTargets().size
        Thread.sleep(1_300)
        assertEquals(targets, vehicle.followTargets().size)
        assertEquals(1, vehicle.loiterCommands().size)

        // The HUD's one-shot clears.
        manager.acknowledgeFollowStop()
        assertEquals(FollowHealth.Idle, manager.followHealth.value)
    }

    @Test
    fun aFixThatComesBackBeforeTheLimitResumesTheStream() {
        val gps = Gps()
        // Long enough to recover in, short enough to wait for.
        val manager = connectedManager(gps, followLimits = FollowLimits(maxTargetAgeMs = 1_000, maxHoldMs = 6_000))

        assertEquals(UASManager.FollowMeResult.Started, runBlocking { manager.startFollowMe() })
        awaitTrue(5_000, "the first target") { vehicle.followTargets().isNotEmpty() }

        gps.freeze()
        manager.awaitHealth(8_000, "the stream to hold") { it is FollowHealth.Holding }
        val whileHolding = vehicle.followTargets().size

        // The GPS is back well inside the limit.
        gps.resume()
        manager.awaitHealth(5_000, "the stream to resume") { it == FollowHealth.Streaming }
        awaitTrue(5_000, "targets to flow again") { vehicle.followTargets().size > whileHolding }

        assertTrue(manager.followMeActive.value)
        assertTrue("no LOITER for a stall that recovered", vehicle.loiterCommands().isEmpty())
    }

    @Test
    fun stoppingFollowMeParksTheVehicleAndLeavesNothingToAnnounce() {
        val gps = Gps()
        val manager = connectedManager(gps)
        assertEquals(UASManager.FollowMeResult.Started, runBlocking { manager.startFollowMe() })
        awaitTrue(5_000, "the first target") { vehicle.followTargets().isNotEmpty() }

        runBlocking { manager.stopFollowMe() }

        awaitTrue(3_000, "the LOITER command to arrive") { vehicle.loiterCommands().isNotEmpty() }
        assertFalse(manager.followMeActive.value)
        assertEquals(FollowHealth.Idle, manager.followHealth.value)
        Thread.sleep(200) // a tick that was already in flight when it stopped
        val targets = vehicle.followTargets().size
        Thread.sleep(1_300)
        assertEquals(targets, vehicle.followTargets().size)
    }

    @Test
    fun withNoAltitudeReferenceTheVehicleIsNeverSwitchedToFollow() {
        val gps = Gps().apply { hasAltitude = false }
        val manager = connectedManager(gps, terrain = StubTerrain(null))

        assertEquals(UASManager.FollowMeResult.NoAltitudeReference, runBlocking { manager.startFollowMe() })

        // Let a couple of heartbeats go by: whatever else was going to be sent
        // would have been by now.
        Thread.sleep(1_500)
        assertTrue(vehicle.followModeCommands().isEmpty())
        assertTrue(vehicle.followTargets().isEmpty())
        assertTrue(vehicle.loiterCommands().isEmpty())
        assertEquals(FollowHealth.Idle, manager.followHealth.value)
    }

    @Test
    fun px4IsSentTheTerrainUnderTheTargetPlusCruiseNotTheOperatorsAltitude() {
        // The operator's fix says 600 m; the terrain under it says 500 m.
        val manager = connectedManager(Gps(), terrain = StubTerrain(500.0))
        assertEquals(UASManager.FollowMeResult.Started, runBlocking { manager.startFollowMe() })
        awaitTrue(5_000, "the first target") { vehicle.followTargets().isNotEmpty() }

        val target = vehicle.followTargets().first().payload as FollowTarget
        assertEquals("500 m of terrain plus the default 80 m cruise", 580f, target.alt(), 0.01f)
        assertEquals(47.6e7, target.lat().toDouble(), 1.0)
        assertEquals(-117.4e7, target.lon().toDouble(), 1.0)
    }

    @Test
    fun px4IsSentTheOperatorsAltitudePlusCruiseWhenThereIsNoTerrain() {
        val manager = connectedManager(Gps(), terrain = StubTerrain(null))
        assertEquals(UASManager.FollowMeResult.Started, runBlocking { manager.startFollowMe() })
        awaitTrue(5_000, "the first target") { vehicle.followTargets().isNotEmpty() }

        val target = vehicle.followTargets().first().payload as FollowTarget
        assertEquals("600 m operator altitude plus the default 80 m cruise", 680f, target.alt(), 0.01f)
    }

    @Test
    fun anArduPilotCopterIsFedTheSameTargetAndGoesQuietTheSameWay() {
        val gps = Gps()
        val manager = connectedManager(
            gps,
            autopilot = MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA,
            vehicleType = MavType.MAV_TYPE_QUADROTOR,
        )
        assertEquals(UASManager.FollowMeResult.Started, runBlocking { manager.startFollowMe() })
        awaitTrue(5_000, "the first targets") { vehicle.targetPositions().size >= 2 }
        assertEquals(1, vehicle.arduFollowModeCommands().size)
        assertTrue("PX4's target message is not used", vehicle.followTargets().isEmpty())

        val position = vehicle.targetPositions().first().payload as GlobalPositionInt
        assertEquals(47.6e7, position.lat().toDouble(), 1.0)
        assertEquals("operator altitude 600 m plus 80 m cruise, in mm", 680_000, position.alt())
        assertEquals("the cruise height above the target, in mm", 80_000, position.relativeAlt())

        gps.freeze()
        manager.awaitHealth(10_000, "the stream to stop") { it is FollowHealth.Stopped }
        awaitTrue(3_000, "the LOITER command to arrive") { vehicle.loiterCommands().isNotEmpty() }
        val gap = vehicle.loiterCommands().first().msSince(vehicle.targetPositions().last())
        assertTrue("the wire was quiet for $gap ms before LOITER", gap >= limits.maxHoldMs)
    }

    @Test
    fun anAltitudeThatDisappearsMidFlightHoldsAndThenStops() {
        val gps = Gps()
        val manager = connectedManager(gps, terrain = StubTerrain(null))
        assertEquals(UASManager.FollowMeResult.Started, runBlocking { manager.startFollowMe() })
        awaitTrue(5_000, "the first target") { vehicle.followTargets().isNotEmpty() }

        // The fix goes on arriving but loses its altitude, and there is no terrain.
        gps.hasAltitude = false
        manager.awaitHealth(8_000, "the stream to hold") { it is FollowHealth.Holding }
        assertEquals(
            FollowHoldReason.NoAltitudeReference,
            (manager.followHealth.value as FollowHealth.Holding).reason,
        )

        manager.awaitHealth(10_000, "the stream to stop") { it is FollowHealth.Stopped }
        assertEquals(
            FollowHoldReason.NoAltitudeReference,
            (manager.followHealth.value as FollowHealth.Stopped).reason,
        )
        awaitTrue(3_000, "the LOITER command to arrive") { vehicle.loiterCommands().isNotEmpty() }
    }
}
