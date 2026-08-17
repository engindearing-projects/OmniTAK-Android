package soy.engindearing.omnitak.mobile.data.discovery

import soy.engindearing.omnitak.mobile.data.ConnectionProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #158 — LAN TAK server discovery over Bonjour/mDNS. NsdManager does the
 * browse/resolve on-device; this pins the pure part — turning a resolved
 * `_tak._tcp` service into a TAKServer ready to prefill Add-Server, including
 * the TLS decision from TXT records or the port convention.
 */
class TakMdnsTest {

    private fun svc(
        name: String = "TAK Server",
        host: String = "192.168.1.50",
        port: Int = 8089,
        attrs: Map<String, String> = emptyMap(),
    ) = DiscoveredTakService(name, host, port, attrs)

    @Test fun service_type_is_tak_tcp() {
        assertEquals("_tak._tcp", TakMdns.SERVICE_TYPE)
    }

    @Test fun maps_host_port_and_name() {
        val s = TakMdns.toTakServer(svc(name = "OPS", host = "10.0.0.5", port = 8088))
        assertEquals("OPS", s.name)
        assertEquals("10.0.0.5", s.host)
        assertEquals(8088, s.port)
    }

    @Test fun name_falls_back_to_host_when_blank() {
        assertEquals("10.0.0.5", TakMdns.toTakServer(svc(name = "  ", host = "10.0.0.5")).name)
    }

    @Test fun tls_streaming_port_8089_is_tls() {
        val s = TakMdns.toTakServer(svc(port = 8089))
        assertTrue(s.useTLS)
        assertEquals(ConnectionProtocol.TLS, s.protocolEnum)
    }

    @Test fun plain_port_8088_is_tcp() {
        val s = TakMdns.toTakServer(svc(port = 8088))
        assertFalse(s.useTLS)
        assertEquals(ConnectionProtocol.TCP, s.protocolEnum)
    }

    @Test fun txt_tls_true_overrides_a_plain_port() {
        assertTrue(TakMdns.toTakServer(svc(port = 8088, attrs = mapOf("tls" to "true"))).useTLS)
    }

    @Test fun txt_tls_false_overrides_a_tls_port() {
        assertFalse(TakMdns.toTakServer(svc(port = 8089, attrs = mapOf("tls" to "false"))).useTLS)
    }

    @Test fun txt_protocol_ssl_is_tls() {
        assertTrue(TakMdns.toTakServer(svc(port = 8088, attrs = mapOf("protocol" to "ssl"))).useTLS)
    }
}
