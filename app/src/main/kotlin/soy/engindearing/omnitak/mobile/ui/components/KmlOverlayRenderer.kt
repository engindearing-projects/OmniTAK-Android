package soy.engindearing.omnitak.mobile.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import android.graphics.BitmapFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import soy.engindearing.omnitak.mobile.data.KmlVectorOverlay
import soy.engindearing.omnitak.mobile.data.KmlVectorOverlayStore
import soy.engindearing.omnitak.mobile.data.MBTilesOverlay
import soy.engindearing.omnitak.mobile.data.MBTilesOverlayStore
import soy.engindearing.omnitak.mobile.data.RasterOverlay
import soy.engindearing.omnitak.mobile.data.RasterOverlayStore
import java.net.URI

/** Lets the Map Overlays sheet ask the map to frame bounds. */
object KmlOverlayEvents {
    private val _zoomTo = MutableStateFlow<KmlVectorOverlay?>(null)
    val zoomTo: StateFlow<KmlVectorOverlay?> = _zoomTo.asStateFlow()
    fun requestZoom(overlay: KmlVectorOverlay) { _zoomTo.value = overlay }
    fun consumed() { _zoomTo.value = null }

    // Generic [north, south, east, west] zoom for raster/MBTiles overlays.
    private val _zoomBounds = MutableStateFlow<DoubleArray?>(null)
    val zoomBounds: StateFlow<DoubleArray?> = _zoomBounds.asStateFlow()
    fun requestZoomBounds(north: Double, south: Double, east: Double, west: Double) {
        _zoomBounds.value = doubleArrayOf(north, south, east, west)
    }
    fun boundsConsumed() { _zoomBounds.value = null }
}

/** The image id registered once in the MapLibre style for the KML pushpin icon. */
private const val KML_PUSHPIN_IMAGE_ID = "kml-pushpin"

/**
 * Generates a classic teardrop pushpin bitmap programmatically:
 * yellow fill (#FFD400), dark outline, white center dot, ~96px tall.
 * The pin's visual anchor point is at the bottom-center of the bitmap.
 */
private fun buildPushpinBitmap(): Bitmap {
    val w = 64
    val h = 96
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)

    // Teardrop body: circle on top, tapering path at the bottom.
    val cx = w / 2f
    val circleR = w / 2f - 4f          // circle fills most of the width
    val circleTop = 4f
    val circleCy = circleTop + circleR  // center of the circle

    // Fill paint — yellow
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD400")
        style = Paint.Style.FILL
    }
    // Stroke paint — dark outline
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    // Teardrop path: arc for the balloon + two lines meeting at the tip.
    val path = Path()
    val tipY = (h - 4).toFloat()
    // Start at the left tangent of the circle where the tail begins
    // Use a RectF arc for the balloon portion (roughly 270° of circle)
    val oval = android.graphics.RectF(
        cx - circleR, circleTop,
        cx + circleR, circleTop + 2 * circleR,
    )
    // Start from bottom-left of circle (angle 120° = lower-left tangent)
    path.arcTo(oval, 120f, 300f, true)   // arc 120° → 60° (300° sweep = leaves 60° gap at bottom)
    // Line to tip
    path.lineTo(cx, tipY)
    path.close()

    canvas.drawPath(path, fillPaint)
    canvas.drawPath(path, strokePaint)

    // White center dot
    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, circleCy, circleR * 0.35f, dotPaint)

    return bmp
}

/**
 * Renders imported KML overlays onto the MapLibre style as one GeoJsonSource
 * per overlay (loaded natively from the on-disk .geojson) plus line / fill /
 * symbol (pushpin) layers. This is the GPU-vector approach that scales to 50k+
 * features where per-feature annotations crash. Toggling = a layer-visibility
 * flip.
 *
 * Call [apply] whenever overlays change AND after every style (re)load — a
 * setStyle wipes added sources/layers, so they must be re-applied.
 */
object KmlOverlayRenderer {
    private val installed = mutableSetOf<String>()

    fun apply(style: Style, overlays: List<KmlVectorOverlay>, store: KmlVectorOverlayStore) {
        val wanted = overlays.map { it.id }.toSet()

        // Remove overlays no longer present.
        for (id in installed - wanted) {
            for (layerId in layerIds(id)) style.removeLayer(layerId)
            style.removeSource("kmlsrc-$id")
        }
        installed.clear()
        installed.addAll(wanted)

        // Register the pushpin bitmap once per style load. getImage returns
        // null if the style was wiped (setStyle) so we re-add it every apply.
        if (style.getImage(KML_PUSHPIN_IMAGE_ID) == null) {
            style.addImage(KML_PUSHPIN_IMAGE_ID, buildPushpinBitmap())
        }

        for (overlay in overlays) {
            val sourceId = "kmlsrc-${overlay.id}"

            if (style.getSource(sourceId) == null) {
                val uri = URI("file://" + store.fileFor(overlay).absolutePath)
                style.addSource(GeoJsonSource(sourceId, uri, GeoJsonOptions().withTolerance(1.0f)))
                style.addLayer(FillLayer("kmlfill-${overlay.id}", sourceId))
                style.addLayer(
                    LineLayer("kmlline-${overlay.id}", sourceId).withProperties(
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
                )
                // SymbolLayer for Point placemarks — yellow pushpin + name label.
                // Filtered to Point geometry so lines/polygons are unaffected.
                style.addLayer(
                    SymbolLayer("kmlsym-${overlay.id}", sourceId).withProperties(
                        PropertyFactory.iconImage(KML_PUSHPIN_IMAGE_ID),
                        PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.textField(Expression.get("name")),
                        PropertyFactory.textColor(Color.WHITE),
                        PropertyFactory.textHaloColor(Color.BLACK),
                        PropertyFactory.textHaloWidth(1.2f),
                        PropertyFactory.textSize(12f),
                        PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                        PropertyFactory.textOffset(arrayOf(0f, 0.8f)),
                        PropertyFactory.textOptional(true),
                        PropertyFactory.textAllowOverlap(false),
                    ).withFilter(
                        Expression.eq(Expression.geometryType(), Expression.literal("Point")),
                    ),
                )
            }

            // Re-apply styling every pass so edits (color / opacity / line
            // width / visibility) take effect live without a reload.
            val vis = if (overlay.visible) Property.VISIBLE else Property.NONE
            val color = runCatching { Color.parseColor(overlay.colorHex) }.getOrDefault(Color.MAGENTA)
            val m = overlay.lineWidth
            style.getLayerAs<FillLayer>("kmlfill-${overlay.id}")?.setProperties(
                PropertyFactory.visibility(vis),
                PropertyFactory.fillColor(color),
                PropertyFactory.fillOutlineColor(color),
                PropertyFactory.fillOpacity(overlay.opacity * 0.25f),
            )
            style.getLayerAs<LineLayer>("kmlline-${overlay.id}")?.setProperties(
                PropertyFactory.visibility(vis),
                PropertyFactory.lineColor(color),
                PropertyFactory.lineOpacity(overlay.opacity),
                PropertyFactory.lineWidth(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(6, 0.6f * m),
                        Expression.stop(12, 1.6f * m),
                        Expression.stop(16, 3.0f * m),
                    ),
                ),
            )
            // Pushpin + label visibility follows the overlay toggle.
            style.getLayerAs<SymbolLayer>("kmlsym-${overlay.id}")?.setProperties(
                PropertyFactory.visibility(vis),
            )
        }
    }

    private fun layerIds(id: String) = listOf("kmlfill-$id", "kmlline-$id", "kmlsym-$id")

    // Single-image raster overlays (KMZ GroundOverlay etc.) via ImageSource.
    private val installedRaster = mutableSetOf<String>()

    fun applyRaster(style: Style, overlays: List<RasterOverlay>, store: RasterOverlayStore) {
        val wanted = overlays.map { it.id }.toSet()
        for (id in installedRaster - wanted) {
            style.removeLayer("rasterlyr-$id")
            style.removeSource("rastersrc-$id")
        }
        installedRaster.clear()
        installedRaster.addAll(wanted)

        for (overlay in overlays) {
            val sourceId = "rastersrc-${overlay.id}"
            val layerId = "rasterlyr-${overlay.id}"
            if (style.getSource(sourceId) == null) {
                val bmp = runCatching { BitmapFactory.decodeFile(store.fileFor(overlay).absolutePath) }.getOrNull() ?: continue
                val quad = LatLngQuad(
                    LatLng(overlay.north, overlay.west), // top-left
                    LatLng(overlay.north, overlay.east), // top-right
                    LatLng(overlay.south, overlay.east), // bottom-right
                    LatLng(overlay.south, overlay.west), // bottom-left
                )
                style.addSource(ImageSource(sourceId, quad, bmp))
                style.addLayer(RasterLayer(layerId, sourceId))
            }
            val vis = if (overlay.visible) Property.VISIBLE else Property.NONE
            style.getLayerAs<RasterLayer>(layerId)?.setProperties(
                PropertyFactory.visibility(vis),
                PropertyFactory.rasterOpacity(overlay.opacity),
            )
        }
    }

    // MBTiles raster tile sources (served by the in-app HTTP tile server).
    private val installedMBTiles = mutableSetOf<String>()

    fun applyMBTiles(style: Style, overlays: List<MBTilesOverlay>, store: MBTilesOverlayStore) {
        val wanted = overlays.map { it.id }.toSet()
        for (id in installedMBTiles - wanted) {
            style.removeLayer("mbtileslyr-$id")
            style.removeSource("mbtilessrc-$id")
        }
        installedMBTiles.clear()
        installedMBTiles.addAll(wanted)

        for (overlay in overlays) {
            val sourceId = "mbtilessrc-${overlay.id}"
            val layerId = "mbtileslyr-${overlay.id}"
            if (style.getSource(sourceId) == null) {
                val template = store.tileUrlTemplate(overlay) ?: continue
                val tileSet = TileSet("2.1.0", template).apply {
                    minZoom = overlay.minZoom.toFloat()
                    maxZoom = overlay.maxZoom.toFloat()
                }
                style.addSource(RasterSource(sourceId, tileSet, 256))
                style.addLayer(RasterLayer(layerId, sourceId))
            }
            val vis = if (overlay.visible) Property.VISIBLE else Property.NONE
            style.getLayerAs<RasterLayer>(layerId)?.setProperties(
                PropertyFactory.visibility(vis),
                PropertyFactory.rasterOpacity(overlay.opacity),
            )
        }
    }

    // Downloaded offline regions (#120). Each region is an MBTiles file
    // served by the same in-app tile server, so it renders exactly like an
    // imported MBTiles overlay — just a raster source/layer. The
    // OfflineTilePolicy decision controls whether these sit above the live
    // basemap (offline → cache wins) or are simply present (online).
    private val installedOffline = mutableSetOf<String>()

    fun applyOfflineRegions(
        style: Style,
        regions: List<soy.engindearing.omnitak.mobile.data.offline.OfflineRegion>,
        store: soy.engindearing.omnitak.mobile.data.offline.OfflineRegionStore,
        decision: soy.engindearing.omnitak.mobile.data.offline.OfflineDecision,
    ) {
        val wanted = decision.activeRegionIds.toSet()
        for (id in installedOffline - wanted) {
            style.removeLayer("offlinelyr-$id")
            style.removeSource("offlinesrc-$id")
        }
        installedOffline.clear()
        installedOffline.addAll(wanted)

        for (region in regions) {
            if (region.id !in wanted) continue
            val sourceId = "offlinesrc-${region.id}"
            val layerId = "offlinelyr-${region.id}"
            if (style.getSource(sourceId) == null) {
                val template = store.tileUrlTemplate(region) ?: continue
                val tileSet = TileSet("2.1.0", template).apply {
                    minZoom = region.minZoom.toFloat()
                    maxZoom = region.maxZoom.toFloat()
                }
                style.addSource(RasterSource(sourceId, tileSet, 256))
                // When offline, draw cached tiles on top so they win over the
                // (unreachable) live basemap; online, let it layer normally.
                style.addLayer(RasterLayer(layerId, sourceId))
            }
            style.getLayerAs<RasterLayer>(layerId)?.setProperties(
                PropertyFactory.visibility(Property.VISIBLE),
                PropertyFactory.rasterOpacity(1.0f),
            )
        }
    }
}
