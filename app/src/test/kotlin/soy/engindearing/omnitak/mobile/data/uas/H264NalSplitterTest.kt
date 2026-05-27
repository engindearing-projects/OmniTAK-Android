package soy.engindearing.omnitak.mobile.data.uas

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class H264NalSplitterTest {
    private fun b(vararg ints: Int): ByteArray = ByteArray(ints.size) { ints[it].toByte() }

    @Test fun `single complete nal followed by start code emits one nal`() {
        val splitter = H264NalSplitter()
        // [sc, A, B, sc, C] — first NAL closes when second sc arrives
        val out = splitter.push(b(0x00, 0x00, 0x01, 0xAA, 0xBB, 0x00, 0x00, 0x01, 0xCC))
        assertEquals(1, out.size)
        assertArrayEquals(b(0xAA, 0xBB), out[0])
    }

    @Test fun `incomplete first nal emits nothing`() {
        val splitter = H264NalSplitter()
        val out = splitter.push(b(0x00, 0x00, 0x01, 0xAA, 0xBB))
        assertTrue(out.isEmpty())
    }

    @Test fun `nal split across two pushes emits on the second`() {
        val splitter = H264NalSplitter()
        assertTrue(splitter.push(b(0x00, 0x00, 0x01, 0xAA, 0xBB)).isEmpty())
        val out = splitter.push(b(0xCC, 0x00, 0x00, 0x01, 0xDD))
        assertEquals(1, out.size)
        assertArrayEquals(b(0xAA, 0xBB, 0xCC), out[0])
    }

    @Test fun `start code straddling chunk boundary is detected`() {
        val splitter = H264NalSplitter()
        // First chunk ends mid-start-code; second chunk completes it.
        // Combined stream: [sc, AA, sc, BB, sc, CC] — AA and BB
        // complete after push2; CC is still in-progress.
        assertTrue(splitter.push(b(0x00, 0x00, 0x01, 0xAA, 0x00, 0x00)).isEmpty())
        val out = splitter.push(b(0x01, 0xBB, 0x00, 0x00, 0x01, 0xCC))
        assertEquals(2, out.size)
        assertArrayEquals(b(0xAA), out[0])
        assertArrayEquals(b(0xBB), out[1])
    }

    @Test fun `four byte start codes recognized as single start code`() {
        val splitter = H264NalSplitter()
        // 4-byte sc, N1, 4-byte sc, N2, 3-byte sc (closing N2)
        val out = splitter.push(
            b(
                0x00, 0x00, 0x00, 0x01, 0x11,
                0x00, 0x00, 0x00, 0x01, 0x22,
                0x00, 0x00, 0x01, 0x33,
            ),
        )
        assertEquals(2, out.size)
        assertArrayEquals(b(0x11), out[0])
        assertArrayEquals(b(0x22), out[1])
    }

    @Test fun `garbage before first start code is discarded`() {
        val splitter = H264NalSplitter()
        val out = splitter.push(b(0xFF, 0xFE, 0xFD, 0x00, 0x00, 0x01, 0xAA, 0x00, 0x00, 0x01, 0xBB))
        assertEquals(1, out.size)
        assertArrayEquals(b(0xAA), out[0])
    }

    @Test fun `many small chunks one byte at a time still produce correct nals`() {
        val splitter = H264NalSplitter()
        val full = b(
            0x00, 0x00, 0x01, 0xAA, 0xBB,
            0x00, 0x00, 0x01, 0xCC, 0xDD, 0xEE,
            0x00, 0x00, 0x01, 0xFF,
        )
        val collected = mutableListOf<ByteArray>()
        for (i in full.indices) {
            collected += splitter.push(full, i, 1)
        }
        assertEquals(2, collected.size)
        assertArrayEquals(b(0xAA, 0xBB), collected[0])
        assertArrayEquals(b(0xCC, 0xDD, 0xEE), collected[1])
    }

    @Test fun `reset clears state`() {
        val splitter = H264NalSplitter()
        splitter.push(b(0x00, 0x00, 0x01, 0xAA))
        splitter.reset()
        val out = splitter.push(b(0xBB, 0x00, 0x00, 0x01, 0xCC, 0x00, 0x00, 0x01))
        // Bytes before the first post-reset start code are discarded.
        assertEquals(1, out.size)
        assertArrayEquals(b(0xCC), out[0])
    }

    @Test fun `trailing zero before start code is treated as 4 byte sc not nal payload`() {
        val splitter = H264NalSplitter()
        // [sc, A, B, 00, 00, 00, 01, C, sc] — the 0x00 before the
        // canonical 00 00 01 is cabac padding, not part of NAL1.
        // NAL1 should emit as just [A, B] (no trailing 0).
        val out = splitter.push(
            b(0x00, 0x00, 0x01, 0xAA, 0xBB, 0x00, 0x00, 0x00, 0x01, 0xCC, 0x00, 0x00, 0x01),
        )
        assertEquals(2, out.size)
        assertArrayEquals(b(0xAA, 0xBB), out[0])
        assertArrayEquals(b(0xCC), out[1])
    }
}
