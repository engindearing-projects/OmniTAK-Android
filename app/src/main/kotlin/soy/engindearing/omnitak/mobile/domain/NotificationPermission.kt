package soy.engindearing.omnitak.mobile.domain

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import soy.engindearing.omnitak.mobile.ui.onboarding.OnboardingManager

/**
 * When the app asks for permission to show notifications. Android 13 and later drop a notification from an
 * app that was never allowed, so chat messages ([MeshChatNotifier]) and geofence alerts ([GeofenceNotifier])
 * are silent until the operator says yes.
 *
 * One rule for every place that asks, with one flag between them, so a person is asked once: at the first
 * of those places they reach. A person who said no is not asked again (Android stops showing its prompt
 * after two refusals anyway). Never at launch, and never on the Welcome page or the other onboarding pages:
 * someone who has just installed the app has no use for the answer yet.
 */
object NotificationPermission {

    /** Android 13 added the permission; before it a notification needs none. */
    const val FIRST_SDK_WITH_PERMISSION = 33

    /**
     * The string of `Manifest.permission.POST_NOTIFICATIONS`, a field that exists from Android 13 (API 33). The
     * screens that ask pass this, so none of them names the newer field.
     */
    const val PERMISSION = "android.permission.POST_NOTIFICATIONS"

    /**
     * Whether to ask now: only where the permission exists ([sdkInt]), only while it is not granted, only if nobody
     * has asked before ([alreadyAsked]), and never before onboarding is done ([onboardingComplete]): the Welcome page
     * and the pages after it are not a place to ask, and nothing is used up while they are open.
     */
    fun shouldAsk(sdkInt: Int, granted: Boolean, alreadyAsked: Boolean, onboardingComplete: Boolean): Boolean {
        if (!onboardingComplete) return false
        return sdkInt >= FIRST_SDK_WITH_PERMISSION && !granted && !alreadyAsked
    }

    private const val PREFS = "omnitak_notifications"
    private const val KEY_ASKED = "notification_permission_asked"

    fun isGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < FIRST_SDK_WITH_PERMISSION ||
            ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun wasAsked(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)

    fun markAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASKED, true).apply()
    }

    /**
     * Reads what the phone says and applies [shouldAsk]. When it is time to ask it records that the question was
     * asked, so no other place asks again, and returns true: the caller then shows the system prompt. The callers
     * are the Chat tab (the first time it is opened: where a teammate's message is read, so the question explains
     * itself) and the geofence form (a geofence's alerts are notifications too).
     */
    fun takeTurnToAsk(context: Context): Boolean {
        val ask = shouldAsk(Build.VERSION.SDK_INT, isGranted(context), wasAsked(context), OnboardingManager.isComplete(context))
        if (ask) markAsked(context)
        return ask
    }
}
