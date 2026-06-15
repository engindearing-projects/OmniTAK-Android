package soy.engindearing.omnitak.mobile.data.offline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cache key + write→read round-trip for the offline tile store (#120).
 *
 * Downloaded regions are persisted as standard MBTiles SQLite files so they
 * reuse the existing [soy.engindearing.omnitak.mobile.data.MBTilesDb] read
 * path + [soy.engindearing.omnitak.mobile.data.MBTilesServer] HTTP serve.
 * MBTiles stores rows in TMS order (y grows north), while the map requests
 * XYZ (y grows south) — so the write path must flip Y to TMS exactly as
 * MBTilesDb flips it back on read.
 *
 * SQLite itself is an Android API (no JVM impl in this module's test
 * config), so these tests exercise the *key derivation + flip + round-trip*
 * over an in-memory [TileSink] that stores the same (z, column, tmsRow)
 * tuple the SQLite rows hold. The real SQLite-backed writer is verified on
 * device (see [OfflineMbtilesWriter]).
 */
class TileStoreRoundTripTest {

    /** In-memory stand-in keyed exactly like the MBTiles `tiles` table. */
    private class MemSink : TileSink {
        data class Row(val z: Int, val col: Int, val tmsRow: Int)
        val rows = HashMap<Row, ByteArray>()
        override fun put(z: Int, column: Int, tmsRow: Int, data: ByteArray) {
            rows[Row(z, column, tmsRow)] = data
        }
        override fun get(z: Int, column: Int, tmsRow: Int): ByteArray? = rows[Row(z, column, tmsRow)]
        fun rawGet(z: Int, column: Int, tmsRow: Int): ByteArray? = rows[Row(z, column, tmsRow)]
    }

    @Test fun xyz_y_is_flipped_to_tms_on_write() {
        // The TMS row for an XYZ tile is (2^z - 1 - y) — the same identity
        // MBTilesDb uses on read. Verify the writer stores under that row.
        val sink = MemSink()
        val w = TileCacheWriter(sink)
        val payload = byteArrayOf(1, 2, 3)
        w.writeXyz(Tile(z = 4, x = 5, y = 6), payload)

        val expectedTmsRow = (1 shl 4) - 1 - 6 // = 9
        assertArrayEquals(payload, sink.rawGet(4, 5, expectedTmsRow))
        // and NOT stored under the un-flipped row
        assertNull(sink.rawGet(4, 5, 6))
    }

    @Test fun write_then_read_back_same_bytes_via_xyz() {
        // End-to-end at the XYZ layer: what we write at (z,x,y) is exactly
        // what an XYZ read at (z,x,y) returns — proving the flip is its own
        // inverse across write+read.
        val sink = MemSink()
        val w = TileCacheWriter(sink)
        val a = "tileA".toByteArray()
        val b = "tileB".toByteArray()
        w.writeXyz(Tile(10, 163, 395), a)
        w.writeXyz(Tile(10, 163, 396), b)

        assertArrayEquals(a, w.readXyz(Tile(10, 163, 395)))
        assertArrayEquals(b, w.readXyz(Tile(10, 163, 396)))
        assertNull(w.readXyz(Tile(10, 163, 999))) // never written
    }

    @Test fun top_and_bottom_rows_round_trip_at_low_zoom() {
        // The flip is most error-prone at the extremes. At z1 (2x2 grid):
        // XYZ y=0 (north) -> TMS row 1; XYZ y=1 (south) -> TMS row 0.
        val sink = MemSink()
        val w = TileCacheWriter(sink)
        w.writeXyz(Tile(1, 0, 0), byteArrayOf(0))
        w.writeXyz(Tile(1, 0, 1), byteArrayOf(1))
        assertArrayEquals(byteArrayOf(1), sink.rawGet(1, 0, 0)) // south -> row 0
        assertArrayEquals(byteArrayOf(0), sink.rawGet(1, 0, 1)) // north -> row 1
        assertArrayEquals(byteArrayOf(0), w.readXyz(Tile(1, 0, 0)))
        assertArrayEquals(byteArrayOf(1), w.readXyz(Tile(1, 0, 1)))
    }

    @Test fun written_count_tracks_unique_tiles() {
        val sink = MemSink()
        val w = TileCacheWriter(sink)
        w.writeXyz(Tile(8, 1, 1), byteArrayOf(1))
        w.writeXyz(Tile(8, 1, 2), byteArrayOf(2))
        w.writeXyz(Tile(8, 1, 1), byteArrayOf(9)) // overwrite same coord
        assertEquals(2, sink.rows.size)
    }

    @Test fun tms_flip_is_self_inverse_for_arbitrary_tiles() {
        // Property: flip(flip(y)) == y for every valid y at a zoom.
        for (z in 0..18) {
            val n = 1 shl z
            for (y in intArrayOf(0, 1, n / 2, n - 2, n - 1).filter { it in 0 until n }) {
                val tms = MbtilesRow.xyzToTms(z, y)
                assertEquals(y, MbtilesRow.tmsToXyz(z, tms))
                assertTrue(tms in 0 until n)
            }
        }
    }
}
