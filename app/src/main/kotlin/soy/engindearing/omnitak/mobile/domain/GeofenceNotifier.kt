package soy.engindearing.omnitak.mobile.domain

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import soy.engindearing.omnitak.mobile.MainActivity
import soy.engindearing.omnitak.mobile.R
import soy.engindearing.omnitak.mobile.i18n.Loc
import java.util.concurrent.atomic.AtomicInteger

/**
 * Raises an Android notification for a geofence alert, so an operator whose phone is in a pocket
 * or on a table still sees "ALPHA-2 entered North gate". It has a channel of its own, "Geofence
 * alerts", so the operator can silence geofences without silencing chat or the connection
 * notice. The text is the same sentence the banner on the map shows.
 *
 * Posting is quietly skipped when notifications are not allowed (Android 13 and later ask for
 * permission; [NotificationPermission] says when the app asks), so a denied permission is
 * never a crash.
 */
class GeofenceNotifier(private val context: Context) {

    private val counter = AtomicInteger()

    fun notify(sentence: String) {
        ensureChannel(context)
        val tap = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(Loc.t("geofence.notification.title"))
            .setContentText(sentence)
            .setStyle(NotificationCompat.BigTextStyle().bigText(sentence))
            .setSmallIcon(R.mipmap.app_icon)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tap)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId(counter.getAndIncrement()), notification)
        }
    }

    companion object {
        const val CHANNEL_ID = "geofence_alerts"
        private const val BASE_NOTIFICATION_ID = 3000

        /** How many alerts can sit in the shade at once; a newer one then replaces the oldest. */
        private const val MOST_IN_THE_SHADE = 20

        /** The id of the [n]th alert: each its own, so two alerts do not replace each other. */
        fun notificationId(n: Int): Int = BASE_NOTIFICATION_ID + Math.floorMod(n, MOST_IN_THE_SHADE)

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    Loc.t("geofence.channel.name"),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = Loc.t("geofence.channel.description")
                    setShowBadge(true)
                },
            )
        }
    }
}

/**
 * Whether any screen of the app is showing, counted from the activities that are started. With the
 * app in front the banner on the map is the alert; with it behind, or the screen off, a
 * notification is raised as well. Registered once by the application.
 */
class ForegroundTracker {
    private val started = AtomicInteger()

    fun activityStarted() {
        started.incrementAndGet()
    }

    fun activityStopped() {
        // Never below zero, whatever order the system reports in.
        started.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    val inForeground: Boolean get() = started.get() > 0
}
