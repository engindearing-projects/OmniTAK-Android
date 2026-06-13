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
    // CSR enrollment port (TAK default 8446). Used only when username +
    // password are present and the server speaks TLS — see [needsEnrollment].
    val enrollmentPort: Int = 8446,
    // Trust-all during enrollment by default so a single QR works for both
    // self-signed and publicly-trusted (Let's Encrypt) endpoints. Override
    // with trust=ca / trustselfsigned=false on the link for strict validation.
    val trustSelfSigned: Boolean = true,
) {
    /**
     * A TLS server with username + password can't connect with bare creds —
     * TAK Servers gate the streaming port behind mutual TLS. When we have
     * both, the right move is to CSR-enroll a client cert first (same flow
     * as the Quick Connect screen) rather than add a cert-less server that
     * will be rejected at the handshake.
     */
    val needsEnrollment: Boolean
        get() = useTLS && !username.isNullOrBlank() && !password.isNullOrEmpty()
}

object DeepLinkImport {
    /**
     * Returns true when the URI carries a configuration-profile payload
     * (`omnitak://profile?d=…`). Check this BEFORE [isServerConfig] because
     * the `omnitak://` scheme is shared between both URL shapes.
     */
    fun isProfileConfig(uri: Uri?): Boolean = ProfileQrCodec.isProfileUri(uri)

    /**
     * Decode a profile URI produced by [ProfileQrCodec.encode].
     * Returns null if the URI is malformed or the JSON payload can't be
     * parsed — callers should surface a user-visible error in that case.
     */
    fun parseProfileConfig(uri: Uri): ConfigProfile? = ProfileQrCodec.decode(uri)

    /** Accept any URI we recognise as a server-onboarding payload.
     *  Restricted to `atak://` and `omnitak://` schemes only — HTTP/HTTPS
     *  links from arbitrary sources could be used for drive-by server injection. */
    fun isServerConfig(uri: Uri?): Boolean {
        if (uri == null) return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme !in setOf("atak", "omnitak")) return false
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

        val enrollmentPort = (uri.getQueryParameter("enrollmentport")
            ?: uri.getQueryParameter("enrollport"))
            ?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 8446

        val trustRaw = (uri.getQueryParameter("trustselfsigned")
            ?: uri.getQueryParameter("trust"))?.lowercase()
        val trustSelfSigned = trustRaw !in setOf("false", "ca", "system", "0", "no")

        return ImportedServerConfig(
            name = name,
            host = host,
            port = port,
            useTLS = useTLS,
            username = username,
            password = password,
            enrollmentPort = enrollmentPort,
            trustSelfSigned = trustSelfSigned,
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
