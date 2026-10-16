package io.github.clinejj.adguarddebug

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.random.Random

enum class Status { OK, WARN, FAIL }

data class ProbeResult(val key: String, val name: String, val status: Status, val summary: String, val ms: Long)

data class ProbeRun(val time: Long, val reason: String, val results: List<ProbeResult>, val hints: List<String>) {
    fun toText(): String = buildString {
        appendLine("Probe run at ${EventLog.fullTime(time)} ($reason)")
        hints.forEach { appendLine("HINT: $it") }
        results.forEach { appendLine("[${it.status}] ${it.name} (${it.ms}ms) — ${it.summary}") }
    }

    /** Used to avoid logging full detail when nothing changed since last run. */
    val signature get() = results.joinToString("|") { "${it.key}:${it.status}" }
}

object Probes {
    private const val TIMEOUT_MS = 5000
    private const val HTTP_204 = "http://connectivitycheck.gstatic.com/generate_204"
    private const val HTTPS_204 = "https://www.google.com/generate_204"
    private const val TEST_HOST = "www.google.com"

    suspend fun runAll(ctx: Context): List<ProbeResult> = coroutineScope {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val jobs = mutableListOf<Deferred<ProbeResult>>()
        fun add(key: String, name: String, block: () -> Pair<Status, String>) {
            jobs += async(Dispatchers.IO) { timed(key, name, block) }
        }

        // Through the default network (= AdGuard's VPN when it's on), like any normal app.
        add("http204", "Android connectivity check (HTTP)") { http204(HTTP_204, null) }
        add("https204", "HTTPS generate_204") { http204(HTTPS_204, null) }
        add("cftrace", "Cloudflare trace (HTTPS)") { cfTrace() }
        add("sysdns", "DNS: system resolver ($TEST_HOST)") { sysDns(null) }
        add("udp8", "DNS: UDP to 8.8.8.8") { udpDns(InetAddress.getByName("8.8.8.8"), null) }
        add("udp1", "DNS: UDP to 1.1.1.1") { udpDns(InetAddress.getByName("1.1.1.1"), null) }
        cm.activeNetwork?.let { cm.getLinkProperties(it) }?.dnsServers?.take(2)?.forEach { s ->
            add("lpdns:${s.hostAddress}", "DNS: UDP to default-net server ${s.hostAddress}") { udpDns(s, null) }
        }
        add("doh", "DNS over HTTPS (https://1.1.1.1, no system DNS)") { doh() }
        add("tcp1", "TCP 1.1.1.1:443 (no DNS)") { tcp("1.1.1.1", null) }
        add("tcp8", "TCP 8.8.8.8:443 (no DNS)") { tcp("8.8.8.8", null) }
        add("tcp6", "TCP [2606:4700:4700::1111]:443 (IPv6)") {
            val r = tcp("2606:4700:4700::1111", null)
            if (r.first == Status.FAIL) Status.WARN to r.second else r
        }
        add("tls", "TLS certificate chain ($TEST_HOST)") { tlsChain(TEST_HOST) }

        // Directly on each underlying (non-VPN) network, bypassing AdGuard if the VPN allows it.
        @Suppress("DEPRECATION")
        cm.allNetworks.forEach { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@forEach
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@forEach
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@forEach
            val lbl = Net.label(n, NetworkWatcher.networks.value[n]?.caps ?: caps)
            add("net:http204:$n", "[$lbl] connectivity check, bypassing VPN") { http204(HTTP_204, n) }
            add("net:dns:$n", "[$lbl] DNS via this network, bypassing VPN") { sysDns(n) }
        }

        jobs.awaitAll()
    }

    private fun timed(key: String, name: String, block: () -> Pair<Status, String>): ProbeResult {
        val start = System.nanoTime()
        val (status, summary) = try {
            block()
        } catch (e: Exception) {
            Status.FAIL to "${e.javaClass.simpleName}: ${e.message}"
        }
        return ProbeResult(key, name, status, summary, (System.nanoTime() - start) / 1_000_000)
    }

    private fun open(url: String, net: Network?): HttpURLConnection {
        val u = URL(url)
        val c = (net?.openConnection(u) ?: u.openConnection()) as HttpURLConnection
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        c.instanceFollowRedirects = false
        c.useCaches = false
        c.setRequestProperty("Cache-Control", "no-cache")
        return c
    }

    private fun http204(url: String, net: Network?): Pair<Status, String> {
        val c = open(url, net)
        try {
            val code = c.responseCode
            return when (code) {
                204 -> Status.OK to "204 No Content (expected)"
                in 300..399 -> Status.FAIL to "$code redirect → ${c.getHeaderField("Location")} (captive portal or interception)"
                else -> {
                    val body = runCatching { c.inputStream.bufferedReader().readText().take(150) }.getOrDefault("")
                    Status.FAIL to "HTTP $code (expected 204) ${body.replace('\n', ' ')}"
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun cfTrace(): Pair<Status, String> {
        val c = open("https://www.cloudflare.com/cdn-cgi/trace", null)
        try {
            if (c.responseCode != 200) return Status.FAIL to "HTTP ${c.responseCode}"
            val kv = c.inputStream.bufferedReader().readLines()
                .mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
                .toMap()
            return Status.OK to listOf("ip", "loc", "colo", "http", "tls", "warp")
                .joinToString(" ") { "$it=${kv[it] ?: "?"}" }
        } finally {
            c.disconnect()
        }
    }

    private fun sysDns(net: Network?): Pair<Status, String> {
        val addrs = net?.getAllByName(TEST_HOST) ?: InetAddress.getAllByName(TEST_HOST)
        val list = addrs.joinToString { it.hostAddress ?: "?" }
        return if (addrs.any { it.isAnyLocalAddress }) {
            Status.WARN to "Returned blocked address (0.0.0.0/::): $list"
        } else {
            Status.OK to list
        }
    }

    private fun tcp(ip: String, net: Network?): Pair<Status, String> {
        Socket().use { s ->
            net?.bindSocket(s)
            s.connect(InetSocketAddress(InetAddress.getByName(ip), 443), TIMEOUT_MS)
            return Status.OK to "connected (local ${s.localAddress.hostAddress})"
        }
    }

    private fun doh(): Pair<Status, String> {
        val c = open("https://1.1.1.1/dns-query?name=example.com&type=A", null)
        c.setRequestProperty("Accept", "application/dns-json")
        try {
            if (c.responseCode != 200) return Status.FAIL to "HTTP ${c.responseCode}"
            val body = c.inputStream.bufferedReader().readText()
            val ips = Regex("\"data\"\\s*:\\s*\"([^\"]+)\"").findAll(body).map { it.groupValues[1] }.toList()
            return if (ips.isEmpty()) Status.FAIL to "no answers: ${body.take(150)}"
            else Status.OK to "example.com → ${ips.joinToString()}"
        } finally {
            c.disconnect()
        }
    }

    /** Raw DNS A query over UDP; bypasses Android's resolver (but not the VPN). */
    private fun udpDns(server: InetAddress, net: Network?): Pair<Status, String> {
        val id = Random.nextInt(0x10000)
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).apply {
            writeShort(id); writeShort(0x0100); writeShort(1); writeShort(0); writeShort(0); writeShort(0)
            TEST_HOST.split('.').forEach { writeByte(it.length); write(it.toByteArray()) }
            writeByte(0); writeShort(1); writeShort(1)
        }
        val q = bos.toByteArray()
        DatagramSocket().use { s ->
            net?.bindSocket(s)
            s.soTimeout = 3000
            s.send(DatagramPacket(q, q.size, server, 53))
            val buf = ByteArray(1500)
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            return parseDns(buf, p.length)
        }
    }

    private fun parseDns(b: ByteArray, len: Int): Pair<Status, String> {
        fun u16(i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
        fun skipName(start: Int): Int {
            var i = start
            while (i < len) {
                val l = b[i].toInt() and 0xff
                if (l == 0) return i + 1
                if (l and 0xc0 == 0xc0) return i + 2
                i += l + 1
            }
            return i
        }
        val rcode = b[3].toInt() and 0x0f
        val an = u16(6)
        var i = skipName(12) + 4
        val ips = mutableListOf<String>()
        repeat(an) {
            if (i >= len) return@repeat
            i = skipName(i)
            val type = u16(i)
            val rdlen = u16(i + 8)
            i += 10
            if (type == 1 && rdlen == 4) ips += InetAddress.getByAddress(b.copyOfRange(i, i + 4)).hostAddress ?: "?"
            i += rdlen
        }
        val rc = listOf("NOERROR", "FORMERR", "SERVFAIL", "NXDOMAIN", "NOTIMP", "REFUSED").getOrElse(rcode) { "RCODE$rcode" }
        return when {
            rcode != 0 -> Status.FAIL to rc
            ips.isEmpty() -> Status.FAIL to "$rc but no A records"
            ips.any { it == "0.0.0.0" } -> Status.WARN to "blocked answer: ${ips.joinToString()}"
            else -> Status.OK to "$rc ${ips.joinToString()}"
        }
    }

    /** Shows who issued the certificate we actually received — AdGuard, a captive portal, or Google. */
    private fun tlsChain(host: String): Pair<Status, String> {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        val delegate = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        var chain: Array<out X509Certificate>? = null
        val capture = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) = delegate.checkClientTrusted(c, a)
            override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {
                chain = c
                delegate.checkServerTrusted(c, a)
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(capture), null) }
        var error: String? = null
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(host, 443), TIMEOUT_MS)
            raw.soTimeout = TIMEOUT_MS
            (ssl.socketFactory.createSocket(raw, host, 443, true) as SSLSocket).use { it.startHandshake() }
        } catch (e: Exception) {
            error = "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            runCatching { raw.close() }
        }
        val c = chain
        if (c.isNullOrEmpty()) return Status.FAIL to (error ?: "no certificate received")
        fun cn(p: String) = Regex("CN=([^,]+)").find(p)?.groupValues?.get(1) ?: p
        val leafIssuer = cn(c[0].issuerX500Principal.name)
        val root = cn(c.last().issuerX500Principal.name)
        val desc = "leaf issued by \"$leafIssuer\", top issuer \"$root\", ${c.size} certs"
        val allIssuers = c.joinToString { it.issuerX500Principal.name }
        return when {
            allIssuers.contains("AdGuard", ignoreCase = true) -> Status.WARN to "AdGuard is intercepting HTTPS for this app: $desc" + (error?.let { "; $it" } ?: "")
            error != null -> Status.FAIL to "$desc; NOT trusted: $error"
            else -> Status.OK to "$desc; trusted"
        }
    }
}

/** Turns raw state into plain-English guesses about what's wrong. */
object Analysis {
    fun hints(results: List<ProbeResult>, nets: Map<Network, NetInfo>, def: NetInfo?): List<String> {
        val h = mutableListOf<String>()
        fun st(key: String) = results.firstOrNull { it.key == key }?.status
        fun ok(key: String) = st(key) == Status.OK
        fun failed(key: String) = st(key) == Status.FAIL
        val underlying = nets.values.filter { !it.isVpn && it.hasInternet }

        if (def == null) {
            h += "There is no default network, so every app sees OFFLINE."
        } else {
            if (def.blocked) h += "Network access is blocked for this app (Data Saver, battery restrictions or a firewall). Other apps in the background may be blocked too."
            if (def.isVpn && !def.validated) {
                val goodUnder = underlying.firstOrNull { it.validated }
                val portal = underlying.firstOrNull { it.captivePortal }
                h += when {
                    goodUnder != null -> "AdGuard's VPN is NOT validated even though ${goodUnder.label} is. Android tells apps they're offline. AdGuard probably didn't pick up the change in the network underneath it. Toggling AdGuard should fix it, and this is worth reporting to AdGuard."
                    portal != null -> "${portal.label} has a captive portal that isn't signed in. Sign in (pausing AdGuard may help the login page load). Until then, the VPN can't be validated."
                    else -> "Neither AdGuard's VPN nor the network underneath it is validated. Either connectivity is really down, or Android's validation check is being blocked."
                }
            }
            if (!def.isVpn && def.captivePortal) h += "The default network has a captive portal that isn't signed in."
        }

        val directOk = results.any { it.key.startsWith("net:http204") && it.status == Status.OK }
        val directAllFailed = results.filter { it.key.startsWith("net:http204") }.let { it.isNotEmpty() && it.all { r -> r.status == Status.FAIL } }
        if (failed("http204") && directOk) {
            h += "Android's connectivity check FAILS through the VPN but WORKS when sent directly over the network underneath. AdGuard is blocking or changing it. Try allowlisting connectivitycheck.gstatic.com and www.google.com/generate_204 in AdGuard."
        }
        if (def?.isVpn == true && directAllFailed && ok("http204")) {
            h += "The bypass-VPN probes fail, probably because AdGuard doesn't let apps bypass its VPN. That's expected. To compare, exclude this app in AdGuard and run again."
        }
        if (failed("sysdns") && (ok("tcp1") || ok("tcp8"))) {
            h += "DNS lookups fail but connecting by IP address works, so this is a DNS problem. AdGuard's upstream DNS server may be unreachable, for example encrypted DNS blocked by this Wi-Fi or captive portal."
        }
        val udpOk = ok("udp8") || ok("udp1")
        if (failed("doh") && udpOk) {
            h += "Encrypted DNS (DoH) is blocked on this network while plain DNS works. If AdGuard's DNS upstream uses DoH/DoT/DoQ, lookups will fail here. Add a plain-DNS fallback upstream in AdGuard."
        }
        if (st("sysdns") == Status.WARN) h += "The system resolver returned 0.0.0.0 for $TEST_HOST, so a filter is blocking it."
        if (st("tls") == Status.WARN) h += "AdGuard HTTPS filtering is intercepting this app's traffic. Apps that pin certificates or don't trust user certificates will fail. Exclude affected apps from HTTPS filtering."
        else if (failed("tls") && results.first { it.key == "tls" }.summary.contains("NOT trusted")) {
            h += "The TLS certificate isn't trusted, so something (probably a captive portal) is intercepting HTTPS."
        }
        if (listOf("tcp1", "tcp8").all { failed(it) }) h += "Even raw TCP connections by IP address fail, so there's no real connectivity through the current path."
        if (h.isEmpty() && def?.appsSeeOnline == true && results.none { it.status == Status.FAIL }) {
            h += "Everything looks healthy right now. When an app says it's offline, tap Mark (in the app, the notification, or the Quick Settings tile)."
        }
        return h
    }

    private const val TEST_HOST = "www.google.com"
}
