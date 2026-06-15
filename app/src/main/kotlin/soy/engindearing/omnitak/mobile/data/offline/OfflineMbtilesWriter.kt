package soy.engindearing.omnitak.mobile.data.offline

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Writable MBTiles file backing a downloaded region (#120). Creates the
 * standard MBTiles 1.3 schema (a `metadata` table + a `tiles` table keyed by
 * zoom_level/tile_column/tile_row in TMS order) and implements [TileSink] so
 * [TileCacheWriter] / [RegionDownloader] can stream tiles straight in.
 *
 * Because the file is a bog-standard MBTiles, the existing read+serve path —
 * [soy.engindearing.omnitak.mobile.data.MBTilesDb] +
 * [soy.engindearing.omnitak.mobile.data.MBTilesServer] — reads it back with
 * no new code: the offline-serve story is "register the file as an MBTiles
 * overlay". That reuse is the whole point of writing MBTiles rather than a
 * bespoke cache format.
 *
 * Device/instrumented-verified: SQLite is an Android API with no JVM impl in
 * this module's unit-test config. The key/flip logic it relies on is covered
 * by [TileStoreRoundTripTest] over an in-memory [TileSink]; this class is the
 * thin SQLite binding around that logic.
 */
class OfflineMbtilesWriter private constructor(
    private val db: SQLiteDatabase,
    val format: String,
) : TileSink, AutoCloseable {

    override fun put(z: Int, column: Int, tmsRow: Int, data: ByteArray) {
        val cv = ContentValues(4).apply {
            put("zoom_level", z)
            put("tile_column", column)
            put("tile_row", tmsRow)
            put("tile_data", data)
        }
        db.insertWithOnConflict("tiles", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun get(z: Int, column: Int, tmsRow: Int): ByteArray? = runCatching {
        db.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
            arrayOf(z.toString(), column.toString(), tmsRow.toString()),
        ).use { c -> if (c.moveToFirst()) c.getBlob(0) else null }
    }.getOrNull()

    /** Number of tiles written so far. */
    fun tileCount(): Int = runCatching {
        db.rawQuery("SELECT COUNT(*) FROM tiles", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }.getOrDefault(0)

    /**
     * Stamp the MBTiles `metadata` table from a finished region so the file
     * is a valid, self-describing MBTiles (and the existing
     * [soy.engindearing.omnitak.mobile.data.MBTilesDb] reader picks up the
     * right zoom range + bounds).
     */
    fun writeMetadata(region: OfflineRegion) {
        fun meta(name: String, value: String) {
            db.insertWithOnConflict(
                "metadata", null,
                ContentValues(2).apply { put("name", name); put("value", value) },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
        meta("name", region.name)
        meta("format", format)
        meta("type", "baselayer")
        meta("version", "1.0")
        meta("minzoom", region.minZoom.toString())
        meta("maxzoom", region.maxZoom.toString())
        // MBTiles bounds are "west,south,east,north" in WGS84.
        meta("bounds", "${region.west},${region.south},${region.east},${region.north}")
        meta("description", "OmniTAK offline region")
    }

    override fun close() {
        runCatching { db.close() }
    }

    companion object {
        /**
         * Create (or open) a writable MBTiles file at [path] and ensure the
         * schema exists. [format] is the tile image type stored ("png" /
         * "jpg") — drives the metadata + the content-type the server returns.
         */
        fun create(path: String, format: String = "png"): OfflineMbtilesWriter {
            File(path).parentFile?.mkdirs()
            val db = SQLiteDatabase.openOrCreateDatabase(path, null)
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS tiles (" +
                    "zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS tile_index ON tiles " +
                    "(zoom_level, tile_column, tile_row)",
            )
            db.execSQL("CREATE TABLE IF NOT EXISTS metadata (name TEXT, value TEXT)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS metadata_name ON metadata (name)")
            return OfflineMbtilesWriter(db, format)
        }
    }
}
