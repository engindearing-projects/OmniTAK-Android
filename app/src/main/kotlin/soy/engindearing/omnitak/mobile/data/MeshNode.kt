package soy.engindearing.omnitak.mobile.data

/**
 * A single node on a Meshtastic mesh. Mirrors the iOS MeshNode
 * model — node id is the 32-bit radio address (rendered as lowercase
 * hex for display). Position is optional (some nodes never send one).
 *
 * This slice ships the data model + TCP client + bridge to CoT, but
 * **no protobuf decoding** yet — getting protobuf into the build is a
 * follow-up (requires adding the meshtastic .proto set + a codegen
 * step). For now, nodes get populated via manual test injection and
 * the TCP client streams raw bytes so we can verify the transport.
 */
data class MeshNode(
    val id: Long,
    val shortName: String,
    val longName: String,
    val position: MeshPosition? = null,
    val lastHeardEpoch: Long,
    val snr: Double? = null,
    val hopDistance: Int? = null,
    val batteryLevel: Int? = null,
    /** Meshtastic device role (`User.role`, config.proto enum value), or
     *  null when the NodeInfo didn't carry one. */
    val role: Int? = null,
) {
    val idHex: String get() = "%08x".format(id.toInt())

    /** Role `TAK` — a radio paired to a phone running a TAK client. Its
     *  position duplicates the operator's own PLI, so the map can hide it
     *  (field feedback: two mismatched dots per person). */
    val isTakPaired: Boolean get() = role == ROLE_TAK

    companion object {
        /** meshtastic config.proto Config.DeviceConfig.Role values we care about. */
        const val ROLE_TAK = 7
        const val ROLE_TAK_TRACKER = 10
    }
}

data class MeshPosition(
    val lat: Double,
    val lon: Double,
    val altitudeM: Int? = null,
)

enum class MeshConnectionType { BLUETOOTH, TCP }

data class MeshtasticDevice(
    val id: String,
    val name: String,
    val connectionType: MeshConnectionType,
    val devicePath: String,
    val isConnected: Boolean,
    val snr: Double? = null,
    val hopCount: Int? = null,
    val batteryLevel: Int? = null,
)
