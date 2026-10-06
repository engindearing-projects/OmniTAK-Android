package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #261 - the start-up decision for the Meshtastic TCP link.
 *
 * It looks at three things, and the first block spells out every combination:
 * whether the operator wants the link up, whether a host is saved, and whether
 * the last transport was TCP (Meshtastic selected) or something else (MeshCore
 * selected). A Bluetooth pick has no framework of its own, since Bluetooth is a
 * tab of the Meshtastic screen; it is recorded by clearing the wanted flag, which
 * [UserPrefsMeshTcpTest] covers.
 */
class MeshTcpRestoreTest {

    private fun prefs(
        wanted: Boolean,
        host: String,
        framework: MeshFramework,
        port: Int = 4403,
    ) = UserPrefs(
        meshTcpWanted = wanted,
        meshTcpHost = host,
        meshTcpPort = port,
        selectedMeshFramework = framework,
    )

    private fun decide(wanted: Boolean, host: String, framework: MeshFramework) =
        MeshTcpRestore.targetAtStart(prefs(wanted, host, framework))

    private val saved = "127.0.0.1"
    private val unsaved = ""

    // region every combination of wanted, host saved, last transport

    @Test fun `wanted with a host saved and TCP last brings the link back`() {
        assertEquals(
            MeshTcpTarget("127.0.0.1", 4403),
            decide(wanted = true, host = saved, framework = MeshFramework.MESHTASTIC),
        )
    }

    @Test fun `wanted with a host saved and MeshCore last dials nothing`() {
        assertNull(decide(wanted = true, host = saved, framework = MeshFramework.MESHCORE))
    }

    @Test fun `wanted with no host saved and TCP last dials nothing`() {
        assertNull(decide(wanted = true, host = unsaved, framework = MeshFramework.MESHTASTIC))
    }

    @Test fun `wanted with no host saved and MeshCore last dials nothing`() {
        assertNull(decide(wanted = true, host = unsaved, framework = MeshFramework.MESHCORE))
    }

    @Test fun `not wanted with a host saved and TCP last dials nothing`() {
        assertNull(decide(wanted = false, host = saved, framework = MeshFramework.MESHTASTIC))
    }

    @Test fun `not wanted with a host saved and MeshCore last dials nothing`() {
        assertNull(decide(wanted = false, host = saved, framework = MeshFramework.MESHCORE))
    }

    @Test fun `not wanted with no host saved and TCP last dials nothing`() {
        assertNull(decide(wanted = false, host = unsaved, framework = MeshFramework.MESHTASTIC))
    }

    @Test fun `not wanted with no host saved and MeshCore last dials nothing`() {
        assertNull(decide(wanted = false, host = unsaved, framework = MeshFramework.MESHCORE))
    }

    // endregion

    @Test fun `a fresh install never dials the built-in address`() {
        // Nothing saved: the pane shows 192.168.1.100 but nobody asked for a link.
        assertNull(MeshTcpRestore.targetAtStart(UserPrefs()))
    }

    @Test fun `the saved port is the one dialed`() {
        assertEquals(
            MeshTcpTarget("10.1.2.3", 14403),
            MeshTcpRestore.targetAtStart(prefs(true, "10.1.2.3", MeshFramework.MESHTASTIC, port = 14403)),
        )
    }

    @Test fun `the host is trimmed before it is dialed`() {
        assertEquals(
            MeshTcpTarget("10.1.2.3", 4403),
            decide(wanted = true, host = "  10.1.2.3 ", framework = MeshFramework.MESHTASTIC),
        )
    }

    @Test fun `a host of only spaces counts as not saved`() {
        assertNull(decide(wanted = true, host = "   ", framework = MeshFramework.MESHTASTIC))
    }

    @Test fun `the lowest and highest real ports are dialed`() {
        assertEquals(
            MeshTcpTarget("h", 1),
            MeshTcpRestore.targetAtStart(prefs(true, "h", MeshFramework.MESHTASTIC, port = 1)),
        )
        assertEquals(
            MeshTcpTarget("h", 65535),
            MeshTcpRestore.targetAtStart(prefs(true, "h", MeshFramework.MESHTASTIC, port = 65535)),
        )
    }

    @Test fun `a port outside 1 to 65535 dials nothing`() {
        for (bad in listOf(0, -1, 65536, 99999)) {
            assertNull(
                "port $bad must not be dialed",
                MeshTcpRestore.targetAtStart(prefs(true, "h", MeshFramework.MESHTASTIC, port = bad)),
            )
        }
    }
}
