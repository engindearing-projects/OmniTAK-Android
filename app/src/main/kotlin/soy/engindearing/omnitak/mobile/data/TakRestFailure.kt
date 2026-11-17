package soy.engindearing.omnitak.mobile.data

import java.io.EOFException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * How the REST session authenticates to the Marti API (iOS #169 parity).
 *
 *  - [ClientCertificate]: mutual TLS on the certificate port (8443).
 *  - [Bearer]: OAuth2 password grant on the enrollment port; TAK Server 5.x
 *    serves the Marti API there with `Authorization: Bearer` (HTTP Basic on
 *    that port only reaches the enrollment endpoints).
 *  - [Basic]: HTTP Basic on the enrollment port (OpenTAKServer, taky).
 */
sealed interface TakRestAuthRoute {
    data object ClientCertificate : TakRestAuthRoute
    data class Bearer(val port: Int) : TakRestAuthRoute
    data class Basic(val port: Int) : TakRestAuthRoute

    val label: String
        get() = when (this) {
            ClientCertificate -> "client certificate"
            is Bearer -> "username and password ($port)"
            is Basic -> "username and password ($port)"
        }

    val usesCredentials: Boolean get() = this != ClientCertificate

    fun authorizationHeader(username: String?, password: String?, token: String?): String? = when (this) {
        ClientCertificate -> null
        is Bearer -> token?.takeIf { it.isNotBlank() }?.let { "Bearer $it" }
        is Basic ->
            if (username.isNullOrBlank() || password.isNullOrBlank()) null
            else "Basic " + java.util.Base64.getEncoder()
                .encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
    }
}

/**
 * A Marti REST request that never got an HTTP answer, explained. The
 * platform exception says "Connection reset" or "Received fatal alert:
 * certificate_required"; combined with what this entry has configured, the
 * same failure reads "the server requires a client certificate and this
 * server has none", which a user can act on. Pure, so it is unit-tested.
 */
object TakRestFailure {

    /** One or two sentences for the server row. Names the endpoint and what to do. */
    fun describe(t: Throwable, host: String, port: Int, clientCertificateName: String?): String {
        val endpoint = "$host:$port"
        val msg = (t.message ?: "").lowercase()
        val certHint = if (clientCertificateName == null) {
            "It requires a client certificate and this server has none. Enroll with your username and password, or import a .p12."
        } else {
            "It did not accept the client certificate \"$clientCertificateName\": check that it was issued by this server's CA, or re-enroll."
        }
        return when {
            t is SSLHandshakeException && CLIENT_CERT_ALERTS.any { msg.contains(it) } ->
                "$endpoint refused the TLS handshake. $certHint"
            t is SSLHandshakeException && (UNTRUSTED_MARKERS.any { msg.contains(it) } || t.cause is CertificateException) ->
                "The certificate $endpoint presented is not trusted and no truststore is pinned for this server. " +
                    "Enroll to install the server CA, or turn on Trust untrusted certificates."
            t is SSLPeerUnverifiedException ->
                "The certificate $endpoint presented does not match its host name or is not trusted. " +
                    "Enroll to install the server CA, or turn on Trust untrusted certificates."
            t is SSLHandshakeException ->
                "TLS handshake with $endpoint failed before authentication: ${t.message}"
            // A TAK Server's Tomcat completes the handshake and then closes the
            // connection when the client certificate is missing or unacceptable.
            t is SSLException || t is EOFException || (t is SocketException && t !is ConnectException) ->
                "$endpoint closed the connection right after the TLS handshake. $certHint"
            t is ConnectException ->
                "$endpoint refused the connection. Check the Marti API port (usually 8443)."
            t is UnknownHostException ->
                "Could not resolve $host."
            t is SocketTimeoutException ->
                "$endpoint did not answer in time."
            else ->
                "$endpoint: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    /**
     * Whether trying the enrollment port with a username and password could
     * help: anything the certificate port did at the TLS or TCP layer, not a
     * host that cannot be resolved or a request that timed out.
     */
    fun allowsCredentialFallback(t: Throwable): Boolean = when (t) {
        is UnknownHostException, is SocketTimeoutException -> false
        is SSLException, is EOFException, is SocketException, is CertificateException -> true
        else -> false
    }

    /** Everything a bug report needs, one fact per line. Never a password. */
    fun detail(
        t: Throwable,
        host: String,
        port: Int,
        route: TakRestAuthRoute,
        clientCertificateName: String?,
        caCertificateName: String?,
        allowUntrustedTls: Boolean,
    ): String = listOfNotNull(
        "Server: $host:$port",
        "Route: ${route.label}",
        "System error: ${t.javaClass.name}: ${t.message}",
        t.cause?.let { "Caused by: ${it.javaClass.name}: ${it.message}" },
        "Trust policy: " + when {
            allowUntrustedTls -> "trust untrusted certificates (on)"
            caCertificateName != null -> "pinned CA \"$caCertificateName\""
            else -> "system trust"
        },
        "Client certificate: ${clientCertificateName ?: "none configured"}",
    ).joinToString("\n")

    private val CLIENT_CERT_ALERTS = listOf(
        "certificate_required", "certificate required", "bad_certificate", "bad certificate",
        "unknown_ca", "certificate_unknown", "handshake_failure",
    )
    private val UNTRUSTED_MARKERS = listOf(
        "trust anchor", "certpath", "not trusted", "unable to find valid certification path", "pkix",
    )
}
