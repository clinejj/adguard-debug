package io.github.clinejj.adguarddebug

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFC62828)
private val Amber = Color(0xFFEF6C00)

class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) NetworkWatcher.reRegister(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val needed = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) permissions.launch(needed.toTypedArray())
        if (Prefs.monitoring.value) MonitorService.start(this)

        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)) {
                MainScreen()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        NetworkWatcher.acquire(this)
        val last = Diagnostics.lastRun.value
        if (last == null || System.currentTimeMillis() - last.time > 30_000) Diagnostics.runProbesAsync("app opened")
    }

    override fun onStop() {
        NetworkWatcher.release()
        super.onStop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen() {
    val ctx = LocalContext.current
    val nets by NetworkWatcher.networks.collectAsStateWithLifecycle()
    val def by NetworkWatcher.defaultNet.collectAsStateWithLifecycle()
    val run by Diagnostics.lastRun.collectAsStateWithLifecycle()
    val running by Diagnostics.running.collectAsStateWithLifecycle()
    val events by EventLog.events.collectAsStateWithLifecycle()
    val monitoring by Prefs.monitoring.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showMark by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AdGuard Debug") },
                actions = {
                    IconButton(onClick = { ctx.startActivity(Snapshot.exportIntent(ctx)) }) {
                        Icon(Icons.Default.Share, contentDescription = "Export log")
                    }
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear log")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            StatusBanner(def)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(checked = monitoring, onCheckedChange = { on ->
                    Prefs.setMonitoring(ctx, on)
                    if (on) MonitorService.start(ctx) else MonitorService.stop(ctx)
                })
                Text("Background", Modifier.padding(start = 6.dp), fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { Diagnostics.runProbesAsync("manual") }, enabled = !running) {
                    Text(if (running) "Probing…" else "Probe")
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { showMark = true }) { Text("Mark") }
            }
            TabRow(selectedTabIndex = tab) {
                listOf("Networks", "Probes", "Timeline").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            when (tab) {
                0 -> NetworksTab(nets.values.sortedBy { it.network.toString() }, def)
                1 -> ProbesTab(run)
                else -> TimelineTab(events)
            }
        }
    }

    if (showMark) {
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showMark = false },
            title = { Text("Mark an issue") },
            text = {
                Column {
                    Text("Saves a full snapshot and runs probes. Optional note (e.g. which app says it's offline):")
                    OutlinedTextField(value = note, onValueChange = { note = it }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    Diagnostics.mark(note.ifBlank { "from app" })
                    showMark = false
                    tab = 2
                }) { Text("Mark") }
            },
            dismissButton = { TextButton(onClick = { showMark = false }) { Text("Cancel") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the event log?") },
            confirmButton = { TextButton(onClick = { EventLog.clear(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatusBanner(def: NetInfo?) {
    val online = def?.appsSeeOnline == true
    val (title, sub) = when {
        def == null -> "Apps see: OFFLINE" to "No default network"
        online -> "Apps see: ONLINE" to "via ${def.label}"
        def.blocked -> "Apps see: OFFLINE" to "Network blocked for this app (Data Saver / battery?)"
        def.captivePortal -> "Apps see: OFFLINE" to "${def.label}: captive portal not signed in"
        else -> "Apps see: OFFLINE" to "${def.label} is not VALIDATED"
    }
    Column(
        Modifier.fillMaxWidth().padding(12.dp)
            .background(if (online) Green else Red, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(sub, color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun Mono(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
}

@Composable
private fun NetworksTab(nets: List<NetInfo>, def: NetInfo?) {
    val ctx = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(nets, key = { it.network.toString() }) { n ->
            val isDefault = n.network == def?.network
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text(n.label + if (isDefault) "  ★ default" else "", fontWeight = FontWeight.Bold)
                    val f = Net.flags(n.caps)
                    Text(buildAnnotatedString {
                        listOf("INTERNET", "VALIDATED", "CAPTIVE_PORTAL", "PARTIAL_CONNECTIVITY", "NOT_SUSPENDED").forEach { name ->
                            val has = name in f
                            val bad = name == "CAPTIVE_PORTAL" || name == "PARTIAL_CONNECTIVITY"
                            val color = when {
                                bad && has -> Red
                                bad -> Color.Gray
                                has -> Green
                                else -> Red
                            }
                            withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) {
                                append(if (has) "✓$name  " else "✗$name  ")
                            }
                        }
                    }, fontSize = 12.sp)
                    Spacer(Modifier.padding(2.dp))
                    Mono(Net.describe(n, isDefault).lines().drop(1).joinToString("\n").trim())
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp)) {
                    Text("System", fontWeight = FontWeight.Bold)
                    Mono(Snapshot.system(ctx).trim())
                }
            }
        }
    }
}

@Composable
private fun statusColor(s: Status) = when (s) {
    Status.OK -> Green
    Status.WARN -> Amber
    Status.FAIL -> Red
}

@Composable
private fun ProbesTab(run: ProbeRun?) {
    if (run == null) {
        Text("No probe run yet. Tap Probe.", Modifier.padding(16.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("Last run ${EventLog.shortTime(run.time)} (${run.reason})", fontSize = 13.sp, color = Color.Gray)
        }
        items(run.hints) { h ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Text("💡 $h", Modifier.padding(10.dp), fontSize = 13.sp)
            }
        }
        items(run.results, key = { it.key }) { r ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.status.name, color = statusColor(r.status), fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.width(44.dp))
                    Text(r.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("${r.ms}ms", fontSize = 11.sp, color = Color.Gray)
                }
                Text(r.summary, fontSize = 12.sp, modifier = Modifier.padding(start = 44.dp))
                HorizontalDivider(Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun TimelineTab(events: List<EventLog.Event>) {
    var hideProbes by rememberSaveable { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(setOf<Long>()) }
    val shown = if (hideProbes) events.filter { it.type != EventLog.Type.PROBE } else events
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            Checkbox(checked = hideProbes, onCheckedChange = { hideProbes = it })
            Text("Hide probe runs", fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text("${events.size} events  ", fontSize = 12.sp, color = Color.Gray)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            items(shown) { e ->
                val color = when (e.type) {
                    EventLog.Type.MARK -> Color(0xFF6A1B9A)
                    EventLog.Type.NET -> Color(0xFF1565C0)
                    EventLog.Type.PROBE -> if (e.title.contains("problems")) Amber else Green
                    EventLog.Type.INFO -> Color.Gray
                }
                val isOpen = e.time in expanded
                Column(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = e.detail.isNotBlank()) {
                            expanded = if (isOpen) expanded - e.time else expanded + e.time
                        }
                        .padding(vertical = 5.dp),
                ) {
                    Row {
                        Text(EventLog.shortTime(e.time), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.Gray)
                        Text(" ${e.type} ", fontSize = 12.sp, color = color, fontWeight = FontWeight.Bold)
                        Text(e.title + if (e.detail.isNotBlank() && !isOpen) " ▸" else "", fontSize = 13.sp)
                    }
                    if (isOpen) Mono(e.detail.trimEnd())
                }
                HorizontalDivider()
            }
        }
    }
}
