package com.allnetworktools.data.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.allnetworktools.ui.theme.Sym
import java.io.IOException
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// ---- UPnP / SSDP --------------------------------------------------------------------------------

data class UpnpDevice(
    val ip: String,
    val location: String,
    val server: String?,
    val friendlyName: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val deviceType: String? = null,
    val services: List<String> = emptyList(),
    val presentationUrl: String? = null,
    val searchTargets: Set<String> = emptySet(),
) {
    val name get() = friendlyName ?: model ?: ip

    /** "MediaRenderer" from "urn:schemas-upnp-org:device:MediaRenderer:1". */
    val typeShort get() = (deviceType ?: searchTargets.firstOrNull { ":device:" in it })?.split(':')?.getOrNull(3)

    val icon: String
        get() = when {
            typeShort == null -> Sym.Devices
            "Gateway" in typeShort!! || "WAN" in typeShort!! -> Sym.Router
            "Renderer" in typeShort!! || "tv" in (model ?: "").lowercase() -> Sym.Tv
            "Server" in typeShort!! -> Sym.Storage
            "Printer" in typeShort!! -> Sym.Print
            "Basic" in typeShort!! -> Sym.Hub
            else -> Sym.Devices
        }
}

object Upnp {
    private fun header(msg: String, name: String) =
        msg.lineSequence().firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim()?.ifEmpty { null }

    private fun tag(xml: String, name: String) =
        Regex("<(?:\\w+:)?$name>([^<]*)</(?:\\w+:)?$name>").find(xml)?.groupValues?.get(1)?.trim()
            ?.replace("&amp;", "&")?.replace("&lt;", "<")?.replace("&gt;", ">")?.ifEmpty { null }

    /** Parses a device description (the XML at LOCATION). */
    fun describe(d: UpnpDevice, xml: String): UpnpDevice {
        val pres = tag(xml, "presentationURL")?.let { runCatching { URL(URL(d.location), it).toString() }.getOrNull() }
        return d.copy(
            friendlyName = tag(xml, "friendlyName"),
            manufacturer = tag(xml, "manufacturer"),
            model = listOfNotNull(tag(xml, "modelName"), tag(xml, "modelNumber")).distinct().joinToString(" ").ifEmpty { null },
            deviceType = tag(xml, "deviceType"),
            services = Regex("<serviceType>([^<]+)</serviceType>").findAll(xml).map { m -> m.groupValues[1].let { t -> t.split(':').getOrElse(3) { t } } }.distinct().toList(),
            presentationUrl = pres,
        )
    }

    /** Sends M-SEARCH ssdp:all twice and emits each device (by LOCATION) as soon as it answers, then with its description. */
    fun discover(context: Context, listenMs: Long = 4000): Flow<UpnpDevice> = channelFlow {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("ant-ssdp")?.apply { setReferenceCounted(false); runCatching { acquire() } }
        try {
            val seen = mutableMapOf<String, UpnpDevice>()
            val socket = MulticastSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(0)); soTimeout = 250 }
            socket.use { s ->
                val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n" +
                    "USER-AGENT: Android/1 UPnP/1.1 AllNetworkTools/1.0\r\n\r\n").toByteArray()
                val group = InetAddress.getByName("239.255.255.250")
                val buf = ByteArray(2048)
                val end = System.currentTimeMillis() + listenMs
                var sent = 0
                coroutineScope {
                    while (System.currentTimeMillis() < end) {
                        if (sent < 2 && System.currentTimeMillis() > end - listenMs + sent * 1000) {
                            runCatching { s.send(DatagramPacket(msg, msg.size, group, 1900)) }; sent++
                        }
                        val p = DatagramPacket(buf, buf.size)
                        try {
                            s.receive(p)
                        } catch (_: SocketTimeoutException) {
                            continue
                        }
                        val text = String(p.data, 0, p.length, Charsets.ISO_8859_1)
                        val loc = header(text, "LOCATION") ?: continue
                        val st = header(text, "ST")
                        val prev = seen[loc]
                        if (prev != null) {
                            if (st != null && st !in prev.searchTargets) seen[loc] = prev.copy(searchTargets = prev.searchTargets + st)
                            continue
                        }
                        val d = UpnpDevice(p.address.hostAddress ?: "?", loc, header(text, "SERVER"), searchTargets = setOfNotNull(st))
                        seen[loc] = d
                        send(d)
                        launch(Dispatchers.IO) { fetch(loc)?.let { send(describe(seen[loc] ?: d, it)) } }
                    }
                }
            }
        } finally {
            runCatching { lock?.release() }
        }
    }.flowOn(Dispatchers.IO)

    private fun fetch(url: String): String? = runCatching {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 2000; readTimeout = 2500
            try { inputStream.bufferedReader().readText().take(200_000) } finally { disconnect() }
        }
    }.getOrNull()
}

// ---- Bonjour / DNS-SD ---------------------------------------------------------------------------

data class BonjourService(
    val type: String,
    val name: String,
    val host: String?,
    val port: Int?,
    val txt: Map<String, String>,
)

object Bonjour {
    /** Friendly labels for common service types. */
    val Labels = mapOf(
        "_googlecast._tcp" to "Google Cast", "_airplay._tcp" to "AirPlay", "_raop._tcp" to "AirPlay audio",
        "_spotify-connect._tcp" to "Spotify Connect", "_ipp._tcp" to "Impression IPP", "_ipps._tcp" to "Impression IPP (TLS)",
        "_printer._tcp" to "Imprimante LPD", "_pdl-datastream._tcp" to "Imprimante (port 9100)", "_scanner._tcp" to "Scanner",
        "_uscan._tcp" to "Scanner eSCL", "_http._tcp" to "Serveur web", "_https._tcp" to "Serveur web (TLS)", "_ssh._tcp" to "SSH",
        "_sftp-ssh._tcp" to "SFTP", "_smb._tcp" to "Partage Windows (SMB)", "_afpovertcp._tcp" to "Partage Apple (AFP)",
        "_device-info._tcp" to "Infos appareil", "_companion-link._tcp" to "Apple Companion", "_homekit._tcp" to "HomeKit",
        "_hap._tcp" to "HomeKit (accessoire)", "_matter._tcp" to "Matter", "_matterc._udp" to "Matter (mise en service)",
        "_meshcop._udp" to "Thread", "_sleep-proxy._udp" to "Sleep Proxy", "_workstation._tcp" to "Poste de travail",
        "_home-assistant._tcp" to "Home Assistant", "_hue._tcp" to "Philips Hue", "_sonos._tcp" to "Sonos",
        "_amzn-wplay._tcp" to "Amazon Fire TV", "_androidtvremote2._tcp" to "Android TV Remote", "_adb-tls-connect._tcp" to "Débogage ADB",
        "_rdlink._tcp" to "Apple rdlink", "_touch-able._tcp" to "Apple TV Remote", "_mqtt._tcp" to "MQTT", "_nvstream._tcp" to "NVIDIA GameStream",
    )

    fun label(type: String) = Labels[type.removeSuffix(".").removeSuffix(".local")] ?: type.removeSuffix(".")

    fun icon(type: String): String = when {
        "cast" in type || "airplay" in type || "raop" in type || "amzn" in type || "androidtv" in type -> Sym.Cast
        "ipp" in type || "print" in type || "pdl" in type -> Sym.Print
        "scan" in type -> Sym.Print
        "http" in type -> Sym.Public
        "ssh" in type || "adb" in type -> Sym.Terminal
        "smb" in type || "afp" in type -> Sym.Storage
        "homekit" in type || "hap" in type || "matter" in type || "hue" in type || "home-assistant" in type || "meshcop" in type -> Sym.Lightbulb
        "spotify" in type || "sonos" in type -> Sym.Speaker
        else -> Sym.Hub
    }

    /**
     * Browses every advertised service type (the "_services._dns-sd._udp" meta-query), then the
     * instances of each type, resolving them one by one (NsdManager handles a single resolve at a time).
     */
    fun browse(context: Context): Flow<BonjourService> = callbackFlow {
        val mgr = context.getSystemService(NsdManager::class.java) ?: run { close(); return@callbackFlow }
        val listeners = mutableListOf<NsdManager.DiscoveryListener>()
        val resolveLock = Mutex()
        val types = mutableSetOf<String>()
        fun listener(onFound: (NsdServiceInfo) -> Unit) = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onServiceFound(info: NsdServiceInfo) = onFound(info)
        }
        fun browseType(type: String) {
            val l = listener { info ->
                launch {
                    val resolved = resolveLock.withLock { resolve(mgr, info) }
                    trySend(
                        BonjourService(
                            type, info.serviceName,
                            resolved?.host?.hostAddress, resolved?.port?.takeIf { it > 0 },
                            resolved?.attributes.orEmpty().mapValues { (_, v) -> v?.toString(Charsets.UTF_8).orEmpty() },
                        ),
                    )
                }
            }
            listeners += l
            runCatching { mgr.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l) }
        }
        val meta = listener { info ->
            // The meta-query answers with name "_googlecast" and type "_tcp.local."
            val proto = info.serviceType.substringBefore('.').ifEmpty { "_tcp" }
            val type = "${info.serviceName}.$proto"
            if (types.add(type)) browseType(type)
        }
        listeners += meta
        runCatching { mgr.discoverServices("_services._dns-sd._udp", NsdManager.PROTOCOL_DNS_SD, meta) }
        awaitClose { listeners.forEach { runCatching { mgr.stopServiceDiscovery(it) } } }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(mgr: NsdManager, info: NsdServiceInfo): NsdServiceInfo? = withTimeoutOrNull(3000) {
        suspendCancellableCoroutine { cont ->
            runCatching {
                mgr.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, errorCode: Int) {
                        if (cont.isActive) cont.resumeWith(Result.success(null))
                    }

                    override fun onServiceResolved(s: NsdServiceInfo) {
                        if (cont.isActive) cont.resumeWith(Result.success(s))
                    }
                })
            }.onFailure { if (cont.isActive) cont.resumeWith(Result.success(null)) }
        }
    }
}

// ---- Whois ---------------------------------------------------------------------------------------

data class WhoisHop(val server: String, val text: String)

data class WhoisResult(val query: String, val isIp: Boolean, val hops: List<WhoisHop>) {
    /** The most specific answer: the last server that returned something useful. */
    val text get() = hops.lastOrNull { it.text.lines().count { l -> ':' in l } > 3 }?.text ?: hops.lastOrNull()?.text.orEmpty()

    private fun first(vararg keys: String): String? {
        for (h in hops.asReversed()) for (k in keys) {
            val v = h.text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("$k:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("REDACTED", true) }
            if (v != null) return v
        }
        return null
    }

    private fun all(vararg keys: String, lower: Boolean = true): List<String> = hops.asReversed().firstNotNullOfOrNull { h ->
        h.text.lineSequence().map { it.trim() }
            .filter { l -> keys.any { l.startsWith("$it:", ignoreCase = true) } }
            .map { it.substringAfter(':').trim().substringBefore(' ').let { v -> if (lower) v.lowercase() else v } }
            .filter { it.isNotEmpty() }.distinct().toList().takeIf { it.isNotEmpty() }
    }.orEmpty()

    val registrar get() = first("Registrar", "registrar", "Sponsoring Registrar")
    val created get() = first("Creation Date", "created", "Registered on", "Registration Time", "RegDate")
    val expires get() = first("Registry Expiry Date", "Registrar Registration Expiration Date", "Expiry Date", "Expiration Date", "expire", "paid-till")
    val updated get() = first("Updated Date", "last-update", "changed", "Last Modified", "Updated")
    val nameServers get() = all("Name Server", "nserver")
    val status get() = all("Domain Status", "status", lower = false).map { it.substringBefore('(') }.take(4)
    val dnssec get() = first("DNSSEC", "dnssec")
    val registrant get() = first("Registrant Organization", "Registrant", "holder-c", "org-name", "OrgName", "Organization")
    val country get() = first("Registrant Country", "country", "Country")
    val network get() = first("inetnum", "NetRange", "inet6num", "CIDR")
    val netName get() = first("netname", "NetName")
    val asn get() = first("origin", "OriginAS")
    val description get() = first("descr", "org-name", "OrgName")
}

object Whois {
    private val IpRegex = Regex("""^[0-9.]+$|^[0-9a-fA-F:]+$""")

    fun isIp(q: String) = IpRegex.matches(q) && (q.count { it == '.' } == 3 || ':' in q)

    private suspend fun ask(server: String, query: String): String = withContext(Dispatchers.IO) {
        Socket().use { s ->
            s.connect(InetSocketAddress(server, 43), 5000)
            s.soTimeout = 8000
            s.getOutputStream().write("$query\r\n".toByteArray())
            s.getInputStream().readBytes().toString(Charsets.UTF_8).take(100_000)
        }
    }

    private fun refer(text: String): String? = text.lineSequence().map { it.trim() }.firstNotNullOfOrNull { l ->
        when {
            l.startsWith("refer:", true) || l.startsWith("whois:", true) -> l.substringAfter(':').trim()
            l.startsWith("Registrar WHOIS Server:", true) -> l.substringAfter(':').trim()
            l.startsWith("ReferralServer:", true) -> l.substringAfter("whois://").trim().trimEnd('/')
            else -> null
        }
    }?.removePrefix("whois://")?.substringBefore(':')?.takeIf { it.contains('.') }

    /** Follows the referral chain from IANA to the registry, then the registrar (at most 3 servers). */
    suspend fun lookup(input: String): WhoisResult {
        val q = input.trim().lowercase().removePrefix("http://").removePrefix("https://").substringBefore('/').removePrefix("www.")
        val ip = isIp(q)
        val hops = mutableListOf<WhoisHop>()
        var server = "whois.iana.org"
        var query = if (ip) q else q.substringAfterLast('.')
        repeat(3) {
            val text = try {
                ask(server, query)
            } catch (e: IOException) {
                if (hops.isEmpty()) throw e else return WhoisResult(q, ip, hops)
            }
            hops += WhoisHop(server, text)
            val next = refer(text)?.takeIf { it != server } ?: return WhoisResult(q, ip, hops)
            server = next
            query = when {
                ip && server == "whois.arin.net" -> "n + $q"
                else -> q
            }
        }
        return WhoisResult(q, ip, hops)
    }
}
