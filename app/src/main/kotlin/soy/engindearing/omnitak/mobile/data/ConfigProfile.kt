package soy.engindearing.omnitak.mobile.data

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A shareable configuration snapshot that a captain/staff generates and
 * teammates import by scanning a QR code.
 *
 * SECURITY CONTRACT:
 * - No private certs, cert passphrases, or server passwords ever live here.
 * - [servers] strips all secrets via [ProfileServer.fromServer] before inclusion.
 * - [enrollmentPointer] carries only connection parameters; the joining teammate
 *   enrolls their own cert through the normal CSR flow.
 * - Callsign is intentionally omitted — each teammate keeps their own.
 *
 * QR payload: `omnitak://profile?d=<base64url(gzip(json))>`
 * Target < 2 KB so a standard QR (version ≤ 14) scans reliably at a glance.
 */
@Serializable
data class ConfigProfile(
    /** Stable UUID — survives rename. */
    val id: String = UUID.randomUUID().toString(),
    /** Human-readable label shown in the profile list and QR import preview. */
    val name: String,

    // ── Identity template ───────────────────────────────────────────────────
    /** Default team color for teammates who import this profile. */
    val team: String = "Cyan",
    // Callsign is deliberately excluded — each operator sets their own.

    // ── Servers (secrets stripped) ──────────────────────────────────────────
    val servers: List<ProfileServer> = emptyList(),

    // ── Enrollment pointer ──────────────────────────────────────────────────
    /**
     * Where a new teammate should enroll their client cert. Only present when
     * the server requires mTLS (useTLS + username in [servers]). Points at the
     * CSR enrollment HTTPS port (TAK default 8446).
     */
    val enrollmentPointer: EnrollmentPointer? = null,

    // ── Map ─────────────────────────────────────────────────────────────────
    val mapProvider: String = MapProvider.TOPO_HINT.name,
    val customTileUrl: String = "",

    // ── Units & coordinates ─────────────────────────────────────────────────
    val coordFormat: String = CoordFormat.LATLON_DECIMAL.name,
    val distanceUnit: String = DistanceUnit.METRIC.name,

    // ── Feature toggles ─────────────────────────────────────────────────────
    val callsignCardVisible: Boolean = true,
    val gridEnabled: Boolean = false,
    val drawingsVisible: Boolean = true,
    val aircraftVisible: Boolean = true,
    val contactsVisible: Boolean = true,
    val useMilStdSelfSymbol: Boolean = true,

    // ── Mesh settings ────────────────────────────────────────────────────────
    val autoPublishMeshToTak: Boolean = true,
    val broadcastOverMesh: Boolean = true,
    val meshBroadcastIntervalSecs: Int = 30,
    val meshNodesLayerVisible: Boolean = true,
)

/**
 * A server entry safe for QR export: host/port/protocol only, no secrets.
 *
 * Cert names are intentionally included (non-secret) so teammates know which
 * cert file they need to enroll; the actual P12 bytes and passphrase stay in
 * the device CertVault.
 */
@Serializable
data class ProfileServer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val protocol: String,
    val useTLS: Boolean,
    val allowUntrustedTls: Boolean = false,
    val username: String? = null,
    // password / certificatePassword / certificateName intentionally excluded.
) {
    companion object {
        /** Strip secrets from a [TAKServer] before adding to a profile. */
        fun fromServer(s: TAKServer): ProfileServer = ProfileServer(
            id = s.id,
            name = s.name,
            host = s.host,
            port = s.port,
            protocol = s.protocol,
            useTLS = s.useTLS,
            allowUntrustedTls = s.allowUntrustedTls,
            username = s.username,
        )

        /** Reconstitute a [TAKServer] from an imported profile server entry.
         *  Secrets stay null — the operator enrolls their own cert afterward. */
        fun toServer(ps: ProfileServer): TAKServer = TAKServer(
            id = ps.id,
            name = ps.name,
            host = ps.host,
            port = ps.port,
            protocol = ps.protocol,
            useTLS = ps.useTLS,
            allowUntrustedTls = ps.allowUntrustedTls,
            username = ps.username,
        )
    }
}

/** Where a teammate should hit the CSR enrollment endpoint. */
@Serializable
data class EnrollmentPointer(
    val host: String,
    val enrollmentPort: Int = 8446,
    /** Full enrollment URL if different from the standard TAK pattern. */
    val enrollUrl: String? = null,
    val username: String? = null,
    val trustSelfSigned: Boolean = true,
)
