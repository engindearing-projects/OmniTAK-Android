package soy.engindearing.omnitak.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.meshDeviceConfigDataStore by preferencesDataStore(name = "mesh_device_config")

/**
 * Operator role on the mesh. Mirrors the Meshtastic firmware
 * `Config_DeviceConfig_Role` enum so the eventual admin-protobuf
 * write path can map 1:1 by ordinal name.
 *
 * Practitioner-facing labels live in [MeshRole.label] — the dropdown
 * surfaces those, not the SCREAMING_SNAKE wire constants.
 */
enum class MeshRole(val label: String, val description: String) {
    CLIENT("Client", "Standard handheld. Routes for the mesh."),
    CLIENT_MUTE("Client Mute", "Receives only — never rebroadcasts. Quiet handheld."),
    ROUTER("Router", "Dedicated repeater. No UI presence, just routes."),
    ROUTER_CLIENT("Router Client", "Routes and acts as a client. Most flexible."),
    REPEATER("Repeater", "Pure repeater, no telemetry, lowest overhead."),
    TRACKER("Tracker", "Broadcasts position frequently, sleeps between."),
    SENSOR("Sensor", "Periodic telemetry only. No mesh routing."),
    TAK("TAK", "TAK-tuned defaults — what you usually want with OmniTAK."),
    CLIENT_HIDDEN("Client Hidden", "Client that doesn't appear in nodelist."),
    LOST_AND_FOUND("Lost & Found", "Beacons location for recovery."),
    TAK_TRACKER("TAK Tracker", "TAK-tuned tracker. Position-heavy, no chat."),
}

/**
 * Channel preset — these match the Meshtastic firmware's named PSK
 * presets. `DEFAULT` is the public channel everyone shares; `LONG_FAST`
 * etc. correspond to the LoRa modem profile presets.
 *
 * For OmniTAK we treat this as the headline modem-profile knob — it's
 * the single setting that decides range vs throughput tradeoff. The
 * actual PSK bytes are derived from this name on the device.
 */
enum class MeshChannelPreset(val label: String, val blurb: String) {
    LONG_FAST("Long Fast", "Default. Balanced range and throughput."),
    LONG_SLOW("Long Slow", "Maximum range, very slow."),
    VERY_LONG_SLOW("Very Long Slow", "Extreme range, painfully slow. Last resort."),
    MEDIUM_SLOW("Medium Slow", "Mid range, slow."),
    MEDIUM_FAST("Medium Fast", "Mid range, faster."),
    SHORT_SLOW("Short Slow", "Short range, slow."),
    SHORT_FAST("Short Fast", "Short range, fastest. Crowded events."),
    SHORT_TURBO("Short Turbo", "Highest throughput, very short range."),
}

/**
 * Local-draft of a Meshtastic radio's user-configurable settings. We
 * persist what the operator *intends* the device config to be — this
 * is the edit buffer the Device Settings screen mutates.
 *
 * **Write-to-device is not yet wired.** The Meshtastic admin protocol
 * round-trip (ToRadio AdminMessage with set_owner / set_config) needs
 * the protobuf set in the Gradle build first. Until that lands this
 * config is operator-intent only; the screen surfaces a clear
 * "device sync coming soon" affordance per-section.
 *
 * Fields chosen to match the four headline asks from the 80-node
 * airsoft practitioner: long/short name, role, PLI cadence, primary
 * channel name + preset.
 */
data class MeshDeviceConfig(
    val longName: String = "OmniTAK",
    val shortName: String = "OTK",
    val role: MeshRole = MeshRole.TAK,
    /** Position broadcast interval in seconds. 0 disables PLI broadcasts. */
    val positionBroadcastSecs: Int = 30,
    val channelName: String = "OmniTAK",
    val channelPreset: MeshChannelPreset = MeshChannelPreset.LONG_FAST,
)

/**
 * DataStore-backed persistence for [MeshDeviceConfig]. Same shape as
 * [UserPrefsStore] — Flow for reads, suspend `update {}` for writes,
 * enums round-trip by name.
 */
class MeshDeviceConfigStore(private val context: Context) {
    private val KEY_LONG_NAME = stringPreferencesKey("device_long_name")
    private val KEY_SHORT_NAME = stringPreferencesKey("device_short_name")
    private val KEY_ROLE = stringPreferencesKey("device_role")
    private val KEY_PLI = intPreferencesKey("device_pli_secs")
    private val KEY_CH_NAME = stringPreferencesKey("device_ch0_name")
    private val KEY_CH_PRESET = stringPreferencesKey("device_ch0_preset")

    val config: Flow<MeshDeviceConfig> = context.meshDeviceConfigDataStore.data.map { p ->
        MeshDeviceConfig(
            longName = p[KEY_LONG_NAME] ?: "OmniTAK",
            shortName = p[KEY_SHORT_NAME] ?: "OTK",
            role = p[KEY_ROLE]?.let { runCatching { MeshRole.valueOf(it) }.getOrNull() }
                ?: MeshRole.TAK,
            positionBroadcastSecs = p[KEY_PLI] ?: 30,
            channelName = p[KEY_CH_NAME] ?: "OmniTAK",
            channelPreset = p[KEY_CH_PRESET]?.let { runCatching { MeshChannelPreset.valueOf(it) }.getOrNull() }
                ?: MeshChannelPreset.LONG_FAST,
        )
    }

    suspend fun update(block: (MeshDeviceConfig) -> MeshDeviceConfig) {
        context.meshDeviceConfigDataStore.edit { p ->
            val current = MeshDeviceConfig(
                longName = p[KEY_LONG_NAME] ?: "OmniTAK",
                shortName = p[KEY_SHORT_NAME] ?: "OTK",
                role = p[KEY_ROLE]?.let { runCatching { MeshRole.valueOf(it) }.getOrNull() }
                    ?: MeshRole.TAK,
                positionBroadcastSecs = p[KEY_PLI] ?: 30,
                channelName = p[KEY_CH_NAME] ?: "OmniTAK",
                channelPreset = p[KEY_CH_PRESET]?.let { runCatching { MeshChannelPreset.valueOf(it) }.getOrNull() }
                    ?: MeshChannelPreset.LONG_FAST,
            )
            val next = block(current)
            p[KEY_LONG_NAME] = next.longName
            p[KEY_SHORT_NAME] = next.shortName
            p[KEY_ROLE] = next.role.name
            p[KEY_PLI] = next.positionBroadcastSecs.coerceIn(0, 24 * 60 * 60)
            p[KEY_CH_NAME] = next.channelName
            p[KEY_CH_PRESET] = next.channelPreset.name
        }
    }
}
