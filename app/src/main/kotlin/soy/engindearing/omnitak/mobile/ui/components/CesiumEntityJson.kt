package soy.engindearing.omnitak.mobile.ui.components

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
 * Issue #98 — a contact resolving to a standard TAK icon set (Spot Map via
 * `<usericon iconsetpath>` / the `b-m-p-s-m` type) instead carries a `spot` hex
 * colour so the globe renders the same coloured dot the 2D map + picker show.
 * Resolution order mirrors ATAK: iconset path first, then CoT type, then SIDC.
 */
internal fun buildCesiumEntitiesJson(
    contacts: List<CoTEvent>,
    selfLat: Double?,
    selfLon: Double?,
    selfCallsign: String,
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
                // #98 — TAK icon-suite (Spot Map) resolves first; the dot's
                // colour rides <color argb> (else the swatch default). The
                // milsymbol branch on the JS side is skipped when `spot` is set.
                val spotHex = spotHexFor(c)
                if (spotHex != null) {
                    put("spot", spotHex)
                } else {
                    put("sidc", MilStdIconService.getSidc(c.type))
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
 */
private fun spotHexFor(c: CoTEvent): String? {
    if (!TakIconRegistry.handles(c.type, c.iconsetPath)) return null
    val argb = c.colorArgb?.let { TakIconRegistry.normalizeArgb(it) }
        ?: c.iconsetPath?.let { TakIconRegistry.SpotIcon.fromIconsetPath(it)?.argb }
        ?: TakIconRegistry.SpotIcon.WHITE.argb
    return "#%06X".format(argb and 0x00FFFFFF)
}

private fun affChar(a: CoTAffiliation): String = when (a) {
    CoTAffiliation.FRIEND -> "f"
    CoTAffiliation.HOSTILE -> "h"
    CoTAffiliation.NEUTRAL -> "n"
    else -> "u"
}
