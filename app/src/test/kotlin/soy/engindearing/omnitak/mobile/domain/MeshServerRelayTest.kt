package soy.engindearing.omnitak.mobile.domain

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.CoTSource
import soy.engindearing.omnitak.mobile.data.MeshFramework
import soy.engindearing.omnitak.mobile.data.UserPrefs
import soy.engindearing.omnitak.mobile.domain.MeshServerRelay.RelayDirections
import soy.engindearing.omnitak.mobile.domain.MeshServerRelay.RelayInputs
import soy.engindearing.omnitak.mobile.domain.MeshServerRelay.RelayTarget

/**
 * #179 — exhaustive coverage of the PURE relay decision [MeshServerRelay.relayTarget]
 * and the per-uid dedup/throttle gate [MeshServerRelay.admitForward]:
 *  - mesh-origin → server
 *  - server-origin → mesh
 *  - loop block both ways (never relayed back onto its own transport)
 *  - disabled → none
 *  - one-transport-down → none
 *  - type filtering server→mesh (only meaningful types ride LoRa)
 *  - dedup (mesh→server ping-pong) + throttle (server→mesh airtime)
 *
 * #212: each direction has its own switch ([RelayInputs.toServerEnabled] /
 * [RelayInputs.toMeshEnabled]), and to-mesh is forced off while MeshCore is
 * the active mesh ([RelayDirections.effective]).
 */
class MeshServerRelayTest {

    private fun event(uid: String = "U1", type: String = "a-f-G-U-C"): CoTEvent =
        CoTEvent(uid = uid, type = type, lat = 1.0, lon = 2.0)

    private fun inputs(
        source: CoTSource?,
        type: String = "a-f-G-U-C",
        serverConnected: Boolean = true,
        meshConnected: Boolean = true,
        toServerEnabled: Boolean = true,
        toMeshEnabled: Boolean = true,
    ) = RelayInputs(
        event = event(type = type),
        source = source,
        serverConnected = serverConnected,
        meshConnected = meshConnected,
        toServerEnabled = toServerEnabled,
        toMeshEnabled = toMeshEnabled,
    )

    // region direction --------------------------------------------------------

    @Test
    fun `mesh origin relays to server`() {
        assertEquals(
            RelayTarget.TO_SERVER,
            MeshServerRelay.relayTarget(inputs(CoTSource.mesh("Meshtastic"))),
        )
    }

    @Test
    fun `server origin relays to mesh`() {
        assertEquals(
            RelayTarget.TO_MESH,
            MeshServerRelay.relayTarget(inputs(CoTSource.takServer("HQ"))),
        )
    }

    // region loop block -------------------------------------------------------

    @Test
    fun `mesh origin never goes back to mesh`() {
        // The ONLY non-NONE result for a mesh-origin event is TO_SERVER.
        val target = MeshServerRelay.relayTarget(inputs(CoTSource.mesh("MeshCore")))
        assertTrue(target == RelayTarget.TO_SERVER)
        assertFalse(target == RelayTarget.TO_MESH)
    }

    @Test
    fun `server origin never goes back to server`() {
        val target = MeshServerRelay.relayTarget(inputs(CoTSource.takServer("HQ")))
        assertTrue(target == RelayTarget.TO_MESH)
        assertFalse(target == RelayTarget.TO_SERVER)
    }

    // region gating -----------------------------------------------------------

    @Test
    fun `both directions off relays nothing`() {
        assertEquals(
            RelayTarget.NONE,
            MeshServerRelay.relayTarget(
                inputs(CoTSource.mesh("Meshtastic"), toServerEnabled = false, toMeshEnabled = false),
            ),
        )
        assertEquals(
            RelayTarget.NONE,
            MeshServerRelay.relayTarget(
                inputs(CoTSource.takServer("HQ"), toServerEnabled = false, toMeshEnabled = false),
            ),
        )
    }

    @Test
    fun `server down relays nothing even for mesh origin`() {
        assertEquals(
            RelayTarget.NONE,
            MeshServerRelay.relayTarget(inputs(CoTSource.mesh("Meshtastic"), serverConnected = false)),
        )
    }

    @Test
    fun `mesh down relays nothing even for server origin`() {
        assertEquals(
            RelayTarget.NONE,
            MeshServerRelay.relayTarget(inputs(CoTSource.takServer("HQ"), meshConnected = false)),
        )
    }

    @Test
    fun `both down relays nothing`() {
        assertEquals(
            RelayTarget.NONE,
            MeshServerRelay.relayTarget(
                inputs(CoTSource.mesh("Meshtastic"), serverConnected = false, meshConnected = false),
            ),
        )
    }

    // region per-direction switches (#212) -----------------------------------

    @Test
    fun `to-server off blocks mesh origin even with to-mesh on`() {
        assertEquals(
            RelayTarget.NONE,
            MeshServerRelay.relayTarget(
                inputs(CoTSource.mesh("Meshtastic"), toServerEnabled = false, toMeshEnabled = true),
            ),
        )
    }

    @Test
    fun `to-mesh off blocks server origin even with to-server on`() {
        assertEquals(
            RelayTarget.NONE,
            MeshServerRelay.relayTarget(
                inputs(CoTSource.takServer("HQ"), toServerEnabled = true, toMeshEnabled = false),
            ),
        )
    }

    @Test
    fun `to-mesh off still relays mesh origin up to the server`() {
        assertEquals(
            RelayTarget.TO_SERVER,
            MeshServerRelay.relayTarget(
                inputs(CoTSource.mesh("Meshtastic"), toServerEnabled = true, toMeshEnabled = false),
            ),
        )
    }

    @Test
    fun `to-server off still relays server origin down to the mesh`() {
        assertEquals(
            RelayTarget.TO_MESH,
            MeshServerRelay.relayTarget(
                inputs(CoTSource.takServer("HQ"), toServerEnabled = false, toMeshEnabled = true),
            ),
        )
    }

    @Test
    fun `each direction is gated only by its own switch`() {
        for (toServer in listOf(false, true)) for (toMesh in listOf(false, true)) {
            val fromMesh = MeshServerRelay.relayTarget(
                inputs(CoTSource.mesh("Meshtastic"), toServerEnabled = toServer, toMeshEnabled = toMesh),
            )
            val fromServer = MeshServerRelay.relayTarget(
                inputs(CoTSource.takServer("HQ"), toServerEnabled = toServer, toMeshEnabled = toMesh),
            )
            assertEquals(
                "mesh origin, toServer=$toServer toMesh=$toMesh",
                if (toServer) RelayTarget.TO_SERVER else RelayTarget.NONE,
                fromMesh,
            )
            assertEquals(
                "server origin, toServer=$toServer toMesh=$toMesh",
                if (toMesh) RelayTarget.TO_MESH else RelayTarget.NONE,
                fromServer,
            )
        }
    }

    @Test
    fun `local untagged and other origins stay NONE whatever the switches say`() {
        for (source in listOf(CoTSource.LOCAL, null, CoTSource(CoTSource.Transport.OTHER, "x"))) {
            assertEquals(
                "source=$source",
                RelayTarget.NONE,
                MeshServerRelay.relayTarget(
                    inputs(source, toServerEnabled = true, toMeshEnabled = true),
                ),
            )
        }
    }

    // region source classification -------------------------------------------

    @Test
    fun `local origin relays nothing`() {
        assertEquals(
            RelayTarget.NONE,
            MeshServerRelay.relayTarget(inputs(CoTSource.LOCAL)),
        )
    }

    @Test
    fun `untagged origin relays nothing (loop-safe default)`() {
        assertEquals(
            RelayTarget.NONE,
            MeshServerRelay.relayTarget(inputs(null)),
        )
    }

    @Test
    fun `other transport relays nothing`() {
        assertEquals(
            RelayTarget.NONE,
            MeshServerRelay.relayTarget(inputs(CoTSource(CoTSource.Transport.OTHER, "x"))),
        )
    }

    // region server->mesh type filter ----------------------------------------

    @Test
    fun `meaningful server types ride down to mesh`() {
        assertTrue(MeshServerRelay.isRelayableToMesh("a-f-G-U-C")) // PLI
        assertTrue(MeshServerRelay.isRelayableToMesh("a-h-G"))     // hostile marker
        assertTrue(MeshServerRelay.isRelayableToMesh("a-u-G"))     // unknown marker
        assertTrue(MeshServerRelay.isRelayableToMesh("b-t-f"))     // GeoChat
        assertTrue(MeshServerRelay.isRelayableToMesh("b-m-p-w"))   // waypoint
    }

    @Test
    fun `chatter server types do not ride down to mesh`() {
        assertFalse(MeshServerRelay.isRelayableToMesh("t-x-d-d"))  // delete tasking
        assertFalse(MeshServerRelay.isRelayableToMesh("t-x-c-t"))  // ping
        assertFalse(MeshServerRelay.isRelayableToMesh("b-i-x-i"))  // sensor/image
        assertFalse(MeshServerRelay.isRelayableToMesh("b-r-f-h-c")) // medevac request etc
    }

    @Test
    fun `server origin with chatter type relays nothing`() {
        assertEquals(
            RelayTarget.NONE,
            MeshServerRelay.relayTarget(inputs(CoTSource.takServer("HQ"), type = "t-x-d-d")),
        )
    }

    @Test
    fun `mesh origin relays to server regardless of type`() {
        // mesh→server is type-agnostic (IP is cheap); even a tasking event flows.
        assertEquals(
            RelayTarget.TO_SERVER,
            MeshServerRelay.relayTarget(inputs(CoTSource.mesh("Meshtastic"), type = "t-x-d-d")),
        )
    }

    // region dedup / throttle gate -------------------------------------------

    private fun newRelay(): MeshServerRelay = MeshServerRelay(
        sendToServer = { true },
        sendToMesh = { true },
        eventToServerXml = { "" },
        serverConnected = { true },
        meshConnected = { true },
        relayDirections = { RelayDirections(toServer = true, toMesh = true) },
    )

    @Test
    fun `server-to-mesh throttles repeated uid inside the window`() {
        val r = newRelay()
        val t0 = 1_000_000L
        assertTrue("first send admitted", r.admitForward("U1", RelayTarget.TO_MESH, t0))
        // 10s later — still inside the 30s LoRa throttle window.
        assertFalse(
            "second send within throttle window blocked",
            r.admitForward("U1", RelayTarget.TO_MESH, t0 + 10_000L),
        )
        // After the window elapses, a fresh send is admitted.
        assertTrue(
            "send after throttle window admitted",
            r.admitForward("U1", RelayTarget.TO_MESH, t0 + MeshServerRelay.serverToMeshThrottleMs + 1L),
        )
    }

    @Test
    fun `mesh-to-server dedup swallows the immediate ping-pong echo`() {
        val r = newRelay()
        val t0 = 2_000_000L
        assertTrue(r.admitForward("U2", RelayTarget.TO_SERVER, t0))
        // The far side re-broadcasts; the echo arrives ~1s later → suppressed.
        assertFalse(
            "echo within dedup window blocked",
            r.admitForward("U2", RelayTarget.TO_SERVER, t0 + 1_000L),
        )
        // Past the small dedup window, a genuine fresh update gets through.
        assertTrue(
            "update past dedup window admitted",
            r.admitForward("U2", RelayTarget.TO_SERVER, t0 + MeshServerRelay.dedupWindowMs + 1L),
        )
    }

    @Test
    fun `dedup is keyed per uid and per direction`() {
        val r = newRelay()
        val t0 = 3_000_000L
        // Same uid going opposite directions does not share a throttle bucket.
        assertTrue(r.admitForward("U3", RelayTarget.TO_MESH, t0))
        assertTrue(r.admitForward("U3", RelayTarget.TO_SERVER, t0))
        // Different uid is independent.
        assertTrue(r.admitForward("U4", RelayTarget.TO_MESH, t0))
        // ...but the same uid+direction repeats are still gated.
        assertFalse(r.admitForward("U3", RelayTarget.TO_MESH, t0 + 1L))
    }

    @Test
    fun `reset clears the throttle state`() {
        val r = newRelay()
        val t0 = 4_000_000L
        assertTrue(r.admitForward("U5", RelayTarget.TO_MESH, t0))
        assertFalse(r.admitForward("U5", RelayTarget.TO_MESH, t0 + 1L))
        r.reset()
        assertTrue("after reset the uid is admitted again", r.admitForward("U5", RelayTarget.TO_MESH, t0 + 2L))
    }

    @Test
    fun `none target is never admitted`() {
        val r = newRelay()
        assertFalse(r.admitForward("U6", RelayTarget.NONE, 5_000_000L))
    }

    // region RelayDirections: MeshCore forces to-mesh off (#212) --------------

    @Test
    fun `MeshCore forces to-mesh off even when the operator asked for it`() {
        assertEquals(
            RelayDirections(toServer = true, toMesh = false),
            RelayDirections.effective(
                toServerEnabled = true,
                toMeshEnabled = true,
                framework = MeshFramework.MESHCORE,
            ),
        )
    }

    @Test
    fun `Meshtastic keeps both directions as asked`() {
        for (toServer in listOf(false, true)) for (toMesh in listOf(false, true)) {
            assertEquals(
                RelayDirections(toServer = toServer, toMesh = toMesh),
                RelayDirections.effective(toServer, toMesh, MeshFramework.MESHTASTIC),
            )
        }
    }

    @Test
    fun `MeshCore leaves the to-server direction alone`() {
        assertEquals(
            RelayDirections(toServer = true, toMesh = false),
            RelayDirections.effective(true, false, MeshFramework.MESHCORE),
        )
        assertEquals(
            RelayDirections(toServer = false, toMesh = false),
            RelayDirections.effective(false, true, MeshFramework.MESHCORE),
        )
    }

    @Test
    fun `only MeshCore cannot take server contacts`() {
        assertTrue(RelayDirections.meshAcceptsServerContacts(MeshFramework.MESHTASTIC))
        assertFalse(RelayDirections.meshAcceptsServerContacts(MeshFramework.MESHCORE))
    }

    @Test
    fun `directions are read from prefs with MeshCore applied`() {
        val prefs = UserPrefs(
            relayToServerEnabled = true,
            relayToMeshEnabled = true,
            selectedMeshFramework = MeshFramework.MESHCORE,
        )
        assertEquals(RelayDirections(toServer = true, toMesh = false), RelayDirections.from(prefs))
        assertEquals(
            RelayDirections(toServer = true, toMesh = true),
            RelayDirections.from(prefs.copy(selectedMeshFramework = MeshFramework.MESHTASTIC)),
        )
        // Defaults: both off.
        assertEquals(RelayDirections.OFF, RelayDirections.from(UserPrefs()))
    }

    @Test
    fun `labels name the active directions`() {
        assertEquals("off", RelayDirections.OFF.label())
        assertEquals("mesh → server", RelayDirections(toServer = true, toMesh = false).label())
        assertEquals("server → mesh", RelayDirections(toServer = false, toMesh = true).label())
        assertEquals(
            "mesh → server and server → mesh",
            RelayDirections(toServer = true, toMesh = true).label(),
        )
    }

    @Test
    fun `status text says what is on and what it is waiting for`() {
        val toServerOnly = RelayDirections(toServer = true, toMesh = false)
        assertEquals("Relaying mesh → server.", toServerOnly.statusText(true, true))
        assertEquals(
            "Set to relay mesh → server. Waiting for a TAK server to connect.",
            toServerOnly.statusText(serverConnected = false, meshConnected = true),
        )
        assertEquals(
            "Set to relay mesh → server. Waiting for a mesh radio to connect.",
            toServerOnly.statusText(serverConnected = true, meshConnected = false),
        )
        assertEquals(
            "Set to relay mesh → server. Waiting for a TAK server and a mesh radio to connect.",
            toServerOnly.statusText(serverConnected = false, meshConnected = false),
        )
        assertEquals(
            "Relaying mesh → server and server → mesh.",
            RelayDirections(toServer = true, toMesh = true).statusText(true, true),
        )
        // Off stays off whatever is connected.
        assertEquals("Relay is off.", RelayDirections.OFF.statusText(true, true))
        assertEquals("Relay is off.", RelayDirections.OFF.statusText(false, false))
    }

    // region onInbound end to end (#212) -------------------------------------

    private class Sent {
        val toServer = mutableListOf<String>()
        val toMesh = mutableListOf<String>()
    }

    private fun relayWith(
        sent: Sent,
        directions: () -> RelayDirections,
    ) = MeshServerRelay(
        sendToServer = { xml -> sent.toServer += xml; true },
        sendToMesh = { e -> sent.toMesh += e.uid; true },
        eventToServerXml = { e -> "<event uid=\"${e.uid}\"/>" },
        serverConnected = { true },
        meshConnected = { true },
        relayDirections = directions,
    )

    @Test
    fun `with MeshCore active a server contact is never written to the mesh`() = runTest {
        // The hazard behind forcing to-mesh off: the only place MeshCore has
        // for a position is the radio's own advert, so a relayed contact
        // written there would move the operator's advertised position.
        // MeshCoreManager refuses it as well (MeshCoreOutboundTest, #234).
        val sent = Sent()
        val relay = relayWith(sent) {
            RelayDirections.effective(
                toServerEnabled = true,
                toMeshEnabled = true,
                framework = MeshFramework.MESHCORE,
            )
        }

        relay.onInbound(event(uid = "SERVER-CONTACT"), CoTSource.takServer("HQ"))
        assertTrue("nothing may reach the MeshCore radio", sent.toMesh.isEmpty())

        // The other direction keeps working.
        relay.onInbound(event(uid = "MESH-NODE"), CoTSource.mesh("MeshCore"))
        assertEquals(listOf("<event uid=\"MESH-NODE\"/>"), sent.toServer)
    }

    @Test
    fun `with Meshtastic active a server contact is written to the mesh`() = runTest {
        val sent = Sent()
        val relay = relayWith(sent) {
            RelayDirections.effective(true, true, MeshFramework.MESHTASTIC)
        }

        relay.onInbound(event(uid = "SERVER-CONTACT"), CoTSource.takServer("HQ"))

        assertEquals(listOf("SERVER-CONTACT"), sent.toMesh)
    }

    @Test
    fun `flipping a direction switch takes effect on the next event`() = runTest {
        val sent = Sent()
        var directions = RelayDirections(toServer = true, toMesh = true)
        val relay = relayWith(sent) { directions }

        relay.onInbound(event(uid = "A"), CoTSource.takServer("HQ"))
        assertEquals(listOf("A"), sent.toMesh)

        // Operator turns server -> mesh off; mesh -> server stays on.
        directions = RelayDirections(toServer = true, toMesh = false)
        relay.onInbound(event(uid = "B"), CoTSource.takServer("HQ"))
        relay.onInbound(event(uid = "C"), CoTSource.mesh("Meshtastic"))

        assertEquals("to-mesh is off now", listOf("A"), sent.toMesh)
        assertEquals(listOf("<event uid=\"C\"/>"), sent.toServer)
    }
}
