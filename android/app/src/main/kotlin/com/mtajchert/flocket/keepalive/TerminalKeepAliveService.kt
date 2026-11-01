package com.mtajchert.flocket.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mtajchert.flocket.MainActivity
import com.mtajchert.flocket.R
import com.mtajchert.flocket.core.data.ConnectionManager
import com.mtajchert.flocket.core.data.KeepAliveService
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `shortService` foreground service (ADR-0006): while it runs, the process is exempt from the background
 * network block, so open terminals keep their SSH links for up to ~3 minutes after the user leaves the app.
 * It does no work itself; ConnectionManager decides when it runs. On `onTimeout` it stops and tells the
 * manager to detach.
 *
 * Lives in `:app` because it is UI-facing (notification, tap action into [MainActivity], app icon and
 * strings); `core:data` only sees the [KeepAliveService] port, which keeps the policy unit-testable.
 */
@AndroidEntryPoint
class TerminalKeepAliveService : Service() {
    @Inject lateinit var connections: ConnectionManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val terminals = intent?.getIntExtra(EXTRA_TERMINALS, 1) ?: 1
        // startForeground must follow startForegroundService, even when a stop raced ahead of us.
        val ok =
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(terminals), foregroundType())
                true
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException and friends: the window is lost.
                Log.w(TAG, "startForeground refused", e)
                false
            }
        if (!ok) {
            stopSelf()
            connections.keepAliveEnded()
        } else if (!wanted) {
            finish()
        }
        return START_NOT_STICKY
    }

    /** API 34: the shortService ran out of time. */
    override fun onTimeout(startId: Int) = timedOut()

    /** API 35+: the shortService ran out of time. */
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) = timedOut()

    private fun timedOut() {
        Log.i(TAG, "shortService timeout")
        finish()
        connections.keepAliveEnded()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiped away from recents: nothing to come back to.
        finish()
        connections.keepAliveEnded()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
        } else {
            0
        }

    private fun notification(terminals: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.keep_alive_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = getString(R.string.keep_alive_channel_description) },
            )
        }
        val open =
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val tap = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_flocket)
            .setContentTitle(resources.getQuantityString(R.plurals.keep_alive_title, terminals, terminals))
            .setContentText(getString(R.string.keep_alive_text))
            .setContentIntent(tap)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "KeepAliveService"
        private const val CHANNEL_ID = "connection"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_TERMINALS = "terminals"

        /** Whether the manager still wants the service; read in onStartCommand (a stop may arrive first). */
        @Volatile internal var wanted = false

        /** The running instance; main thread only. */
        private var instance: TerminalKeepAliveService? = null

        internal fun intent(
            context: Context,
            terminals: Int,
        ) = Intent(context, TerminalKeepAliveService::class.java).putExtra(EXTRA_TERMINALS, terminals)

        /** Main thread: stop the running instance, if any. A pending start sees [wanted] = false. */
        internal fun stopRunning() {
            instance?.finish()
        }
    }
}

/** [KeepAliveService] over [TerminalKeepAliveService]. */
@Singleton
class AndroidKeepAliveService
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : KeepAliveService {
        private val main = Handler(Looper.getMainLooper())

        override fun start(terminals: Int): Boolean {
            TerminalKeepAliveService.wanted = true
            return try {
                ContextCompat.startForegroundService(context, TerminalKeepAliveService.intent(context, terminals))
                true
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (API 31+) is an IllegalStateException.
                Log.w("KeepAliveService", "start not allowed", e)
                TerminalKeepAliveService.wanted = false
                false
            }
        }

        override fun stop() {
            TerminalKeepAliveService.wanted = false
            main.post { TerminalKeepAliveService.stopRunning() }
        }
    }
