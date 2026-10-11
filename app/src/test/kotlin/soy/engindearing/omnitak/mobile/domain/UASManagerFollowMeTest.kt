package soy.engindearing.omnitak.mobile.domain

import io.dronefleet.mavlink.minimal.MavAutopilot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.SelfFix
import soy.engindearing.omnitak.mobile.data.uas.MavlinkConnection
import soy.engindearing.omnitak.mobile.data.uas.TerrainSampler

/**
 * #235: Follow-Me must not steer a vehicle toward the position the app
 * remembered from its last session.
 *
 * #249: and it must not engage with nothing to measure the flight height from.
 */
class UASManagerFollowMeTest {

    private fun fix(restored: Boolean, hasAltitude: Boolean = true) = SelfFix(
        lat = 10.0,
        lon = 20.0,
        altitudeM = if (hasAltitude) 100.0 else 0.0,
        speedKmh = 0.0,
        accuracyM = 5f,
        timeMs = 1_790_000_000_000L,
        restored = restored,
        hasAltitude = hasAltitude,
    )

    private fun manager(operatorFix: () -> SelfFix?) =
        UASManager(sendCoT = { true }, operatorFix = operatorFix)

    // ------------------------------------------------------------- #249 rig

    /** Stands in for the tile feed: answers with [meters] (null = the lookup
     *  failed) and counts how often it was asked. */
    private class StubTerrain(private val meters: Double?) : TerrainSampler() {
        var asked = 0
        override suspend fun sampleMeters(latDeg: Double, lonDeg: Double): Double? {
            asked++
            return meters
        }

        override suspend fun sample(latDeg: Double, lonDeg: Double): TerrainSampler.Sample? {
            asked++
            return meters?.let { TerrainSampler.Sample(it, zoomUsed = 12) }
        }
    }

    /** A link that has heard a heartbeat from [autopilot] just now. Nothing is
     *  connected underneath: commands sent on it go nowhere. */
    private fun linkTo(autopilot: MavAutopilot = MavAutopilot.MAV_AUTOPILOT_PX4) =
        MavlinkConnection().apply {
            injectStateUpdate {
                it.copy(lastHeartbeat = System.currentTimeMillis(), autopilot = autopilot.name)
            }
        }

    private fun connectedManager(
        terrain: TerrainSampler,
        link: MavlinkConnection = linkTo(),
        operatorFix: () -> SelfFix?,
    ) = UASManager(
        sendCoT = { true },
        terrain = terrain,
        operatorFix = operatorFix,
        mavlink = link,
    )

    /** A pursued contact: a position and no time, because a contact is not aged. */
    private val contact = FollowTargetPoint(lat = 11.0, lon = 21.0, timeMs = null)

    // ---------------------------------------------------------------- #235

    @Test
    fun aRestoredFixIsNotAPositionToFollow() = runBlocking {
        val result = manager { fix(restored = true) }.startFollowMe()
        assertEquals(UASManager.FollowMeResult.NoGpsFix, result)
    }

    @Test
    fun noFixAtAllStillReportsNoGpsFix() = runBlocking {
        val result = manager { null }.startFollowMe()
        assertEquals(UASManager.FollowMeResult.NoGpsFix, result)
    }

    @Test
    fun aLiveFixGetsPastThePositionCheck() = runBlocking {
        // No vehicle is connected in this test, so the next check is what answers.
        val result = manager { fix(restored = false) }.startFollowMe()
        assertEquals(UASManager.FollowMeResult.NotConnected, result)
    }

    @Test
    fun aLiveFixThatReplacesARestoredOneIsAccepted() = runBlocking {
        var current = fix(restored = true)
        val uas = manager { current }
        assertEquals(UASManager.FollowMeResult.NoGpsFix, uas.startFollowMe())
        current = fix(restored = false)
        assertEquals(UASManager.FollowMeResult.NotConnected, uas.startFollowMe())
    }

    // ------------------------------------------- #249: no altitude reference

    @Test
    fun followMeRefusesWhenThereIsNoTerrainAndTheFixHasNoAltitude() = runBlocking {
        val terrain = StubTerrain(null)
        val uas = connectedManager(terrain) { fix(restored = false, hasAltitude = false) }

        assertEquals(UASManager.FollowMeResult.NoAltitudeReference, uas.startFollowMe())

        assertEquals(1, terrain.asked)
        assertFalse(uas.followMeActive.value)
        assertNull(uas.followingWhat.value)
        assertEquals(FollowHealth.Idle, uas.followHealth.value)
    }

    @Test
    fun aFixWithAnAltitudeEngagesWithoutAskingTerrain() = runBlocking {
        val terrain = StubTerrain(null)
        val uas = connectedManager(terrain) { fix(restored = false, hasAltitude = true) }

        assertEquals(UASManager.FollowMeResult.Started, uas.startFollowMe())

        assertEquals("the tile feed is not needed when the fix has an altitude", 0, terrain.asked)
        assertTrue(uas.followMeActive.value)
        assertEquals(UASManager.FollowSubject.Operator, uas.followingWhat.value)
        assertEquals(FollowHealth.Streaming, uas.followHealth.value)
    }

    @Test
    fun terrainStandsInForAFixWithoutAnAltitude() = runBlocking {
        val terrain = StubTerrain(600.0)
        val uas = connectedManager(terrain) { fix(restored = false, hasAltitude = false) }

        assertEquals(UASManager.FollowMeResult.Started, uas.startFollowMe())
        assertEquals(1, terrain.asked)
    }

    @Test
    fun aTerrainSampleOfZeroMetresIsAReference() = runBlocking {
        val uas = connectedManager(StubTerrain(0.0)) { fix(restored = false, hasAltitude = false) }
        assertEquals(UASManager.FollowMeResult.Started, uas.startFollowMe())
    }

    @Test
    fun pursueRefusesWhenThereIsNoTerrainAndNoOperatorFix() = runBlocking {
        val terrain = StubTerrain(null)
        val uas = connectedManager(terrain) { null }

        val result = uas.startPursueContact("uid-1", "BRAVO") { contact }

        assertEquals(UASManager.FollowMeResult.NoAltitudeReference, result)
        assertFalse(uas.followMeActive.value)
        assertEquals(FollowHealth.Idle, uas.followHealth.value)
    }

    @Test
    fun pursueEngagesOnTheTerrainUnderTheContact() = runBlocking {
        val uas = connectedManager(StubTerrain(300.0)) { null }

        val result = uas.startPursueContact("uid-1", "BRAVO") { contact }

        assertEquals(UASManager.FollowMeResult.Started, result)
        assertEquals(UASManager.FollowSubject.Contact("uid-1", "BRAVO"), uas.followingWhat.value)
        assertEquals(FollowHealth.Streaming, uas.followHealth.value)
    }

    @Test
    fun pursueFallsBackToTheOperatorsAltitude() = runBlocking {
        val terrain = StubTerrain(null)
        val uas = connectedManager(terrain) { fix(restored = false, hasAltitude = true) }

        assertEquals(
            UASManager.FollowMeResult.Started,
            uas.startPursueContact("uid-1", "BRAVO") { contact },
        )
        assertEquals(0, terrain.asked)
    }

    @Test
    fun aRestoredFixAltitudeIsNotAReferenceForPursue() = runBlocking {
        // Last session's altitude, from wherever the operator was then.
        val uas = connectedManager(StubTerrain(null)) { fix(restored = true, hasAltitude = true) }

        val result = uas.startPursueContact("uid-1", "BRAVO") { contact }

        assertEquals(UASManager.FollowMeResult.NoAltitudeReference, result)
    }

    @Test
    fun pursueWithNoContactPositionIsNoGpsFix() = runBlocking {
        val uas = connectedManager(StubTerrain(300.0)) { fix(restored = false) }
        assertEquals(UASManager.FollowMeResult.NoGpsFix, uas.startPursueContact("uid-1", "BRAVO") { null })
    }

    // ---------------------------------------- #249: the order of the checks

    @Test
    fun aDeadLinkIsReportedBeforeTheAltitudeCheckAndCostsNoLookup() = runBlocking {
        val terrain = StubTerrain(null)
        val uas = UASManager(
            sendCoT = { true },
            terrain = terrain,
            operatorFix = { fix(restored = false, hasAltitude = false) },
        )

        assertEquals(UASManager.FollowMeResult.NotConnected, uas.startFollowMe())
        assertEquals(0, terrain.asked)
    }

    @Test
    fun anUnsupportedAutopilotIsReportedBeforeTheAltitudeCheck() = runBlocking {
        val terrain = StubTerrain(null)
        val uas = connectedManager(terrain, linkTo(MavAutopilot.MAV_AUTOPILOT_GENERIC)) {
            fix(restored = false, hasAltitude = false)
        }

        val result = uas.startFollowMe()

        assertEquals(
            UASManager.FollowMeResult.AutopilotNotSupported(MavAutopilot.MAV_AUTOPILOT_GENERIC.name),
            result,
        )
        assertEquals(0, terrain.asked)
    }

    // ------------------------------------------------ #249: follow health

    @Test
    fun stoppingPutsEveryPieceOfFollowStateBack() = runBlocking {
        val uas = connectedManager(StubTerrain(600.0)) { fix(restored = false) }
        assertEquals(UASManager.FollowMeResult.Started, uas.startFollowMe())

        uas.stopFollowMe()

        assertFalse(uas.followMeActive.value)
        assertNull(uas.followingWhat.value)
        assertEquals(FollowHealth.Idle, uas.followHealth.value)
    }

    @Test
    fun disconnectingPutsEveryPieceOfFollowStateBack() = runBlocking {
        val uas = connectedManager(StubTerrain(600.0)) { fix(restored = false) }
        assertEquals(UASManager.FollowMeResult.Started, uas.startFollowMe())

        uas.disconnect()

        assertFalse(uas.followMeActive.value)
        assertNull(uas.followingWhat.value)
        assertEquals(FollowHealth.Idle, uas.followHealth.value)
    }

    @Test
    fun anEmergencyStopEndsFollowing() = runBlocking {
        val uas = connectedManager(StubTerrain(600.0)) { fix(restored = false) }
        assertEquals(UASManager.FollowMeResult.Started, uas.startFollowMe())

        uas.emergencyStop()

        assertFalse(uas.followMeActive.value)
        assertEquals(FollowHealth.Idle, uas.followHealth.value)
    }

    @Test
    fun acknowledgingLeavesAnyOtherHealthAlone() = runBlocking {
        val uas = connectedManager(StubTerrain(600.0)) { fix(restored = false) }
        uas.acknowledgeFollowStop()
        assertEquals(FollowHealth.Idle, uas.followHealth.value)

        assertEquals(UASManager.FollowMeResult.Started, uas.startFollowMe())
        uas.acknowledgeFollowStop()
        assertEquals(FollowHealth.Streaming, uas.followHealth.value)
    }

    @Test
    fun aRefusedEngageLeavesTheHealthAsItWas() = runBlocking {
        val uas = connectedManager(StubTerrain(null)) { fix(restored = false, hasAltitude = false) }
        assertEquals(UASManager.FollowMeResult.NoAltitudeReference, uas.startFollowMe())
        assertEquals(FollowHealth.Idle, uas.followHealth.value)
    }
}
