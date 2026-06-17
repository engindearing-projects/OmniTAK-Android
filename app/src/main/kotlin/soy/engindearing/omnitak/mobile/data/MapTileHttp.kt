package soy.engindearing.omnitak.mobile.data

import android.content.Context
import android.os.Build
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import soy.engindearing.omnitak.mobile.BuildConfig

/**
 * Installs a custom HTTP client into MapLibre so every tile request carries an
 * app-identifying `User-Agent`.
 *
 * Why this exists (#139): OpenStreetMap's and OpenTopoMap's volunteer tile
 * servers enforce a tile usage policy that *requires* a valid User-Agent
 * identifying the application. Clients that send a generic UA (the default
 * okhttp/Dalvik string MapLibre ships with) are served a "403 Access blocked"
 * placeholder tile pointing at osm.wiki/Blocked instead of real map data — the
 * map looks broken. A UA that starts with `OmniTAK/<version>` gets real tiles.
 *
 * Verified empirically against tile.openstreetmap.org: empty / `okhttp/4.x` /
 * `Dalvik/...` UAs all return the 6987-byte "Access blocked" tile, while
 * `OmniTAK/0.38.0 (+https://omnitak.engindearing.soy)` returns the real tile.
 *
 * Satellite (Esri) was unaffected because Esri's imagery endpoint does not
 * enforce a UA policy — which is exactly why the reporter saw Satellite work
 * while OSM did not.
 */
object MapTileHttp {

    /**
     * Builds the User-Agent string. Pure + side-effect free so it can be unit
     * tested. OSM policy asks for something that identifies the app and offers
     * a way to make contact; the project URL serves as that contact point.
     *
     * Example: `OmniTAK/0.38.0 (+https://omnitak.engindearing.soy; Android 14)`
     */
    fun buildUserAgent(
        versionName: String = BuildConfig.VERSION_NAME,
        androidRelease: String = Build.VERSION.RELEASE ?: "",
    ): String {
        val version = versionName.ifBlank { "dev" }
        val androidPart = if (androidRelease.isBlank()) "Android" else "Android $androidRelease"
        return "OmniTAK/$version (+https://omnitak.engindearing.soy; $androidPart)"
    }

    /**
     * Build the OkHttp client MapLibre should use. Adds the User-Agent header to
     * every request; otherwise leans on okhttp defaults (MapLibre keeps its own
     * SQLite tile cache, so an HTTP cache here is unnecessary).
     */
    fun buildClient(userAgent: String = buildUserAgent()): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(userAgentInterceptor(userAgent))
            .build()

    /** Interceptor that sets `User-Agent` on the outgoing request. */
    fun userAgentInterceptor(userAgent: String): Interceptor =
        Interceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", userAgent)
                .build()
            chain.proceed(request)
        }

    /**
     * Install the identifying client into MapLibre. Call once at app start,
     * before any [org.maplibre.android.maps.MapView] is created so the very
     * first tile fetch already carries the UA.
     *
     * [MapLibre.getInstance] must run first: MapLibre's HTTP layer
     * (`HttpRequestImpl`) statically initializes against the MapLibre singleton
     * via `HttpIdentifier`, so calling [HttpRequestUtil.setOkHttpClient] before
     * the singleton exists throws MapLibreConfigurationException. getInstance is
     * idempotent, so the lazy call inside the map composable stays a no-op.
     */
    fun install(context: Context) {
        MapLibre.getInstance(context)
        HttpRequestUtil.setOkHttpClient(buildClient())
    }
}
