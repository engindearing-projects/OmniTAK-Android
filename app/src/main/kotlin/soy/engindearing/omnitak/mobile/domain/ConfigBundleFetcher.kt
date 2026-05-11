package soy.engindearing.omnitak.mobile.domain

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import soy.engindearing.omnitak.mobile.data.AtakUriParser
import soy.engindearing.omnitak.mobile.data.CertVault
import soy.engindearing.omnitak.mobile.data.DeepLinkAction
import soy.engindearing.omnitak.mobile.data.PreferenceUriApplier
import soy.engindearing.omnitak.mobile.data.UserPrefsStore
import java.net.HttpURLConnection
import java.net.URL

/**
 * GAP-108 — server-driven config. When [UserPrefs.configBundleUrl] is set
 * we hit it on every launch (and on demand from Settings → "Refresh config
 * now"). The response can be either:
 *
 *  - a TAK data-package `.zip` — staged into the import dir for
 *    [DataPackageBootstrap] to consume (servers + certs + prefs)
 *  - a `text/plain` body containing one or more
 *    `tak://com.atakmap.app/preference?…` URLs, one per line. Each is
 *    parsed and applied to [UserPrefs].
 *
 * The pattern an operator wants: publish one URL per team on their
 * OpenTAKserver onboarding portal, point every EUD at it once, edit the
 * file server-side as the event evolves. Every EUD picks up changes on
 * the next launch without anyone re-scanning a QR.
 *
 * Non-goals (deferred):
 *  - periodic background polling — adds Doze / battery complexity and
 *    most operators only need pull-on-launch + manual refresh
 *  - signed bundles — relies on HTTPS + operator-controlled URL
 */
object ConfigBundleFetcher {
    private const val TAG = "ConfigBundleFetcher"
    private const val MAX_BYTES = 2L * 1024L * 1024L // 2 MB ceiling on text payloads
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000

    sealed class Result {
        data class AppliedPrefs(val applied: List<String>, val ignored: List<String>) : Result()
        data class StagedZip(val filename: String) : Result()
        data class Failure(val reason: String) : Result()
        data object NoUrlConfigured : Result()
    }

    /**
     * Fetch the configured URL and apply. Side effects: writes prefs via
     * [UserPrefsStore] and / or stages a zip into `<app>/files/import/`.
     * Caller (e.g. [OmniTAKApp.onCreate]) decides whether to follow up
     * with [DataPackageBootstrap.runIfNeeded].
     */
    suspend fun runIfConfigured(
        context: Context,
        userPrefsStore: UserPrefsStore,
        certVault: CertVault,
        serverManager: ServerManager,
        url: String?,
    ): Result = withContext(Dispatchers.IO) {
        if (url.isNullOrBlank()) return@withContext Result.NoUrlConfigured

        val (bodyBytes, contentType) = runCatching { fetch(url) }
            .getOrElse {
                Log.w(TAG, "Fetch failed: ${it.javaClass.simpleName}: ${it.message}")
                return@withContext Result.Failure(it.message ?: it.javaClass.simpleName)
            }

        // Zip signature — `PK\x03\x04` — wins regardless of declared
        // content-type, since some webservers mislabel zips as octet-stream.
        if (bodyBytes.isZip()) {
            val downloaded = DataPackageDownloader.download(context, url) ?: run {
                return@withContext Result.Failure("zip download failed")
            }
            DataPackageBootstrap(context, certVault, serverManager).runIfNeeded()
            return@withContext Result.StagedZip(downloaded.name)
        }

        // Otherwise treat as text/plain — parse `tak://…/preference?…` URLs
        // one per line. Strip blanks and comments (`#`).
        val text = String(bodyBytes, Charsets.UTF_8)
        val entries = mutableListOf<DeepLinkAction.PreferenceEntry>()
        for (line in text.lineSequence().map { it.trim() }) {
            if (line.isEmpty() || line.startsWith("#")) continue
            val parsed = parsePreferenceLine(line) ?: continue
            entries += parsed
        }
        if (entries.isEmpty()) {
            return@withContext Result.Failure(
                "no tak://preference URLs found in body (content-type=$contentType, ${bodyBytes.size} bytes)"
            )
        }

        var captured: PreferenceUriApplier.ApplyResult? = null
        userPrefsStore.update { current ->
            val res = PreferenceUriApplier.apply(current, entries)
            captured = res
            res.prefs
        }
        val r = captured!!
        Log.i(TAG, "Applied ${r.applied.size} prefs from $url (ignored=${r.ignored.size})")
        Result.AppliedPrefs(applied = r.applied, ignored = r.ignored)
    }

    /** Parse one `tak://com.atakmap.app/preference?keyN=…&valueN=…` line. */
    internal fun parsePreferenceLine(line: String): DeepLinkAction.PreferenceEntry? {
        val uri = runCatching { Uri.parse(line) }.getOrNull() ?: return null
        val query = uri.queryParameterNames
            .mapNotNull { name -> uri.getQueryParameter(name)?.let { name to it } }
            .toMap()
        val action = AtakUriParser.parse(uri.scheme, uri.path, query)
        return (action as? DeepLinkAction.SetPreferences)?.entries?.firstOrNull()
    }

    private fun fetch(url: String): Pair<ByteArray, String> {
        var current = url
        var hops = 0
        while (true) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("User-Agent", "OmniTAK-Android/0.3.0")
                setRequestProperty("Accept", "application/zip,text/plain;q=0.9,*/*;q=0.5")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location") ?: error("redirect missing Location")
                hops++
                if (hops > 5) error("too many redirects")
                conn.disconnect()
                current = location
                continue
            }
            if (code !in 200..299) error("HTTP $code")
            val ct = conn.contentType ?: "application/octet-stream"
            val bytes = conn.inputStream.use { input ->
                val out = ByteArray(MAX_BYTES.toInt() + 1)
                var off = 0
                while (off < out.size) {
                    val n = input.read(out, off, out.size - off)
                    if (n <= 0) break
                    off += n
                }
                if (off > MAX_BYTES) error("body exceeds $MAX_BYTES bytes")
                out.copyOf(off)
            }
            conn.disconnect()
            return bytes to ct
        }
    }

    private fun ByteArray.isZip(): Boolean =
        size >= 4 && this[0] == 0x50.toByte() && this[1] == 0x4B.toByte() &&
            this[2] == 0x03.toByte() && this[3] == 0x04.toByte()
}
