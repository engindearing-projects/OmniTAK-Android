package soy.engindearing.omnitak.mobile.data.offline

/**
 * MBTiles row addressing. An MBTiles `tiles` table is keyed by
 * (zoom_level, tile_column, tile_row) where tile_row is in TMS order
 * (origin bottom-left, y grows north). The map requests XYZ (origin
 * top-left, y grows south), so a Y flip bridges the two.
 *
 * This is the exact identity [soy.engindearing.omnitak.mobile.data.MBTilesDb]
 * applies on read; the offline writer applies it on write so tiles persisted
 * here are served back correctly by the existing read path.
 */
object MbtilesRow {
    /** XYZ y -> MBTiles TMS tile_row at zoom [z]. */
    fun xyzToTms(z: Int, y: Int): Int = (1 shl z) - 1 - y
    /** MBTiles TMS tile_row -> XYZ y at zoom [z] (same formula, self-inverse). */
    fun tmsToXyz(z: Int, tmsRow: Int): Int = (1 shl z) - 1 - tmsRow
}

/**
 * Write-side storage for raw tile blobs, keyed exactly like the MBTiles
 * `tiles` table: (zoom_level, tile_column, tile_row[TMS]). The on-device
 * implementation ([OfflineMbtilesWriter]) backs this with a writable
 * SQLite MBTiles file; tests back it with an in-memory map to verify the
 * key/flip logic without an Android SQLite runtime.
 */
interface TileSink {
    fun put(z: Int, column: Int, tmsRow: Int, data: ByteArray)
    fun get(z: Int, column: Int, tmsRow: Int): ByteArray? = null
}

/**
 * Bridges XYZ tile coordinates (what the downloader fetches and the map
 * requests) to MBTiles TMS rows (what the [TileSink] stores). Pure logic —
 * no Android dependency — so the round-trip is unit-testable.
 */
class TileCacheWriter(private val sink: TileSink) {

    /** Persist an XYZ tile's bytes, flipping Y to the MBTiles TMS row. */
    fun writeXyz(tile: Tile, data: ByteArray) {
        sink.put(tile.z, tile.x, MbtilesRow.xyzToTms(tile.z, tile.y), data)
    }

    /** Read back an XYZ tile's bytes (mirrors the write flip). */
    fun readXyz(tile: Tile): ByteArray? =
        sink.get(tile.z, tile.x, MbtilesRow.xyzToTms(tile.z, tile.y))
}
