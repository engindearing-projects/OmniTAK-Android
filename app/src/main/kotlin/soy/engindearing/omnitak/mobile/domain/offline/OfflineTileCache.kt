package soy.engindearing.omnitak.mobile.domain.offline

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Pluggable read function — OfflineTileStore::readTile in
 *  production, an in-memory map in tests. */
typealias TileLookup = (z: Int, x: Int, y: Int) -> ByteArray?

/**
 * OkHttp interceptor that short-circuits map-tile requests with bytes
 * from the on-disk [OfflineTileStore] when a downloaded region covers
 * the requested tile.
 *
 * When wired into MapLibre via `HttpRequestUtil.setOkHttpClient(...)`
 * (the standard MapLibre Android override hook), every tile request
 * MapLibre dispatches first hits this interceptor. Hits return a
 * synthetic 200 with the cached PNG; misses fall through to the
 * network and MapLibre's own memory cache takes care of repeat reads
 * within the session.
 *
 * The interceptor stays a pure read-through; the downloader is the
 * sole writer. This avoids two writers racing on the same file and
 * means partial network responses never poison the offline cache.
 */
class OfflineTileCache(private val lookup: TileLookup) : Interceptor {

    /** Convenience constructor for production wiring. */
    constructor(store: OfflineTileStore) : this(store::readTile)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val tile = TileMath.parseTileFromUrl(request.url.toString())
        if (tile != null) {
            val (z, x, y) = tile
            val cached = lookup(z, x, y)
            if (cached != null) {
                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK (offline tile cache)")
                    .body(cached.toResponseBody(PNG_MEDIA_TYPE))
                    .build()
            }
        }
        return chain.proceed(request)
    }

    companion object {
        private val PNG_MEDIA_TYPE = "image/png".toMediaTypeOrNull()
    }
}
