package soy.engindearing.omnitak.mobile.data

/** Where a restored Meshtastic TCP link dials. */
data class MeshTcpTarget(val host: String, val port: Int)

/**
 * #261 - whether the Meshtastic TCP link comes back when the app starts, and
 * where it dials. Pure Kotlin (no Android imports) so the rule is unit-testable
 * on the JVM; [soy.engindearing.omnitak.mobile.OmniTAKApp] makes the call, once
 * per process start.
 *
 * The link is restored only when all of these hold:
 *  - [UserPrefs.meshTcpWanted] is set: the operator's last link choice was the
 *    TCP gateway. Connect sets it. Disconnect clears it, and so does picking a
 *    Bluetooth or MeshCore radio. A link that drops while the app runs leaves it
 *    set.
 *  - a host is saved and the port is a real one.
 *  - Meshtastic is still the selected framework.
 *
 * The app does not record which tab of the Meshtastic screen (TCP or BLE) was
 * last in use, so the TCP flag above is the only record of the Meshtastic
 * transport. The selected framework is persisted, and when it is MeshCore
 * nothing is dialed whatever the flag says. When neither record says TCP,
 * nothing is dialed.
 */
object MeshTcpRestore {

    private const val MIN_PORT = 1
    private const val MAX_PORT = 65_535

    /** The gateway to dial at app start, or null when the link stays down. */
    fun targetAtStart(prefs: UserPrefs): MeshTcpTarget? {
        if (!prefs.meshTcpWanted) return null
        if (prefs.selectedMeshFramework != MeshFramework.MESHTASTIC) return null
        val host = prefs.meshTcpHost.trim()
        if (host.isEmpty()) return null
        if (prefs.meshTcpPort !in MIN_PORT..MAX_PORT) return null
        return MeshTcpTarget(host, prefs.meshTcpPort)
    }
}
