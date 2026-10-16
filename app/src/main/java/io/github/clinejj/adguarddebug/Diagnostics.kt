package io.github.clinejj.adguarddebug

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.io.File

object Diagnostics {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _lastRun = MutableStateFlow<ProbeRun?>(null)
    val lastRun: StateFlow<ProbeRun?> = _lastRun.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Runs all probes unless a run is already in progress. */
    suspend fun runProbes(reason: String) {
        if (!mutex.tryLock()) return
        try {
            _running.value = true
            val results = Probes.runAll(App.instance)
            val hints = Analysis.hints(results, NetworkWatcher.networks.value, NetworkWatcher.defaultNet.value)
            val run = ProbeRun(System.currentTimeMillis(), reason, results, hints)
            val prev = _lastRun.value
            _lastRun.value = run

            val notOk = results.filter { it.status != Status.OK }
            val title = "Probes ($reason): ${results.size - notOk.size}/${results.size} OK" +
                if (notOk.isNotEmpty()) " — problems: " + notOk.joinToString { it.name } else ""
            // Periodic runs that look identical to the last one only get a one-liner.
            val unchanged = reason == "periodic" && prev?.signature == run.signature
            EventLog.add(EventLog.Type.PROBE, title, if (unchanged) "" else run.toText())
        } finally {
            _running.value = false
            mutex.unlock()
        }
    }

    fun runProbesAsync(reason: String) {
        scope.launch { runProbes(reason) }
    }

    /** "An app just said it's offline": snapshot everything right now, then probe. */
    fun mark(note: String) {
        scope.launch {
            EventLog.add(EventLog.Type.MARK, "MARK: $note", Snapshot.full(App.instance))
            runProbes("mark: $note")
        }
    }
}

object Snapshot {
    fun system(ctx: Context): String = buildString {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val pm = ctx.getSystemService(PowerManager::class.java)
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), patch ${Build.VERSION.SECURITY_PATCH}")
        val dataSaver = when (cm.restrictBackgroundStatus) {
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "ON (background data restricted)"
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "ON (this app exempt)"
            else -> "off"
        }
        appendLine("Data Saver: $dataSaver")
        appendLine("Battery saver: ${pm.isPowerSaveMode}  Doze idle: ${pm.isDeviceIdleMode}  This app battery-unrestricted: ${pm.isIgnoringBatteryOptimizations(ctx.packageName)}")
        appendLine("Airplane mode: ${Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1}")
        // Not public settings; may be unreadable on newer Android.
        val pdMode = runCatching { Settings.Global.getString(ctx.contentResolver, "private_dns_mode") }.getOrNull()
        val pdHost = runCatching { Settings.Global.getString(ctx.contentResolver, "private_dns_specifier") }.getOrNull()
        appendLine("Private DNS setting: ${pdMode ?: "(unreadable)"}${pdHost?.let { " ($it)" } ?: ""}")
        appendLine("Monitoring: ${Prefs.monitoring.value}")
    }

    fun networks(ctx: Context): Pair<List<NetInfo>, NetInfo?> {
        if (NetworkWatcher.active) {
            return NetworkWatcher.networks.value.values.sortedBy { it.network.toString() } to NetworkWatcher.defaultNet.value
        }
        // Fallback when nothing is listening (e.g. Mark from the Quick Settings tile).
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val list = cm.allNetworks.map { NetInfo(it, cm.getNetworkCapabilities(it), cm.getLinkProperties(it)) }
        val def = cm.activeNetwork?.let { a -> list.firstOrNull { it.network == a } }
        return list to def
    }

    fun full(ctx: Context): String = buildString {
        appendLine(system(ctx))
        val (nets, def) = networks(ctx)
        appendLine(
            "Apps see: " + when {
                def == null -> "OFFLINE (no default network)"
                def.appsSeeOnline -> "ONLINE via ${def.label}"
                else -> "OFFLINE (default ${def.label} not validated/blocked)"
            }
        )
        appendLine()
        nets.forEach { appendLine(Net.describe(it, it.network == def?.network)) }
    }

    fun exportIntent(ctx: Context): Intent {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = EventLog.fullTime(System.currentTimeMillis()).replace(Regex("[^0-9]"), "").take(14)
        val f = File(dir, "adguard-debug-$stamp.txt")
        f.writeText(buildString {
            appendLine("===== AdGuard Debug report ${EventLog.fullTime(System.currentTimeMillis())} =====")
            appendLine()
            appendLine("===== Current state =====")
            appendLine(full(ctx))
            appendLine("===== Last probe run =====")
            appendLine(Diagnostics.lastRun.value?.toText() ?: "(none)")
            appendLine()
            appendLine("===== Event log (oldest first) =====")
            appendLine(EventLog.readable())
        })
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, f.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Export debug log")
    }
}
