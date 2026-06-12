package soy.engindearing.omnitak.mobile.data

import android.net.Uri
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.Base64

/**
 * Encodes and decodes [ConfigProfile]s as compact QR-friendly URIs.
 *
 * Format: `omnitak://profile?d=<base64url(gzip(json))>`
 *
 * Design:
 *  - JSON → gzip → base64url (URL-safe, no padding) keeps the payload
 *    under ~2 KB for a typical profile so a single QR (version ≤ 14,
 *    error-correction L) scans in under a second on any modern phone.
 *  - No secrets: [ConfigProfile] excludes certs, passphrases, and passwords.
 *  - Self-contained: no server round-trip; the teammate's camera app decodes
 *    the link, taps it, and OmniTAK imports inline.
 *
 * Deep-link interception is registered in [DeepLinkImport.isProfileConfig] so
 * the app handles the `omnitak://profile` URL from any entry point
 * (camera scan → default browser → app, or in-app QR scanner).
 */
object ProfileQrCodec {

    private val SCHEME = "omnitak"
    private val HOST = "profile"
    private val PARAM = "d"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ── Encode ────────────────────────────────────────────────────────────

    /**
     * Encode a [ConfigProfile] into a URI string suitable for QR generation.
     *
     * Example output:
     * `omnitak://profile?d=H4sIAAAAAAAAA6tWKkktLlGyUlJQKs9ILUpVslIqS8z...`
     */
    fun encode(profile: ConfigProfile): String {
        val jsonBytes = json.encodeToString(ConfigProfile.serializer(), profile).toByteArray(Charsets.UTF_8)
        val compressed = gzip(jsonBytes)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)
        return "$SCHEME://$HOST?$PARAM=$encoded"
    }

    // ── Decode ────────────────────────────────────────────────────────────

    /**
     * Decode a URI produced by [encode] back into a [ConfigProfile].
     * Returns null and logs nothing — callers decide how to surface parse errors.
     */
    fun decode(uri: Uri): ConfigProfile? {
        if (!isProfileUri(uri)) return null
        val encoded = uri.getQueryParameter(PARAM) ?: return null
        return runCatching {
            val compressed = Base64.getUrlDecoder().decode(encoded)
            val jsonBytes = gunzip(compressed)
            json.decodeFromString(ConfigProfile.serializer(), String(jsonBytes, Charsets.UTF_8))
        }.getOrNull()
    }

    /**
     * Decode a raw URI string. Works on both Android (via [Uri.parse]) and
     * the JVM test environment (manually extracts the `d=` query param).
     */
    fun decode(uriString: String): ConfigProfile? = runCatching {
        // Fast path: try Android Uri.parse.
        val uri = Uri.parse(uriString)
        if (isProfileUri(uri)) return@runCatching decode(uri)
        // Fallback: manual parse so JVM unit tests don't need Robolectric.
        if (!uriString.startsWith("$SCHEME://$HOST?")) return@runCatching null
        val query = uriString.substringAfter("$SCHEME://$HOST?")
        val encoded = query.split("&")
            .firstOrNull { it.startsWith("$PARAM=") }
            ?.removePrefix("$PARAM=") ?: return@runCatching null
        if (encoded.isBlank()) return@runCatching null
        val compressed = Base64.getUrlDecoder().decode(encoded)
        val jsonBytes = gunzip(compressed)
        json.decodeFromString(ConfigProfile.serializer(), String(jsonBytes, Charsets.UTF_8))
    }.getOrNull()

    /** Returns true if this URI carries a profile payload. */
    fun isProfileUri(uri: Uri?): Boolean {
        if (uri == null) return false
        return uri.scheme?.lowercase() == SCHEME &&
            uri.host?.lowercase() == HOST &&
            !uri.getQueryParameter(PARAM).isNullOrBlank()
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray {
        GZIPInputStream(ByteArrayInputStream(data)).use { return it.readBytes() }
    }
}
