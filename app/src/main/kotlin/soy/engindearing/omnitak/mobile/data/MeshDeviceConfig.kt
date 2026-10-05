package soy.engindearing.omnitak.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
 * LoRa region — mirrors the Meshtastic firmware
 * `Config_LoRaConfig_RegionCode` enum (config.proto). [wire] is the
 * proto-enum number that travels in `LoRaConfig.region` (field 7).
 *
 * Region sets the legal frequency band + duty cycle for the radio; it
 * MUST be set before a fresh radio will transmit. This is the headline
 * "make the stock app obsolete" knob — #181. Labels are operator-facing
 * (the picker shows these, not the SCREAMING_SNAKE wire names).
 *
 * Numbers are pinned to the proto, not the Kotlin declaration order, so
 * the list can be reordered for the UI without breaking the wire format.
 */
enum class MeshRegion(val wire: Int, val label: String) {
    UNSET(0, "Unset"),
    US(1, "United States"),
    EU_433(2, "EU 433 MHz"),
    EU_868(3, "EU 868 MHz"),
    CN(4, "China"),
    JP(5, "Japan"),
    ANZ(6, "Australia / NZ"),
    KR(7, "Korea"),
    TW(8, "Taiwan"),
    RU(9, "Russia"),
    IN(10, "India"),
    NZ_865(11, "New Zealand 865 MHz"),
    TH(12, "Thailand"),
    LORA_24(13, "2.4 GHz (WLAN band)"),
    UA_433(14, "Ukraine 433 MHz"),
    UA_868(15, "Ukraine 868 MHz"),
    MY_433(16, "Malaysia 433 MHz"),
    MY_919(17, "Malaysia 919 MHz"),
    SG_923(18, "Singapore 923 MHz"),
    PH_433(19, "Philippines 433 MHz"),
    PH_868(20, "Philippines 868 MHz"),
    PH_915(21, "Philippines 915 MHz"),
    ANZ_433(22, "Australia / NZ 433 MHz"),
    KZ_433(23, "Kazakhstan 433 MHz"),
    KZ_863(24, "Kazakhstan 863 MHz"),
    NP_865(25, "Nepal 865 MHz"),
    BR_902(26, "Brazil 902 MHz"),
    ;

    companion object {
        fun fromWire(wire: Int): MeshRegion? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * Rebroadcast scope — mirrors the Meshtastic firmware
 * `Config_DeviceConfig_RebroadcastMode` enum (config.proto). [wire] is the
 * proto-enum ordinal that travels in `DeviceConfig.rebroadcast_mode`.
 *
 * PatoG1899's "rebroadcast only known channels" request maps to
 * [KNOWN_ONLY]; [LOCAL_ONLY] is the slightly looser variant that still
 * suppresses foreign-mesh traffic.
 */
enum class RebroadcastMode(val wire: Int, val label: String, val blurb: String) {
    ALL(0, "All", "Rebroadcast every packet the radio hears (default)."),
    LOCAL_ONLY(2, "Local only", "Drop packets from foreign meshes; relay local ones."),
    KNOWN_ONLY(3, "Known channels only", "Only relay packets on channels this radio has configured."),
    NONE(4, "None", "Never rebroadcast — receive only."),
}

/**
 * The draft the Device Settings screen edits: the operator's intent for a
 * Meshtastic radio's user-configurable settings.
 *
 * The draft is not a description of the radio and is never written to it as a
 * whole. Its values start as fresh-install defaults (or whatever an earlier
 * radio left), so only the settings the operator changed away from what the
 * connected radio reported are sent (see [DeviceSettingsState]).
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
 * The Device Settings screen's state: the draft the operator edits (kept
 * across launches in DataStore) and what the connected radio last reported
 * (in memory only, null when nothing is loaded or the link is down). The rules
 * that tie the two together, which settings count as edited and how a report
 * moves the draft, live in [DeviceSettingsState] and are tested there.
 *
 * Updates are applied to the in-memory state immediately and in the order they
 * arrive; only the draft is written to disk, afterwards.
 */
class MeshDeviceConfigStore(private val context: Context) {
    private val KEY_LONG_NAME = stringPreferencesKey("device_long_name")
    private val KEY_SHORT_NAME = stringPreferencesKey("device_short_name")
    private val KEY_ROLE = stringPreferencesKey("device_role")
    private val KEY_PLI = intPreferencesKey("device_pli_secs")
    private val KEY_CH_NAME = stringPreferencesKey("device_ch0_name")
    private val KEY_CH_PRESET = stringPreferencesKey("device_ch0_preset")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(DeviceSettingsState())

    /** The draft and what the radio reported. */
    val state: StateFlow<DeviceSettingsState> = _state.asStateFlow()

    /** Just the draft. */
    val config: Flow<MeshDeviceConfig> = _state.map { it.draft }.distinctUntilChanged()

    init {
        // The saved draft is where the screen starts, until a radio reports: a report that gets here first wins.
        scope.launch {
            val saved = readDraft(context.meshDeviceConfigDataStore.data.first())
            _state.update { if (it.radio == null && it.draft == MeshDeviceConfig()) it.copy(draft = saved) else it }
        }
    }

    /**
     * GAP-109 read-back: fold one report from the connected radio into the state. Only the radio's own
     * reports may come here (the manager drops admin messages from any other node). A setting the
     * operator has edited keeps the operator's value; every other one follows the radio.
     */
    fun applyAdminResponse(response: AdminResponse) {
        _state.update { it.withReport(response) }
        persistLater()
    }

    /** The link dropped: what the radio reported no longer holds, and nothing is edited until the next report. */
    fun onLinkDown() {
        _state.update { it.withLinkDown() }
    }

    suspend fun update(block: (MeshDeviceConfig) -> MeshDeviceConfig) {
        _state.update { current ->
            val next = block(current.draft)
            current.copy(draft = next.copy(positionBroadcastSecs = next.positionBroadcastSecs.coerceIn(0, 24 * 60 * 60)))
        }
        persist()
    }

    private var lastPersisted: MeshDeviceConfig? = null

    private fun persistLater() {
        scope.launch { persist() }
    }

    private suspend fun persist() {
        val draft = _state.value.draft
        if (draft == lastPersisted) return
        lastPersisted = draft
        context.meshDeviceConfigDataStore.edit { p ->
            p[KEY_LONG_NAME] = draft.longName
            p[KEY_SHORT_NAME] = draft.shortName
            p[KEY_ROLE] = draft.role.name
            p[KEY_PLI] = draft.positionBroadcastSecs.coerceIn(0, 24 * 60 * 60)
            p[KEY_CH_NAME] = draft.channelName
            p[KEY_CH_PRESET] = draft.channelPreset.name
        }
    }

    private fun readDraft(p: Preferences) = MeshDeviceConfig(
        longName = p[KEY_LONG_NAME] ?: "OmniTAK",
        shortName = p[KEY_SHORT_NAME] ?: "OTK",
        role = p[KEY_ROLE]?.let { runCatching { MeshRole.valueOf(it) }.getOrNull() } ?: MeshRole.TAK,
        positionBroadcastSecs = p[KEY_PLI] ?: 30,
        channelName = p[KEY_CH_NAME] ?: "OmniTAK",
        channelPreset = p[KEY_CH_PRESET]?.let { runCatching { MeshChannelPreset.valueOf(it) }.getOrNull() }
            ?: MeshChannelPreset.LONG_FAST,
    )
}
