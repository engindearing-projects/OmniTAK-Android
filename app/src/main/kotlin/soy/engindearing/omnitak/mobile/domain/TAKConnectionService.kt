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
 */
class TAKConnectionService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val linkLabel = intent?.getStringExtra(EXTRA_LINK_LABEL) ?: "TAK Server"
        startForeground(NOTIFICATION_ID, buildNotification(linkLabel), foregroundServiceTypes())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * dataSync keeps the socket read loop alive; the location type keeps
     * fused GPS updates flowing while the screen is off, which is what
     * makes background PPLI work (field feedback, PatoG 2026-08). The
     * location type may only be claimed while while-in-use location is
     * actually granted — Android 14 throws SecurityException otherwise —
     * so it degrades to dataSync-only when the user denied location.
     */
    private fun foregroundServiceTypes(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (hasLocationPermission()) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return types
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

    companion object {
        private const val CHANNEL_ID = "tak_connection"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_LINK_LABEL = "link_label"

        /** [linkLabel] names what we're holding open — a server name,
         *  "Meshtastic mesh", or both — shown in the persistent chip. */
        fun start(context: Context, linkLabel: String) {
            ensureChannel(context)
            val intent = Intent(context, TAKConnectionService::class.java)
                .putExtra(EXTRA_LINK_LABEL, linkLabel)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
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
