package soy.engindearing.omnitak.mobile.data

/**
 * What the attached radio last told us about its own settings, kept as the
 * raw bytes it sent.
 *
 * Why raw bytes: the firmware replaces a whole config (or channel, or owner)
 * with whatever a settings write carries. A write built from the few fields
 * this app knows about therefore resets every other field to its default:
 * region, hop limit and transmit switch on a preset change, the channel key
 * on a rename, a licensed operator's flag on an owner rename. The only safe
 * write is the radio's own message with one field changed, so the message is
 * kept whole and edited with [ProtoFields].
 *
 * Entries, by [Key]:
 *  - a `Config` variant by its oneof field number: only the three the app
 *    patches (device 1, position 2, lora 6), as the variant's own message
 *    without the Config wrapper;
 *  - a channel slot by index: the whole Channel message;
 *  - the radio's own User record.
 *
 * Filled from the config download (`FromRadio.config`, `FromRadio.channel`,
 * and the `FromRadio.node_info` that carries our own node number) and from the
 * admin responses to `get_*_request`. Emptied when the link drops and when a
 * new download starts, so a write can never start from another session's, or
 * another radio's, settings. It holds what the radio said and nothing else:
 * what the app sent is not put here as if the radio had accepted it. A write
 * asks the radio for the entry it is about to patch (removing the old copy
 * first) and takes the answer, so what it patches is current.
 *
 * Only what the app patches is kept. Everything else in the download is
 * dropped as it arrives, the security config (the radio's private key) and the
 * network config (its Wi-Fi password) among it: nothing in the app writes them,
 * so they have no reason to sit in memory. A channel does hold its key, because
 * a rename has to carry it back unchanged. [toString] shows counts, never
 * contents.
 *
 * Pure Kotlin, safe to call from any thread.
 */
class RadioSettingsCache {

    /** What an entry is. */
    sealed interface Key {
        /** A Config variant by its oneof field number: device, position or lora. The value is that variant's own message. */
        data class Config(val variant: Int) : Key

        /** A channel slot, 0 to 7. The value is the whole Channel message. */
        data class Channel(val index: Int) : Key

        /** The radio's own User record. */
        data object Owner : Key
    }

    private val lock = Any()
    private val entries = HashMap<Key, ByteArray>()

    /** The stored bytes (a copy), or null when the radio has not told us. */
    fun get(key: Key): ByteArray? = synchronized(lock) { entries[key]?.copyOf() }

    fun config(variant: Int): ByteArray? = get(Key.Config(variant))

    fun channel(index: Int): ByteArray? = get(Key.Channel(index))

    fun owner(): ByteArray? = get(Key.Owner)

    /**
     * Store [bytes] under [key], or ignore them when they are not a well-formed message or when [key] is a
     * Config variant the app does not patch. Returns whether they were stored.
     */
    fun put(key: Key, bytes: ByteArray): Boolean {
        if (key is Key.Config && key.variant !in PATCHED_CONFIGS) return false
        if (ProtoFields.parse(bytes) == null) return false
        synchronized(lock) { entries[key] = bytes.copyOf() }
        return true
    }

    /** Forget one entry, so the next one that arrives is known to be newer than the moment of this call. */
    fun remove(key: Key) {
        synchronized(lock) { entries.remove(key) }
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    val size: Int get() = synchronized(lock) { entries.size }

    /**
     * Take what one frame from the radio has to say. Safe to call with every
     * frame: anything that is not a settings frame is ignored.
     *
     * [myNodeNum] is the node number the radio reported in `my_info`. A
     * NodeInfo only counts when it is ours, and an admin response only when it
     * claims to come from our own node: the radio forwards any packet addressed
     * to us to the phone, so another node could send an admin message that
     * looks like a settings response, and the next write would carry it back to
     * the radio.
     */
    fun onFromRadio(frame: FromRadioFrame, myNodeNum: UInt?) {
        when (frame) {
            // The first frame of every config download: what we hold is from the last one.
            is FromRadioFrame.MyInfo -> clear()
            is FromRadioFrame.ConfigFrame -> ingestConfig(frame.raw)
            is FromRadioFrame.ChannelFrame -> ingestChannel(frame.raw)
            is FromRadioFrame.NodeInfoFrame -> {
                val user = frame.userRaw
                if (user != null && myNodeNum != null && frame.node.id == (myNodeNum.toLong() and 0xFFFFFFFFL)) {
                    put(Key.Owner, user)
                }
            }
            is FromRadioFrame.Packet -> {
                val packet = frame.packet
                if (packet.portnum == PORTNUM_ADMIN_APP && myNodeNum != null && packet.from == myNodeNum) {
                    ingestAdminMessage(packet.payload)
                }
            }
            is FromRadioFrame.ConfigComplete, FromRadioFrame.Unknown -> Unit
        }
    }

    /**
     * A `Config` message (from `FromRadio.config` or an admin `get_config_response`): store its variant if the
     * app patches it. Returns whether anything was stored.
     */
    fun ingestConfig(config: ByteArray): Boolean {
        val fields = ProtoFields.parse(config) ?: return false
        var stored = false
        for (field in fields) {
            val variant = field.bytes() ?: continue
            if (put(Key.Config(field.number), variant)) stored = true
        }
        return stored
    }

    /** A `Channel` message (from `FromRadio.channel` or an admin `get_channel_response`): store it under its own index. */
    fun ingestChannel(channel: ByteArray): Boolean {
        val fields = ProtoFields.parse(channel) ?: return false
        // Channel.index is a proto3 int32: slot 0 is not on the wire at all.
        val index = ProtoFields.lastVarint(fields, CHANNEL_INDEX) ?: 0uL
        if (index >= MAX_CHANNELS.toULong()) return false
        return put(Key.Channel(index.toInt()), channel)
    }

    /** An `AdminMessage` payload: store whichever of its three `get_*_response` variants it carries. */
    fun ingestAdminMessage(admin: ByteArray): Boolean {
        val fields = ProtoFields.parse(admin) ?: return false
        var stored = false
        for (field in fields) {
            val inner = field.bytes() ?: continue
            when (field.number) {
                ADMIN_GET_CHANNEL_RESPONSE -> if (ingestChannel(inner)) stored = true
                ADMIN_GET_OWNER_RESPONSE -> if (put(Key.Owner, inner)) stored = true
                ADMIN_GET_CONFIG_RESPONSE -> if (ingestConfig(inner)) stored = true
            }
        }
        return stored
    }

    /** Counts and keys only, never contents. Safe to log. */
    override fun toString(): String = synchronized(lock) {
        val configs = entries.keys.filterIsInstance<Key.Config>().map { it.variant }.sorted()
        val channels = entries.keys.filterIsInstance<Key.Channel>().map { it.index }.sorted()
        "RadioSettingsCache(configs=$configs, channels=$channels, owner=${Key.Owner in entries})"
    }

    companion object {
        /** Config oneof field numbers (config.proto). */
        const val CONFIG_DEVICE = 1
        const val CONFIG_POSITION = 2
        const val CONFIG_LORA = 6

        /** The only Config variants the cache keeps: the ones the app patches. */
        private val PATCHED_CONFIGS = setOf(CONFIG_DEVICE, CONFIG_POSITION, CONFIG_LORA)

        /** Firmware slot count; a Channel index past it is not a real channel. */
        const val MAX_CHANNELS = 8

        private const val CHANNEL_INDEX = 1 // Channel.index

        // AdminMessage (admin.proto): the three responses that carry a settings message.
        private const val ADMIN_GET_CHANNEL_RESPONSE = 2
        private const val ADMIN_GET_OWNER_RESPONSE = 4
        private const val ADMIN_GET_CONFIG_RESPONSE = 6

        private const val PORTNUM_ADMIN_APP = 6u
    }
}
