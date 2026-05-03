package soy.engindearing.omnitak.mobile.data

import android.net.Uri

/**
 * GAP-105 rest — onboarding via deep link. Mirrors ATAK / OpenTAKserver's
 * QR-onboarding pattern: scan a QR (any phone camera handles it natively),
 * the resulting URL deep-links into the app, we parse it and apply the
 * server config in one tap.
 *
 * Three URL shapes we accept today:
 *
 * 1. `atak://com.atakmap.app/connect?host=tak.example.com&port=8089&proto=tls`
 *    — matches the de-facto ATAK Quick-Connect format some onboarding
 *    portals already generate. We tolerate either `tls=true` or
 *    `proto=tls`. ATAK's own client also reads `username` / `token`.
 *
 * 2. `omnitak://server?host=...&port=...&tls=true&user=...&pw=...&name=...`
 *    — our own future-proof scheme so we don't have to depend on ATAK's
 *    URL conventions for new fields (callsign, team, basemap, etc.).
 *
 * 3. Anything that has a `host` query param and looks like a TAK endpoint —
 *    used as a generic fallback so OpenTAKserver onboarding portals can
 *    point at any URL of the form `https://example.com/?host=...`.
 *
 * The parser intentionally avoids cert / data-package zip handling for
 * now — that's filed as the next iteration of GAP-105 (full ATAK
 * data-package import with embedded P12 client certs).
 */
data class ImportedServerConfig(
    val name: String,
    val host: String,
    val port: Int,
    val useTLS: Boolean,
    val username: String?,
    val password: String?,
)

object DeepLinkImport {
    /** Accept any URI we recognise as a server-onboarding payload. */
    fun isServerConfig(uri: Uri?): Boolean {
        if (uri == null) return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme !in setOf("atak", "omnitak", "http", "https")) return false
        return !uri.getQueryParameter("host").isNullOrBlank()
    }

    /**
     * Parse a server-config URI. Returns null if the URI doesn't carry
     * a usable host or port — the caller should toast a friendly error
     * rather than silently dropping the import.
     */
    fun parseServerConfig(uri: Uri): ImportedServerConfig? {
        val host = uri.getQueryParameter("host")?.trim().orEmpty()
        if (host.isBlank()) return null

        val port = uri.getQueryParameter("port")?.toIntOrNull() ?: 8089
        if (port !in 1..65535) return null

        val tlsFromFlag = uri.getQueryParameter("tls")?.equals("true", ignoreCase = true)
        val tlsFromProto = uri.getQueryParameter("proto")?.equals("tls", ignoreCase = true)
        val useTLS = tlsFromFlag ?: tlsFromProto ?: (port == 8089)

        // Both schemes share the credential param names, with shorthand
        // `user` / `pw` in our own scheme to keep QR payloads tighter.
        val username = (uri.getQueryParameter("username")
            ?: uri.getQueryParameter("user"))
            ?.takeIf { it.isNotBlank() }
        val password = (uri.getQueryParameter("password")
            ?: uri.getQueryParameter("pw"))
            ?.takeIf { it.isNotEmpty() }

        val name = uri.getQueryParameter("name")?.takeIf { it.isNotBlank() }
            ?: host

        return ImportedServerConfig(
            name = name,
            host = host,
            port = port,
            useTLS = useTLS,
            username = username,
            password = password,
        )
    }

    /** Convert an [ImportedServerConfig] to a [TAKServer] ready for the manager. */
    fun toServer(cfg: ImportedServerConfig): TAKServer = TAKServer(
        name = cfg.name,
        host = cfg.host,
        port = cfg.port,
        protocol = if (cfg.useTLS) ConnectionProtocol.TLS.wire else ConnectionProtocol.TCP.wire,
        useTLS = cfg.useTLS,
        username = cfg.username,
        password = cfg.password,
    )
}
