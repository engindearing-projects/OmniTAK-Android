package soy.engindearing.omnitak.mobile.data

import android.net.Uri

/**
 * GAP-105 rest — onboarding via deep link. Mirrors ATAK / OpenTAKserver's
 * QR-onboarding pattern: scan a QR (any phone camera handles it natively),
 * the resulting URL deep-links into the app, we parse it and apply the
 * server config in one tap.
 *
 * URL shapes we accept today:
 *
 * 1. `tak://com.atakmap.app/enroll?host=argustak.com%3A8089&username=u&token=t`
 *    — the STANDARD ATAK / TAK Server / ArgusTAK enrollment QR (#100). The
 *    `host` carries the (URL-encoded) connection `host:port`; `username` +
 *    `token` are one-time CSR-enrollment credentials. Parsed by
 *    [isEnrollLink] / [parseEnrollLink] and routed to the CSR enroll flow.
 *
 * 2. `atak://com.atakmap.app/connect?host=tak.example.com&port=8089&proto=tls`
 *    — matches the de-facto ATAK Quick-Connect format some onboarding
 *    portals already generate. We tolerate either `tls=true` or
 *    `proto=tls`. ATAK's own client also reads `username` / `token`.
 *
 * 3. `omnitak://server?host=...&port=...&tls=true&user=...&pw=...&name=...`
 *    — our own future-proof scheme so we don't have to depend on ATAK's
 *    URL conventions for new fields (callsign, team, basemap, etc.).
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
     * #100 — recognise the *standard* ATAK / TAK Server / ArgusTAK
     * enrollment deep link:
     *
     *   `tak://com.atakmap.app/enroll?host=<host>[:port]&username=<u>&token=<t>`
     *
     * This is what ArgusTAK / ATAK / TAK Server emit in their onboarding QR
     * codes. It is shaped differently from our [isServerConfig] connect form:
     *  - the path is `/enroll` (the connect form has no path),
     *  - the `host` query param frequently carries `host:port` (URL-encoded,
     *    e.g. `argustak.com%3A8089`) rather than a separate `port=`,
     *  - the credential is a one-time CSR-enrollment `token`, not a password.
     *
     * Accepts the `tak`, `atak`, and `omnitak` schemes so the same QR works
     * whether the generator used ATAK's canonical `tak://` host
     * (`com.atakmap.app`) or just the scheme. HTTP/HTTPS are intentionally
     * excluded — same drive-by-injection guard as [isServerConfig].
     */
    fun isEnrollLink(uri: Uri?): Boolean {
        if (uri == null) return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme !in setOf("tak", "atak", "omnitak")) return false
        // The path is the discriminator: `…/enroll`. Uri.path can be null for
        // opaque URIs, so fall back to a substring probe on the raw string.
        val isEnrollPath = uri.path?.trimEnd('/')?.endsWith("enroll", ignoreCase = true) == true ||
            uri.toString().contains("/enroll", ignoreCase = true)
        if (!isEnrollPath) return false
        return !uri.getQueryParameter("host").isNullOrBlank()
    }

    /**
     * Parse a [isEnrollLink] enrollment URI into an [ImportedServerConfig]
     * ready for the CSR enrollment flow. Returns null when the link is
     * missing the host or enrollment credentials (the caller should toast a
     * friendly error rather than silently dropping the import).
     *
     * Field mapping:
     *  - `host` → split on `:` so `argustak.com:8089` yields host
     *    `argustak.com` + **connection** port 8089. A bare host with no
     *    colon defaults the connection port to 8089 (TAK streaming default).
     *  - `username` → CSR auth username (becomes the cert CN).
     *  - `token`  → CSR auth secret. Carried in [ImportedServerConfig.password]
     *    because [CSREnrollmentService] sends username+secret as HTTP Basic
     *    auth to `/Marti/api/tls/signClient/v2` — the token IS the password
     *    on the enrollment endpoint.
     *  - enrollment port → defaults to 8446 (TAK Server's cert-enrollment
     *    port, which differs from the 8089 connection port), overridable via
     *    `enrollport` / `enrollmentport` on the link for servers that colocate
     *    enrollment on a non-standard port.
     */
    fun parseEnrollLink(uri: Uri): ImportedServerConfig? {
        // `host` may be percent-encoded host:port (Uri.getQueryParameter
        // already decodes %3A → ':'). Split host from the connection port.
        val rawHost = uri.getQueryParameter("host")?.trim().orEmpty()
        if (rawHost.isBlank()) return null
        val (host, portFromHost) = splitHostPort(rawHost)
        if (host.isBlank()) return null

        val username = uri.getQueryParameter("username")?.takeIf { it.isNotBlank() }
            ?: return null
        // ATAK uses `token`; tolerate `password` as a fallback for generators
        // that reuse the connect-form param name on an enroll link.
        val token = (uri.getQueryParameter("token") ?: uri.getQueryParameter("password"))
            ?.takeIf { it.isNotEmpty() } ?: return null

        // Connection port: prefer host:port suffix, then an explicit ?port=,
        // else the TAK streaming default 8089.
        val connectPort = portFromHost
            ?: uri.getQueryParameter("port")?.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: 8089

        // Enrollment port is a SEPARATE port from the connection port — TAK
        // Server enrolls on 8446 by default. Overridable for non-standard rigs.
        val enrollmentPort = (uri.getQueryParameter("enrollport")
            ?: uri.getQueryParameter("enrollmentport"))
            ?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 8446

        // Trust-all during enrollment by default so a single QR onboards both
        // self-signed (self-hosted TAK Server) and publicly-trusted (ArgusTAK
        // behind Let's Encrypt) endpoints. Override with trust=ca on the link.
        val trustRaw = (uri.getQueryParameter("trustselfsigned")
            ?: uri.getQueryParameter("trust"))?.lowercase()
        val trustSelfSigned = trustRaw !in setOf("false", "ca", "system", "0", "no")

        val name = uri.getQueryParameter("name")?.takeIf { it.isNotBlank() } ?: host

        return ImportedServerConfig(
            name = name,
            host = host,
            port = connectPort,
            useTLS = true,            // enrollment is always mTLS
            username = username,
            password = token,         // token = the enrollment Basic-auth secret
            enrollmentPort = enrollmentPort,
            trustSelfSigned = trustSelfSigned,
        )
    }

    /**
     * Split a `host` or `host:port` string into (host, port?). Tolerates a
     * trailing colon with no port and a non-numeric port (treated as no
     * port). Bracketed IPv6 literals (`[::1]:8089`) are handled so the
     * colons inside the address aren't mistaken for the port separator.
     *
     * `internal` so it can be unit-tested without Robolectric — this is the
     * load-bearing decode of the URL-encoded `host=argustak.com%3A8089` form.
     */
    internal fun splitHostPort(value: String): Pair<String, Int?> {
        val v = value.trim()
        if (v.startsWith("[")) {
            // IPv6: [addr]:port
            val close = v.indexOf(']')
            if (close < 0) return v to null
            val addr = v.substring(1, close)
            val rest = v.substring(close + 1)
            val port = rest.removePrefix(":").toIntOrNull()?.takeIf { it in 1..65535 }
            return addr to port
        }
        val idx = v.lastIndexOf(':')
        if (idx <= 0 || idx == v.length - 1) return v.removeSuffix(":") to null
        val maybePort = v.substring(idx + 1).toIntOrNull()?.takeIf { it in 1..65535 }
        // A malformed/out-of-range port after the colon must not poison the
        // hostname (DNS would fail on "host:abc") — strip to the host portion
        // and report no port so the caller falls back to its default.
        return v.substring(0, idx) to maybePort
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
