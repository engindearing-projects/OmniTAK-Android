package soy.engindearing.omnitak.mobile.data

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Renders a [ConfigProfile] QR code as an Android [Bitmap].
 *
 * Uses the ZXing `core` library (pure Java, no Android resources).
 * Error-correction level M is the default: tolerates ~15% surface damage
 * (good for printed/laminated team cards) while keeping the module count
 * low enough to scan from across a table.
 */
object ProfileQrGenerator {

    /**
     * Generate a square [Bitmap] containing the QR code for [profile].
     *
     * @param profile  The profile to encode.
     * @param sizePx   Width and height of the output bitmap in pixels.
     *                 512 × 512 is a safe default for Compose `Image`.
     * @param darkColor Foreground colour (default opaque black).
     * @param lightColor Background colour (default opaque white).
     * @return Rendered bitmap, or null if encoding fails (shouldn't happen for
     *         well-formed profiles, but callers should handle null defensively).
     */
    fun generate(
        profile: ConfigProfile,
        sizePx: Int = 512,
        darkColor: Int = Color.BLACK,
        lightColor: Int = Color.WHITE,
    ): Bitmap? {
        val content = ProfileQrCodec.encode(profile)
        return renderQr(content, sizePx, darkColor, lightColor)
    }

    /**
     * Render any string as a QR [Bitmap]. Exposed for testing.
     */
    fun renderQr(
        content: String,
        sizePx: Int = 512,
        darkColor: Int = Color.BLACK,
        lightColor: Int = Color.WHITE,
    ): Bitmap? = runCatching {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 2,
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx) { idx ->
            if (matrix[idx % sizePx, idx / sizePx]) darkColor else lightColor
        }
        Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        }
    }.getOrNull()
}
