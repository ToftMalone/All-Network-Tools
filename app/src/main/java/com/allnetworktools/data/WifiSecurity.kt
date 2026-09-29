package com.allnetworktools.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.allnetworktools.data.net.DnsClient
import com.allnetworktools.data.net.DnsServer
import com.allnetworktools.data.net.DnsType
import com.allnetworktools.data.net.Net
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class CheckLevel(val label: String) { Bad("Risque"), Warn("À améliorer"), Info("Info"), Good("OK") }

data class SecCheck(val level: CheckLevel, val title: String, val detail: String)

/** Addresses and state of the active network, read from Android's link properties. */
data class LinkSnapshot(
    val wifi: Boolean,
    val gateway: String?,
    val ipv6Routers: List<String>,
    val dns: List<String>,
    val dhcpServer: String?,
    val privateDns: Boolean,
    val privateDnsServer: String?,
    val validated: Boolean,
    val captivePortal: Boolean,
)

// ---- 1. Audit of the connected network ------------------------------------------------------------

object NetworkAudit {
    /**
     * [security] is Android's description of the link (WifiInfo), [caps] the capabilities of the
     * connected AP in the last scan ("[RSN-SAE+PSK-CCMP][MFPC][WPS][ESS]"), [openPorts] the admin
     * ports found open on the gateway.
     */
    fun checks(conn: WifiConnection, caps: String?, link: LinkSnapshot?, openPorts: Set<Int>?): List<SecCheck> = buildList {
        val sec = conn.security.orEmpty()
        val c = caps.orEmpty()
        add(
            when {
                sec.startsWith("Ouvert") -> SecCheck(CheckLevel.Bad, "Réseau ouvert", "Aucun chiffrement : toute personne à portée peut lire le trafic non chiffré (HTTP, DNS).")
                "WEP" in sec -> SecCheck(CheckLevel.Bad, "Chiffrement WEP", "WEP se casse en quelques minutes. Passez la box en WPA2 ou WPA3.")
                "OWE" in sec -> SecCheck(CheckLevel.Warn, "Enhanced Open (OWE)", "Le trafic est chiffré, mais n'importe qui peut se connecter et rien ne prouve l'identité du point d'accès.")
                "192" in sec -> SecCheck(CheckLevel.Good, "WPA3-Entreprise 192 bits", "Le niveau de chiffrement Wi-Fi le plus élevé.")
                "EAP" in sec -> SecCheck(CheckLevel.Good, "WPA-Entreprise", "Chaque utilisateur a ses identifiants : bonne pratique en entreprise, à condition de vérifier le certificat du serveur.")
                "SAE" in sec -> SecCheck(CheckLevel.Good, "WPA3-Personnel", "Protocole actuel : protège le mot de passe contre les attaques hors ligne.")
                "PSK" in sec || "WPA2" in sec -> SecCheck(CheckLevel.Info, "WPA2-Personnel", "Correct si le mot de passe est long et aléatoire. WPA3 protège mieux contre les attaques par dictionnaire.")
                else -> SecCheck(CheckLevel.Info, "Sécurité : ${sec.ifEmpty { "inconnue" }}", "Android ne précise pas le protocole utilisé.")
            },
        )
        if ("SAE" in c && "PSK" in c) {
            add(SecCheck(CheckLevel.Warn, "Mode de transition WPA2/WPA3", "Le point d'accès accepte encore WPA2 : un attaquant peut forcer une connexion en WPA2, plus facile à attaquer. Passez en WPA3 seul si tous vos appareils le gèrent."))
        }
        if ("TKIP" in c) {
            add(SecCheck(CheckLevel.Bad, "Chiffrement TKIP", "TKIP (WPA1) est obsolète et vulnérable. Réglez la box sur « AES / CCMP » uniquement."))
        }
        if ("WPS" in c) {
            add(SecCheck(CheckLevel.Warn, "WPS activé", "Le code PIN WPS peut être forcé (attaques Pixie Dust, force brute) pour récupérer la clé Wi-Fi. Désactivez WPS dans la box."))
        }
        when {
            "MFPR" in c || ("SAE" in c && "PSK" !in c) -> add(SecCheck(CheckLevel.Good, "Trames de gestion protégées (PMF)", "Obligatoires : empêche les déconnexions forcées et certaines usurpations."))
            "MFPC" in c -> add(SecCheck(CheckLevel.Info, "Trames de gestion protégées (PMF)", "Proposées mais pas obligatoires : les appareils anciens restent vulnérables aux déconnexions forcées."))
            caps != null -> add(SecCheck(CheckLevel.Warn, "Trames de gestion non protégées", "Le point d'accès n'annonce pas PMF : un attaquant peut déconnecter les appareils à volonté (attaque par désauthentification)."))
        }
        if (conn.standard in 1..4) {
            add(SecCheck(CheckLevel.Info, "Matériel ancien (Wi-Fi 4 ou antérieur)", "Les points d'accès anciens ne reçoivent souvent plus de mises à jour de sécurité."))
        }
        if (link != null) {
            add(
                if (link.privateDns) SecCheck(CheckLevel.Good, "DNS chiffré", "Vos requêtes DNS sont chiffrées" + (link.privateDnsServer?.let { " vers $it" } ?: "") + " : le réseau ne voit pas les noms des sites visités.")
                else SecCheck(CheckLevel.Warn, "DNS en clair", "Le réseau peut voir et modifier les noms des sites que vous visitez. Activez le « DNS privé » dans les paramètres réseau d'Android."),
            )
        }
        if (openPorts != null) {
            if (23 in openPorts) add(SecCheck(CheckLevel.Bad, "Telnet ouvert sur la box", "Le port 23 de la passerelle accepte des connexions Telnet non chiffrées."))
            if (80 in openPorts && 443 !in openPorts) add(SecCheck(CheckLevel.Warn, "Administration de la box en HTTP", "L'interface de la box n'est accessible qu'en HTTP : le mot de passe d'administration circule en clair sur le Wi-Fi."))
            else if (443 in openPorts) add(SecCheck(CheckLevel.Good, "Administration de la box en HTTPS", "L'interface de la box est accessible en HTTPS."))
            if (openPorts.isEmpty()) add(SecCheck(CheckLevel.Good, "Aucun port d'administration ouvert", "Ni Telnet, ni HTTP, ni HTTPS ne répondent sur la passerelle."))
        }
    }

    fun score(list: List<SecCheck>): Int = (100 - 30 * list.count { it.level == CheckLevel.Bad } - 10 * list.count { it.level == CheckLevel.Warn }).coerceIn(0, 100)

    fun grade(score: Int) = when {
        score >= 90 -> "A"
        score >= 75 -> "B"
        score >= 60 -> "C"
        score >= 40 -> "D"
        else -> "E"
    }
}

// ---- 2. Captive portal and DNS tampering -------------------------------------------------------

/** Network probes, replaced by fakes in tests. */
interface NetProbes {
    /** HTTP status and body length of the connectivity check URL, redirects not followed; null when unreachable. */
    suspend fun connectivityCheck(): Triple<Int, Int, String?>?

    /** IPv4 addresses from the system resolver; empty for "no such domain", null on network error. */
    suspend fun systemResolve(host: String): List<String>?

    /** A records from a DNS query sent over UDP straight to [server]; null when nobody answered. */
    suspend fun udpQuery(server: String, host: String): List<String>?

    /** Source of the "time exceeded" reply to a TTL 1 probe: the first router on the path. */
    suspend fun firstHop(): String?

    suspend fun tcpOpen(host: String, port: Int): Boolean
}

class RealProbes : NetProbes {
    override suspend fun connectivityCheck(): Triple<Int, Int, String?>? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(ConnectivityUrl).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 5000
            c.readTimeout = 5000
            c.useCaches = false
            try {
                val code = c.responseCode
                val body = runCatching { (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().size } ?: 0 }.getOrDefault(0)
                Triple(code, body, c.getHeaderField("Location"))
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    override suspend fun systemResolve(host: String): List<String>? = withContext(Dispatchers.IO) {
        try {
            InetAddress.getAllByName(host).filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
        } catch (_: java.net.UnknownHostException) {
            emptyList()
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun udpQuery(server: String, host: String): List<String>? = runCatching {
        DnsClient.resolve(host, DnsType.A, DnsServer(server, server, null), doh = false, timeoutMs = 2500).records.filter { it.type == "A" }.map { it.value }
    }.getOrNull()

    override suspend fun firstHop(): String? = runCatching { Net.ping(InetAddress.getByName("1.1.1.1"), ttl = 1).from }.getOrNull()

    override suspend fun tcpOpen(host: String, port: Int): Boolean =
        runCatching { Net.tcp(InetAddress.getByName(host), port, 1200).state == Net.Tcp.Open }.getOrDefault(false)

    companion object {
        const val ConnectivityUrl = "http://connectivitycheck.gstatic.com/generate_204"
    }
}

object PortalDnsCheck {
    /** Names whose addresses never change: any other answer is a forged one. */
    val Anchors = mapOf(
        "one.one.one.one" to setOf("1.1.1.1", "1.0.0.1"),
        "dns.google" to setOf("8.8.8.8", "8.8.4.4"),
    )

    /** Documentation address (RFC 5737): nothing listens there, so a DNS answer means port 53 is intercepted. */
    const val Blackhole = "192.0.2.53"

    suspend fun run(p: NetProbes, link: LinkSnapshot?, random: String = java.util.UUID.randomUUID().toString().take(12)): List<SecCheck> = buildList {
        // Captive portal / HTTP interception.
        val cc = p.connectivityCheck()
        add(
            when {
                cc == null -> SecCheck(CheckLevel.Info, "Test HTTP impossible", "Le serveur de test de Google n'a pas répondu : pas d'accès Internet, ou flux HTTP bloqué.")
                cc.first == 204 && cc.second == 0 -> SecCheck(CheckLevel.Good, "Pas de portail captif", "La page de test HTTP arrive intacte : le réseau ne détourne ni ne modifie le trafic web non chiffré.")
                cc.first in 300..399 -> SecCheck(CheckLevel.Warn, "Portail captif", "Le réseau redirige le trafic web" + (cc.third?.let { " vers ${it.take(80)}" } ?: "") + " : une page de connexion est probablement requise.")
                else -> SecCheck(CheckLevel.Bad, "Trafic web modifié", "La page de test (réponse vide attendue) revient avec le code ${cc.first} et ${cc.second} octets : le réseau injecte ou remplace du contenu HTTP.")
            },
        )
        if (link != null) {
            if (link.captivePortal) add(SecCheck(CheckLevel.Warn, "Portail signalé par Android", "Android a détecté une page de connexion sur ce réseau."))
            else if (!link.validated) add(SecCheck(CheckLevel.Warn, "Accès Internet non validé", "Android n'a pas pu vérifier l'accès à Internet sur ce réseau."))
        }
        // Forged answers for names with fixed addresses.
        Anchors.forEach { (host, expected) ->
            val got = p.systemResolve(host)
            when {
                got == null -> add(SecCheck(CheckLevel.Info, "$host non résolu", "Le résolveur du système n'a pas répondu."))
                got.isNotEmpty() && got.all { it in expected } -> add(SecCheck(CheckLevel.Good, "$host → ${got.joinToString()}", "Adresse authentique : le DNS du réseau ne falsifie pas cette réponse."))
                else -> add(SecCheck(CheckLevel.Bad, "Réponse DNS falsifiée", "$host devrait donner ${expected.joinToString(" ou ")}, le DNS a répondu ${got.ifEmpty { listOf("« domaine inexistant »") }.joinToString()}."))
            }
        }
        // Answers for a domain that does not exist.
        val fake = "$random.example.com"
        val nx = p.systemResolve(fake)
        when {
            nx == null -> Unit
            nx.isEmpty() -> add(SecCheck(CheckLevel.Good, "Domaines inexistants signalés", "Un nom qui n'existe pas renvoie bien une erreur : pas de redirection publicitaire ou de portail DNS."))
            else -> add(SecCheck(CheckLevel.Bad, "Domaines inexistants redirigés", "Un nom inventé ($fake) a reçu l'adresse ${nx.joinToString()} : le DNS redirige les erreurs vers une autre page."))
        }
        // Transparent DNS proxy: an answer from an address where no server exists.
        val trap = p.udpQuery(Blackhole, "one.one.one.one")
        add(
            if (trap != null) SecCheck(CheckLevel.Bad, "DNS intercepté", "Une requête envoyée à $Blackhole, où aucun serveur n'existe, a reçu une réponse : le réseau capte tout le trafic DNS (port 53), quel que soit le serveur choisi.")
            else SecCheck(CheckLevel.Good, "Pas d'interception du port 53", "Une requête DNS vers une adresse vide reste sans réponse, comme il se doit."),
        )
        val direct = p.udpQuery("1.1.1.1", "one.one.one.one")
        if (direct != null && direct.isNotEmpty() && direct.any { it !in Anchors.getValue("one.one.one.one") }) {
            add(SecCheck(CheckLevel.Bad, "Réponse de 1.1.1.1 falsifiée", "Interrogé directement, 1.1.1.1 a répondu ${direct.joinToString()} : la réponse a été remplacée en chemin."))
        }
        if (link?.privateDns == true) {
            add(SecCheck(CheckLevel.Good, "DNS privé actif", "Vos applications passent par un DNS chiffré" + (link.privateDnsServer?.let { " ($it)" } ?: "") + ", à l'abri de ces manipulations."))
        }
    }
}

// ---- 3. Man-in-the-middle watch ------------------------------------------------------------------

data class GatewaySnapshot(
    val timeMs: Long,
    val gateway: String?,
    val dhcpServer: String?,
    val dns: List<String>,
    val ipv6Routers: List<String>,
    val firstHop: String?,
    val bssid: String?,
)

data class MitmEvent(val timeMs: Long, val level: CheckLevel, val text: String)

object MitmWatch {
    fun checks(s: GatewaySnapshot): List<SecCheck> = buildList {
        val gw = s.gateway
        when {
            gw == null -> add(SecCheck(CheckLevel.Info, "Pas de passerelle IPv4", "Le réseau n'annonce pas de routeur IPv4."))
            s.firstHop == null -> add(SecCheck(CheckLevel.Info, "Premier saut inconnu", "Le premier routeur n'a pas répondu au test (ICMP filtré)."))
            s.firstHop == gw -> add(SecCheck(CheckLevel.Good, "Le trafic passe par la box", "Le premier routeur sur le chemin d'Internet est bien la passerelle $gw."))
            else -> add(
                SecCheck(
                    CheckLevel.Bad, "Un appareil s'intercale",
                    "Vos paquets passent d'abord par ${s.firstHop} au lieu de la passerelle $gw. C'est la signature d'une usurpation ARP (« ARP spoofing ») ou d'une redirection ICMP.",
                ),
            )
        }
        if (s.dhcpServer != null && gw != null && s.dhcpServer != gw) {
            add(SecCheck(CheckLevel.Warn, "Serveur DHCP distinct de la box", "L'adresse IP a été attribuée par ${s.dhcpServer}, pas par la passerelle $gw. Normal en entreprise ; suspect sur un réseau domestique (serveur DHCP pirate)."))
        } else if (s.dhcpServer != null) {
            add(SecCheck(CheckLevel.Good, "Serveur DHCP : la passerelle", "L'adresse IP a été attribuée par la box elle-même."))
        }
        if (s.ipv6Routers.size > 1) {
            add(SecCheck(CheckLevel.Warn, "Plusieurs routeurs IPv6", "${s.ipv6Routers.size} routeurs s'annoncent en IPv6 (${s.ipv6Routers.joinToString()}). Un appareil pirate peut détourner le trafic IPv6 ainsi (attaque SLAAC)."))
        }
        if (s.dns.any { d -> !isPrivate(d) && d != gw } && s.dns.none { it == gw }) {
            add(SecCheck(CheckLevel.Info, "DNS public", "Le réseau distribue les serveurs DNS ${s.dns.joinToString()}."))
        }
    }

    /** What changed between two snapshots on the same access point. */
    fun changes(a: GatewaySnapshot, b: GatewaySnapshot): List<MitmEvent> = buildList {
        if (a.bssid != b.bssid) return@buildList
        val t = b.timeMs
        if (a.gateway != b.gateway) add(MitmEvent(t, CheckLevel.Bad, "La passerelle a changé : ${a.gateway} → ${b.gateway}"))
        if (a.dhcpServer != b.dhcpServer && a.dhcpServer != null && b.dhcpServer != null) add(MitmEvent(t, CheckLevel.Bad, "Le serveur DHCP a changé : ${a.dhcpServer} → ${b.dhcpServer}"))
        if (a.dns.toSet() != b.dns.toSet()) add(MitmEvent(t, CheckLevel.Warn, "Les serveurs DNS ont changé : ${a.dns.joinToString()} → ${b.dns.joinToString()}"))
        (b.ipv6Routers - a.ipv6Routers.toSet()).forEach { add(MitmEvent(t, CheckLevel.Warn, "Nouveau routeur IPv6 : $it")) }
        if (a.firstHop != null && b.firstHop != null && a.firstHop != b.firstHop) add(MitmEvent(t, CheckLevel.Bad, "Le premier routeur a changé : ${a.firstHop} → ${b.firstHop}"))
    }

    fun isPrivate(ip: String): Boolean {
        val p = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return ip.startsWith("fe80") || ip.startsWith("fd") || ip.startsWith("fc")
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) || p[0] == 127 || (p[0] == 100 && p[1] in 64..127)
    }
}

/** Reads the active network's link properties and DHCP lease. */
open class LinkReader(private val context: Context) {
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    open fun read(): LinkSnapshot? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val net = cm.activeNetwork ?: return null
        val lp = cm.getLinkProperties(net) ?: return null
        val caps = cm.getNetworkCapabilities(net)
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val dhcp = if (wifi) runCatching { context.getSystemService(WifiManager::class.java)?.dhcpInfo?.serverAddress }.getOrNull() else null
        return LinkSnapshot(
            wifi = wifi,
            gateway = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress,
            ipv6Routers = lp.routes.filter { it.isDefaultRoute && it.gateway is Inet6Address && !it.gateway!!.isAnyLocalAddress }
                .mapNotNull { it.gateway?.hostAddress?.substringBefore('%') }.distinct(),
            dns = lp.dnsServers.mapNotNull { it.hostAddress?.substringBefore('%') },
            dhcpServer = dhcp?.takeIf { it != 0 }?.let { "%d.%d.%d.%d".format(it and 0xFF, it shr 8 and 0xFF, it shr 16 and 0xFF, it ushr 24) },
            privateDns = lp.isPrivateDnsActive,
            privateDnsServer = lp.privateDnsServerName,
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            captivePortal = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
        )
    }
}
