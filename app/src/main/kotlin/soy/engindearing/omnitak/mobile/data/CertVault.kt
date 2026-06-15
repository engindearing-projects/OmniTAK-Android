package soy.engindearing.omnitak.mobile.data

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * App-internal store for client `.p12` (and CA `.pem`) files referenced by
 * [TAKServer.certificateName] / [TAKServer.caCertificateName].
 *
 * Why a separate store instead of stuffing bytes into the JSON DataStore:
 *   - cert files can be 2–10 KB each; the DataStore JSON blob would grow
 *     unbounded with every server added
 *   - keeping the bytes off-JSON means we can later swap to encrypted
 *     storage without touching the TAKServer schema
 *   - mirrors the iOS pattern (FileManager directory next to UserDefaults)
 *
 * Files are kept under `<filesDir>/tak-certs/` with the original display
 * name, sanitized to remove path traversal characters. If the same name is
 * imported twice, the new bytes overwrite the old — callers that care about
 * versioning should namespace the name themselves.
 *
 * Security posture: the `.p12` blobs are NOT app-layer encrypted — they
 * rely on Android file-based encryption plus `allowBackup=false` /
 * dataExtractionRules (set in the manifest). Their passphrases, however,
 * live in the Keystore-backed [SecureCredentialStore] rather than in
 * plaintext JSON, so a leaked vault file alone is not enough to unlock the
 * client key. Next hardening step if ever needed: EncryptedFile, or import
 * the keys into AndroidKeyStore as non-exportable entries.
 *
 * [read] is `open` so pure-JVM unit tests can subclass with an in-memory
 * map without requiring Robolectric or a real Android Context.
 */
open class CertVault protected constructor(private val dir: File) {

    /** Primary production constructor — resolves [dir] from [context]. */
    constructor(context: Context) : this(
        File(context.filesDir, DIRNAME).also { it.mkdirs() },
    )

    /**
     * Copy [uri] into the vault using [displayName] as the on-disk filename.
     * Returns the sanitized filename actually written, or null if the copy
     * failed (which is the only signal callers need — the actual cert bytes
     * never leave the IO thread).
     */
    fun import(context: Context, uri: Uri, displayName: String): String? {
        val safeName = sanitize(displayName)
        if (safeName.isBlank()) return null
        val target = File(dir, safeName)
        return try {
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return null
                target.outputStream().use { out -> input.copyTo(out) }
            }
            safeName
        } catch (t: Throwable) {
            Log.w(TAG, "import failed: ${t.javaClass.simpleName}: ${t.message}")
            target.delete()
            null
        }
    }

    /**
     * Save a cert that's already in memory (e.g. extracted from a zip).
     * Returns the sanitized filename written, or null on IO failure.
     */
    fun importBytes(displayName: String, bytes: ByteArray): String? {
        val safeName = sanitize(displayName)
        if (safeName.isBlank()) return null
        val target = File(dir, safeName)
        return try {
            target.writeBytes(bytes)
            safeName
        } catch (t: Throwable) {
            Log.w(TAG, "importBytes failed: ${t.javaClass.simpleName}: ${t.message}")
            target.delete()
            null
        }
    }

    /** Read the raw bytes for [name], or null if missing/unreadable. */
    open fun read(name: String): ByteArray? {
        val f = File(dir, sanitize(name))
        if (!f.exists()) return null
        return runCatching { f.readBytes() }.getOrNull()
    }

    /** Delete [name] from the vault. No-op if absent. */
    fun remove(name: String) {
        File(dir, sanitize(name)).delete()
    }

    private fun sanitize(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\').take(MAX_NAME_LEN)
            .replace(Regex("[^A-Za-z0-9._-]"), "_")

    companion object {
        private const val TAG = "CertVault"
        private const val DIRNAME = "tak-certs"
        private const val MAX_NAME_LEN = 96
    }
}
