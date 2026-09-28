package com.allnetworktools.data

import com.allnetworktools.ui.theme.Sym

enum class BleKind(val label: String, val filter: String, val icon: String) {
    Audio("Audio", "Audio", Sym.Headphones),
    Watch("Montre", "Montres", Sym.Watch),
    Tag("Tag", "Tags", Sym.Sell),
    Phone("Téléphone", "Téléphones", Sym.Smartphone),
    Computer("Ordinateur", "Ordinateurs", Sym.Laptop),
    Media("TV & média", "TV & médias", Sym.Tv),
    Accessory("Accessoire", "Accessoires", Sym.Devices),
    Health("Santé", "Santé", Sym.Favorite),
    Home("Maison", "Maison", Sym.Lightbulb),
    Vehicle("Véhicule", "Véhicules", Sym.DirectionsCar),
    Unknown("Inconnu", "Inconnu", Sym.Bluetooth),
}

/** Result of [BleIdentify.identify]: what the device is, how sure we are, and why. */
data class BleIdentity(
    val kind: BleKind = BleKind.Unknown,
    val maker: String? = null,
    val model: String? = null,
    val icon: String? = null,
    /** 0–99. Below 60 the identification is a probable guess. */
    val confidence: Int = 0,
    val evidence: List<String> = emptyList(),
    /** Decoded values (iBeacon UUID, temperature…), shown in the device page. */
    val details: List<Pair<String, String>> = emptyList(),
) {
    val known get() = kind != BleKind.Unknown
    val guessed get() = known && confidence < 60
}

/**
 * Classifies a BLE device from everything its advertisements reveal. Each rule proposes a
 * candidate (kind, label, score, reason); the best-scored candidate wins and the others that
 * agree with it raise the confidence. Pure Kotlin: no Android types, so it is unit-testable.
 */
object BleIdentify {
    private class Cand(val kind: BleKind, val label: String?, val maker: String?, val icon: String?, val score: Int, val reason: String)

    private class Ctx {
        val cands = mutableListOf<Cand>()
        val details = mutableListOf<Pair<String, String>>()
        fun add(kind: BleKind, label: String?, maker: String?, score: Int, reason: String, icon: String? = null) {
            cands += Cand(kind, label, maker, icon, score, reason)
        }

        fun detail(k: String, v: String) {
            details += k to v
        }
    }

    fun identify(name: String?, ads: List<AdStructure>, address: String = "", gatt: GattIdentity? = null): BleIdentity {
        val ctx = Ctx()
        val localName = (name ?: Ad.name(ads) ?: gatt?.name)?.trim()?.takeIf { it.isNotEmpty() }
        val mfg = Ad.manufacturer(ads)
        val sdata = Ad.serviceData(ads)

        // 1. Company identifiers and their payloads.
        mfg.forEach { (cid, d) ->
            when (cid) {
                0x004C -> apple(ctx, d)
                0x0006 -> microsoft(ctx, d)
                0x0499 -> ruuvi(ctx, d)
                else -> Unit
            }
            MakerHints[cid]?.let { h -> ctx.add(h.kind, h.label, Companies.plain(cid), h.score, "Fabricant : ${Companies.plain(cid)}", h.icon) }
        }

        // 2. Service data with a known layout.
        sdata[0xFEAA]?.let { eddystone(ctx, it) }
        sdata[0xFE2C]?.let { fastPair(ctx, it) }
        sdata[0xFE95]?.let { xiaomi(ctx, it) }
        sdata[0xFD6F]?.let { ctx.detail("Notification d'exposition", "identifiant de proximité aléatoire (${it.size} octets)") }

        // 3. Advertised (or discovered) services.
        val uuids16 = Ad.uuids16(ads) + (gatt?.services ?: emptySet())
        uuids16.forEach { u -> Svc16[u]?.let { h -> ctx.add(h.kind, h.label, h.maker, h.score + (if (gatt != null && u in gatt.services && u !in Ad.uuids16(ads)) 6 else 0), "Service ${"0x%04X".format(u)}${h.maker?.let { " ($it)" } ?: ""}", h.icon) } }
        Ad.uuids128(ads).forEach { u -> Svc128.firstOrNull { it.matches(u) }?.let { h -> ctx.add(h.kind, h.label, h.maker, h.score, "Service $u", h.icon) } }

        // 4. What the device says it is: appearance, class of device.
        (gatt?.appearance ?: Ad.appearance(ads))?.let { appearance(ctx, it, if (gatt?.appearance != null) "GATT" else "annonce") }
        Ad.classOfDevice(ads)?.let { classOfDevice(ctx, it) }

        // 5. Names: advertised name plus, after a connection, manufacturer and model strings.
        val texts = buildList {
            localName?.let { add(it to "nom « $it »") }
            gatt?.model?.let { add(it to "modèle GATT « $it »") }
            gatt?.manufacturer?.let { add(it to "fabricant GATT « $it »") }
        }
        texts.forEach { (t, src) -> nameRules(ctx, t, src) }
        localName?.let { n -> if (TeslaName.matches(n)) ctx.add(BleKind.Vehicle, "Tesla", "Tesla", 92, "Nom Tesla « $n »", Sym.DirectionsCar) }
        gatt?.manufacturer?.let { m -> gattMaker(m)?.let { ctx.add(BleKind.Unknown, null, it, 0, "Fabricant GATT : $it") } }

        return conclude(ctx, mfg.keys, gatt, uuids16, ads, address)
    }

    private fun conclude(ctx: Ctx, companies: Set<Int>, gatt: GattIdentity?, uuids16: Set<Int>, ads: List<AdStructure>, address: String): BleIdentity {
        val known = ctx.cands.filter { it.kind != BleKind.Unknown && it.score >= 40 }
        val best = known.maxWithOrNull(compareBy<Cand> { it.score }.thenBy { it.label != null })
        val makerFromCompany = companies.mapNotNull { c -> Companies.plain(c)?.takeIf { !Companies.isChipset(c) } }.firstOrNull()
            ?: companies.firstNotNullOfOrNull { c -> Companies.name(c) }
            ?: companies.firstOrNull()?.let { "Fabricant 0x%04X".format(it) }
        val makerFromGatt = ctx.cands.firstOrNull { it.kind == BleKind.Unknown && it.maker != null }?.maker
        if (best == null) {
            val ev = buildList {
                if (ads.isEmpty()) add("Aucune trame d'annonce reçue") else add("Aucun indice d'identité dans l'annonce (${ads.size} structure${if (ads.size > 1) "s" else ""})")
                if (makerFromCompany != null) add("Fabricant : $makerFromCompany")
            }
            return BleIdentity(BleKind.Unknown, makerFromGatt ?: makerFromCompany, null, null, 0, ev, ctx.details)
        }
        val agreeing = known.filter { it !== best && it.kind == best.kind }.map { it.reason }.distinct()
        val confidence = minOf(99, best.score + 4 * minOf(agreeing.size, 3))
        val maker = best.maker ?: known.firstOrNull { it.kind == best.kind && it.maker != null }?.maker ?: makerFromGatt ?: makerFromCompany
        val model = best.label ?: known.firstOrNull { it.kind == best.kind && it.label != null }?.label
        val icon = best.icon ?: known.firstOrNull { it.kind == best.kind && it.icon != null && it.score >= best.score - 10 }?.icon
        return BleIdentity(best.kind, maker, model, icon, confidence, (listOf(best.reason) + agreeing).take(6), ctx.details)
    }

    // ---- Apple Continuity ------------------------------------------------------------------------

    private val AppleTypes = mapOf(
        0x02 to "iBeacon", 0x03 to "AirPrint", 0x05 to "AirDrop", 0x06 to "HomeKit", 0x07 to "Proximity Pairing", 0x08 to "Hey Siri",
        0x09 to "AirPlay (cible)", 0x0A to "AirPlay (source)", 0x0B to "Magic Switch", 0x0C to "Handoff", 0x0D to "Partage de connexion (cible)",
        0x0E to "Partage de connexion (source)", 0x0F to "Nearby Action", 0x10 to "Nearby Info", 0x12 to "Localiser",
    )

    private val AirPods = mapOf(
        0x0220 to "AirPods", 0x0F20 to "AirPods (2e génération)", 0x1320 to "AirPods (3e génération)", 0x0E20 to "AirPods Pro",
        0x1420 to "AirPods Pro (2e génération)", 0x0A20 to "AirPods Max", 0x0B20 to "Powerbeats Pro", 0x0520 to "BeatsX",
        0x0620 to "Beats Solo3", 0x0920 to "Beats Studio3", 0x0320 to "Powerbeats3", 0x0C20 to "Beats Solo Pro",
        0x1020 to "Beats Flex", 0x1120 to "Beats Studio Buds",
    )

    private val IBeaconUuids = mapOf(
        "B9407F30-F5F8-466E-AFF9-25556B57FE6D" to "Estimote", "F7826DA6-4FA2-4E98-8024-BC5B71E0893E" to "Kontakt.io",
        "2F234454-CF6D-4A0F-ADF2-F4911BA9FFA6" to "Radius Networks", "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0" to "AirLocate / Minew",
        "FDA50693-A4E2-4FB1-AFCF-C6EB07647825" to "Feasycom / beacon générique", "61687109-905F-4436-91F8-E602F514C96D" to "BlueCats",
        "23A01AF0-232A-4518-9C0E-323FB773F5EF" to "Sensoro",
    )

    private fun tlv(d: ByteArray): List<Pair<Int, ByteArray>> {
        val out = mutableListOf<Pair<Int, ByteArray>>()
        var i = 0
        while (i + 2 <= d.size) {
            val t = d.u8(i)
            val l = d.u8(i + 1)
            if (t == 0) break
            out += t to d.copyOfRange(i + 2, minOf(i + 2 + l, d.size))
            i += 2 + l
        }
        return out
    }

    private fun apple(ctx: Ctx, d: ByteArray) {
        val items = tlv(d)
        if (items.isEmpty()) {
            ctx.add(BleKind.Phone, "Appareil Apple", "Apple", 45, "Données fabricant Apple", Sym.Smartphone)
            return
        }
        val types = items.map { it.first }.toSet()
        ctx.detail("Trames Continuity", types.sorted().joinToString(", ") { AppleTypes[it] ?: "0x%02X".format(it) })
        for ((t, p) in items) when (t) {
            0x02 -> if (p.size >= 21) {
                val uuid = listOf(p.hex(0, 4), p.hex(4, 6), p.hex(6, 8), p.hex(8, 10), p.hex(10, 16)).joinToString("-") { it.replace(" ", "") }
                val brand = IBeaconUuids[uuid]
                ctx.add(BleKind.Tag, "iBeacon" + (brand?.let { " ($it)" } ?: ""), brand?.substringBefore(' ') ?: null, 92, "Trame iBeacon (Apple 0x02)", Sym.Sell)
                ctx.detail("iBeacon UUID", uuid)
                ctx.detail("Major / minor", "${p.u16be(16)} / ${p.u16be(18)}")
                ctx.detail("Puissance à 1 m", "${p[20]} dBm")
            }
            0x07 -> if (p.size >= 3) {
                val model = p.u16be(1)
                val label = AirPods[model]
                ctx.add(BleKind.Audio, label ?: "Écouteurs Apple / Beats", "Apple", 96, "Trame Proximity Pairing (0x07), modèle 0x%04X".format(model), Sym.Headphones)
                if (label == null) ctx.detail("Modèle Apple", "0x%04X (non répertorié)".format(model))
            }
            0x12 -> ctx.add(BleKind.Tag, "AirTag / accessoire Localiser", "Apple", 86, "Trame « Localiser » (Find My, 0x12)", Sym.Sell)
            0x03 -> ctx.add(BleKind.Accessory, "Imprimante AirPrint", null, 88, "Trame AirPrint (0x03)", Sym.Print)
            0x06 -> ctx.add(BleKind.Home, "Accessoire HomeKit", "Apple", 86, "Trame HomeKit (0x06)", Sym.Lightbulb)
            0x0B -> ctx.add(BleKind.Watch, "Apple Watch", "Apple", 84, "Trame Magic Switch (0x0B), émise par l'Apple Watch", Sym.Watch)
            0x09 -> ctx.add(BleKind.Media, "Apple TV, HomePod ou Mac (AirPlay)", "Apple", 68, "Cible AirPlay (0x09)", Sym.Tv)
            else -> Unit
        }
        // Any other Continuity frame: an iPhone, iPad, Mac or Watch nearby.
        ctx.add(BleKind.Phone, "Appareil Apple (iPhone, iPad, Mac ou Watch)", "Apple", 55, "Trames Continuity Apple", Sym.Smartphone)
    }

    // ---- Microsoft CDP ---------------------------------------------------------------------------

    private fun microsoft(ctx: Ctx, d: ByteArray) {
        if (d.size >= 2 && d.u8(0) == 0x01) {
            val t = d.u8(1) and 0x3F
            val r = when (t) {
                1 -> Triple(BleKind.Media, "Xbox One", Sym.Tv)
                6 -> Triple(BleKind.Phone, "iPhone (Lien avec Windows)", Sym.Smartphone)
                7 -> Triple(BleKind.Phone, "iPad (Lien avec Windows)", Sym.Tablet)
                8 -> Triple(BleKind.Phone, "Téléphone Android (Lien avec Windows)", Sym.Smartphone)
                9 -> Triple(BleKind.Computer, "PC Windows de bureau", Sym.Laptop)
                11 -> Triple(BleKind.Phone, "Windows Phone", Sym.Smartphone)
                12 -> Triple(BleKind.Computer, "Appareil Linux", Sym.Laptop)
                13 -> Triple(BleKind.Home, "Windows IoT", Sym.Memory)
                14 -> Triple(BleKind.Media, "Surface Hub", Sym.Tv)
                15 -> Triple(BleKind.Computer, "PC portable Windows", Sym.Laptop)
                16 -> Triple(BleKind.Computer, "Tablette Windows", Sym.Tablet)
                else -> null
            }
            ctx.detail("Type CDP Microsoft", t.toString())
            if (r != null) {
                val ms = t in setOf(1, 9, 11, 13, 14, 15, 16)
                ctx.add(r.first, r.second, if (ms) "Microsoft" else null, 88, "Beacon Microsoft CDP, type $t", r.third)
                return
            }
        }
        ctx.add(BleKind.Computer, "Appareil Microsoft", "Microsoft", 52, "Données fabricant Microsoft", Sym.Laptop)
    }

    // ---- Ruuvi -------------------------------------------------------------------------------------

    private fun ruuvi(ctx: Ctx, d: ByteArray) {
        var label = "Capteur RuuviTag"
        if (d.size >= 12 && d.u8(0) == 5) {
            val temp = ((d[1].toInt() shl 8) or d.u8(2)) * 0.005
            val hum = d.u16be(3) * 0.0025
            val press = (d.u16be(5) + 50000) / 100.0
            ctx.detail("Température", "%.1f °C".format(java.util.Locale.FRANCE, temp))
            ctx.detail("Humidité", "%.0f %%".format(java.util.Locale.FRANCE, hum))
            ctx.detail("Pression", "%.0f hPa".format(java.util.Locale.FRANCE, press))
            label = "RuuviTag · %.1f °C".format(java.util.Locale.FRANCE, temp)
        }
        ctx.add(BleKind.Home, label, "Ruuvi", 97, "Données fabricant Ruuvi", Sym.Thermostat)
    }

    // ---- Service data layouts -----------------------------------------------------------------

    private fun eddystone(ctx: Ctx, d: ByteArray) {
        if (d.isEmpty()) return
        when (d.u8(0)) {
            0x00 -> {
                ctx.add(BleKind.Tag, "Beacon Eddystone-UID", null, 92, "Trame Eddystone-UID", Sym.Sell)
                if (d.size >= 18) {
                    ctx.detail("Eddystone namespace", d.hex(2, 12).replace(" ", ""))
                    ctx.detail("Eddystone instance", d.hex(12, 18).replace(" ", ""))
                }
            }
            0x10 -> {
                ctx.add(BleKind.Tag, "Beacon Eddystone-URL", null, 92, "Trame Eddystone-URL", Sym.Sell)
                eddystoneUrl(d)?.let { ctx.detail("URL", it) }
            }
            0x20 -> {
                ctx.add(BleKind.Tag, "Beacon Eddystone-TLM", null, 88, "Trame Eddystone-TLM (télémétrie)", Sym.Sell)
                if (d.size >= 6) {
                    ctx.detail("Batterie", "${d.u16be(2)} mV")
                    if (d.u16be(4) != 0x8000) ctx.detail("Température", "%.1f °C".format(java.util.Locale.FRANCE, ((d[4].toInt() shl 8) or d.u8(5)) / 256.0))
                }
            }
            0x30 -> ctx.add(BleKind.Tag, "Beacon Eddystone-EID", null, 90, "Trame Eddystone-EID", Sym.Sell)
            0x40, 0x41 -> ctx.add(BleKind.Tag, "Tag du réseau Localiser (Google)", "Google", 90, "Trame Find Hub (Eddystone 0x%02X)".format(d.u8(0)), Sym.Sell)
        }
    }

    private val UrlSchemes = arrayOf("http://www.", "https://www.", "http://", "https://")
    private val UrlEnc = arrayOf(".com/", ".org/", ".edu/", ".net/", ".info/", ".biz/", ".gov/", ".com", ".org", ".edu", ".net", ".info", ".biz", ".gov")

    private fun eddystoneUrl(d: ByteArray): String? {
        if (d.size < 4 || d.u8(2) > 3) return null
        val sb = StringBuilder(UrlSchemes[d.u8(2)])
        for (i in 3 until d.size) {
            val c = d.u8(i)
            if (c < UrlEnc.size) sb.append(UrlEnc[c]) else if (c in 33..126) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun fastPair(ctx: Ctx, d: ByteArray) {
        if (d.size == 3) {
            ctx.detail("Fast Pair", "modèle %06X (détectable)".format((d.u8(0) shl 16) or (d.u8(1) shl 8) or d.u8(2)))
            ctx.add(BleKind.Audio, "Accessoire Google Fast Pair", "Google", 66, "Service Fast Pair (mode appairage)", Sym.Headphones)
        } else {
            ctx.detail("Fast Pair", "mode non détectable (${d.size} octets)")
            ctx.add(BleKind.Audio, "Accessoire Google Fast Pair", "Google", 60, "Service Fast Pair", Sym.Headphones)
        }
    }

    private val XiaomiProducts = mapOf(
        0x01AA to "Thermomètre Xiaomi (LYWSDCGQ)", 0x045B to "Thermomètre Xiaomi (LYWSD02)", 0x055B to "Thermomètre Xiaomi (LYWSD03MMC)",
        0x0347 to "Thermomètre Qingping (CGG1)", 0x06D3 to "Thermomètre Xiaomi (MHO-C303)", 0x0098 to "Capteur de plantes Xiaomi (Flower Care)",
    )

    private fun xiaomi(ctx: Ctx, d: ByteArray) {
        if (d.size >= 4) {
            val product = d.u16le(2)
            val label = XiaomiProducts[product]
            ctx.detail("Produit Xiaomi (MiBeacon)", "0x%04X".format(product))
            if (label != null) {
                ctx.add(BleKind.Home, label, "Xiaomi", 92, "Trame MiBeacon, produit 0x%04X".format(product), Sym.Thermostat)
                return
            }
        }
        ctx.add(BleKind.Home, "Appareil Xiaomi Mi Home", "Xiaomi", 58, "Service Xiaomi (0xFE95)", Sym.Sensors)
    }

    // ---- Appearance ------------------------------------------------------------------------------------

    private class App(val kind: BleKind, val label: String, val icon: String?)

    private fun appearance(ctx: Ctx, v: Int, source: String) {
        val cat = v shr 6
        val sub = v and 0x3F
        fun a(kind: BleKind, label: String, icon: String? = null) = App(kind, label, icon)
        val r: App? = when (cat) {
            1 -> a(BleKind.Phone, "Téléphone", Sym.Smartphone)
            2 -> when (sub) {
                7 -> a(BleKind.Phone, "Tablette", Sym.Tablet)
                6 -> a(BleKind.Watch, "Ordinateur portable au poignet", Sym.Watch)
                else -> a(BleKind.Computer, "Ordinateur", Sym.Laptop)
            }
            3 -> a(BleKind.Watch, if (sub == 2) "Montre connectée" else if (sub == 1) "Montre de sport" else "Montre", Sym.Watch)
            4 -> a(BleKind.Home, "Horloge", Sym.Schedule)
            5 -> a(BleKind.Media, "Écran", Sym.Tv)
            6 -> a(BleKind.Accessory, "Télécommande", Sym.Devices)
            7 -> a(BleKind.Accessory, "Lunettes connectées", Sym.Devices)
            8 -> a(BleKind.Tag, "Tag", Sym.Sell)
            9 -> a(BleKind.Tag, "Porte-clés connecté", Sym.Sell)
            10 -> a(BleKind.Media, "Lecteur multimédia", Sym.MusicNote)
            11 -> a(BleKind.Accessory, "Lecteur de codes-barres", Sym.Devices)
            12 -> a(BleKind.Health, "Thermomètre", Sym.Thermostat)
            13 -> a(BleKind.Health, if (sub == 1) "Ceinture cardiaque" else "Capteur de fréquence cardiaque", Sym.Favorite)
            14 -> a(BleKind.Health, "Tensiomètre", Sym.Favorite)
            15 -> when (sub) {
                1 -> a(BleKind.Accessory, "Clavier", Sym.Keyboard)
                2 -> a(BleKind.Accessory, "Souris", Sym.Mouse)
                3 -> a(BleKind.Accessory, "Joystick", Sym.Gamepad)
                4 -> a(BleKind.Accessory, "Manette de jeu", Sym.Gamepad)
                5 -> a(BleKind.Accessory, "Tablette graphique", Sym.Edit)
                7 -> a(BleKind.Accessory, "Stylet numérique", Sym.Edit)
                8 -> a(BleKind.Accessory, "Lecteur de codes-barres", Sym.Devices)
                else -> a(BleKind.Accessory, "Périphérique HID", Sym.Keyboard)
            }
            16 -> a(BleKind.Health, "Glucomètre", Sym.Favorite)
            17 -> a(BleKind.Health, "Capteur de course à pied", Sym.Favorite)
            18 -> a(BleKind.Health, "Capteur de vélo", Sym.PedalBike)
            19 -> a(BleKind.Home, "Appareil de contrôle", Sym.Hub)
            20 -> a(BleKind.Home, "Point d'accès / passerelle", Sym.Router)
            21 -> a(BleKind.Home, "Capteur", Sym.Sensors)
            22 -> a(BleKind.Home, "Luminaire", Sym.Lightbulb)
            23 -> a(BleKind.Home, "Ventilateur", Sym.Hub)
            24 -> a(BleKind.Home, if (sub == 1) "Thermostat" else "Chauffage / ventilation", Sym.Thermostat)
            25 -> a(BleKind.Home, "Climatisation", Sym.Thermostat)
            26 -> a(BleKind.Home, "Humidificateur", Sym.Hub)
            27 -> a(BleKind.Home, "Chauffage", Sym.Thermostat)
            28 -> a(BleKind.Home, "Contrôle d'accès", Sym.Lock)
            29 -> a(BleKind.Home, "Appareil motorisé (garage, portail)", Sym.Hub)
            30 -> a(BleKind.Home, "Alimentation / prise", Sym.Bolt)
            31 -> a(BleKind.Home, "Source lumineuse", Sym.Lightbulb)
            32 -> a(BleKind.Home, "Store / volet", Sym.Hub)
            33 -> a(BleKind.Audio, "Enceinte / récepteur audio", Sym.Speaker)
            34 -> a(BleKind.Audio, "Source audio", Sym.MusicNote)
            35 -> a(BleKind.Vehicle, when (sub) { 1 -> "Voiture"; 2 -> "Moto"; 3 -> "Vélo électrique"; else -> "Véhicule motorisé" }, Sym.DirectionsCar)
            36 -> a(BleKind.Home, "Électroménager", Sym.Hub)
            37 -> a(BleKind.Audio, when (sub) { 1 -> "Écouteurs"; 2 -> "Casque-micro"; 3 -> "Casque audio"; 4 -> "Tour-de-cou audio"; else -> "Audio portable" }, Sym.Headphones)
            38 -> a(BleKind.Vehicle, "Aéronef / drone", Sym.Flight)
            39 -> a(BleKind.Media, "Équipement audio-vidéo", Sym.Tv)
            40 -> a(BleKind.Media, when (sub) { 1 -> "Téléviseur"; 2 -> "Moniteur"; 3 -> "Projecteur"; else -> "Équipement d'affichage" }, Sym.Tv)
            41 -> a(BleKind.Audio, "Aide auditive", Sym.Headphones)
            42 -> a(BleKind.Media, when (sub) { 1 -> "Console de salon"; 2 -> "Console portable"; else -> "Console de jeu" }, Sym.Gamepad)
            43 -> a(BleKind.Media, "Affichage dynamique", Sym.Tv)
            49 -> a(BleKind.Health, "Oxymètre de pouls", Sym.Favorite)
            50 -> a(BleKind.Health, "Pèse-personne", Sym.Scale)
            51 -> a(BleKind.Vehicle, "Mobilité personnelle", Sym.ElectricScooter)
            52 -> a(BleKind.Health, "Capteur de glycémie continu", Sym.Favorite)
            53 -> a(BleKind.Health, "Pompe à insuline", Sym.Favorite)
            54 -> a(BleKind.Health, "Dispositif d'administration de médicaments", Sym.Favorite)
            55 -> a(BleKind.Health, "Spiromètre", Sym.Favorite)
            81 -> a(BleKind.Watch, "Appareil de sport GPS", Sym.Watch)
            else -> null
        }
        if (r != null) ctx.add(r.kind, r.label, null, 84, "Apparence déclarée (%s) : 0x%04X".format(source, v), r.icon)
    }

    private fun classOfDevice(ctx: Ctx, v: Int) {
        val major = (v shr 8) and 0x1F
        val minor = (v shr 2) and 0x3F
        val r: Triple<BleKind, String, String>? = when (major) {
            1 -> when (minor) {
                7 -> Triple(BleKind.Phone, "Tablette", Sym.Tablet)
                else -> Triple(BleKind.Computer, "Ordinateur", Sym.Laptop)
            }
            2 -> Triple(BleKind.Phone, "Téléphone", Sym.Smartphone)
            3 -> Triple(BleKind.Home, "Point d'accès réseau", Sym.Router)
            4 -> when (minor) {
                1, 2, 6 -> Triple(BleKind.Audio, "Casque / oreillette", Sym.Headphones)
                5, 7, 10 -> Triple(BleKind.Audio, "Enceinte", Sym.Speaker)
                8 -> Triple(BleKind.Audio, "Autoradio", Sym.Speaker)
                9, 14, 15 -> Triple(BleKind.Media, "Écran / décodeur", Sym.Tv)
                else -> Triple(BleKind.Media, "Audio-vidéo", Sym.Tv)
            }
            5 -> when {
                (v and 0x40) != 0 && (v and 0x80) == 0 -> Triple(BleKind.Accessory, "Clavier", Sym.Keyboard)
                (v and 0x80) != 0 && (v and 0x40) == 0 -> Triple(BleKind.Accessory, "Souris / pointeur", Sym.Mouse)
                (v and 0x0F) in 1..2 -> Triple(BleKind.Accessory, "Manette de jeu", Sym.Gamepad)
                else -> Triple(BleKind.Accessory, "Périphérique", Sym.Keyboard)
            }
            6 -> when {
                (v and 0x80) != 0 -> Triple(BleKind.Accessory, "Imprimante", Sym.Print)
                (v and 0x40) != 0 -> Triple(BleKind.Accessory, "Scanner", Sym.Print)
                (v and 0x20) != 0 -> Triple(BleKind.Accessory, "Appareil photo", Sym.Videocam)
                else -> Triple(BleKind.Media, "Écran d'imagerie", Sym.Tv)
            }
            7 -> when (minor) {
                1 -> Triple(BleKind.Watch, "Montre", Sym.Watch)
                5 -> Triple(BleKind.Accessory, "Lunettes connectées", Sym.Devices)
                else -> Triple(BleKind.Watch, "Objet portable", Sym.Watch)
            }
            8 -> Triple(BleKind.Accessory, "Jouet connecté", Sym.SmartToy)
            9 -> Triple(BleKind.Health, "Appareil de santé", Sym.Favorite)
            else -> null
        }
        if (r != null) ctx.add(r.first, r.second, null, 80, "Classe d'appareil Bluetooth : 0x%06X".format(v), r.third)
    }

    // ---- Services ------------------------------------------------------------------------------------------

    private class Hint(val kind: BleKind, val label: String?, val score: Int, val maker: String? = null, val icon: String? = null)

    private val MakerHints: Map<Int, Hint> = mapOf(
        0x009E to Hint(BleKind.Audio, "Produit Bose", 75, icon = Sym.Headphones),
        0x0057 to Hint(BleKind.Audio, "Produit Harman (JBL, AKG, Harman Kardon)", 66, icon = Sym.Speaker),
        0x0067 to Hint(BleKind.Audio, "Produit Jabra", 78, icon = Sym.Headphones),
        0x0055 to Hint(BleKind.Audio, "Produit Poly / Plantronics", 78, icon = Sym.Headphones),
        0x05A7 to Hint(BleKind.Audio, "Enceinte Sonos", 86, icon = Sym.Speaker),
        0x0087 to Hint(BleKind.Watch, "Appareil Garmin (montre, capteur)", 68, icon = Sym.Watch),
        0x006B to Hint(BleKind.Health, "Capteur Polar", 72, icon = Sym.Favorite),
        0x009F to Hint(BleKind.Watch, "Montre Suunto", 76, icon = Sym.Watch),
        0x0157 to Hint(BleKind.Watch, "Bracelet / montre Amazfit ou Mi Band", 80, icon = Sym.Watch),
        0x0078 to Hint(BleKind.Watch, "Appareil Nike+", 55, icon = Sym.Watch),
        0x01DA to Hint(BleKind.Accessory, "Périphérique Logitech", 74, icon = Sym.Mouse),
        0x0075 to Hint(BleKind.Phone, "Appareil Samsung (Galaxy)", 42, icon = Sym.Smartphone),
        0x02E5 to Hint(BleKind.Home, "Module ESP32", 58, icon = Sym.Memory),
        0x0822 to Hint(BleKind.Home, "Carte Adafruit", 70, icon = Sym.Memory),
        0x0171 to Hint(BleKind.Home, "Appareil Amazon (Echo, Fire TV…)", 52, icon = Sym.Speaker),
        0x00C4 to Hint(BleKind.Media, "Appareil LG (TV, écouteurs)", 44, icon = Sym.Tv),
        0x012D to Hint(BleKind.Audio, "Appareil Sony", 44, icon = Sym.Headphones),
        0x027D to Hint(BleKind.Phone, "Appareil Huawei", 44, icon = Sym.Smartphone),
        0x0002 to Hint(BleKind.Computer, "PC (Bluetooth Intel)", 46, icon = Sym.Laptop),
        0x058E to Hint(BleKind.Accessory, "Appareil Meta (Quest, lunettes)", 56),
        0x01AB to Hint(BleKind.Accessory, "Appareil Meta (Quest, lunettes)", 56),
    )

    private val Svc16: Map<Int, Hint> = mapOf(
        0x180D to Hint(BleKind.Health, "Capteur de fréquence cardiaque", 88, icon = Sym.Favorite),
        0x1814 to Hint(BleKind.Health, "Capteur de course à pied", 82, icon = Sym.Favorite),
        0x1816 to Hint(BleKind.Health, "Capteur de vélo (vitesse / cadence)", 85, icon = Sym.PedalBike),
        0x1818 to Hint(BleKind.Health, "Capteur de puissance vélo", 88, icon = Sym.PedalBike),
        0x1826 to Hint(BleKind.Health, "Machine de fitness (FTMS)", 86, icon = Sym.PedalBike),
        0x181D to Hint(BleKind.Health, "Pèse-personne", 88, icon = Sym.Scale),
        0x181B to Hint(BleKind.Health, "Balance impédancemètre", 86, icon = Sym.Scale),
        0x1810 to Hint(BleKind.Health, "Tensiomètre", 90, icon = Sym.Favorite),
        0x1808 to Hint(BleKind.Health, "Glucomètre", 90, icon = Sym.Favorite),
        0x181F to Hint(BleKind.Health, "Capteur de glycémie continu", 90, icon = Sym.Favorite),
        0x1809 to Hint(BleKind.Health, "Thermomètre médical", 88, icon = Sym.Thermostat),
        0x1822 to Hint(BleKind.Health, "Oxymètre de pouls", 90, icon = Sym.Favorite),
        0x1819 to Hint(BleKind.Health, "Compteur GPS (localisation et navigation)", 50, icon = Sym.PedalBike),
        0x181A to Hint(BleKind.Home, "Capteur d'environnement (température, humidité)", 86, icon = Sym.Thermostat),
        0x1812 to Hint(BleKind.Accessory, "Périphérique HID (clavier, souris, manette)", 78, icon = Sym.Keyboard),
        0x1803 to Hint(BleKind.Tag, "Traceur (alerte de perte de lien)", 56, icon = Sym.Sell),
        0x1802 to Hint(BleKind.Tag, "Traceur (alerte immédiate)", 46, icon = Sym.Sell),
        0x1811 to Hint(BleKind.Watch, "Montre / bracelet (notifications)", 52, icon = Sym.Watch),
        0x1827 to Hint(BleKind.Home, "Nœud Bluetooth Mesh", 90, icon = Sym.Hub),
        0x1828 to Hint(BleKind.Home, "Nœud Bluetooth Mesh (proxy)", 90, icon = Sym.Hub),
        0x1852 to Hint(BleKind.Audio, "Diffusion Auracast", 93, icon = Sym.Speaker),
        0x1851 to Hint(BleKind.Audio, "Diffusion audio LE", 86, icon = Sym.Speaker),
        0x1850 to Hint(BleKind.Audio, "Appareil LE Audio", 70, icon = Sym.Headphones),
        0x184E to Hint(BleKind.Audio, "Appareil LE Audio", 72, icon = Sym.Headphones),
        0x184F to Hint(BleKind.Audio, "Récepteur Auracast", 72, icon = Sym.Headphones),
        0x1853 to Hint(BleKind.Audio, "Appareil LE Audio", 65, icon = Sym.Headphones),
        0x1854 to Hint(BleKind.Audio, "Aide auditive", 88, icon = Sym.Headphones),
        0x1844 to Hint(BleKind.Audio, "Appareil LE Audio (volume)", 60, icon = Sym.Headphones),
        0x1846 to Hint(BleKind.Audio, "Écouteurs (jeu coordonné)", 62, icon = Sym.Headphones),
        0x1843 to Hint(BleKind.Audio, "Appareil LE Audio", 60, icon = Sym.Headphones),
        0x1820 to Hint(BleKind.Home, "Nœud IPv6 sur BLE (IPSP)", 70, icon = Sym.Hub),
        0x1821 to Hint(BleKind.Tag, "Tag de positionnement intérieur", 56, icon = Sym.Sell),
        0x1815 to Hint(BleKind.Home, "Automatisation (entrées/sorties)", 66, icon = Sym.Hub),
        0x1823 to Hint(BleKind.Home, "Passerelle HTTP", 60, icon = Sym.Hub),
        0xFEED to Hint(BleKind.Tag, "Tile", 96, "Tile", Sym.Sell),
        0xFEEC to Hint(BleKind.Tag, "Tile", 96, "Tile", Sym.Sell),
        0xFD84 to Hint(BleKind.Tag, "Tile", 96, "Tile", Sym.Sell),
        0xFD5A to Hint(BleKind.Tag, "Galaxy SmartTag", 92, "Samsung", Sym.Sell),
        0xFD69 to Hint(BleKind.Tag, "Tag Samsung SmartThings Find", 76, "Samsung", Sym.Sell),
        0xFE33 to Hint(BleKind.Tag, "Chipolo", 90, "Chipolo", Sym.Sell),
        0xFE9A to Hint(BleKind.Tag, "Beacon Estimote", 92, "Estimote", Sym.Sell),
        0xFEAA to Hint(BleKind.Tag, "Beacon Eddystone", 80, icon = Sym.Sell),
        0xFD6F to Hint(BleKind.Phone, "Téléphone (notification d'exposition)", 80, icon = Sym.Smartphone),
        0xFE2C to Hint(BleKind.Audio, "Accessoire Google Fast Pair", 60, "Google", Sym.Headphones),
        0xFE07 to Hint(BleKind.Audio, "Enceinte Sonos", 92, "Sonos", Sym.Speaker),
        0xFEBE to Hint(BleKind.Audio, "Produit Bose", 86, "Bose", Sym.Headphones),
        0xFEE0 to Hint(BleKind.Watch, "Bracelet / montre Amazfit ou Mi Band", 86, "Huami", Sym.Watch),
        0xFEE1 to Hint(BleKind.Watch, "Bracelet / montre Amazfit ou Mi Band", 80, "Huami", Sym.Watch),
        0xFE95 to Hint(BleKind.Home, "Appareil Xiaomi Mi Home", 58, "Xiaomi", Sym.Sensors),
        0xFE0F to Hint(BleKind.Home, "Éclairage Philips Hue", 86, "Philips", Sym.Lightbulb),
        0xFDCD to Hint(BleKind.Home, "Capteur Qingping", 86, "Qingping", Sym.Thermostat),
        0xFE59 to Hint(BleKind.Home, "Module Nordic (mise à jour DFU)", 56, "Nordic Semiconductor", Sym.Memory),
        0xFEB7 to Hint(BleKind.Accessory, "Appareil Meta (Quest, lunettes)", 60, "Meta", Sym.Devices),
        0xFEB8 to Hint(BleKind.Accessory, "Appareil Meta (Quest, lunettes)", 60, "Meta", Sym.Devices),
        0xFEE7 to Hint(BleKind.Home, "Objet connecté WeChat (Tencent)", 50, "Tencent", Sym.Hub),
        0xFFE0 to Hint(BleKind.Home, "Module BLE série générique (HM-10…)", 44, icon = Sym.Memory),
        0xFFF0 to Hint(BleKind.Home, "Module BLE série générique", 40, icon = Sym.Memory),
    )

    private class Hint128(val prefix: String?, val suffix: String?, val hint: Hint) {
        val kind get() = hint.kind
        val label get() = hint.label
        val score get() = hint.score
        val maker get() = hint.maker
        val icon get() = hint.icon
        fun matches(u: String) = (prefix == null || u.startsWith(prefix)) && (suffix == null || u.endsWith(suffix))
    }

    private val Svc128: List<Hint128> = listOf(
        Hint128("6E400001-B5A3-F393-E0A9-E50E24DCCA9E", null, Hint(BleKind.Home, "Module série (Nordic UART)", 58, "Nordic Semiconductor", Sym.Memory)),
        Hint128("00000211-B2D1-43F0-9B88-960CEBF8B91E", null, Hint(BleKind.Vehicle, "Tesla", 94, "Tesla", Sym.DirectionsCar)),
        Hint128("6BA1B218-15A8-461F-9FA8-5DCAE273EAFD", null, Hint(BleKind.Home, "Nœud Meshtastic (LoRa)", 92, "Meshtastic", Sym.Router)),
        Hint128("7905F431-B5CE-4E99-A40F-4B1E122D00D0", null, Hint(BleKind.Watch, "Bracelet / montre (notifications iOS)", 66, icon = Sym.Watch)),
        Hint128("ADABFB00-6E7D-4601-BDA2-BFFAA68956BA", null, Hint(BleKind.Watch, "Fitbit", 94, "Fitbit", Sym.Watch)),
        Hint128(null, "-667B-11E3-949A-0800200C9A66", Hint(BleKind.Watch, "Appareil Garmin", 90, "Garmin", Sym.Watch)),
        Hint128("932C32BD-0000-47A2-835A-A8D455B859DD", null, Hint(BleKind.Home, "Éclairage Philips Hue", 92, "Philips", Sym.Lightbulb)),
        Hint128("00001623-1212-EFDE-1623-785FEABCD123", null, Hint(BleKind.Accessory, "Hub LEGO (Powered Up)", 94, "LEGO", Sym.SmartToy)),
        Hint128("98ED0001-A541-11E4-B6A0-0002A5D5C51B", null, Hint(BleKind.Health, "Bague Oura", 92, "Oura", Sym.Favorite)),
        Hint128("61080001-8D6D-82B8-614A-1C8CB0F8DCC6", null, Hint(BleKind.Health, "Bracelet Whoop", 90, "Whoop", Sym.Favorite)),
        Hint128("00000001-19CA-4651-86E5-FA29DCDD09D1", null, Hint(BleKind.Accessory, "Contrôleur Zwift", 88, "Zwift", Sym.Gamepad)),
    )

    private val TeslaName = Regex("""^S[0-9a-f]{16}C$""")

    // ---- Names ------------------------------------------------------------------------------------------------

    private class NameRule(val re: Regex, val kind: BleKind, val label: String, val maker: String?, val score: Int, val icon: String?)

    private fun n(p: String, kind: BleKind, label: String, maker: String? = null, score: Int = 80, icon: String? = null) =
        NameRule(Regex(p, RegexOption.IGNORE_CASE), kind, label, maker, score, icon)

    private val NameRules: List<NameRule> by lazy {
        val A = BleKind.Audio; val W = BleKind.Watch; val T = BleKind.Tag; val P = BleKind.Phone; val C = BleKind.Computer
        val M = BleKind.Media; val X = BleKind.Accessory; val H = BleKind.Health; val O = BleKind.Home; val V = BleKind.Vehicle
        listOf(
            // Audio
            n("""airpods?\s*max""", A, "AirPods Max", "Apple", 93, Sym.Headphones),
            n("""airpods?\s*pro""", A, "AirPods Pro", "Apple", 93, Sym.Headphones),
            n("""airpods?""", A, "AirPods", "Apple", 92, Sym.Headphones),
            n("""homepod""", A, "HomePod", "Apple", 92, Sym.Speaker),
            n("""beats\s*(x|solo|studio|flex|fit|pill)|powerbeats|\bbeats\b""", A, "Casque / écouteurs Beats", "Apple", 88, Sym.Headphones),
            n("""pixel\s*buds""", A, "Pixel Buds", "Google", 93, Sym.Headphones),
            n("""galaxy\s*buds|\bsm-r\d{3}""", A, "Galaxy Buds", "Samsung", 92, Sym.Headphones),
            n("""\b(le )?w[hf]-\w+|linkbuds|\bsony\s*(mdr|ult|srs)|\bsrs-\w+""", A, "Audio Sony", "Sony", 88, Sym.Headphones),
            n("""jbl\s*(flip|charge|go|clip|xtreme|boombox|pulse|partybox|link|authentics|horizon|encore)""", A, "Enceinte JBL", "Harman", 92, Sym.Speaker),
            n("""\bjbl\b|\bharman\b|\bakg\b""", A, "Audio JBL / Harman", "Harman", 86, Sym.Headphones),
            n("""soundlink|bose\s*(revolve|micro|portable|home)""", A, "Enceinte Bose", "Bose", 90, Sym.Speaker),
            n("""\bbose\b|\bqc\s?\d{2}|quietcomfort|\bnc\s?700""", A, "Casque / écouteurs Bose", "Bose", 88, Sym.Headphones),
            n("""sennheiser|momentum|\bhd\s?\d{3}\b|cx\s?(plus|true)""", A, "Audio Sennheiser", "Sennheiser", 86, Sym.Headphones),
            n("""jabra|elite\s*\d""", A, "Audio Jabra", "Jabra", 88, Sym.Headphones),
            n("""soundcore|anker|liberty\s*\d|life\s*(q|p|a)\d""", A, "Audio Anker Soundcore", "Anker", 86, Sym.Headphones),
            n("""sonos|\broam\b\s*\d?""", A, "Enceinte Sonos", "Sonos", 88, Sym.Speaker),
            n("""marshall|emberton|stanmore|acton|woburn|willen""", A, "Enceinte Marshall", "Marshall", 88, Sym.Speaker),
            n("""\bue\s*(boom|megaboom|wonderboom|hyperboom)|ultimate\s*ears|\b(mega|wonder)boom""", A, "Enceinte Ultimate Ears", "Ultimate Ears", 90, Sym.Speaker),
            n("""bang.{0,3}olufsen|beoplay|beosound|\bb&o\b""", A, "Audio Bang & Olufsen", "Bang & Olufsen", 90, Sym.Speaker),
            n("""nest\s*(mini|audio)|google\s*home""", A, "Enceinte Google Nest", "Google", 90, Sym.Speaker),
            n("""echo\s*buds""", A, "Amazon Echo Buds", "Amazon", 90, Sym.Headphones),
            n("""echo\s*(dot|show|studio|pop|spot|flex|input)?\b|\balexa\b""", A, "Enceinte Amazon Echo", "Amazon", 84, Sym.Speaker),
            n("""redmi\s*buds|xiaomi\s*buds|mi\s*(true\s*wireless|air)|airdots|mi\s*neckband""", A, "Écouteurs Xiaomi", "Xiaomi", 88, Sym.Headphones),
            n("""oneplus\s*buds|nord\s*buds""", A, "OnePlus Buds", "OnePlus", 90, Sym.Headphones),
            n("""oppo\s*enco|\benco\s*(x|w|air|buds|free)""", A, "Écouteurs OPPO", "OPPO", 88, Sym.Headphones),
            n("""freebuds|freelace|huawei\s*(fb|earphone)""", A, "Écouteurs Huawei", "Huawei", 90, Sym.Headphones),
            n("""realme\s*buds""", A, "Écouteurs realme", "realme", 88, Sym.Headphones),
            n("""nothing\s*ear|\bear\s*\(\d\)|cmf\s*buds""", A, "Écouteurs Nothing", "Nothing", 90, Sym.Headphones),
            n("""skullcandy|crusher|indy\s*evo|sesh""", A, "Audio Skullcandy", "Skullcandy", 86, Sym.Headphones),
            n("""soundbar|sound\s*bar|barre\s*de\s*son""", A, "Barre de son", null, 86, Sym.Speaker),
            n("""tozo|soundpeats|edifier|haylou|\bqcy\b|lenovo\s*(lp|thinkplus)|\btws\b|earbuds?|earphones?|écouteurs?|headphones?|casque|headset|\bbuds\b|\bpods\b|\bneckband\b""", A, "Écouteurs / casque", null, 72, Sym.Headphones),
            n("""speaker|enceinte|boombox|bt\s*speaker|mini\s*speaker|\bboom\b|haut-?parleur""", A, "Enceinte Bluetooth", null, 76, Sym.Speaker),
            n("""car\s*(kit|audio|stereo)|carkit|hands-?free|mains\s*libres|autoradio|pioneer|kenwood|alpine|\bjvc\b""", A, "Kit mains libres / autoradio", null, 70, Sym.Speaker),

            // Watches and bands
            n("""apple\s*watch""", W, "Apple Watch", "Apple", 93, Sym.Watch),
            n("""galaxy\s*(watch|fit|ring)|gear\s*(s\d|fit|sport)""", W, "Samsung Galaxy Watch", "Samsung", 93, Sym.Watch),
            n("""pixel\s*watch""", W, "Pixel Watch", "Google", 93, Sym.Watch),
            n("""fitbit|versa\s*\d|inspire\s*\d|\bionic\b""", W, "Fitbit", "Fitbit", 88, Sym.Watch),
            n("""amazfit|mi\s*(smart\s*)?band|mi\s*watch|xiaomi\s*(smart\s*)?(band|watch)|redmi\s*(watch|band)|\bgts\s?\d|\bgtr\s?\d|\bbip\b|zepp""", W, "Amazfit / Mi Band", "Xiaomi", 90, Sym.Watch),
            n("""garmin|fenix|forerunner|\bvenu\b|vivo(active|smart|fit|move)|instinct|\bepix\b|approach\s*[sg]\d|\blily\b|enduro|tactix|descent""", W, "Montre Garmin", "Garmin", 88, Sym.Watch),
            n("""\bedge\s?\d{3,4}\b""", H, "Compteur GPS Garmin Edge", "Garmin", 84, Sym.PedalBike),
            n("""huawei\s*(watch|band)|honor\s*(watch|band)|\bband\s?\d\b""", W, "Bracelet / montre Huawei", "Huawei", 84, Sym.Watch),
            n("""oura""", H, "Bague Oura", "Oura", 92, Sym.Favorite),
            n("""whoop""", H, "Bracelet Whoop", "Whoop", 92, Sym.Favorite),
            n("""suunto|coros|ticwatch|mobvoi|fossil|casio|g-?shock|\bgbd|\bgbx|\bwsd|pebble|wear\s*os|polar\s*(vantage|grit|ignite|pacer|unite)""", W, "Montre connectée", null, 82, Sym.Watch),
            n("""smart\s*watch|smartwatch|\bwatch\b|montre|\bband\b|bracelet|\bsmart\s*band""", W, "Montre / bracelet", null, 72, Sym.Watch),

            // Phones and tablets
            n("""iphone""", P, "iPhone", "Apple", 93, Sym.Smartphone),
            n("""ipad""", P, "iPad", "Apple", 93, Sym.Tablet),
            n("""galaxy\s*tab|mediapad|matepad|\btab\s?[a-z]?\d|tablet|tablette""", P, "Tablette", null, 82, Sym.Tablet),
            n("""galaxy\s*(s|a|z|note|m|f|xcover)\s?\d|\bsm-[gnsamfej]\d{3}""", P, "Samsung Galaxy", "Samsung", 90, Sym.Smartphone),
            n("""pixel\s*\d|\bpixel\b(?!\s*(buds|watch))""", P, "Google Pixel", "Google", 88, Sym.Smartphone),
            n("""oneplus(?!\s*buds)|\bop\d{4}\b|nord\s*(ce|n)?\s*\d""", P, "OnePlus", "OnePlus", 86, Sym.Smartphone),
            n("""redmi(?!\s*(buds|watch|band))|\bpoco\b|xiaomi\s*\d{1,2}|\bmi\s?\d{1,2}\b|mi\s*(note|max|mix|play)""", P, "Xiaomi / Redmi / POCO", "Xiaomi", 82, Sym.Smartphone),
            n("""huawei(?!\s*(watch|band|fb|earphone|freebuds))|honor\s*\d|\bnova\s?\d""", P, "Huawei / Honor", "Huawei", 82, Sym.Smartphone),
            n("""\boppo\b|reno\s?\d|find\s*x|realme(?!\s*buds)|vivo\s*[xyvs]\d|iqoo|motorola|moto\s*[gex]\s?\d|xperia|nokia\s*[a-z]?\d|zenfone|rog\s*phone|fairphone|nothing\s*phone""", P, "Téléphone Android", null, 80, Sym.Smartphone),
            n("""android|smartphone|\bphone\b|téléphone|telephone""", P, "Téléphone", null, 70, Sym.Smartphone),

            // Computers
            n("""macbook|imac|mac\s?mini|mac\s?studio|mac\s?pro""", C, "Mac", "Apple", 93, Sym.Laptop),
            n("""^(desktop|laptop)-[a-z0-9]{5,8}$""", C, "PC Windows", "Microsoft", 90, Sym.Laptop),
            n("""thinkpad|thinkcentre|ideapad|latitude|elitebook|probook|zenbook|vivobook|inspiron|\bsurface\b|chromebook|\bportable\b|\blaptop\b|notebook|ordinateur|\bpc\b|desktop""", C, "Ordinateur", null, 80, Sym.Laptop),
            n("""raspberry|raspberrypi|\brpi\b|jetson|arduino|esp-?32|esp8266|nrf5\d|nrf\d{4}|micro:?bit|\bpico\b|stm32|bluefruit|adafruit|dev\s*board|devkit""", O, "Carte de développement", null, 76, Sym.Memory),
            n("""flipper""", O, "Flipper Zero", "Flipper Devices", 93, Sym.Memory),
            n("""meshtastic|^mesh_[0-9a-f]{4}$""", O, "Nœud Meshtastic (LoRa)", "Meshtastic", 92, Sym.Router),

            // TV and media
            n("""^\[(tv|av)\]|samsung.*\b(tv|q\d{2}|frame|serif)|the\s*frame|qled|neo\s*qled|crystal\s*uhd""", M, "TV Samsung", "Samsung", 90, Sym.Tv),
            n("""\[lg\]|lg\s*webos|webos|lg.*\boled|oled\d{2}[a-z]\d""", M, "TV LG", "LG", 90, Sym.Tv),
            n("""bravia""", M, "TV Sony Bravia", "Sony", 92, Sym.Tv),
            n("""chromecast""", M, "Chromecast", "Google", 92, Sym.Cast),
            n("""fire\s*tv|firestick|\baft[a-z]{1,3}\b|fire\s*stick""", M, "Amazon Fire TV", "Amazon", 90, Sym.Tv),
            n("""android\s*tv|google\s*tv|mibox|mi\s*box|nvidia\s*shield|shield\s*(tv|remote)""", M, "Android TV / box", null, 82, Sym.Tv),
            n("""nest\s*hub|home\s*hub""", M, "Écran Google Nest Hub", "Google", 84, Sym.Tv),
            n("""apple\s*tv""", M, "Apple TV", "Apple", 93, Sym.Tv),
            n("""\broku\b""", M, "Roku", "Roku", 90, Sym.Tv),
            n("""xbox\s*wireless|xbox\s*elite|xbox\s*adaptive""", X, "Manette Xbox", "Microsoft", 94, Sym.Gamepad),
            n("""\bxbox\b""", M, "Console Xbox", "Microsoft", 88, Sym.Gamepad),
            n("""dualsense|dualshock|wireless\s*controller|playstation|\bps[345]\b""", X, "Manette PlayStation", "Sony", 88, Sym.Gamepad),
            n("""pro\s*controller|joy-?con|nintendo|\bswitch\b\s*(pro|lite|oled)?""", X, "Manette / console Nintendo", "Nintendo", 86, Sym.Gamepad),
            n("""hisense|\btcl\b|vizio|panasonic|sharp\s*aquos|philips\s*(tv|the\s*one)|toshiba.*tv|xiaomi\s*tv|mi\s*tv|vestel|thomson|telefunken|grundig|(^|[\s\-\[])tv([\s\-\]\d]|$)|téléviseur|television""", M, "Téléviseur", null, 74, Sym.Tv),
            n("""projector|projecteur|beamer|xgimi|nebula\s*(capsule|cosmos|mars)""", M, "Projecteur", null, 82, Sym.Tv),
            n("""kindle|kobo|remarkable|e-?reader|liseuse""", M, "Liseuse", null, 82, Sym.Tablet),
            n("""gopro|insta360|action\s*cam|\bosmo\b|\bcamera\b|caméra|webcam|dashcam""", X, "Caméra", null, 76, Sym.Videocam),

            // Accessories
            n("""keyboard|clavier|keychron|k380|k780|k480|mx\s*keys|mx\s*mechanical|magic\s*keyboard|\bkeys\b|\bcraft\b""", X, "Clavier", null, 88, Sym.Keyboard),
            n("""mouse|souris|mx\s*(master|anywhere|ergo|vertical)|magic\s*mouse|\bm(3[05]|5[57])\d\b|trackball""", X, "Souris", null, 88, Sym.Mouse),
            n("""trackpad|touchpad""", X, "Trackpad", null, 88, Sym.Mouse),
            n("""logitech|\blogi\b""", X, "Périphérique Logitech", "Logitech", 76, Sym.Mouse),
            n("""controller|gamepad|joystick|8bitdo|gamesir|steam\s*controller|steam\s*deck|backbone|stadia""", X, "Manette de jeu", null, 88, Sym.Gamepad),
            n("""razer|steelseries|corsair|hyperx|roccat|redragon""", X, "Périphérique gaming", null, 72, Sym.Mouse),
            n("""\bpen\b|s[\s-]?pen|pencil|stylus|stylet|wacom""", X, "Stylet", null, 80, Sym.Edit),
            n("""remote|télécommande|telecommande|magic\s*remote|presenter|clicker|spotlight""", X, "Télécommande", null, 76, Sym.Devices),
            n("""selfie|shutter""", X, "Télécommande photo", null, 78, Sym.Videocam),
            n("""printer|imprimante|laserjet|deskjet|officejet|hp\s*(envy|smart\s*tank|neverstop)|epson\s*(xp|et|wf|l\d)|\bxp-\d{3}|pixma|canon\s*(ts|mg|g\d|tr)\d|brother\s*(hl|dcp|mfc)|hl-l\d|dcp-|mfc-""", X, "Imprimante", null, 88, Sym.Print),
            n("""dymo|phomemo|niimbot|\bd11\b|\bd110\b|\bb21\b|peripage|munbyn|label\s*printer|étiqueteuse|zebra\s*(zd|zq|zt|ql)|pos-?\d|thermal|cat\s*printer""", X, "Imprimante d'étiquettes / thermique", null, 84, Sym.Print),
            n("""barcode|code-?barres?|scan\s*gun|honeywell|socket\s*mobile|netum|eyoyo|inateck|scanner""", X, "Lecteur de codes-barres", null, 76, Sym.Devices),
            n("""ray-?ban|meta\s*(ray|quest|glasses)|\bquest\b|oculus|\bvr\b|xreal|rokid|vuzix|even\s*g1|bose\s*frames|glasses|lunettes""", X, "Lunettes / casque VR", null, 80, Sym.Devices),
            n("""sphero|bb-8|ozobot|lego|powered\s*up|mindstorms|cozmo|meccano|ubtech|wowwee|\brobot\b|\btoy\b|jouet""", X, "Jouet / robot", null, 80, Sym.SmartToy),

            // Trackers and beacons
            n("""airtag|find\s*my|findmy""", T, "AirTag", "Apple", 95, Sym.Sell),
            n("""^tile\b|\btile\s*(mate|pro|slim|sticker|tracker)""", T, "Tile", "Tile", 94, Sym.Sell),
            n("""chipolo""", T, "Chipolo", "Chipolo", 94, Sym.Sell),
            n("""smart\s*tag|smarttag""", T, "Galaxy SmartTag", "Samsung", 94, Sym.Sell),
            n("""pebblebee""", T, "Pebblebee", "Pebblebee", 94, Sym.Sell),
            n("""eufy.*track|smarttrack|moto\s*tag|jio\s*tag|find\s*hub|findhub|cube\s*(tracker|pro|shadow)|trackr|nutale|\bnut\s*(mini|find)|orbit\s*(tag|tracker)?|\bitag\b|anti-?lost|antilost|key\s*finder|keyfinder|traceur|tracker""", T, "Traceur d'objets", null, 84, Sym.Sell),
            n("""beacon|ibeacon|eddystone|estimote|kontakt|minew|feasycom|holyiot|accent\s*systems|bluecats|sensoro|gimbal|radbeacon|jaalee|blukii|bluvision|wiliot|ble-?tag|asset\s*tag""", T, "Beacon BLE", null, 80, Sym.Sell),
            n("""\btag\b""", T, "Tag", null, 66, Sym.Sell),

            // Health and fitness
            n("""polar\s*(h\d|verity|oh1)|\bh10\b|\bh9\b|tickr|hrm-?(dual|pro|run|fit|tri)|hr\s*monitor|heart\s*rate|coospo|magene|myzone|ceinture\s*cardio""", H, "Ceinture cardiaque", null, 88, Sym.Favorite),
            n("""wahoo|kickr|elemnt|igpsport|stages|quarq|assioma|favero|4iiii|power\s*meter|cadence|\bspeed\s*sensor|zwift\s*(hub|cog|ride)|tacx|elite\s*(direto|suito)|wattbike|home\s*trainer""", H, "Capteur / home-trainer vélo", null, 84, Sym.PedalBike),
            n("""\bpm5\b|concept2|treadmill|tapis|rower|elliptical|ftms|peloton|technogym|kettler|domyos|nordictrack|schwinn|echelon|bowflex|proform|yesoul|merach|fitshow""", H, "Machine de fitness", null, 80, Sym.PedalBike),
            n("""omron|beurer\s*bm|blood\s*pressure|tensiom|\bbp\d{2,3}\b|\bbpm\b|qardio|ihealth\s*(bp|track)""", H, "Tensiomètre", null, 88, Sym.Favorite),
            n("""glucose|glucometer|glucomètre|accu-?chek|\bcontour\b|onetouch|one\s*touch|freestyle|\blibre\b|dexcom|guardian|medtronic""", H, "Capteur de glycémie", null, 88, Sym.Favorite),
            n("""thermometer|thermomètre|\bthermo\b\s*(baby|ear|front)?|forehead""", H, "Thermomètre", null, 76, Sym.Thermostat),
            n("""oximeter|oxymètre|spo2|pulse\s*ox|wellue|checkme|viatom|lepu|o2ring""", H, "Oxymètre de pouls", null, 90, Sym.Favorite),
            n("""mi\s*(body|smart\s*scale)|mibfs|mi\s*scale|xiaomi\s*(body\s*)?scale|renpho|etekcity|yolanda|qn-?scale|tanita|inbody|body\s*(comp|fat|scale)|withings\s*(body|scale)|pèse-?personne|\bscale\b|balance""", H, "Pèse-personne", null, 86, Sym.Scale),
            n("""oral-?b|sonicare|kolibree|toothbrush|brosse\s*à\s*dents""", H, "Brosse à dents connectée", null, 90, Sym.Favorite),
            n("""massager|massage|foreo""", H, "Appareil de massage / soin", null, 76, Sym.Favorite),

            // Home
            n("""philips\s*hue|hue\s*(lamp|bulb|go|play|bridge|white|color|lightstrip)|^hue\b""", O, "Éclairage Philips Hue", "Philips", 92, Sym.Lightbulb),
            n("""lifx""", O, "Ampoule LIFX", "LIFX", 92, Sym.Lightbulb),
            n("""nanoleaf""", O, "Éclairage Nanoleaf", "Nanoleaf", 92, Sym.Lightbulb),
            n("""gvh5\d{3}|govee_?\s?h5\d{3}|govee.*(thermo|hygro)""", O, "Thermomètre Govee", "Govee", 94, Sym.Thermostat),
            n("""govee|ihoment|\bh6\d{3}\b|\bh7\d{3}\b""", O, "Éclairage Govee", "Govee", 88, Sym.Lightbulb),
            n("""yeelight|yeelink""", O, "Éclairage Yeelight", "Yeelight", 90, Sym.Lightbulb),
            n("""sengled|\bwiz\b|tapo\s*l\d|kasa|\bkl\d{3}\b|ikea|tradfri|ledvance|osram|innr|sylvania""", O, "Ampoule connectée", null, 80, Sym.Lightbulb),
            n("""elk-?bled|triones|ledble|melk|bledom|led\s*(strip|controller|lamp|bulb|net)|^led|^lednet|zengge|magic\s*hue|smart\s*(bulb|led|light)|\blamp\b|\blight\b|ampoule|lampe|luminaire|ruban""", O, "Éclairage / bandeau LED", null, 76, Sym.Lightbulb),
            n("""nuki|august|yale|kwikset|schlage|lockly|tedee|igloohome|ultraloq|smart\s*lock|serrure|verrou|door\s*lock|padlock|cadenas|masterlock|tapplock|sesame|danalock|ttlock|sciener|\block\b""", O, "Serrure connectée", null, 82, Sym.Lock),
            n("""switchbot\s*(meter|hygrometer|thermo)|thermopro|tp3[59]\d|inkbird|ibs-?th|lywsd|mj[_ -]?ht|cgg1|cgdk|atc_|pvvx|ruuvi|hygro|humidity|humidité|thermo-?hygro|airthings|qingping|temp(erature)?\s*(sensor|&)|capteur|\bsensor\b""", O, "Capteur de température / humidité", null, 84, Sym.Thermostat),
            n("""flower\s*care|miflora|mi\s*flora|hhcc|plant\s*sensor|\bflora\b""", O, "Capteur de plantes", "Xiaomi", 88, Sym.Sensors),
            n("""switchbot""", O, "SwitchBot", "SwitchBot", 92, Sym.Hub),
            n("""shelly|sonoff|\bplug\b|prise|relay|relais|smart\s*switch|interrupteur|meross|tapo\s*p\d|aqara|\blumi\b|mijia""", O, "Prise / interrupteur connecté", null, 82, Sym.Bolt),
            n("""thermostat|tado|netatmo|ecobee|\bhive\b|eve\s*(room|thermo|energy|door|motion|weather|button|aqua|degree)""", O, "Thermostat / capteur domotique", null, 86, Sym.Thermostat),
            n("""nest\s*(thermostat|protect|cam|doorbell)""", O, "Google Nest", "Google", 90, Sym.Thermostat),
            n("""dyson|roomba|roborock|irobot|vacuum|aspirateur|dreame|ecovacs|braava|purifier|humidifier|diffuser|kettle|bouilloire|nespresso|cafetière|coffee|fridge|frigo|oven|\bfour\b|washer|dishwasher|blender|thermomix|airfryer|instant\s*pot|anova|sous-?vide""", O, "Électroménager connecté", null, 80, Sym.Hub),
            n("""meater|igrill|weber|traeger|\bbbq\b|barbecue|grill""", O, "Thermomètre de cuisson", null, 80, Sym.Thermostat),
            n("""ring\s*(doorbell|alarm|cam|chime|stick)|arlo|eufy|wyze|blink|reolink|tapo\s*c\d|doorbell|sonnette|alarm|alarme|siren|smoke|détecteur|simplisafe|canary|somfy|hikvision|dahua|imou|ezviz""", O, "Caméra / sonnette / alarme", null, 80, Sym.Videocam),
            n("""garage|portail|\bgate\b|blind|store|volet|rideau|curtain|velux|chamberlain|myq|liftmaster|hörmann|hormann|marantec""", O, "Ouvrant (garage, volet, portail)", null, 78, Sym.Hub),
            n("""hm-?10|hmsoft|bt-?05|jdy-?\d+|at-?09|cc41|ble-?uart|hc-?0[56]|hc-?42|bluno|dsd\s?tech|ble\s?(module|serial)|nordic_?uart|\buart\b""", O, "Module série BLE (HM-10…)", null, 80, Sym.Memory),
            n("""tuya|smart\s*life|smartlife""", O, "Objet Tuya / Smart Life", "Tuya", 78, Sym.Hub),

            // Vehicles
            n("""obd-?ii|obd2|obd\s*link|elm327|veepeak|carista|bluedriver|vlink|v-link|vgate|viecar|konnwei|bafx|icar|carly|thinkdiag|foxwell|fixd|\bobd\b""", V, "Adaptateur OBD-II", null, 92, Sym.DirectionsCar),
            n("""tpms|tpm[ _-]?\d|tire\s*pressure|pression\s*des\s*pneus""", V, "Capteur de pression des pneus", null, 86, Sym.DirectionsCar),
            n("""miscooter|mi\s*scooter|m365|ninebot|segway|scooter|trottinette|kickscooter|dott|\bvoi\b|\btier\b|lime""", V, "Trottinette électrique", null, 84, Sym.ElectricScooter),
            n("""e-?bike|ebike|vélo|\bbike\b|bafang|kiox|nyon|purion|intuvia|shimano\s*(e[678]|steps)|vanmoof|cowboy|specialized\s*(turbo|mission)|stromer|riese""", V, "Vélo électrique", null, 84, Sym.PedalBike),
            n("""drone|tello|mavic|fpv|dji\s*(mini|air|mavic|neo)""", V, "Drone", null, 86, Sym.Flight),
            n("""tesla|\bbmw\b|audi|mercedes|volkswagen|\bvw\b|skoda|peugeot|citro[eë]n|renault|dacia|toyota|honda|nissan|mazda|\bford\b|opel|fiat|volvo|\bkia\b|hyundai|\bjeep\b|land\s*rover|porsche|carplay|android\s*auto""", V, "Véhicule", null, 74, Sym.DirectionsCar),
        )
    }

    private fun nameRules(ctx: Ctx, raw: String, source: String) {
        val text = raw.replace('_', ' ')
        for (r in NameRules) if (r.re.containsMatchIn(text)) ctx.add(r.kind, r.label, r.maker, r.score, "Correspondance avec le $source", r.icon)
    }

    /** Vendor names spelled out in the Device Information manufacturer string. */
    private fun gattMaker(s: String): String? = s.trim().removeSuffix("Inc.").removeSuffix("Inc").removeSuffix("Ltd.").removeSuffix("Corporation").trim(' ', ',').takeIf { it.length >= 2 }
}
