package soy.engindearing.omnitak.mobile.domain.offline

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineTileCacheTest {

    @Test fun returns_cached_bytes_without_calling_through() {
        val cache = OfflineTileCache(lookup = { z, x, y ->
            if (z == 10 && x == 100 && y == 200) PNG_BYTES else null
        })
        val chain = StubChain(
            request = Request.Builder().url("https://tile.openstreetmap.org/10/100/200.png").build(),
            // If the interceptor calls proceed(), the test fails with this stub.
            proceedResponseFactory = { fail("network should not be hit on cache hit") },
        )
        val response = cache.intercept(chain)
        assertEquals(200, response.code)
        assertEquals("OK (offline tile cache)", response.message)
        assertArrayEquals(PNG_BYTES, response.body!!.bytes())
    }

    @Test fun calls_through_when_no_region_covers_the_tile() {
        val cache = OfflineTileCache(lookup = { _, _, _ -> null })
        val networkResponse = stubResponse(
            request = Request.Builder().url("https://tile.openstreetmap.org/10/100/200.png").build(),
            code = 200,
            body = NETWORK_BYTES,
        )
        val chain = StubChain(
            request = networkResponse.request,
            proceedResponseFactory = { networkResponse },
        )
        val response = cache.intercept(chain)
        assertEquals(200, response.code)
        assertArrayEquals(NETWORK_BYTES, response.body!!.bytes())
    }

    @Test fun passes_through_non_tile_urls_unchanged() {
        val cache = OfflineTileCache(lookup = { _, _, _ -> PNG_BYTES })
        val pageRequest = Request.Builder().url("https://example.com/about").build()
        val passThrough = stubResponse(pageRequest, code = 200, body = NETWORK_BYTES)
        val chain = StubChain(
            request = pageRequest,
            proceedResponseFactory = { passThrough },
        )
        val response = cache.intercept(chain)
        assertTrue("non-tile URL must fall through to network", response === passThrough)
    }

    // ---------- helpers --------------------------------------------------

    private fun fail(message: String): Response = throw AssertionError(message)

    private fun stubResponse(request: Request, code: Int, body: ByteArray): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .body(body.toResponseBody(null))
            .build()

    /** Minimal Chain shim covering only what [OfflineTileCache] reaches
     *  for. OkHttp does not provide a public test fake. */
    private class StubChain(
        private val request: Request,
        private val proceedResponseFactory: (Request) -> Response,
    ) : Interceptor.Chain {
        override fun request(): Request = request
        override fun proceed(request: Request): Response = proceedResponseFactory(request)
        override fun connection() = null
        override fun call() = throw UnsupportedOperationException()
        override fun connectTimeoutMillis() = 0
        override fun readTimeoutMillis() = 0
        override fun writeTimeoutMillis() = 0
        override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
    }

    companion object {
        private val PNG_BYTES = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3, 4)
        private val NETWORK_BYTES = byteArrayOf(9, 9, 9, 9)
    }
}
