package soy.engindearing.omnitak.mobile.domain

import android.content.Context
import android.util.Log
import android.util.Xml
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.xmlpull.v1.XmlPullParser
import soy.engindearing.omnitak.mobile.data.CaTrust
import soy.engindearing.omnitak.mobile.data.CertVault
import soy.engindearing.omnitak.mobile.data.ConnectionProtocol
import soy.engindearing.omnitak.mobile.data.TAKServer
import java.io.ByteArrayInputStream
import java.io.File
import java.io.StringReader
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.zip.ZipInputStream

/**
 * Auto-import any TAK data-package `.zip` dropped into the app's external
 * import dir (`<app>/files/import/`). Used as a sideloading path on real
 * devices where the SAF file picker is flaky for sub-MB cert files.
 *
 * Push a zip with `adb push <name>.zip /sdcard/Android/data/<app-id>/files/import/`
 * and the next app launch will:
 *   1. Stream-extract MANIFEST/manifest.xml, certs/server.pref, any .p12s
 *   2. Parse server.pref for connectString + cert name/password
 *   3. Save .p12s into [CertVault]
 *   4. Add the server via [ServerManager.addServer] (auto-connects)
 *   5. Rename the zip to `<name>.zip.imported` so re-launches are idempotent
 *
 * No external storage permission required — getExternalFilesDir is
 * app-private under Android's scoped storage rules.
 */
class DataPackageBootstrap(
    private val context: Context,
    private val certVault: CertVault,
    private val serverManager: ServerManager,
) {

    private val scope = CoroutineScope(Dispatchers.IO)

    fun runIfNeeded() {
        scope.launch {
            val dir = context.getExternalFilesDir(IMPORT_DIR) ?: return@launch
            if (!dir.exists()) return@launch
            val zips = dir.listFiles { f -> f.isFile && f.name.endsWith(".zip", ignoreCase = true) }
                ?: return@launch
            for (zip in zips) {
                runCatching { importZip(zip) }
                    .onSuccess {
                        val renamed = File(zip.parentFile, "${zip.name}.imported")
                        zip.renameTo(renamed)
                        Log.i(TAG, "Imported ${zip.name} → renamed to ${renamed.name}")
                    }
                    .onFailure {
                        Log.w(TAG, "Import of ${zip.name} failed: ${it.javaClass.simpleName}: ${it.message}")
                    }
            }
        }
    }

    private fun importZip(zip: File) {
        zip.inputStream().use { importZipStream(zip.nameWithoutExtension, it) }
    }

    /**
     * Import a TAK Connection Data Package (.zip) from any stream — both the
     * sideload watcher and the in-app file picker funnel here. Extracts certs
     * + the connection `.pref`, pins the server's CA so a private/self-signed
     * root is trusted, and adds (auto-connects) the server. Returns the added
     * server's display name. Runs on an IO thread (caller's responsibility).
     */
    fun importZipStream(zipName: String, input: java.io.InputStream): String {
        var prefXml: String? = null
        val p12s = LinkedHashMap<String, ByteArray>()
        ZipInputStream(input).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                if (entry.isDirectory) {
                    zin.closeEntry()
                    continue
                }
                val bytes = zin.readBytes()
                zin.closeEntry()
                val name = entry.name
                when {
                    // ATAK ships "defaults.pref"; some servers "server.pref" —
                    // accept any *.pref so we don't miss the connection config.
                    name.endsWith(".pref", ignoreCase = true) -> {
                        prefXml = String(bytes, Charsets.UTF_8)
                    }
                    name.endsWith(".p12", ignoreCase = true) || name.endsWith(".pfx", ignoreCase = true) -> {
                        val displayName = name.substringAfterLast('/')
                        p12s[displayName] = bytes
                        certVault.importBytes(displayName, bytes)
                    }
                }
            }
        }

        val xml = prefXml ?: error("no .pref connection file in $zipName")
        val parsed = parseServerPref(xml)
        val connect = parsed.connectString ?: error("connectString missing in $zipName")
        val (host, port, protoTag) = parseConnectString(connect)
        val useTLS = protoTag.equals("ssl", ignoreCase = true) || protoTag.equals("tls", ignoreCase = true)
        val caFileName = parsed.caLocation?.substringAfterLast('/')
        val certFileName = parsed.certificateLocation?.substringAfterLast('/')
            ?: p12s.keys.firstOrNull { !it.equals(caFileName, ignoreCase = true) }

        // Pin the server's CA so a private/self-signed root validates on the
        // streaming socket (system trust would reject it). TAK packages carry
        // the CA both as a dedicated truststore p12 (caLocation/caPassword)
        // AND inside the client p12's own chain — harvest from both.
        val caName = pinCaFromPackage(zipName, parsed, caFileName, certFileName, p12s)

        val server = TAKServer(
            name = parsed.description ?: zipName,
            host = host,
            port = port,
            protocol = if (useTLS) ConnectionProtocol.TLS.wire else ConnectionProtocol.TCP.wire,
            useTLS = useTLS,
            certificateName = certFileName,
            certificatePassword = parsed.clientPassword,
            caCertificateName = caName,
        )
        serverManager.addServer(server)
        Log.i(TAG, "Added server: ${server.name} → ${server.host}:${server.port} TLS=${server.useTLS} cert=${server.certificateName} caPin=${caName ?: "none(system)"}")
        return server.name
    }

    /**
     * Harvest the server CA from the package's p12s, write it to the vault as
     * a PEM pin, and return the vault name (or null → fall back to system
     * trust). Sources in priority: the truststore p12 (caLocation/caPassword),
     * then the CA carried in the client p12's chain. Only true CA certs are
     * pinned — the client leaf (basicConstraints == -1) is skipped.
     */
    private fun pinCaFromPackage(
        base: String,
        parsed: ParsedPref,
        caFileName: String?,
        certFileName: String?,
        p12s: Map<String, ByteArray>,
    ): String? {
        val caCerts = LinkedHashMap<String, X509Certificate>()
        fun harvest(bytes: ByteArray?, password: String?) {
            if (bytes == null || password == null) return
            for (cert in certsInP12(bytes, password)) {
                val isCa = cert.basicConstraints >= 0 ||
                    cert.subjectX500Principal == cert.issuerX500Principal
                if (isCa) caCerts.putIfAbsent(cert.encoded.contentHashCode().toString(), cert)
            }
        }
        harvest(caFileName?.let { p12s[it] }, parsed.caPassword ?: parsed.clientPassword)
        harvest(certFileName?.let { p12s[it] }, parsed.clientPassword)
        if (caCerts.isEmpty()) return null
        val pem = CaTrust.encodePemChain(caCerts.values.toList())
        return certVault.importBytes("$base-ca.pem", pem)
    }

    /** All X.509 certs (entry cert + its chain) inside a PKCS#12 blob, or empty on failure. */
    private fun certsInP12(bytes: ByteArray, password: String): List<X509Certificate> = runCatching {
        val ks = KeyStore.getInstance("PKCS12")
        ByteArrayInputStream(bytes).use { ks.load(it, password.toCharArray()) }
        buildList {
            for (alias in ks.aliases()) {
                (ks.getCertificate(alias) as? X509Certificate)?.let { add(it) }
                ks.getCertificateChain(alias)?.forEach { c -> (c as? X509Certificate)?.let { add(it) } }
            }
        }
    }.getOrElse {
        Log.w(TAG, "certsInP12 failed: ${it.javaClass.simpleName}: ${it.message}")
        emptyList()
    }

    private data class ParsedPref(
        val description: String?,
        val connectString: String?,
        val certificateLocation: String?,
        val clientPassword: String?,
        val caLocation: String?,
        val caPassword: String?,
    )

    private fun parseServerPref(xml: String): ParsedPref {
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))
        var description: String? = null
        var connect: String? = null
        var certLoc: String? = null
        var clientPw: String? = null
        var caLoc: String? = null
        var caPw: String? = null
        var event = parser.eventType
        var currentKey: String? = null
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "entry") {
                currentKey = parser.getAttributeValue(null, "key")
            } else if (event == XmlPullParser.TEXT && currentKey != null) {
                val text = parser.text?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    when (currentKey) {
                        "description0" -> description = text
                        "connectString0" -> connect = text
                        "certificateLocation" -> certLoc = text
                        "clientPassword" -> clientPw = text
                        "caLocation" -> caLoc = text
                        "caPassword" -> caPw = text
                    }
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "entry") {
                currentKey = null
            }
            event = parser.next()
        }
        return ParsedPref(description, connect, certLoc, clientPw, caLoc, caPw)
    }

    /** "host:port:proto" → triple. */
    private fun parseConnectString(s: String): Triple<String, Int, String> {
        val parts = s.split(":")
        require(parts.size >= 2) { "connectString must be host:port[:proto]: $s" }
        val host = parts[0]
        val port = parts[1].toInt()
        val proto = parts.getOrNull(2) ?: "tcp"
        return Triple(host, port, proto)
    }

    companion object {
        private const val TAG = "DataPackageBootstrap"
        private const val IMPORT_DIR = "import"
    }
}
