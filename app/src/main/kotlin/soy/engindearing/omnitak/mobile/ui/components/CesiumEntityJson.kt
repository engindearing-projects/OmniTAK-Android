package soy.engindearing.omnitak.mobile.ui.components

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import soy.engindearing.omnitak.mobile.data.CoTAffiliation
import soy.engindearing.omnitak.mobile.data.CoTEvent
import soy.engindearing.omnitak.mobile.data.symbology.MilStdIconService
import soy.engindearing.omnitak.mobile.data.symbology.TakIconRegistry

/**
 * Build the entity JSON payload `window.OmniBridge.setEntities(...)` consumes.
 * Includes a MIL-STD-2525 `sidc` for each contact so cesium_scene.html's
 * `_billboard()` takes the milsymbol branch and renders the proper symbol
 * (multirotor / fixed-wing UAS / infantry / etc.) instead of falling back
 * to a generic affiliation frame. Self stays sidc-less so it keeps the
 * friendly-affiliation frame circle.
 *
 * Issue #98 — a contact resolving to a standard TAK icon set carries its glyph
 * so the globe matches the 2D map + picker exactly:
 *  - Spot Map (via `<usericon iconsetpath>` / the `b-m-p-s-m` type) emits a
 *    `spot` hex colour and the JS side draws the canvas dot.
 *  - Markers / Google packs emit a fully-rendered `icon` data-URL (the same
 *    badge bitmap the 2D map registers), so the JS side billboards it directly
 *    without re-implementing each glyph.
 * Resolution order mirrors ATAK: iconset path first, then CoT type, then SIDC.
 *
 * @param context decodes the Markers / Google vector glyphs for the `icon`
 *   data-URL; when null those packs fall back to their SIDC on the globe.
 */
internal fun buildCesiumEntitiesJson(
    contacts: List<CoTEvent>,
    selfLat: Double?,
    selfLon: Double?,
    selfCallsign: String,
    context: Context? = null,
): String {
    val arr = JSONArray()
    if (selfLat != null && selfLon != null && !selfLat.isNaN() && !selfLon.isNaN()) {
        arr.put(
            JSONObject().apply {
                put("uid", "__self__")
                put("lat", selfLat)
                put("lon", selfLon)
                put("callsign", selfCallsign)
                put("affiliation", "f")
                put("kind", "self")
            },
        )
    }
    for (c in contacts) {
        if (c.lat.isNaN() || c.lon.isNaN()) continue
        arr.put(
            JSONObject().apply {
                put("uid", c.uid)
                put("lat", c.lat)
                put("lon", c.lon)
                if (c.hae != 0.0) put("hae", c.hae)
                c.callsign?.let { put("callsign", it) }
                put("affiliation", affChar(c.affiliation))
                put("kind", "contact")
                // #98 — TAK icon-suite resolves first, mirroring ATAK order:
                //   1. Spot Map → `spot` hex (JS draws the canvas dot); colour
                //      rides <color argb>, else the swatch default.
                //   2. Markers / Google → `icon` data-URL (the same badge bitmap
                //      the 2D map registers), so the globe matches exactly.
                //   3. otherwise the MIL-STD `sidc`.
                // Setting `spot` or `icon` skips the milsymbol branch on JS.
                val spotHex = spotHexFor(c)
                val badgeUrl = if (spotHex == null && context != null) badgeUrlFor(context, c) else null
                when {
                    spotHex != null -> put("spot", spotHex)
                    badgeUrl != null -> put("icon", badgeUrl)
                    else -> put("sidc", MilStdIconService.getSidc(c.type))
                }
            },
        )
    }
    return arr.toString()
}

/**
 * `#RRGGBB` for a contact that resolves to a Spot Map icon, else null. Mirrors
 * [TakIconRegistry] resolution: an explicit Spot Map iconset path or the
 * `b-m-p-s-m` CoT type qualifies; the colour comes from `<color argb>` when the
 * event carried one, otherwise the swatch named by the path (default white).
 * Markers / Google packs are NOT spot dots — they go through [badgeUrlFor].
 */
private fun spotHexFor(c: CoTEvent): String? {
    val isSpot = c.type == TakIconRegistry.SpotIcon.COT_TYPE ||
        (c.iconsetPath?.let { TakIconRegistry.SpotIcon.fromIconsetPath(it) != null } == true)
    if (!isSpot) return null
    val argb = c.colorArgb?.let { TakIconRegistry.normalizeArgb(it) }
        ?: c.iconsetPath?.let { TakIconRegistry.SpotIcon.fromIconsetPath(it)?.argb }
        ?: TakIconRegistry.SpotIcon.WHITE.argb
    return "#%06X".format(argb and 0x00FFFFFF)
}

/**
 * `data:image/png;base64,…` for a contact that resolves to a Markers / Google
 * badge icon, else null. The data-URL is the exact same badge bitmap the 2D
 * MapLibre layer registers, so the globe and the flat map render identically.
 */
private fun badgeUrlFor(context: Context, c: CoTEvent): String? {
    if (TakIconRegistry.resolveBadgeIcon(c.iconsetPath) == null) return null
    val argb = c.colorArgb?.let { TakIconRegistry.normalizeArgb(it) }
    return TakIconRegistry.dataUrlFor(context, c.type, c.iconsetPath, argb)
}

private fun affChar(a: CoTAffiliation): String = when (a) {
    CoTAffiliation.FRIEND -> "f"
    CoTAffiliation.HOSTILE -> "h"
    CoTAffiliation.NEUTRAL -> "n"
    else -> "u"
}
