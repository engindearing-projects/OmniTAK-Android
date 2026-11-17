package soy.engindearing.omnitak.mobile.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Observes runtime location-permission state and emits true once the
 * user has granted either fine or coarse location. Requests the pair
 * at first composition if neither is granted. The result is a single
 * [State]<Boolean> the caller can use to gate map features.
 *
 * The permission launcher survives configuration changes via
 * [rememberLauncherForActivityResult]. The permission can also change
 * without the launcher: granted in the system settings while the app
 * is in the background. So it is read again every time the app comes
 * back to the foreground ([LocationPermissionRules]).
 */
@Composable
fun rememberLocationPermission(): State<Boolean> {
    val context = LocalContext.current
    val grantedState = remember { mutableStateOf(hasLocationPermission(context)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        grantedState.value = LocationPermissionRules.next(
            grantedState.value,
            LocationPermissionRules.Event.PromptAnswered(granted),
        )
    }

    // Granted (or taken away) outside the app's own prompt, in the system settings, while the app was in
    // the background: the answer is the system's, read when the app is back in front.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                grantedState.value = LocationPermissionRules.next(
                    grantedState.value,
                    LocationPermissionRules.Event.Resumed(hasLocationPermission(context)),
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        if (!grantedState.value) {
            launcher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }
    }

    return grantedState
}

private fun hasLocationPermission(context: android.content.Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    return LocationPermissionRules.granted(fine, coarse)
}

/** What the gate says about the location permission, as plain logic. */
internal object LocationPermissionRules {

    sealed interface Event {
        /** The system prompt the gate raised was answered. */
        data class PromptAnswered(val granted: Boolean) : Event

        /** The app came back to the foreground; [grantedNow] is what the system says now. */
        data class Resumed(val grantedNow: Boolean) : Event
    }

    /** Either the precise or the approximate permission is enough for the map. */
    fun granted(fine: Boolean, coarse: Boolean): Boolean = fine || coarse

    /**
     * The gate's answer after [event], given its answer [current]. The prompt can grant but never
     * takes a permission back; a return to the foreground is the system's own word, so it replaces
     * whatever the gate remembered, both ways.
     */
    fun next(current: Boolean, event: Event): Boolean = when (event) {
        is Event.PromptAnswered -> current || event.granted
        is Event.Resumed -> event.grantedNow
    }
}
