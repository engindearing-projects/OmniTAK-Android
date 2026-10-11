package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import soy.engindearing.omnitak.mobile.domain.OneShotRequest

/**
 * Acts on [request] for as long as this is on screen: [onRequest] runs once
 * for each request, including one made before this was composed (Tools asks
 * and switches to the map in the same frame). A request that was already acted
 * on is gone, so a screen that is built again (back from another tab, the
 * Activity recreated) does not act on it a second time.
 */
@Composable
fun OnRequest(request: OneShotRequest, onRequest: () -> Unit) {
    val latest by rememberUpdatedState(onRequest)
    LaunchedEffect(request) {
        request.handleRequests { latest() }
    }
}
