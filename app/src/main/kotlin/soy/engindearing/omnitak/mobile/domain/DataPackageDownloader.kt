package soy.engindearing.omnitak.mobile.domain

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a TAK data-package zip from an HTTPS URL into the app's
 * private import directory so [DataPackageBootstrap] can pick it up on
 * the next run. Used by the `tak://com.atakmap.app/import?url=…` deep
 * link — the upstream ATAK behaviour mata pasted as the canonical
 * "give me a zip from over there, apply it locally" flow.
 *
 * Returns the downloaded File on success, null on any IO/HTTP failure
 * so the caller can show a toast without exception handling at the
 * call site.
 */
object DataPackageDownloader {
    private const val TAG = "DataPackageDownloader"
    private const val IMPORT_DIR = "import"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val MAX_REDIRECTS = 5
    private const val MAX_BYTES = 25L * 1024L * 1024L // 25 MB ceiling on imported zips

    suspend fun download(context: Context, url: String): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = context.getExternalFilesDir(IMPORT_DIR) ?: error("import dir unavailable")
            if (!dir.exists()) dir.mkdirs()
            val target = File(dir, deriveFilename(url))
            fetch(url, target)
            target
        }.onFailure { Log.w(TAG, "Download of $url failed: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()
    }

    private fun fetch(initial: String, target: File) {
        var current = initial
        var hops = 0
        while (true) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                requestMethod = "GET"
                // ATAK servers tend to gate downloads on a UA — present a
                // neutral one so we aren't filtered as a script.
                setRequestProperty("User-Agent", "OmniTAK/Android")
                setRequestProperty("Accept", "application/zip,application/octet-stream;q=0.9,*/*;q=0.5")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location") ?: error("redirect missing Location")
                hops++
                if (hops > MAX_REDIRECTS) error("too many redirects")
                current = location
                conn.disconnect()
                continue
            }
            if (code !in 200..299) error("HTTP $code")
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    var copied = 0L
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        copied += read
                        if (copied > MAX_BYTES) error("file exceeds $MAX_BYTES bytes")
                        output.write(buf, 0, read)
                    }
                }
            }
            conn.disconnect()
            return
        }
    }

    private fun deriveFilename(url: String): String {
        val tail = url.substringAfterLast('/').substringBefore('?')
        val safe = tail.takeIf { it.isNotBlank() && it.length < 128 }
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val base = safe ?: "import-${System.currentTimeMillis()}.zip"
        // Force `.zip` so DataPackageBootstrap picks it up.
        return if (base.endsWith(".zip", ignoreCase = true)) base else "$base.zip"
    }
}
