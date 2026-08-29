package soy.engindearing.omnitak.mobile.data.offline

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Download orchestration for an offline region (#120): enumerate the tiles,
 * fetch each from the active source template, write into the cache, report
 * progress, and stop on cancel / skip tiles already cached.
 *
 * The real network fetch is an injected suspend lambda (device-pending — it
 * hits the tile server over HTTP). Here it's faked so the *orchestration*
 * (planning, progress accounting, concurrency bound, cancel, resume-skip) is
 * exercised as plain JVM logic.
 */
class RegionDownloaderTest {

    private fun bbox() = BoundingBox(north = 47.62, south = 47.60, east = -122.32, west = -122.34)

    /**
     * A sink that records every written XYZ-equivalent tile.
     *
     * ConcurrentHashMap, not HashMap: [RegionDownloader] fetches behind a
     * semaphore from `launch(Dispatchers.IO)`, so `put` genuinely arrives on
     * several threads at once. A plain HashMap loses writes under that — the
     * symptom is this test intermittently reporting one fewer tile than it
     * downloaded, which reads like a tile-math bug and is not one. The
     * production sink writes through SQLiteDatabase, which serializes
     * internally, so only the fake was ever unsafe.
     */
    private class CountingSink : TileSink {
        val store = java.util.concurrent.ConcurrentHashMap<Triple<Int, Int, Int>, ByteArray>()
        override fun put(z: Int, column: Int, tmsRow: Int, data: ByteArray) {
            store[Triple(z, column, tmsRow)] = data
        }
        override fun get(z: Int, column: Int, tmsRow: Int): ByteArray? = store[Triple(z, column, tmsRow)]
    }

    @Test fun downloads_every_tile_in_region_and_writes_to_cache() = runTest {
        val sink = CountingSink()
        val template = "https://tiles.test/{z}/{x}/{y}.png"
        val expected = TileMath.tileCount(bbox(), 12, 14)
        val fetched = AtomicInteger(0)

        val downloader = RegionDownloader(
            sink = sink,
            fetch = { _ -> fetched.incrementAndGet(); byteArrayOf(0x1) },
        )
        val result = downloader.download(
            bbox = bbox(), minZoom = 12, maxZoom = 14, template = template,
        )

        assertEquals(expected, result.total)
        assertEquals(expected, result.downloaded.toLong())
        assertEquals(0, result.skipped)
        assertEquals(0, result.failed)
        assertEquals(expected.toInt(), fetched.get())
        assertEquals(expected.toInt(), sink.store.size)
        assertTrue(result.completed)
    }

    @Test fun progress_is_reported_monotonically_up_to_total() = runTest {
        val seen = ArrayList<Int>()
        val downloader = RegionDownloader(
            sink = CountingSink(),
            fetch = { _ -> byteArrayOf(1) },
            concurrency = 1,
        )
        val result = downloader.download(
            bbox = bbox(), minZoom = 12, maxZoom = 13, template = "https://t/{z}/{x}/{y}",
            onProgress = { done, _ -> seen.add(done) },
        )
        assertFalse(seen.isEmpty())
        // non-decreasing, last == total (every tile processed exactly once)
        for (i in 1 until seen.size) assertTrue(seen[i] >= seen[i - 1])
        assertEquals(result.total.toInt(), seen.last())
        assertEquals(result.total.toInt(), result.processed)
    }

    @Test fun cancel_stops_before_finishing() = runTest {
        val sink = CountingSink()
        val total = TileMath.tileCount(bbox(), 12, 15)
        assertTrue("region must be large enough to observe a partial stop", total > 4)

        lateinit var downloader: RegionDownloader
        val fetched = AtomicInteger(0)
        downloader = RegionDownloader(
            sink = sink,
            fetch = { _ ->
                // cancel partway through
                if (fetched.incrementAndGet() == 2) downloader.cancel()
                byteArrayOf(1)
            },
            concurrency = 1,
        )
        val result = downloader.download(
            bbox = bbox(), minZoom = 12, maxZoom = 15, template = "https://t/{z}/{x}/{y}",
        )
        assertFalse("cancelled run must not report completed", result.completed)
        assertTrue("must not have processed the whole region", result.processed < result.total)
    }

    @Test fun already_cached_tiles_are_skipped_on_resume() = runTest {
        val sink = CountingSink()
        // Pre-seed the cache with the whole region as if a prior run finished.
        val tiles = TileMath.enumerateTiles(bbox(), 12, 13)
        val writer = TileCacheWriter(sink)
        tiles.forEach { writer.writeXyz(it, byteArrayOf(7)) }

        val fetched = AtomicInteger(0)
        val downloader = RegionDownloader(
            sink = sink,
            fetch = { _ -> fetched.incrementAndGet(); byteArrayOf(0x2) },
            skipExisting = true,
        )
        val result = downloader.download(
            bbox = bbox(), minZoom = 12, maxZoom = 13, template = "https://t/{z}/{x}/{y}",
        )
        assertEquals("nothing should be re-fetched", 0, fetched.get())
        assertEquals(tiles.size.toLong(), result.total)
        // all tiles already present -> all skipped, none freshly downloaded,
        // but progress still reaches 100% (processed == total).
        assertEquals(0, result.downloaded)
        assertEquals(tiles.size, result.skipped)
        assertEquals(tiles.size, result.processed)
        assertTrue(result.completed)
    }

    @Test fun fetch_failures_are_counted_not_fatal() = runTest {
        val sink = CountingSink()
        val downloader = RegionDownloader(
            sink = sink,
            fetch = { _ -> null }, // every fetch fails (e.g. 404 / offline)
        )
        val result = downloader.download(
            bbox = bbox(), minZoom = 12, maxZoom = 12, template = "https://t/{z}/{x}/{y}",
        )
        assertEquals(result.total, result.failed.toLong())
        assertEquals(0, sink.store.size)
        // a run where everything failed still terminates (completed=true) but
        // surfaces the failure count for the UI to warn on. Nothing cached.
        assertTrue(result.completed)
        assertEquals(0, result.downloaded)
        assertEquals(0, result.skipped)
    }

    @Test fun template_is_filled_with_each_tile_coordinate() = runTest {
        val urls = java.util.Collections.synchronizedList(ArrayList<String>())
        val downloader = RegionDownloader(
            sink = CountingSink(),
            fetch = { url -> urls.add(url); byteArrayOf(1) },
            concurrency = 1,
        )
        downloader.download(
            bbox = BoundingBox(47.61, 47.605, -122.33, -122.335),
            minZoom = 12, maxZoom = 12,
            template = "https://tiles.test/{z}/{x}/{y}.png",
        )
        assertTrue(urls.isNotEmpty())
        urls.forEach {
            assertTrue("URL must be fully substituted: $it", !it.contains("{") && !it.contains("}"))
            assertTrue(it.startsWith("https://tiles.test/12/"))
        }
    }
}
