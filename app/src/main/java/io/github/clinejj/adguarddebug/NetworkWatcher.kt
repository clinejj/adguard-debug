package io.github.clinejj.adguarddebug

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL
import android.net.NetworkCapabilities.NET_CAPABILITY_FOREGROUND
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class NetInfo(
    val network: Network,
    val caps: NetworkCapabilities?,
    val lp: LinkProperties?,
    val blocked: Boolean = false,
    val since: Long = System.currentTimeMillis(),
) {
    val isVpn get() = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    val validated get() = caps?.hasCapability(NET_CAPABILITY_VALIDATED) == true
    val captivePortal get() = caps?.hasCapability(NET_CAPABILITY_CAPTIVE_PORTAL) == true
    val hasInternet get() = caps?.hasCapability(NET_CAPABILITY_INTERNET) == true
    /** What a typical app's "am I online?" check would conclude. */
    val appsSeeOnline get() = hasInternet && validated && !blocked
    val label get() = Net.label(network, caps)
}

object Net {
    /** Capabilities that matter for "is the app online" decisions, in display order. */
    val KEY_CAPS = listOf(
        NET_CAPABILITY_INTERNET to "INTERNET",
        NET_CAPABILITY_VALIDATED to "VALIDATED",
        NET_CAPABILITY_CAPTIVE_PORTAL to "CAPTIVE_PORTAL",
        NET_CAPABILITY_NOT_SUSPENDED to "NOT_SUSPENDED",
        NET_CAPABILITY_FOREGROUND to "FOREGROUND",
        NET_CAPABILITY_NOT_METERED to "NOT_METERED",
        NET_CAPABILITY_NOT_VPN to "NOT_VPN",
    )

    fun flags(caps: NetworkCapabilities?): Set<String> {
        if (caps == null) return emptySet()
        val s = KEY_CAPS.filter { caps.hasCapability(it.first) }.map { it.second }.toMutableSet()
        // PARTIAL_CONNECTIVITY isn't public API, but shows up in toString().
        if (caps.toString().contains("PARTIAL_CONNECTIVITY")) s += "PARTIAL_CONNECTIVITY"
        return s
    }

    fun ssid(caps: NetworkCapabilities?): String? {
        val info = caps?.transportInfo as? WifiInfo ?: return null
        val s = info.ssid?.removeSurrounding("\"") ?: return null
        return if (s == WifiManager.UNKNOWN_SSID || s.isBlank()) null else s
    }

    fun transport(caps: NetworkCapabilities?): String = when {
        caps == null -> "Unknown"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "Other"
    }

    fun label(n: Network, caps: NetworkCapabilities?): String {
        val ssid = ssid(caps)?.let { " \"$it\"" } ?: ""
        return "${transport(caps)}$ssid #$n"
    }

    /** Multi-line description of one network, for the UI and exported reports. */
    fun describe(info: NetInfo, isDefault: Boolean): String = buildString {
        val caps = info.caps
        val lp = info.lp
        appendLine("${info.label}${if (isDefault) "  (DEFAULT)" else ""}")
        if (caps != null) {
            val f = flags(caps)
            appendLine("Flags: " + (KEY_CAPS.map { it.second } + "PARTIAL_CONNECTIVITY")
                .joinToString(" ") { if (it in f) "+$it" else "-$it" })
            appendLine("Bandwidth: down ${caps.linkDownstreamBandwidthKbps} kbps / up ${caps.linkUpstreamBandwidthKbps} kbps")
            (caps.transportInfo as? WifiInfo)?.let { w ->
                appendLine("Wi-Fi: ssid=${ssid(caps) ?: "(hidden: need location permission)"} bssid=${w.bssid} rssi=${w.rssi} freq=${w.frequency}MHz link=${w.linkSpeed}Mbps std=${w.wifiStandard}")
            }
            if (info.isVpn) caps.transportInfo?.let { appendLine("VPN info: $it") }
        }
        if (info.blocked) appendLine("BLOCKED for this app (Data Saver / battery / firewall)")
        if (lp != null) {
            appendLine("Interface: ${lp.interfaceName}  MTU: ${lp.mtu.takeIf { it > 0 } ?: "default"}")
            appendLine("Addresses: ${lp.linkAddresses.joinToString { it.toString() }}")
            appendLine("DNS: ${lp.dnsServers.joinToString { it.hostAddress ?: "?" }.ifEmpty { "(none)" }}")
            appendLine("Private DNS: active=${lp.isPrivateDnsActive} server=${lp.privateDnsServerName ?: "(opportunistic/off)"}")
            if (!lp.domains.isNullOrBlank()) appendLine("Search domains: ${lp.domains}")
            lp.httpProxy?.let { appendLine("HTTP proxy: $it") }
            val routes = lp.routes
            appendLine("Routes (${routes.size}): " + routes.take(8).joinToString { it.toString() } +
                if (routes.size > 8) ", …" else "")
        }
        if (caps != null) appendLine("Raw: $caps")
    }
}

/**
 * Listens to ConnectivityManager callbacks while acquired (by the UI or the background
 * service) and logs every meaningful change.
 */
object NetworkWatcher {
    private val _networks = MutableStateFlow<Map<Network, NetInfo>>(emptyMap())
    val networks: StateFlow<Map<Network, NetInfo>> = _networks.asStateFlow()

    /** The default network as this app sees it — i.e. what other apps' online checks see. */
    private val _default = MutableStateFlow<NetInfo?>(null)
    val defaultNet: StateFlow<NetInfo?> = _default.asStateFlow()

    private var refs = 0
    private var cm: ConnectivityManager? = null
    private var allCb: ConnectivityManager.NetworkCallback? = null
    private var defaultCb: ConnectivityManager.NetworkCallback? = null

    val active get() = refs > 0

    @Synchronized
    fun acquire(ctx: Context) {
        if (refs++ == 0) register(ctx.applicationContext)
    }

    @Synchronized
    fun release() {
        if (refs > 0 && --refs == 0) unregister()
    }

    /** Re-register (e.g. after location permission is granted, so SSIDs stop being redacted). */
    @Synchronized
    fun reRegister(ctx: Context) {
        if (refs > 0) {
            unregister()
            register(ctx.applicationContext)
        }
    }

    private fun register(ctx: Context) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        this.cm = cm
        _networks.value = emptyMap()
        _default.value = null

        val all = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onAvailable(network: Network) {
                _networks.update { it + (network to (it[network] ?: NetInfo(network, null, null))) }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val prev = _networks.value[network]
                _networks.update { it + (network to (prev ?: NetInfo(network, null, null)).copy(caps = caps)) }
                logCapsChange(network, prev?.caps, caps, prefix = "")
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                val prev = _networks.value[network]
                _networks.update { it + (network to (prev ?: NetInfo(network, null, null)).copy(lp = lp)) }
                logLpChange(network, prev?.lp, lp, prev?.caps)
            }

            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                val prev = _networks.value[network]
                _networks.update { it + (network to (prev ?: NetInfo(network, null, null)).copy(blocked = blocked)) }
            }

            override fun onLosing(network: Network, maxMsToLive: Int) {
                EventLog.add(EventLog.Type.NET, "${label(network)} losing (${maxMsToLive}ms)")
            }

            override fun onLost(network: Network) {
                val prev = _networks.value[network]
                _networks.update { it - network }
                EventLog.add(EventLog.Type.NET, "${Net.label(network, prev?.caps)} LOST")
            }
        }

        val def = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onAvailable(network: Network) {
                val known = _networks.value[network]
                _default.value = NetInfo(network, known?.caps, known?.lp)
                EventLog.add(EventLog.Type.NET, "DEFAULT network → ${Net.label(network, known?.caps)}")
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val prev = _default.value
                _default.value = (prev?.takeIf { it.network == network } ?: NetInfo(network, null, null)).copy(caps = caps)
                val wasOnline = prev?.takeIf { it.network == network }?.appsSeeOnline
                val nowOnline = _default.value!!.appsSeeOnline
                if (wasOnline != nowOnline) {
                    EventLog.add(
                        EventLog.Type.NET,
                        "Apps now see ${if (nowOnline) "ONLINE" else "OFFLINE"} (default ${Net.label(network, caps)})",
                        "Flags: ${Net.flags(caps).sorted().joinToString(" ")}",
                    )
                }
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                val prev = _default.value
                _default.value = (prev?.takeIf { it.network == network } ?: NetInfo(network, null, null)).copy(lp = lp)
            }

            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                val prev = _default.value
                val cur = (prev?.takeIf { it.network == network } ?: NetInfo(network, null, null))
                _default.value = cur.copy(blocked = blocked)
                if (cur.blocked != blocked || prev?.network != network) {
                    EventLog.add(
                        EventLog.Type.NET,
                        if (blocked) "Default network BLOCKED for this app (Data Saver / battery / firewall)"
                        else "Default network unblocked for this app",
                    )
                }
            }

            override fun onLost(network: Network) {
                _default.value = null
                EventLog.add(EventLog.Type.NET, "NO default network — apps see OFFLINE")
            }
        }

        // The default request excludes VPNs (NOT_VPN); remove that so we see AdGuard's VPN too.
        val req = NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        cm.registerNetworkCallback(req, all)
        cm.registerDefaultNetworkCallback(def)
        allCb = all
        defaultCb = def
    }

    private fun unregister() {
        val cm = cm ?: return
        allCb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        defaultCb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        allCb = null
        defaultCb = null
    }

    private fun label(n: Network) = Net.label(n, _networks.value[n]?.caps)

    private fun logCapsChange(n: Network, old: NetworkCapabilities?, new: NetworkCapabilities, prefix: String) {
        val nf = Net.flags(new)
        if (old == null) {
            EventLog.add(EventLog.Type.NET, "$prefix${Net.label(n, new)} connected", "Flags: ${nf.sorted().joinToString(" ")}")
            return
        }
        val of = Net.flags(old)
        val added = nf - of
        val removed = of - nf
        val ssidChanged = Net.ssid(old) != Net.ssid(new) && Net.ssid(new) != null
        if (added.isEmpty() && removed.isEmpty() && !ssidChanged) return
        val diff = (added.map { "+$it" } + removed.map { "-$it" }).joinToString(" ")
        EventLog.add(
            EventLog.Type.NET,
            "$prefix${Net.label(n, new)}: $diff".trimEnd(),
            "Flags now: ${nf.sorted().joinToString(" ")}",
        )
    }

    private fun lpSummary(lp: LinkProperties) = listOf(
        "iface=${lp.interfaceName}",
        "dns=${lp.dnsServers.joinToString(",") { it.hostAddress ?: "?" }}",
        "privateDns=${lp.isPrivateDnsActive}/${lp.privateDnsServerName}",
        "mtu=${lp.mtu}",
        "addrs=${lp.linkAddresses.joinToString(",")}",
        "proxy=${lp.httpProxy}",
    )

    private fun logLpChange(n: Network, old: LinkProperties?, new: LinkProperties, caps: NetworkCapabilities?) {
        val ns = lpSummary(new)
        if (old == null) {
            EventLog.add(EventLog.Type.NET, "${Net.label(n, caps)} link properties", ns.joinToString("\n"))
            return
        }
        val os = lpSummary(old)
        val changed = ns.filterIndexed { i, s -> s != os[i] }
        if (changed.isEmpty()) return
        EventLog.add(
            EventLog.Type.NET,
            "${Net.label(n, caps)} changed: " + changed.joinToString(" ") { it.substringBefore('=') },
            changed.joinToString("\n") + "\n(was)\n" +
                os.filterIndexed { i, s -> s != ns[i] }.joinToString("\n"),
        )
    }
}
