package soy.engindearing.omnitak.mobile.data

/**
 * A single node on a Meshtastic mesh. Mirrors the iOS MeshNode
 * model — node id is the 32-bit radio address (rendered as lowercase
 * hex for display). Position is optional (some nodes never send one).
 *
 * Populated from decoded FromRadio frames ([MeshtasticProtoParser])
 * arriving over the BLE or TCP transport.
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
) {
    val idHex: String get() = "%08x".format(id.toInt())
}

data class MeshPosition(
    val lat: Double,
    val lon: Double,
    val altitudeM: Int? = null,
)

enum class MeshConnectionType { BLUETOOTH, TCP }
