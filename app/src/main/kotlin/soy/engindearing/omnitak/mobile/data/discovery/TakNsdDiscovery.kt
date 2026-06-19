package soy.engindearing.omnitak.mobile.data.discovery

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import soy.engindearing.omnitak.mobile.data.TAKServer
import java.util.concurrent.ConcurrentHashMap

/**
 * NsdManager-backed Bonjour/mDNS discovery of `_tak._tcp` servers on the LAN
 * (#158). [discover] emits the current set of resolved servers as the browse
 * runs, and stops discovery when the collector cancels.
 *
 * Device-only (NsdManager is an Android system service); the resolved-service →
 * [TAKServer] mapping it relies on is unit-tested in [TakMdns]. The Add-Server
 * UI collects this and prefills a tapped entry.
 */
class TakNsdDiscovery(private val nsdManager: NsdManager) {

    // NsdManager expects the registration-style type with a trailing dot.
    private val type = "${TakMdns.SERVICE_TYPE}."

    fun discover(): Flow<List<TAKServer>> = callbackFlow {
        // Keyed by mDNS instance name so a re-resolve replaces, not duplicates.
        val found = ConcurrentHashMap<String, TAKServer>()
        fun publish() { trySend(found.values.sortedBy { it.name }) }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                // A fresh ResolveListener per resolve — NsdManager rejects a reused one.
                nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val host = resolved.host?.hostAddress ?: return
                        val attrs = resolved.attributes.orEmpty()
                            .mapKeys { it.key.lowercase() }
                            .mapValues { (_, v) -> v?.toString(Charsets.UTF_8).orEmpty() }
                        found[resolved.serviceName] = TakMdns.toTakServer(
                            DiscoveredTakService(resolved.serviceName, host, resolved.port, attrs),
                        )
                        publish()
                    }

                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                })
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                if (found.remove(info.serviceName) != null) publish()
            }

            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        nsdManager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        awaitClose { runCatching { nsdManager.stopServiceDiscovery(discoveryListener) } }
    }
}
