package soy.engindearing.omnitak.mobile.data

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #139 — OSM/OpenTopoMap serve a "403 Access blocked" tile to clients with a
 * generic User-Agent. These lock in that MapLibre's tile requests carry an
 * app-identifying UA (`OmniTAK/<version>`), which the curl repro proved gets
 * real tiles instead of the blocked placeholder.
 */
class MapTileHttpTest {

    @Test fun userAgent_startsWith_OmniTAK_slash_version() {
        // OSM keys its block on the leading token — it MUST be "OmniTAK/<ver>".
        val ua = MapTileHttp.buildUserAgent(versionName = "0.38.0", androidRelease = "14")
        assertTrue("UA must start with OmniTAK/<version>, was: $ua",
            ua.startsWith("OmniTAK/0.38.0"))
    }

    @Test fun userAgent_includes_contact_url() {
        // Policy asks for a way to make contact; the project URL is it.
        val ua = MapTileHttp.buildUserAgent(versionName = "0.38.0", androidRelease = "14")
        assertTrue("UA must carry a contact URL, was: $ua",
            ua.contains("https://omnitak.engindearing.soy"))
    }

    @Test fun userAgent_includes_android_release() {
        val ua = MapTileHttp.buildUserAgent(versionName = "1.0.0", androidRelease = "13")
        assertTrue("UA should mention Android 13, was: $ua", ua.contains("Android 13"))
    }

    @Test fun userAgent_blankVersion_fallsBackToDev() {
        val ua = MapTileHttp.buildUserAgent(versionName = "", androidRelease = "14")
        assertTrue("blank version should degrade to OmniTAK/dev, was: $ua",
            ua.startsWith("OmniTAK/dev"))
    }

    @Test fun userAgent_blankAndroidRelease_stillValid() {
        // Plain JVM unit tests have Build.VERSION.RELEASE == null → blank; the UA
        // must still be well-formed and identifying.
        val ua = MapTileHttp.buildUserAgent(versionName = "0.38.0", androidRelease = "")
        assertTrue("must still start with OmniTAK/, was: $ua", ua.startsWith("OmniTAK/0.38.0"))
        assertTrue("must still contain 'Android', was: $ua", ua.contains("Android"))
    }

    @Test fun interceptor_setsUserAgentHeader_onOutgoingRequest() {
        val ua = "OmniTAK/9.9.9 (+https://omnitak.engindearing.soy; Android test)"
        val interceptor = MapTileHttp.userAgentInterceptor(ua)
        val chain = CapturingChain(Request.Builder().url("https://tile.openstreetmap.org/0/0/0.png").build())

        interceptor.intercept(chain)

        assertEquals(ua, chain.lastRequest?.header("User-Agent"))
    }

    @Test fun buildClient_installsExactlyOneInterceptor() {
        assertEquals(1, MapTileHttp.buildClient("OmniTAK/test").interceptors.size)
    }

    /**
     * Minimal Interceptor.Chain that records the request the interceptor
     * forwards. Our interceptor only touches request() and proceed(); the rest
     * throw because they must never be reached.
     */
    private class CapturingChain(private val request: Request) : Interceptor.Chain {
        var lastRequest: Request? = null
        override fun request(): Request = request
        override fun proceed(request: Request): Response {
            lastRequest = request
            // Return a throwaway 200 so intercept() completes; the test only
            // inspects the forwarded request's headers.
            return Response.Builder()
                .request(request)
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .build()
        }
        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        override fun readTimeoutMillis(): Int = 0
        override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        override fun writeTimeoutMillis(): Int = 0
        override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
    }
}
