package soy.engindearing.omnitak.mobile.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.core.content.ContextCompat
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import soy.engindearing.omnitak.mobile.R
import soy.engindearing.omnitak.mobile.data.CoTAffiliation
import soy.engindearing.omnitak.mobile.data.CoTEvent

/**
 * Renders the contacts overlay using MapLibre's Annotation API
 * (`map.addMarker(...)`) instead of GeoJsonSource + Circle/Symbol
 * layers.
 *
 * History: tried CircleLayer with inline-style geojson source,
 * then programmatic recreation, then SymbolLayer with bitmap icons
 * registered via `style.addImage`. None of those produced visible
 * pixels on real hardware (Adreno 610 was the worst case) even
 * though `style.layers` and `style.getImage` confirmed everything
 * was registered. MapLibre-Android 11.x has a paint pipeline that
 * silently drops circle/symbol layers on a subset of GL drivers.
 *
 * The Annotation API uses MapLibre's bundled `org.maplibre.annotations.points`
 * overlay, which is rendered via a native code path that works
 * across the same drivers — same path LocationComponent uses for
 * its foreground self-marker.
 *
 * Affiliation-to-color mapping follows the ATAK palette:
 *   friend → green, hostile → red, neutral → yellow, unknown → purple.
 */
object ContactLayer {
    private val markers = mutableMapOf<String, Marker>()
    private var iconFriend: org.maplibre.android.annotations.Icon? = null
    private var iconHostile: org.maplibre.android.annotations.Icon? = null
    private var iconNeutral: org.maplibre.android.annotations.Icon? = null
    private var iconUnknown: org.maplibre.android.annotations.Icon? = null

    fun update(map: MapLibreMap, context: Context, contacts: Collection<CoTEvent>) {
        ensureIcons(context)

        // Build a UID set for quick membership checks.
        val incomingByUid = contacts.associateBy { it.uid }

        // Remove markers for contacts that have been deleted.
        val toRemove = markers.keys - incomingByUid.keys
        toRemove.forEach { uid ->
            markers[uid]?.let { map.removeMarker(it) }
            markers.remove(uid)
        }

        // Add or update markers for incoming contacts.
        // Issue #23 — index-stable in-place update. The previous
        // implementation called removeMarker + addMarker on every PPLI
        // tick when the icon or position changed; the brief gap between
        // remove and re-add was visible as a flicker on tap. Mutating
        // the existing Marker's `position`, `title`, and `icon` keeps the
        // same native annotation alive across updates.
        val targetIcon = { c: CoTEvent -> iconFor(c.affiliation) }
        val targetTitle = { c: CoTEvent -> c.callsign ?: c.uid }
        contacts.forEach { c ->
            val existing = markers[c.uid]
            val ll = LatLng(c.lat, c.lon)
            if (existing == null) {
                val marker = map.addMarker(
                    MarkerOptions()
                        .position(ll)
                        .title(targetTitle(c))
                        .icon(targetIcon(c))
                )
                markers[c.uid] = marker
            } else {
                if (existing.position != ll) existing.position = ll
                val newIcon = targetIcon(c)
                if (existing.icon != newIcon) existing.icon = newIcon
                val newTitle = targetTitle(c)
                if (existing.title != newTitle) existing.title = newTitle
            }
        }
    }

    private fun ensureIcons(context: Context) {
        if (iconFriend != null) return
        val factory = IconFactory.getInstance(context)
        iconFriend = factory.fromBitmap(tintedDot(context, 0xFF4ADE80.toInt()))
        iconHostile = factory.fromBitmap(tintedDot(context, 0xFFF44336.toInt()))
        iconNeutral = factory.fromBitmap(tintedDot(context, 0xFFFFC107.toInt()))
        iconUnknown = factory.fromBitmap(tintedDot(context, 0xFFB39DDB.toInt()))
    }

    private fun iconFor(affiliation: CoTAffiliation): org.maplibre.android.annotations.Icon =
        when (affiliation) {
            CoTAffiliation.FRIEND -> iconFriend!!
            CoTAffiliation.HOSTILE -> iconHostile!!
            CoTAffiliation.NEUTRAL -> iconNeutral!!
            else -> iconUnknown!!
        }

    private fun tintedDot(context: Context, colorArgb: Int): Bitmap {
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_contact_dot)!!.mutate()
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 72
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 72
        drawable.setBounds(0, 0, w, h)
        drawable.colorFilter = PorterDuffColorFilter(colorArgb, PorterDuff.Mode.SRC_IN)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawable.draw(Canvas(bmp))
        return bmp
    }

    /** Stable color for previewing an affiliation outside the map. */
    fun previewColor(affiliation: CoTAffiliation): Int = when (affiliation) {
        CoTAffiliation.FRIEND -> 0xFF4ADE80.toInt()
        CoTAffiliation.HOSTILE -> 0xFFF44336.toInt()
        CoTAffiliation.NEUTRAL -> 0xFFFFC107.toInt()
        else -> 0xFFB39DDB.toInt()
    }
}
