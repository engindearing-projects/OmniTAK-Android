package soy.engindearing.omnitak.mobile.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
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
        // Issue #21 — MIL-STD-2525B affiliation frames. Each frame has a
        // standard shape per ATAK convention:
        //   FRIEND   → cyan rounded rectangle
        //   HOSTILE  → red diamond
        //   NEUTRAL  → green square
        //   UNKNOWN  → yellow quatrefoil (drawn as a circle with four
        //              cardinal lobes, simplified for raster output)
        // Frames carry the affiliation; the inner pip stays the same
        // shape so contacts of unknown frame still read as contacts.
        val factory = IconFactory.getInstance(context)
        iconFriend = factory.fromBitmap(framedIcon(CoTAffiliation.FRIEND, 0xFF4ADE80.toInt()))
        iconHostile = factory.fromBitmap(framedIcon(CoTAffiliation.HOSTILE, 0xFFF44336.toInt()))
        iconNeutral = factory.fromBitmap(framedIcon(CoTAffiliation.NEUTRAL, 0xFFFFC107.toInt()))
        iconUnknown = factory.fromBitmap(framedIcon(CoTAffiliation.UNKNOWN, 0xFFB39DDB.toInt()))
    }

    private fun iconFor(affiliation: CoTAffiliation): org.maplibre.android.annotations.Icon =
        when (affiliation) {
            CoTAffiliation.FRIEND -> iconFriend!!
            CoTAffiliation.HOSTILE -> iconHostile!!
            CoTAffiliation.NEUTRAL -> iconNeutral!!
            else -> iconUnknown!!
        }

    private fun framedIcon(affiliation: CoTAffiliation, colorArgb: Int): Bitmap {
        val size = 72
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val pad = 6f
        val left = pad
        val top = pad
        val right = size - pad
        val bottom = size - pad
        val cx = size / 2f
        val cy = size / 2f

        // Outer dark stroke for contrast on light basemaps.
        val stroke = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = 0xFF0A1628.toInt()
        }
        val fill = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = colorArgb
        }

        when (affiliation) {
            CoTAffiliation.FRIEND -> {
                // Rounded rectangle frame.
                val rect = RectF(left, top + 8f, right, bottom - 8f)
                val r = 12f
                canvas.drawRoundRect(rect, r, r, fill)
                canvas.drawRoundRect(rect, r, r, stroke)
            }
            CoTAffiliation.HOSTILE -> {
                // Diamond.
                val path = Path().apply {
                    moveTo(cx, top)
                    lineTo(right, cy)
                    lineTo(cx, bottom)
                    lineTo(left, cy)
                    close()
                }
                canvas.drawPath(path, fill)
                canvas.drawPath(path, stroke)
            }
            CoTAffiliation.NEUTRAL -> {
                // Square.
                val rect = RectF(left + 4f, top + 4f, right - 4f, bottom - 4f)
                canvas.drawRect(rect, fill)
                canvas.drawRect(rect, stroke)
            }
            else -> {
                // Unknown / pending / suspect / exercise / assumed:
                // simplified quatrefoil — circle with four cardinal lobes.
                // Center disc, plus 4 small overlapping discs N/E/S/W.
                val r = (size / 2f) - pad - 4f
                canvas.drawCircle(cx, cy, r * 0.55f, fill)
                canvas.drawCircle(cx, cy, r * 0.55f, stroke)
                val lobeR = r * 0.35f
                val offset = r * 0.55f
                listOf(
                    cx to cy - offset,
                    cx + offset to cy,
                    cx to cy + offset,
                    cx - offset to cy,
                ).forEach { (lx, ly) ->
                    canvas.drawCircle(lx, ly, lobeR, fill)
                    canvas.drawCircle(lx, ly, lobeR, stroke)
                }
            }
        }
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
