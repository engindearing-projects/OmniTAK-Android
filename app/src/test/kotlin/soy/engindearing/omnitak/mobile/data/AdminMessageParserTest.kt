package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Unit tests for [AdminMessageParser] — proves the role ordinal carried
 * on Channel responses lets the consumer distinguish DISABLED from
 * PRIMARY from SECONDARY (GAP-123). All other admin response decodes
 * exercise their own paths in [MeshtasticProtoParserTest].
 */
class AdminMessageParserTest {

    @Test fun channel_role_primary_named() {
        val bytes = adminWithChannel(index = 0, settingsName = "OmniTAK", role = 1)
        val parsed = AdminMessageParser.parse(bytes)
        assertTrue(parsed is AdminResponse.Channel)
        val channel = parsed as AdminResponse.Channel
        assertEquals(0, channel.index)
        assertEquals("OmniTAK", channel.name)
        assertTrue(channel.isPrimary)
        assertFalse(channel.isDisabled)
        assertFalse(channel.isSecondary)
    }

    @Test fun channel_role_secondary_named() {
        val bytes = adminWithChannel(index = 2, settingsName = "Local", role = 2)
        val parsed = AdminMessageParser.parse(bytes) as AdminResponse.Channel
        assertEquals(2, parsed.index)
        assertEquals("Local", parsed.name)
        assertFalse(parsed.isPrimary)
        assertTrue(parsed.isSecondary)
        assertFalse(parsed.isDisabled)
    }

    @Test fun channel_role_disabled_filtered_by_consumer() {
        val bytes = adminWithChannel(index = 5, settingsName = "", role = 0)
        val parsed = AdminMessageParser.parse(bytes) as AdminResponse.Channel
        assertEquals(5, parsed.index)
        assertTrue(parsed.isDisabled)
        assertFalse(parsed.isPrimary)
        assertFalse(parsed.isSecondary)
    }

    /** Build an AdminMessage byte buffer carrying a single
     *  `get_channel_response` (field 2) with the given Channel fields.
     *  Format mirrors canonical Meshtastic firmware admin.proto. */
    private fun adminWithChannel(index: Int, settingsName: String, role: Int): ByteArray {
        // ChannelSettings { 3=name string }
        val settings = ByteArrayOutputStream().apply {
            if (settingsName.isNotEmpty()) {
                write(0x1A) // (3<<3)|2
                writeVarint(this, settingsName.length.toLong())
                write(settingsName.toByteArray(Charsets.UTF_8))
            }
        }.toByteArray()
        // Channel { 1=index varint, 2=settings sub, 3=role varint }
        val channel = ByteArrayOutputStream().apply {
            write(0x08) // (1<<3)|0
            writeVarint(this, index.toLong())
            write(0x12) // (2<<3)|2
            writeVarint(this, settings.size.toLong())
            write(settings)
            write(0x18) // (3<<3)|0
            writeVarint(this, role.toLong())
        }.toByteArray()
        // AdminMessage { 2=get_channel_response sub }
        return ByteArrayOutputStream().apply {
            write(0x12) // (2<<3)|2
            writeVarint(this, channel.size.toLong())
            write(channel)
        }.toByteArray()
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while ((v and 0x7FL.inv()) != 0L) {
            out.write(((v and 0x7FL) or 0x80L).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7FL).toInt())
    }
}
