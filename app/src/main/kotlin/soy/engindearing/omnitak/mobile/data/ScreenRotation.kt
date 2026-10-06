package soy.engindearing.omnitak.mobile.data

import android.content.pm.ActivityInfo

/**
 * #214 - how the app holds the screen: follow the phone, or stay in portrait or
 * landscape whatever the phone's own rotation setting is. Phones in mounts and chest
 * rigs turn at the wrong moment; this is the operator's way to stop that.
 *
 * Free of anything that needs a device, so the rules can be unit tested on the JVM:
 * the stored values, the cycle order, the Activity orientation each mode asks for and
 * the words shown to the operator. (The ActivityInfo values are compile-time constants.)
 */
enum class ScreenRotation(
    /** The value kept in the preferences. The iOS build stores the same three strings. */
    val stored: String,
    /** Short name for Settings and for the accessibility label. */
    val label: String,
    /** The message shown when the operator changes to this mode. */
    val message: String,
    /** What the main Activity asks the system for: [android.app.Activity.setRequestedOrientation]. */
    val orientation: Int,
) {
    /** As before this setting existed: the app follows the phone and its rotation lock. */
    AUTO(
        stored = "auto",
        label = "Auto",
        message = "Screen follows the phone",
        orientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
    ),

    /** Upright portrait, whatever the phone's rotation setting is. */
    PORTRAIT(
        stored = "portrait",
        label = "Portrait",
        message = "Screen stays in portrait",
        orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
    ),

    /**
     * Landscape, whatever the phone's rotation setting is. It may use either landscape
     * direction, following which way the phone is turned, so a mount works either way
     * round; it never goes to portrait. SENSOR_LANDSCAPE keeps reading the sensor even
     * while the phone's rotation lock is on.
     */
    LANDSCAPE(
        stored = "landscape",
        label = "Landscape",
        message = "Screen stays in landscape",
        orientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
    );

    /** The next mode when the operator taps the shortcut: Auto, Portrait, Landscape, Auto. */
    fun next(): ScreenRotation = entries[(ordinal + 1) % entries.size]

    /** What a screen reader says for the shortcut, for example "Screen rotation: Auto". */
    val contentDescription: String get() = "Screen rotation: $label"

    companion object {
        val DEFAULT: ScreenRotation = AUTO

        /** The mode for a stored value. Anything that is not one of the three reads as Auto. */
        fun fromStored(value: String?): ScreenRotation =
            entries.firstOrNull { it.stored == value } ?: DEFAULT
    }
}
