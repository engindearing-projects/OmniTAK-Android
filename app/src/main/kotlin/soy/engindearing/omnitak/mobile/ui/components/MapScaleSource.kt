package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import soy.engindearing.omnitak.mobile.data.MapScale

/**
 * What the scale bar knows: the ground scale under it, and where it is on screen.
 *
 * It is its own holder, read only by [MapScaleBar]: the scale changes with every frame of a pan or
 * a pinch, and nothing else on the map screen may recompose for it. The bar tells the holder where
 * its row is ([setAnchor]), the engine that is showing measures the ground scale along that row and
 * reports it ([refresh] for MapLibre, [reportGlobe] for the 3D globe), and the bar draws what
 * [MapScale] makes of it. One holder for the life of the process, like the map view the 2D listeners
 * are registered on (see [RetainedMapView]).
 *
 * Main thread only.
 */
@Stable
class MapScaleSource {

    /** Which map engine is showing, and so who may report a scale. */
    enum class Engine { NONE, MAPLIBRE, GLOBE }

    private val value = mutableStateOf<Double?>(null)

    /** Metres of ground per dp along the bar's row; null when there is none to give (no map yet, sky, off the globe). */
    val metersPerDp: State<Double?> get() = value

    private val anchor = mutableStateOf<Offset?>(null)

    /** The middle of the bar's row, in dp from the top-left of the window; null until the bar has been laid out. */
    val anchorDp: State<Offset?> get() = anchor

    private var anchorPx: Offset? = null
    private var density = 1f
    private var engine = Engine.NONE
    private var project: ((xPx: Float, yPx: Float) -> Pair<Double, Double>?)? = null

    /**
     * The 2D map is ready: [project] turns a screen point in px into (latitude, longitude), or null
     * when it is not on the ground. Registered once, with the retained map view, and kept for its life.
     */
    fun bindProjection(project: (xPx: Float, yPx: Float) -> Pair<Double, Double>?) {
        this.project = project
        refresh()
    }

    /** The bar's row is at ([xPx], [yPx]) in the window, on a screen of [density] px per dp. */
    fun setAnchor(xPx: Float, yPx: Float, density: Float) {
        if (!(density > 0f) || !xPx.isFinite() || !yPx.isFinite()) return
        this.density = density
        anchorPx = Offset(xPx, yPx)
        anchor.value = Offset(xPx / density, yPx / density)
        refresh()
    }

    /**
     * The engine on screen changed. The value of the old one means nothing to the new, so it is
     * dropped until the new one reports: at once for the 2D map, which can be asked, and with the
     * next camera event for the globe.
     */
    fun useEngine(engine: Engine) {
        this.engine = engine
        value.value = null
        refresh()
    }

    /** The 2D map's camera moved, or the bar did: measure again on the bar's row. */
    fun refresh() {
        if (engine != Engine.MAPLIBRE) return
        val at = anchorPx ?: return
        val projection = project ?: return
        value.value = runCatching { MapScale.metersPerDpOnRow(at.x, at.y, density, projection) }.getOrNull()
    }

    /** The globe's page measured [metersPerDp] on the row it was told about; null when a point missed the globe. */
    fun reportGlobe(metersPerDp: Double?) {
        if (engine != Engine.GLOBE) return
        value.value = metersPerDp
    }
}
