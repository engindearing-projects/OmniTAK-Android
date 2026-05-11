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
    /** Accept any URI we recognise as a TAK-flavoured deep link. */
    fun isServerConfig(uri: Uri?): Boolean = parse(uri) !is DeepLinkAction.Unknown

    /**
     * Parse a TAK deep link into a typed [DeepLinkAction]. Delegates to
     * the pure-Kotlin [AtakUriParser] so the same code path covers
     * `tak://com.atakmap.app/<verb>?…` (canonical ATAK), `atak://…`
     * (de-facto), `omnitak://…` (our own scheme), and `https?://?host=…`
     * fallbacks. [MainActivity.handleImportIntent] dispatches on the
     * returned action.
     */
    fun parse(uri: Uri?): DeepLinkAction {
        if (uri == null) return DeepLinkAction.Unknown
        val query = uri.queryParameterNames
            .mapNotNull { name -> uri.getQueryParameter(name)?.let { name to it } }
            .toMap()
        return AtakUriParser.parse(
            scheme = uri.scheme,
            path = uri.path,
            query = query,
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
