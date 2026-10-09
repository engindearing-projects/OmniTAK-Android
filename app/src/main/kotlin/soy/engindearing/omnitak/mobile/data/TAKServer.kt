package soy.engindearing.omnitak.mobile.data

import kotlinx.serialization.Serializable
import java.util.UUID

enum class ConnectionProtocol(val wire: String) {
    TCP("tcp"),
    UDP("udp"),
    TLS("tls"),
    WebSocket("ws");

    companion object {
        fun fromWire(s: String): ConnectionProtocol =
            entries.firstOrNull { it.wire.equals(s, ignoreCase = true) } ?: TCP
    }
}

@Serializable
data class TAKServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int,
    val protocol: String = ConnectionProtocol.TCP.wire,
    val useTLS: Boolean = false,
    val enabled: Boolean = true,
    val isDefault: Boolean = false,
    val certificateName: String? = null,
    val caCertificateName: String? = null,
    val username: String? = null,
    // GAP-105 — basic-auth password. Persisted in the Keystore-backed
    // SecureCredentialStore, NOT in the server-list JSON: TAKServerStore
    // strips this field on save and rehydrates it on read (with a one-time
    // migration for pre-0.36 plaintext blobs). In-memory instances carry
    // the real value.
    val password: String? = null,
    // GAP-105 — passphrase for the PKCS12 referenced by [certificateName].
    // Same encrypted-at-rest handling as [password]; the CertVault stores
    // the .p12 bytes themselves in app-internal storage (off the JSON blob).
    val certificatePassword: String? = null,
    // Explicit opt-out of server TLS validation (trust-all + no hostname
    // check) for this server. Default false — every connection plane
    // (streaming socket, Marti REST) validates against the enrollment CA
    // pin or the system trust store unless the operator flips this.
    // Decodes as false from pre-0.36 JSON blobs (kotlinx default).
    val allowUntrustedTls: Boolean = false,
    // iOS #169 parity — the enrollment/OAuth port (TAK default 8446). It is
    // the Marti API's second route: when the certificate port refuses the
    // handshake and credentials are stored, the REST client signs in here.
    // Decodes as 8446 from older JSON blobs (kotlinx default).
    val enrollmentPort: Int = 8446,
    // Marti REST port when it is not the conventional 8443 (iOS #114 parity);
    // null means 8443. Decodes as null from older JSON blobs.
    val secureApiPort: Int? = null,
) {
    val displayName: String get() = "$name ($host:$port)"

    /** A username and password are stored: the second route to the Marti API. */
    val hasStoredCredentials: Boolean get() = !username.isNullOrBlank() && !password.isNullOrBlank()

    val protocolEnum: ConnectionProtocol get() = ConnectionProtocol.fromWire(protocol)

    /**
     * Two servers point at the same TAK endpoint when host + port + protocol
     * match (host compared case-insensitively). Credentials and display name
     * are deliberately excluded so re-importing the same server with updated
     * certs is still considered a duplicate — mirrors iOS #42 rule.
     */
    fun matchesEndpoint(other: TAKServer): Boolean =
        host.equals(other.host, ignoreCase = true) &&
                port == other.port &&
                protocol.equals(other.protocol, ignoreCase = true)
}
