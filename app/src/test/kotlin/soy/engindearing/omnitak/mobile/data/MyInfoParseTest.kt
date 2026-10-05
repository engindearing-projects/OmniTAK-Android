package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** `FromRadio.my_info`: the node number and `reboot_count` (field 8), which only ESP32 firmware keeps. */
class MyInfoParseTest {

    @Test fun `the node number and the reboot count are read`() {
        val frame = AdminTestFrames.myInfoFrame(0x0A0B0C0D, rebootCount = 42)
        assertEquals(FromRadioFrame.MyInfo(0x0A0B0C0Du, 42u), MeshtasticProtoParser.parseFromRadio(frame))
    }

    @Test fun `a radio that does not count restarts leaves the count off the wire, which reads as 0`() {
        val frame = AdminTestFrames.myInfoFrame(0x0A0B0C0D)
        assertEquals(FromRadioFrame.MyInfo(0x0A0B0C0Du, 0u), MeshtasticProtoParser.parseFromRadio(frame))
    }

    @Test fun `other fields of my_info are skipped, whichever side of the count they are on`() {
        val info = ProtoMsg()
            .varint(1, 0x0A0B0C0DL)
            .varint(5, 7) // an unknown varint before the count
            .varint(8, 9)
            .varint(11, 30200) // min_app_version
            .bytes(12, byteArrayOf(1, 2, 3)) // device_id
            .varint(15, 1) // nodedb_count
        assertEquals(
            FromRadioFrame.MyInfo(0x0A0B0C0Du, 9u),
            MeshtasticProtoParser.parseFromRadio(ProtoMsg().msg(3, info).build()),
        )
    }

    @Test fun `a count above the int range is still a count`() {
        val frame = AdminTestFrames.myInfoFrame(1, rebootCount = -1) // 0xFFFFFFFF
        assertEquals(FromRadioFrame.MyInfo(1u, 0xFFFFFFFFu), MeshtasticProtoParser.parseFromRadio(frame))
    }
}
