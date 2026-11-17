package soy.engindearing.omnitak.mobile.domain

import android.content.Context
import soy.engindearing.omnitak.mobile.data.TAKServer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's own log, readable from inside the app (iOS #169 parity).
 * Android lets a process read its own logcat lines without a permission;
 * this collects them, puts a header in front (versions, device, server
 * entries with their trust settings, never a password) and writes a .txt
 * for the share sheet. The rendering is pure so it is unit-tested.
 */
object DiagnosticsLog {

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzz", Locale.US)
    private val fileStamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /** What every report starts with. */
    fun header(
        appVersion: String,
        versionCode: Int,
        androidRelease: String,
        sdkInt: Int,
        device: String,
        servers: List<TAKServer>,
        now: Long = System.currentTimeMillis(),
    ): String {
        val lines = mutableListOf(
            "OmniTAK diagnostics",
            "Generated: ${stamp.format(Date(now))}",
            "App: $appVersion ($versionCode)",
            "Android: $androidRelease (API $sdkInt), device: $device",
            "Servers: ${servers.size}",
        )
        servers.forEach { lines += describe(it) }
        return lines.joinToString("\n")
    }

    /** One line per server: endpoint, ports, what secures it. No secrets. */
    fun describe(s: TAKServer): String {
        val parts = mutableListOf("- ${s.name}: ${s.host}:${s.port} ${s.protocol}${if (s.useTLS) " tls" else ""}")
        parts += if (s.enabled) "enabled" else "disabled"
        parts += "enroll ${s.enrollmentPort}"
        parts += "cert ${s.certificateName ?: "none"}"
        parts += "truststore ${s.caCertificateName ?: "none"}"
        if (s.allowUntrustedTls) parts += "trust-untrusted ON"
        parts += if (s.username.isNullOrBlank()) "no credentials" else "credentials stored"
        return parts.joinToString(", ")
    }

    /** The full export: header, blank line, one log line per entry. */
    fun render(header: String, lines: List<String>): String =
        header + "\n\nLog (${lines.size} lines)\n" + lines.joinToString("\n")

    /**
     * This process's logcat lines, oldest first, capped to the most recent
     * [limit]. Logcat's "beginning of main" separators are dropped.
     */
    fun readLogcat(pid: Int = android.os.Process.myPid(), limit: Int = 3000): List<String> {
        val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "--pid=$pid"))
        val lines = process.inputStream.bufferedReader().useLines { seq ->
            seq.filter { it.isNotBlank() && !it.startsWith("--------- beginning of") }.toMutableList()
        }
        process.waitFor()
        return if (lines.size > limit) lines.subList(lines.size - limit, lines.size) else lines
    }

    /** Writes the export under the FileProvider's exports folder. */
    fun exportFile(context: Context, text: String, now: Long = System.currentTimeMillis()): File {
        val dir = File(context.externalCacheDir ?: context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "OmniTAK-diagnostics-${fileStamp.format(Date(now))}.txt")
        file.writeText(text)
        return file
    }
}
