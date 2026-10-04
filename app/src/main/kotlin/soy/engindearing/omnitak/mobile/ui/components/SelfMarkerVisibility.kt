package soy.engindearing.omnitak.mobile.ui.components

/**
 * #210 - pure rules for hiding the operator's own marker on the 2D map.
 * UI only: none of this touches position reporting, which is the separate
 * "Report my position" switch (#211).
 *
 * On the 2D engine the MapLibre LocationComponent is the puck, and it is also
 * what drives camera tracking (follow-me, "Center on me"). To hide the marker
 * the component is simply never activated (and is stopped if it was running),
 * so the camera has to be moved from the raw fix instead while it is off.
 * Kept as plain functions so the rule is unit-testable without a MapView.
 */
internal object SelfMarkerVisibility {

    /**
     * The puck (LocationComponent) is activated and shown only when location
     * is available AND the operator has not hidden their marker.
     */
    fun puckActive(locationEnabled: Boolean, selfMarkerVisible: Boolean): Boolean =
        locationEnabled && selfMarkerVisible

    /**
     * Location is available but the marker is hidden: the LocationComponent
     * stays off, so follow-me and "Center on me" must move the camera from
     * the raw fix themselves. False when location itself is unavailable
     * (nothing to follow) and when the puck is running (it tracks for us).
     */
    fun cameraFollowsFixDirectly(locationEnabled: Boolean, selfMarkerVisible: Boolean): Boolean =
        locationEnabled && !selfMarkerVisible
}
