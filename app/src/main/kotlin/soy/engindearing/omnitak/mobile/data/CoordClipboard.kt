package soy.engindearing.omnitak.mobile.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * Verified clipboard write for the radial "Copy Coords" action — issue #92.
 *
 * A field report (Play closed track) had the success toast showing while
 * third-party apps pasted nothing. The write path couldn't be faulted in
 * repro — instrumented tests plus a full UI drive (radial → press-and-hold
 * Copy → cross-app paste) landed the clip on stock API 36 and Samsung
 * One UI 16 — so this hardens the only remaining seam: the old
 * `LocalClipboardManager.setText` returned nothing, and the toast claimed
 * success unconditionally. Here the clip goes through the platform
 * [ClipboardManager] and is read back before anyone says "Copied" —
 * an OEM-side silent drop (aggressive clipboard managers, focus races)
 * now surfaces as an honest failure instead of a lie.
 *
 * Read-back needs window focus on API 29+; the radial tap that triggers
 * the copy holds it. A blocked read fails closed — wrongly admitting
 * failure beats wrongly claiming success.
 */
object CoordClipboard {

    /**
     * Writes [text] to the system clipboard as plain text and returns
     * whether the clip verifiably landed. Never throws — OEM clipboard
     * services that reject the write report `false` instead.
     */
    fun copy(context: Context, text: String): Boolean = runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("coordinates", text))
        cm.primaryClip?.getItemAt(0)?.text?.toString() == text
    }.getOrDefault(false)
}
