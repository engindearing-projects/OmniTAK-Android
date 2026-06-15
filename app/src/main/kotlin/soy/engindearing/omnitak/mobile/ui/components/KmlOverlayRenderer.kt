package soy.engindearing.omnitak.mobile.ui.components

import android.graphics.Color
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
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

/**
 * Renders imported KML overlays onto the MapLibre style as one GeoJsonSource
 * per overlay (loaded natively from the on-disk .geojson) plus line / fill /
 * circle layers. This is the GPU-vector approach that scales to 50k+ features
 * where per-feature annotations crash. Toggling = a layer-visibility flip.
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
                style.addLayer(
                    CircleLayer("kmlpt-${overlay.id}", sourceId).withProperties(
                        PropertyFactory.circleRadius(3.0f),
                        PropertyFactory.circleStrokeColor(Color.WHITE),
                        PropertyFactory.circleStrokeWidth(1.0f),
                    ),
                )
            }

            // Re-apply styling every pass so edits (color / opacity / line
            // width / visibility) take effect live without a reload.
            val color = runCatching { Color.parseColor(overlay.colorHex) }.getOrDefault(Color.MAGENTA)
            val vis = if (overlay.visible) Property.VISIBLE else Property.NONE
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
            style.getLayerAs<CircleLayer>("kmlpt-${overlay.id}")?.setProperties(
                PropertyFactory.visibility(vis),
                PropertyFactory.circleColor(color),
                PropertyFactory.circleOpacity(overlay.opacity),
            )
        }
    }

    private fun layerIds(id: String) = listOf("kmlfill-$id", "kmlline-$id", "kmlpt-$id")

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
