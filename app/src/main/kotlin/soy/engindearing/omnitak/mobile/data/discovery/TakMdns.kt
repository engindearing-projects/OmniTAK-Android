package soy.engindearing.omnitak.mobile.data.discovery

import soy.engindearing.omnitak.mobile.data.ConnectionProtocol
import soy.engindearing.omnitak.mobile.data.TAKServer

/**
 * LAN TAK server discovery over Bonjour/mDNS (#158, Android parity — iOS
 * declares `_tak._tcp` in Info.plist). The NsdManager browse/resolve lives in
 * [TakNsdDiscovery]; this is the pure part: a resolved service and the mapping
 * to a [TAKServer] ready to prefill Add-Server, so it unit-tests directly.
 */

/** A resolved `_tak._tcp` service from the mDNS responder. */
data class DiscoveredTakService(
    val serviceName: String,
    val host: String,
    val port: Int,
    /** TXT-record attributes, lower-cased keys. */
    val attributes: Map<String, String> = emptyMap(),
)

object TakMdns {

    /** The TAK service type advertised on the LAN (matches iOS' Info.plist). */
    const val SERVICE_TYPE = "_tak._tcp"

    /** TAK streaming/secure ports that imply TLS when no TXT record says otherwise. */
    private val TLS_PORTS = setOf(8089, 8443, 8446, 443)

    /**
     * Decide whether a discovered service is TLS. A `tls` TXT record wins, then
     * a `protocol` TXT record (ssl/tls vs tcp), then the port convention.
     */
    fun isTls(service: DiscoveredTakService): Boolean {
        service.attributes["tls"]?.let { v ->
            return v.equals("true", ignoreCase = true) || v == "1"
        }
        service.attributes["protocol"]?.let { p ->
            if (p.contains("ssl", ignoreCase = true) || p.contains("tls", ignoreCase = true)) return true
            if (p.contains("tcp", ignoreCase = true)) return false
        }
        return service.port in TLS_PORTS
    }

    /**
     * Map a resolved service to a [TAKServer] (new id, sensible defaults) the
     * Add-Server flow can prefill. Name falls back to the host when the mDNS
     * instance name is blank.
     */
    fun toTakServer(service: DiscoveredTakService): TAKServer {
        val tls = isTls(service)
        return TAKServer(
            name = service.serviceName.ifBlank { service.host },
            host = service.host,
            port = service.port,
            protocol = if (tls) ConnectionProtocol.TLS.wire else ConnectionProtocol.TCP.wire,
            useTLS = tls,
        )
    }
}
