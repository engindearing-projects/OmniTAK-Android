package soy.engindearing.omnitak.mobile.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import org.maplibre.android.maps.AttributionDialogManager
import org.maplibre.android.maps.MapLibreMap
import java.lang.ref.WeakReference

/**
 * #266 - shows the map's attribution dialog from the Activity that hosts the
 * map at the moment of the tap.
 *
 * The map view is built once with the application context and kept for the
 * life of the process (#177, [RetainedMapView]). MapLibre's own dialog manager
 * builds its AlertDialog with the map view's context, and a dialog shown from
 * the application context throws `WindowManager.BadTokenException`: tapping the
 * attribution button closed the app (Play vitals, 0.43.0).
 *
 * This manager holds no Activity. On a tap it walks up from the tapped view to
 * the first view whose context is an Activity (the Compose host of the map) and
 * hands the tap to a MapLibre manager built for that Activity. The dialog that
 * is showing keeps that manager alive; here it is only referenced weakly, so
 * that the map can dismiss the dialog when it stops.
 *
 * MapLibre reads the manager from [org.maplibre.android.maps.UiSettings] at
 * every tap, so setting it once on the kept map covers every later Activity.
 */
internal class HostActivityAttributionDialogManager(
    context: Context,
    private val map: MapLibreMap,
) : AttributionDialogManager(context, map) {

    private var showing: WeakReference<AttributionDialogManager>? = null

    override fun onClick(view: View) {
        val activity = view.hostActivity() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val manager = AttributionDialogManager(activity, map)
        showing = WeakReference(manager)
        manager.onClick(view)
    }

    override fun onStop() {
        showing?.get()?.onStop()
        showing = null
    }
}

/** The Activity hosting this view: its own context's, or the first parent's that has one. */
internal fun View.hostActivity(): Activity? {
    var view: View? = this
    while (view != null) {
        view.context.findActivity()?.let { return it }
        view = view.parent as? View
    }
    return null
}

private tailrec fun Context?.findActivity(): Activity? = when (this) {
    null -> null
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
