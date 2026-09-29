package com.allnetworktools.data

/** How worrying a network looks. */
enum class TwinRisk(val label: String) { High("Risque élevé"), Medium("Suspect"), Low("À noter"), None("Normal") }

data class TwinFinding(val risk: TwinRisk, val title: String, val detail: String, val bssids: Set<String>)

data class SsidReport(val ssid: String, val aps: List<WifiAp>, val findings: List<TwinFinding>) {
    val risk: TwinRisk get() = findings.minByOrNull { it.risk.ordinal }?.risk ?: TwinRisk.None
    val flagged: Set<String> get() = findings.filter { it.risk <= TwinRisk.Medium }.flatMap { it.bssids }.toSet()
}

/**
 * Looks for signs of an "evil twin": another access point that copies the name of a network to
 * lure its clients. Wi-Fi cannot prove an AP is fake from a scan, so each rule explains what it saw.
 */
object EvilTwin {
    /** Security family: an attacker usually drops encryption or swaps enterprise for a password. */
    enum class SecClass { Open, Owe, Wep, Wpa, Wpa2, Wpa3, Enterprise }

    fun secClass(sec: String): SecClass = when {
        "EAP" in sec -> SecClass.Enterprise
        sec == "OWE" -> SecClass.Owe
        sec == "Ouvert" -> SecClass.Open
        sec == "WEP" -> SecClass.Wep
        sec == "WPA3" -> SecClass.Wpa3
        sec == "WPA2" || sec == "WPA2/3" -> SecClass.Wpa2
        sec == "WPA" -> SecClass.Wpa
        else -> SecClass.Wpa2
    }

    fun bytes(bssid: String): List<Int>? = bssid.split(':').takeIf { it.size == 6 }?.map { it.toIntOrNull(16) ?: return null }

    /** Locally administered: chosen by software (phone hotspot, travel router, virtual interface) rather than burnt in by a maker. */
    fun locallyAdministered(bssid: String): Boolean = bytes(bssid)?.let { it[0] and 0x02 != 0 } ?: false

    /**
     * Manufacturer key. APs derive their extra BSSIDs by setting the local bit and changing the first
     * byte, so only bytes 2-3 of the prefix are compared.
     */
    fun makerKey(bssid: String): String? = bytes(bssid)?.let { "%02X:%02X".format(it[1], it[2]) }

    fun prefix(bssid: String): String = bssid.uppercase().take(8)

    /** Networks broadcast by thousands of boxes of different generations: many makers is normal there. */
    private val community = listOf("freewifi", "sfr wifi", "bouygues telecom wi-fi", "orange wi-fi", "eduroam", "fon", "wifi-public", "_sncf")

    private fun isCommunity(ssid: String) = ssid.lowercase().let { s -> community.any { s.startsWith(it) } }

    /**
     * [firstSeen] maps each BSSID to when this session first saw it; [sessionStart] lets a BSSID that
     * appears later (while its SSID was already known) be reported.
     */
    fun analyze(
        scan: List<WifiAp>,
        firstSeen: Map<String, Long> = emptyMap(),
        ssidFirstSeen: Map<String, Long> = emptyMap(),
        now: Long = 0L,
    ): List<SsidReport> = scan.filter { it.ssid.isNotBlank() }.groupBy { it.ssid }.map { (ssid, aps) ->
        val findings = mutableListOf<TwinFinding>()
        val bySec = aps.groupBy { secClass(it.security) }
        val protectedAps = aps.filter { secClass(it.security) in setOf(SecClass.Wpa, SecClass.Wpa2, SecClass.Wpa3, SecClass.Enterprise, SecClass.Wep) }
        val open = bySec[SecClass.Open].orEmpty()
        if (open.isNotEmpty() && protectedAps.isNotEmpty()) {
            findings += TwinFinding(
                TwinRisk.High, "Même nom, sans mot de passe",
                "${protectedAps.size} ${if (protectedAps.size > 1) "points d'accès protègent" else "point d'accès protège"} ce réseau, mais ${open.size} ${if (open.size > 1) "l'annoncent" else "l'annonce"} ouvert. " +
                    "Un faux point d'accès ouvert peut voir tout le trafic non chiffré de ceux qui s'y connectent.",
                open.map { it.bssid }.toSet(),
            )
        }
        val ent = bySec[SecClass.Enterprise].orEmpty()
        val personal = aps.filter { secClass(it.security) in setOf(SecClass.Wpa, SecClass.Wpa2, SecClass.Wpa3) }
        if (ent.isNotEmpty() && personal.isNotEmpty()) {
            val minority = if (ent.size <= personal.size) ent else personal
            findings += TwinFinding(
                TwinRisk.High, "Entreprise et mot de passe mélangés",
                "Ce réseau est annoncé à la fois en WPA-Entreprise (identifiant + mot de passe) et en WPA-Personnel. " +
                    "Des points d'accès pirates imitent ainsi un réseau d'entreprise pour récupérer les identifiants.",
                minority.map { it.bssid }.toSet(),
            )
        }
        val wpa3Only = bySec[SecClass.Wpa3].orEmpty()
        val weaker = aps.filter { secClass(it.security) in setOf(SecClass.Wpa2, SecClass.Wpa, SecClass.Wep) }
        if (wpa3Only.isNotEmpty() && weaker.isNotEmpty()) {
            findings += TwinFinding(
                TwinRisk.Medium, "Sécurité rétrogradée",
                "${wpa3Only.size} ${if (wpa3Only.size > 1) "points d'accès exigent" else "point d'accès exige"} WPA3, ${weaker.size} ${if (weaker.size > 1) "acceptent" else "accepte"} un protocole plus ancien. " +
                    "C'est le schéma d'une attaque par rétrogradation (« downgrade »), ou d'un vieux répéteur.",
                weaker.map { it.bssid }.toSet(),
            )
        } else {
            val legacy = aps.filter { secClass(it.security) in setOf(SecClass.Wep, SecClass.Wpa) }
            if (legacy.isNotEmpty() && aps.any { secClass(it.security) in setOf(SecClass.Wpa2, SecClass.Wpa3) }) {
                findings += TwinFinding(
                    TwinRisk.Medium, "Chiffrement obsolète",
                    "Une partie des points d'accès utilisent WEP ou WPA, cassables en quelques minutes, alors que les autres sont en WPA2 ou WPA3.",
                    legacy.map { it.bssid }.toSet(),
                )
            }
        }
        if (aps.size >= 2) {
            val keys = aps.groupBy { makerKey(it.bssid) }
            if (keys.size >= 2) {
                val main = keys.maxBy { it.value.size }
                val others = aps.filter { makerKey(it.bssid) != main.key }
                val community = isCommunity(ssid) || (aps.size >= 4 && keys.size >= 3)
                if (community) {
                    findings += TwinFinding(
                        TwinRisk.Low, "Plusieurs constructeurs",
                        "${keys.size} préfixes d'adresse différents. Normal pour un réseau public diffusé par de nombreuses box ; " +
                            "ne vous y connectez qu'avec un chiffrement (WPA2/3 ou VPN).",
                        emptySet(),
                    )
                } else {
                    val stronger = others.filter { o -> main.value.all { o.rssi >= it.rssi + 10 } }
                    findings += TwinFinding(
                        if (stronger.isNotEmpty()) TwinRisk.High else TwinRisk.Medium,
                        "Constructeur différent",
                        "${others.size} ${if (others.size > 1) "points d'accès ont" else "point d'accès a"} un préfixe d'adresse (${others.map { prefix(it.bssid) }.distinct().joinToString(", ")}) " +
                            "différent des ${main.value.size} autres (${prefix(main.value.first().bssid)}). Un même réseau est normalement diffusé par un seul fabricant" +
                            (if (stronger.isNotEmpty()) ", et celui-ci capte nettement plus fort : c'est le comportement d'un faux point d'accès placé près de vous." else "."),
                        others.map { it.bssid }.toSet(),
                    )
                }
            }
            val soft = aps.filter { a -> locallyAdministered(a.bssid) && aps.none { b -> b !== a && bytes(b.bssid)?.drop(1)?.take(3) == bytes(a.bssid)?.drop(1)?.take(3) } }
            if (soft.isNotEmpty() && soft.size < aps.size) {
                findings += TwinFinding(
                    TwinRisk.Medium, "Adresse générée par logiciel",
                    "${soft.size} ${if (soft.size > 1) "adresses sont" else "adresse est"} « administrée${if (soft.size > 1) "s" else ""} localement » sans lien avec les autres : " +
                        "typique d'un partage de connexion, d'un routeur de poche ou d'un outil d'attaque.",
                    soft.map { it.bssid }.toSet(),
                )
            }
            val known = ssidFirstSeen[ssid]
            val late = aps.filter { a -> known != null && (firstSeen[a.bssid] ?: known) - known > 60_000L && now - (firstSeen[a.bssid] ?: now) < 15 * 60_000L }
            if (late.isNotEmpty()) {
                findings += TwinFinding(
                    TwinRisk.Low, "Apparu pendant l'analyse",
                    "${late.size} ${if (late.size > 1) "nouveaux points d'accès sont apparus" else "nouveau point d'accès est apparu"} alors que le réseau était déjà visible.",
                    late.map { it.bssid }.toSet(),
                )
            }
        }
        SsidReport(ssid, aps.sortedByDescending { it.rssi }, findings.sortedBy { it.risk.ordinal })
    }.sortedWith(compareBy<SsidReport> { it.risk.ordinal }.thenByDescending { it.aps.size }.thenBy { it.ssid.lowercase() })
}
