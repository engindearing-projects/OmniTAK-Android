package soy.engindearing.omnitak.mobile.data

/**
 * What the app last asked each radio to change, until that radio reports the
 * setting again.
 *
 * A radio can take a frame and still keep its own value: a managed radio
 * ignores local admin, the firmware turns a deprecated role into CLIENT, a
 * name that is only spaces is refused. The write itself shows none of that.
 * The next report from the same radio (the re-read after a push, or the
 * download after it restarts) does, so each write is remembered per node and
 * compared with that report.
 *
 * An entry is judged once, by the first report that covers its setting, and
 * dropped with the verdict. An entry the radio never reports on expires after
 * [maxAgeMs], so a stale one cannot blame a later change by another client.
 *
 * Pure Kotlin, safe to call from any thread.
 */
class SentLedger(
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAgeMs: Long = 10 * 60_000L,
) {
    private data class Key(val node: UInt, val setting: AdminSetting)

    private class Entry(val expected: Any, val at: Long)

    private val pending = HashMap<Key, Entry>()

    /** Remember that [setting] was just sent to [node] with the value [expected] (as it was sent, names already cut). */
    @Synchronized
    fun expect(node: UInt, setting: AdminSetting, expected: Any) {
        pending[Key(node, setting)] = Entry(expected, clock())
    }

    /**
     * Compare a report from [node] with what was sent. Returns the settings the radio kept its own value for,
     * in the order of [AdminSetting]. Every setting the report covers is judged and dropped.
     */
    @Synchronized
    fun check(node: UInt, report: AdminResponse): List<AdminSetting> {
        val now = clock()
        pending.entries.removeAll { now - it.value.at > maxAgeMs }
        val kept = ArrayList<AdminSetting>()

        fun judge(setting: AdminSetting, reported: Any?) {
            val entry = pending.remove(Key(node, setting)) ?: return
            if (reported != entry.expected) kept += setting
        }

        when (report) {
            is AdminResponse.Owner -> {
                judge(AdminSetting.LONG_NAME, report.longName)
                judge(AdminSetting.SHORT_NAME, report.shortName)
            }
            is AdminResponse.DeviceConfig -> {
                judge(AdminSetting.ROLE, report.role)
                judge(AdminSetting.REBROADCAST_MODE, report.rebroadcastMode)
            }
            is AdminResponse.PositionConfig -> judge(AdminSetting.POSITION_INTERVAL, report.broadcastSecs)
            is AdminResponse.LoraConfig -> {
                judge(AdminSetting.MODEM_PRESET, report.preset)
                judge(AdminSetting.REGION, report.region)
            }
            is AdminResponse.Channel -> if (report.index == 0) judge(AdminSetting.CHANNEL_NAME, report.name)
        }
        return kept.sortedBy { it.ordinal }
    }

    /**
     * Forget what was sent to [node] and not judged yet. For a sequence that was cut off by a lost link: its writes
     * are judged by what the next link-up finds ([InterruptedWrite]), and a radio that restarted since would
     * otherwise be blamed here for ignoring them ("It may be managed").
     */
    @Synchronized
    fun forget(node: UInt) {
        pending.keys.removeAll { it.node == node }
    }

    @Synchronized
    fun clear() = pending.clear()

    val size: Int @Synchronized get() = pending.size
}
