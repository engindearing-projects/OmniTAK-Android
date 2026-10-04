package soy.engindearing.omnitak.mobile.domain

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import soy.engindearing.omnitak.mobile.MainActivity
import soy.engindearing.omnitak.mobile.R

/**
 * Foreground service that holds the user-perceivable privilege so
 * Android's Doze / app-standby scheduler doesn't kill the TLS read loop
 * within ~10 seconds of backgrounding.
 *
 * The service does NOT own the [TAKConnection] — that still lives on
 * the application-scoped [ServerManager]. This class only carries the
 * `startForeground` privilege so the OS treats our networking as
 * intentional ongoing work. When [ServerManager.connectionState] flips
 * to Connected we start the service; on Disconnected we stop it.
 *
 * Issue #5 — H!rO reported the connection drops after a few seconds in
 * the background and confirmed disabling battery optimisation works
 * around it, which pointed straight at Doze.
 *
 * Issue #223 — the service must never take the process down. Android 15
 * gives a `dataSync` foreground service six hours of background time per
 * day and then calls [onTimeout]; a service that is still running a few
 * seconds later is killed with ForegroundServiceDidNotStopInTimeException,
 * and the START_STICKY restart that follows cannot enter the foreground
 * either ("Time limit already exhausted"). The limit applies to a service
 * that also holds the location type. So the service runs as `location`
 * alone whenever location is granted (no time limit), keeps `dataSync`
 * only as the fallback, stops itself on timeout, and treats every refused
 * foreground start as "not now" instead of a crash.
 */
class TAKConnectionService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val linkLabel = intent?.getStringExtra(EXTRA_LINK_LABEL) ?: "TAK Server"
        val entered = enterForeground(buildNotification(linkLabel))
        // A stop() that arrived between startForegroundService() and this
        // call was parked (see [stop]); honour it now that the promise to
        // call startForeground() has been kept.
        val stopRequested = gate.startDelivered()
        if (!entered || stopRequested) {
            if (entered) stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Android 15+: a time-limited foreground service type has used up its
     * allowance. The service has a few seconds to stop before the system
     * kills the process, so stop now and tell the operator why the
     * background link is about to go quiet.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "foreground service time limit reached (type=$fgsType); stopping")
        notifyPaused()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Try each candidate type in turn. Android refuses a foreground start
     * with an exception rather than a result: SecurityException when a
     * type's precondition is not met (the location type cannot be claimed
     * from the background), ForegroundServiceStartNotAllowedException (an
     * IllegalStateException) when the app is in the background or a
     * time-limited type is exhausted.
     */
    private fun enterForeground(notification: Notification): Boolean {
        for (types in foregroundTypeCandidates(Build.VERSION.SDK_INT, hasLocationPermission())) {
            try {
                startForeground(NOTIFICATION_ID, notification, types)
                return true
            } catch (e: SecurityException) {
                Log.w(TAG, "foreground start refused for types=$types: ${e.message}")
            } catch (e: IllegalStateException) {
                Log.w(TAG, "foreground start refused for types=$types: ${e.message}")
            }
        }
        return false
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun buildNotification(linkLabel: String): Notification {
        ensureChannel(this)
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OmniTAK active")
            .setContentText("Sharing position over $linkLabel")
            .setSmallIcon(R.mipmap.app_icon)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(tap)
            .build()
    }

    /** One-off notice that Android ended the background service at its time limit. */
    private fun notifyPaused() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(this)
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            PAUSED_NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("OmniTAK paused in the background")
                .setContentText("Android's background time limit was reached. Open OmniTAK to resume.")
                .setSmallIcon(R.mipmap.app_icon)
                .setAutoCancel(true)
                .setContentIntent(tap)
                .build(),
        )
    }

    companion object {
        private const val TAG = "TAKConnService"
        private const val CHANNEL_ID = "tak_connection"
        private const val NOTIFICATION_ID = 1001
        private const val PAUSED_NOTIFICATION_ID = 1002
        private const val EXTRA_LINK_LABEL = "link_label"

        private val gate = StartStopGate()

        /**
         * Foreground service types to try, most preferred first.
         *
         * `location` carries no time limit and keeps GPS flowing with the
         * screen off (background PPLI, field feedback PatoG 2026-08), so it
         * is the only type claimed when location is granted. `dataSync` is
         * limited to six hours of background time per day on Android 15+,
         * and that limit also applies when it is combined with `location`
         * (#223), so it is the fallback: for a user who denied location, and
         * for a start from the background, where the location type is
         * refused with a SecurityException.
         */
        internal fun foregroundTypeCandidates(sdkInt: Int, hasLocation: Boolean): List<Int> = when {
            sdkInt < Build.VERSION_CODES.Q -> listOf(0)
            hasLocation -> listOf(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            else -> listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }

        /** [linkLabel] names what we're holding open — a server name,
         *  "Meshtastic mesh", or both — shown in the persistent chip. */
        fun start(context: Context, linkLabel: String) {
            ensureChannel(context)
            val intent = Intent(context, TAKConnectionService::class.java)
                .putExtra(EXTRA_LINK_LABEL, linkLabel)
            gate.startIssued(SystemClock.elapsedRealtime())
            try {
                ContextCompat.startForegroundService(context, intent)
                context.getSystemService(NotificationManager::class.java)?.cancel(PAUSED_NOTIFICATION_ID)
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException: the app is in
                // the background (a reconnect with the screen off). The link
                // carries on without the foreground privilege; the next
                // return to the foreground starts the service again.
                gate.startRefused()
                Log.w(TAG, "startForegroundService refused: ${e.message}")
            }
        }

        /**
         * Stopping a service between startForegroundService() and its
         * startForeground() call is fatal: the system kills the process with
         * ForegroundServiceDidNotStartInTimeException. A server that accepts
         * a connection and drops it at once produces exactly that ordering,
         * however long the start was debounced. So a stop that arrives while
         * a start is pending is parked, and onStartCommand carries it out
         * after it has entered the foreground.
         */
        fun stop(context: Context) {
            if (!gate.stopRequested(SystemClock.elapsedRealtime())) return
            context.stopService(Intent(context, TAKConnectionService::class.java))
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "TAK connection",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Persistent indicator while OmniTAK holds an active TAK Server socket."
                    setShowBadge(false)
                },
            )
        }
    }
}


/**
 * Orders stop requests against starts that are still in flight.
 *
 * `startForegroundService()` obliges the service to call `startForeground()`;
 * stopping it before it has done so kills the process. A stop that arrives
 * while a start is undelivered is therefore parked, and the delivery
 * ([startDelivered], from `onStartCommand`) reports when to carry it out.
 * Starts are counted, because the app can issue a second one (returning to
 * the foreground) before the first is delivered. A start that is never
 * delivered stops blocking after [deliveryWindowMs], the system's own
 * deadline for the `startForeground()` call.
 *
 * No Android types, so the ordering rules are unit tested.
 */
internal class StartStopGate(private val deliveryWindowMs: Long = 10_000L) {
    private var pendingStarts = 0
    private var lastIssuedAt = 0L
    private var stopParked = false

    /** `startForegroundService()` is about to be called. A newer start cancels a parked stop. */
    @Synchronized
    fun startIssued(now: Long) {
        pendingStarts++
        lastIssuedAt = now
        stopParked = false
    }

    /** `startForegroundService()` threw; that start will never be delivered. */
    @Synchronized
    fun startRefused() {
        if (pendingStarts > 0) pendingStarts--
    }

    /** True when the service may be stopped now; false when the stop was parked behind a start. */
    @Synchronized
    fun stopRequested(now: Long): Boolean {
        if (pendingStarts > 0 && now - lastIssuedAt < deliveryWindowMs) {
            stopParked = true
            return false
        }
        pendingStarts = 0
        stopParked = false
        return true
    }

    /** A start reached `onStartCommand`. True when a parked stop is due and no other start is in flight. */
    @Synchronized
    fun startDelivered(): Boolean {
        if (pendingStarts > 0) pendingStarts--
        val stop = stopParked && pendingStarts == 0
        if (stop) stopParked = false
        return stop
    }
}
