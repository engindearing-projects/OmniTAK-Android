package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SentLedger]: what was sent to a radio, judged by that radio's next report.
 *
 * A radio can take a frame and keep its own value (managed, a deprecated role
 * turned into CLIENT, a refused name), and nothing in the write shows it.
 * Node numbers and values are made up.
 */
class SentLedgerTest {

    private val node = 0x0A0B0C0Du
    private val otherNode = 0x01020304u

    private val roleReport = { role: MeshRole? -> AdminResponse.DeviceConfig(role, RebroadcastMode.ALL) }

    @Test fun `a report that matches what was sent is no problem, and is judged once`() {
        val ledger = SentLedger()
        ledger.expect(node, AdminSetting.ROLE, MeshRole.TAK)

        assertEquals(emptyList<AdminSetting>(), ledger.check(node, roleReport(MeshRole.TAK)))
        assertEquals("the entry was dropped with the verdict", 0, ledger.size)
        assertEquals(emptyList<AdminSetting>(), ledger.check(node, roleReport(MeshRole.CLIENT)))
    }

    @Test fun `a report that differs from what was sent names the setting the radio kept`() {
        val ledger = SentLedger()
        ledger.expect(node, AdminSetting.ROLE, MeshRole.TAK)
        ledger.expect(node, AdminSetting.POSITION_INTERVAL, 300)

        assertEquals(listOf(AdminSetting.ROLE), ledger.check(node, roleReport(MeshRole.CLIENT)))
        assertEquals("the interval is judged by the position report, not this one", 1, ledger.size)
        assertEquals(listOf(AdminSetting.POSITION_INTERVAL), ledger.check(node, AdminResponse.PositionConfig(900)))
    }

    @Test fun `a value the app has no name for counts as not taken`() {
        val ledger = SentLedger()
        ledger.expect(node, AdminSetting.ROLE, MeshRole.REPEATER)
        assertEquals(listOf(AdminSetting.ROLE), ledger.check(node, roleReport(null)))
    }

    @Test fun `each setting is judged by the report that covers it`() {
        val ledger = SentLedger()
        ledger.expect(node, AdminSetting.LONG_NAME, "New Long")
        ledger.expect(node, AdminSetting.SHORT_NAME, "NEW")
        ledger.expect(node, AdminSetting.MODEM_PRESET, MeshChannelPreset.MEDIUM_FAST)
        ledger.expect(node, AdminSetting.REGION, MeshRegion.EU_868)
        ledger.expect(node, AdminSetting.CHANNEL_NAME, "ops")
        ledger.expect(node, AdminSetting.REBROADCAST_MODE, RebroadcastMode.KNOWN_ONLY)

        assertEquals(listOf(AdminSetting.SHORT_NAME), ledger.check(node, AdminResponse.Owner("New Long", "OLD")))
        assertEquals(listOf(AdminSetting.REGION), ledger.check(node, AdminResponse.LoraConfig(MeshChannelPreset.MEDIUM_FAST, MeshRegion.US)))
        assertEquals(emptyList<AdminSetting>(), ledger.check(node, AdminResponse.Channel(0, "ops", 1)))
        assertEquals(listOf(AdminSetting.REBROADCAST_MODE), ledger.check(node, AdminResponse.DeviceConfig(MeshRole.TAK, RebroadcastMode.ALL)))
        assertEquals(0, ledger.size)
    }

    @Test fun `only the primary channel's report judges the channel name`() {
        val ledger = SentLedger()
        ledger.expect(node, AdminSetting.CHANNEL_NAME, "ops")
        assertEquals(emptyList<AdminSetting>(), ledger.check(node, AdminResponse.Channel(3, "other", 2)))
        assertEquals("still waiting for slot 0", 1, ledger.size)
        assertEquals(listOf(AdminSetting.CHANNEL_NAME), ledger.check(node, AdminResponse.Channel(0, "", 1)))
    }

    @Test fun `a report from another radio judges nothing`() {
        val ledger = SentLedger()
        ledger.expect(node, AdminSetting.ROLE, MeshRole.TAK)
        assertEquals(emptyList<AdminSetting>(), ledger.check(otherNode, roleReport(MeshRole.CLIENT)))
        assertEquals(1, ledger.size)
        assertEquals(listOf(AdminSetting.ROLE), ledger.check(node, roleReport(MeshRole.CLIENT)))
    }

    @Test fun `a later write replaces the earlier expectation for the same setting`() {
        val ledger = SentLedger()
        ledger.expect(node, AdminSetting.POSITION_INTERVAL, 300)
        ledger.expect(node, AdminSetting.POSITION_INTERVAL, 60)
        assertEquals(emptyList<AdminSetting>(), ledger.check(node, AdminResponse.PositionConfig(60)))
    }

    @Test fun `an expectation the radio never reports on expires instead of blaming a later change`() {
        var now = 0L
        val ledger = SentLedger(clock = { now }, maxAgeMs = 10 * 60_000L)
        ledger.expect(node, AdminSetting.ROLE, MeshRole.TAK)

        now = 11 * 60_000L // eleven minutes on, another client has changed the role
        assertEquals(emptyList<AdminSetting>(), ledger.check(node, roleReport(MeshRole.ROUTER)))
        assertEquals(0, ledger.size)
    }

    @Test fun `clear forgets everything`() {
        val ledger = SentLedger()
        ledger.expect(node, AdminSetting.ROLE, MeshRole.TAK)
        ledger.clear()
        assertTrue(ledger.check(node, roleReport(MeshRole.CLIENT)).isEmpty())
    }

    @Test fun `forget drops what was sent to one radio and leaves the others alone`() {
        val ledger = SentLedger()
        val a = 0x0A0B0C0Du
        val b = 0x01020304u
        ledger.expect(a, AdminSetting.ROLE, MeshRole.TAK)
        ledger.expect(a, AdminSetting.POSITION_INTERVAL, 120)
        ledger.expect(b, AdminSetting.ROLE, MeshRole.TAK)

        ledger.forget(a)

        assertEquals("only the other radio's entry is left", 1, ledger.size)
        assertEquals("the forgotten write is not judged", emptyList<AdminSetting>(), ledger.check(a, AdminResponse.DeviceConfig(MeshRole.CLIENT, RebroadcastMode.ALL)))
        assertEquals("the other radio's still is", listOf(AdminSetting.ROLE), ledger.check(b, AdminResponse.DeviceConfig(MeshRole.CLIENT, RebroadcastMode.ALL)))
    }
}
