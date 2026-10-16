package io.github.clinejj.adguarddebug

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
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object Notifications {
    const val CHANNEL = "monitor"
    const val ID = 1

    fun createChannel(ctx: Context) {
        val ch = NotificationChannel(CHANNEL, "Network monitor", NotificationManager.IMPORTANCE_LOW)
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    fun build(ctx: Context, text: String): Notification {
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(a: String, req: Int) = PendingIntent.getService(
            ctx, req, Intent(ctx, MonitorService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("AdGuard Debug monitoring")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Mark issue now", action(MonitorService.ACTION_MARK, 1)).build())
            .addAction(Notification.Action.Builder(null, "Stop", action(MonitorService.ACTION_STOP, 2)).build())
            .build()
    }
}

class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
        NetworkWatcher.acquire(this)
        EventLog.add(EventLog.Type.INFO, "Background monitoring started")

        scope.launch {
            while (isActive) {
                Diagnostics.runProbes("periodic")
                delay(PROBE_INTERVAL_MS)
            }
        }
        // Re-probe shortly after the default network or its validation state changes.
        scope.launch {
            NetworkWatcher.defaultNet
                .map { it?.network to it?.appsSeeOnline }
                .distinctUntilChanged()
                .drop(1)
                .collectLatest {
                    delay(3000)
                    Diagnostics.runProbes("network change")
                }
        }
        scope.launch {
            combine(NetworkWatcher.defaultNet, Diagnostics.lastRun) { def, run -> statusText(def, run) }
                .distinctUntilChanged()
                .collect { text ->
                    getSystemService(android.app.NotificationManager::class.java)
                        .notify(Notifications.ID, Notifications.build(this@MonitorService, text))
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MARK -> {
                Diagnostics.mark("from notification")
                Toast.makeText(this, "Marked — snapshot + probes running", Toast.LENGTH_SHORT).show()
            }
            ACTION_STOP -> {
                Prefs.setMonitoring(this, false)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        NetworkWatcher.release()
        EventLog.add(EventLog.Type.INFO, "Background monitoring stopped")
        super.onDestroy()
    }

    private fun goForeground() {
        val n = Notifications.build(this, "Starting…")
        val hasLocation = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        try {
            // The location type lets us keep reading the Wi-Fi name while in the background.
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                (if (hasLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            startForeground(Notifications.ID, n, type)
        } catch (e: Exception) {
            startForeground(Notifications.ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
    }

    private fun statusText(def: NetInfo?, run: ProbeRun?): String {
        val net = when {
            def == null -> "Apps see OFFLINE (no default network)"
            def.appsSeeOnline -> "Apps see ONLINE via ${def.label}"
            else -> "Apps see OFFLINE: ${def.label} not validated"
        }
        val probes = run?.let { r ->
            val ok = r.results.count { it.status == Status.OK }
            "\nProbes ${EventLog.shortTime(r.time)}: $ok/${r.results.size} OK" +
                (r.hints.firstOrNull()?.let { "\n$it" } ?: "")
        } ?: ""
        return net + probes
    }

    companion object {
        const val ACTION_MARK = "mark"
        const val ACTION_STOP = "stop"
        private const val PROBE_INTERVAL_MS = 60_000L

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, MonitorService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MonitorService::class.java))
        }
    }
}

/** Quick Settings tile: one tap to bookmark "an app just said it's offline". */
class MarkTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            label = "Mark net issue"
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        Diagnostics.mark("from quick settings tile")
        Toast.makeText(this, "Marked — snapshot + probes running", Toast.LENGTH_SHORT).show()
    }
}
