package soy.engindearing.omnitak.mobile.data

/**
 * ATAK-compatible URL scheme parser. ATAK / iTAK / TAKaware all consume a
 * canonical `tak://com.atakmap.app/<path>?…` deep-link family that lets a
 * QR-code scanner, configuration portal, or OpenTAKserver enrollment flow
 * hand a client server credentials, a data-package URL, or a preference
 * write in one tap.
 *
 * Reference shapes (from upstream ATAK docs):
 *
 * - Server connect:
 *     `tak://com.atakmap.app/connect?host=tak.example.com&port=8089&proto=tls`
 * - Data-package import (download a zip from a URL):
 *     `tak://com.atakmap.app/import?url=https%3A%2F%2Fhost%2Ffile.zip`
 * - Preference write (indexed `keyN` / `typeN` / `valueN` groups):
 *     `tak://com.atakmap.app/preference?key1=displayRed&type1=boolean&value1=true`
 * - CSR enrollment (against TAK Server / OpenTAKserver port 8446):
 *     `tak://com.atakmap.app/enroll?host=takserver.com&username=foo&token=user_token`
 *
 * Parser is pure: takes already-extracted scheme / path / query primitives
 * so it runs in JVM unit tests without Robolectric. The Android-Uri
 * adaptation lives in [DeepLinkImport.parse].
 */
sealed class DeepLinkAction {
    /** Add a server config + auto-connect. */
    data class AddServer(val config: ImportedServerConfig) : DeepLinkAction()

    /**
     * Download a data package (.zip) from [url] and run the import pipeline.
     * URL is expected to be a direct file link — http(s) is the only
     * scheme we honour to avoid `file://` exfiltration on shared storage.
     */
    data class ImportFromUrl(val url: String) : DeepLinkAction()

    /**
     * Write one or more preference key/value pairs to [UserPrefs]. Each
     * entry's [type] is the ATAK-side type tag (`boolean`, `string`, `int`,
     * `double`) — the applier coerces and ignores anything that doesn't
     * map to a known OmniTAK preference.
     */
    data class SetPreferences(val entries: List<PreferenceEntry>) : DeepLinkAction()

    data class PreferenceEntry(val key: String, val type: String, val value: String)

    /**
     * Begin CSR enrollment against OpenTAKserver / TAK Server's signClient
     * endpoint. iOS has the full flow today; Android stages the host so the
     * Servers screen can pick it up when GAP-081 (CSR on Android) lands.
     */
    data class Enroll(
        val host: String,
        val username: String,
        val token: String,
        val port: Int = 8446,
    ) : DeepLinkAction()

    /** URL was something we recognise as TAK-flavoured but had no usable payload. */
    data object Unknown : DeepLinkAction()
}

object AtakUriParser {

    private val SUPPORTED_SCHEMES = setOf("tak", "atak", "omnitak", "http", "https")

    /**
     * Parse already-extracted URI components into a [DeepLinkAction]. The
     * caller (Android) feeds `android.net.Uri.scheme`, `path`, and a map
     * of `queryParameterNames` → first value; iOS feeds `URLComponents`.
     */
    fun parse(
        scheme: String?,
        path: String?,
        query: Map<String, String>,
    ): DeepLinkAction {
        val s = scheme?.lowercase() ?: return DeepLinkAction.Unknown
        if (s !in SUPPORTED_SCHEMES) return DeepLinkAction.Unknown

        return when (normalisePath(path)) {
            "import" -> parseImport(query)
            "preference" -> parsePreferences(query)
            "enroll" -> parseEnroll(query)
            // `/connect` and the empty path both fall through to the
            // legacy host-based server-config parser so `atak://`,
            // `omnitak://`, and bare query-param links keep working.
            else -> parseServer(query)
        }
    }

    private fun normalisePath(path: String?): String {
        // Android Uri yields path like "/import"; URLComponents likewise.
        // Trim leading slash + lowercase so callers don't have to.
        return path?.trim()?.trimStart('/')?.lowercase().orEmpty()
    }

    private fun parseImport(query: Map<String, String>): DeepLinkAction {
        val url = query["url"]?.trim().orEmpty()
        if (url.isBlank()) return DeepLinkAction.Unknown
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return DeepLinkAction.Unknown
        }
        return DeepLinkAction.ImportFromUrl(url)
    }

    private fun parsePreferences(query: Map<String, String>): DeepLinkAction {
        // ATAK convention: keyN / typeN / valueN, indexed from 1 upward.
        // Walk indices until we miss one — keeps us tolerant of arbitrary
        // counts without exposing a max.
        val out = mutableListOf<DeepLinkAction.PreferenceEntry>()
        var i = 1
        while (true) {
            val key = query["key$i"]?.trim().orEmpty()
            if (key.isBlank()) break
            val type = query["type$i"]?.trim()?.lowercase().orEmpty().ifBlank { "string" }
            val value = query["value$i"].orEmpty()
            out.add(DeepLinkAction.PreferenceEntry(key, type, value))
            i++
        }
        if (out.isEmpty()) return DeepLinkAction.Unknown
        return DeepLinkAction.SetPreferences(out)
    }

    private fun parseEnroll(query: Map<String, String>): DeepLinkAction {
        val host = query["host"]?.trim().orEmpty()
        val username = query["username"]?.trim().orEmpty()
        val token = query["token"].orEmpty()
        if (host.isBlank() || username.isBlank() || token.isBlank()) {
            return DeepLinkAction.Unknown
        }
        val port = (query["enrollmentport"] ?: query["port"])
            ?.toIntOrNull()
            ?.takeIf { it in 1..65535 }
            ?: 8446
        return DeepLinkAction.Enroll(host = host, username = username, token = token, port = port)
    }

    private fun parseServer(query: Map<String, String>): DeepLinkAction {
        val host = query["host"]?.trim().orEmpty()
        if (host.isBlank()) return DeepLinkAction.Unknown

        val port = query["port"]?.toIntOrNull() ?: 8089
        if (port !in 1..65535) return DeepLinkAction.Unknown

        val tlsFromFlag = query["tls"]?.equals("true", ignoreCase = true)
        val tlsFromProto = (query["proto"] ?: query["protocol"])
            ?.let { it.equals("tls", ignoreCase = true) || it.equals("ssl", ignoreCase = true) }
        val useTLS = tlsFromFlag ?: tlsFromProto ?: (port == 8089)

        val username = (query["username"] ?: query["user"])?.takeIf { it.isNotBlank() }
        val password = (query["password"] ?: query["pw"])?.takeIf { it.isNotEmpty() }
        val name = query["name"]?.takeIf { it.isNotBlank() } ?: host

        return DeepLinkAction.AddServer(
            ImportedServerConfig(
                name = name,
                host = host,
                port = port,
                useTLS = useTLS,
                username = username,
                password = password,
            ),
        )
    }
}
