package com.allnetworktools.model

import com.allnetworktools.ui.theme.Sym

/** Corner radii (dp) of the dock's leading button: top-start, top-end, bottom-end, bottom-start. */
data class LeadCorners(val ts: Float, val te: Float, val be: Float, val bs: Float)

enum class Network(
    val label: String,
    val icon: String,
    val featuredShort: String,
    val lead: LeadCorners,
) {
    Wifi("Wi-Fi", Sym.Wifi, "Scan", LeadCorners(24f, 24f, 24f, 24f)),
    Bluetooth("Bluetooth", Sym.Bluetooth, "BLE", LeadCorners(18f, 18f, 18f, 18f)),
    Cellular("Réseau mobile", Sym.CellBars3, "Cellules", LeadCorners(24f, 24f, 12f, 24f)),
    Gnss("GNSS", Sym.SatelliteAlt, "Ciel", LeadCorners(14f, 24f, 14f, 24f)),
    Nfc("NFC", Sym.Nfc, "Lire", LeadCorners(24f, 14f, 24f, 14f)),
    ;

    // Getters rather than constructor arguments: Tool's entries reference Network, so eager
    // initialisation in both directions would leave one side null.

    /** Tool highlighted at the top of the network's Tools page. */
    val featured: Tool
        get() = when (this) {
            Wifi -> Tool.WifiScan
            Bluetooth -> Tool.BleScan
            Cellular -> Tool.Neighbors
            Gnss -> Tool.Sky
            Nfc -> Tool.NfcReader
        }

    /** Tool reachable directly from the dock. */
    val dockShortcut: Tool?
        get() = featured
}

/** Where the back arrow of a tool leads. */
sealed interface ToolParent {
    data object Tools : ToolParent
    data object Dashboard : ToolParent
    data class Other(val tool: Tool) : ToolParent
}

enum class Tool(
    val network: Network,
    val title: String,
    val icon: String,
    val topAction: String? = null,
    val parent: ToolParent = ToolParent.Tools,
    val implemented: Boolean = false,
) {
    WifiScan(Network.Wifi, "Scanner Wi-Fi", Sym.WifiFind, implemented = true),
    Channels(Network.Wifi, "Analyseur de canaux", Sym.BarChart, Sym.Refresh),
    Lan(Network.Wifi, "Appareils du LAN", Sym.Devices, Sym.Refresh),
    LanDevice(Network.Wifi, "Appareil du LAN", Sym.Devices, Sym.MoreVert, ToolParent.Other(Lan)),
    Ping(Network.Wifi, "Ping", Sym.NetworkPing, Sym.IosShare),
    Trace(Network.Wifi, "Traceroute", Sym.Route, Sym.IosShare),
    Ports(Network.Wifi, "Scan de ports", Sym.Lan, Sym.IosShare),
    Dns(Network.Wifi, "DNS Lookup", Sym.Dns, Sym.History),
    Speed(Network.Wifi, "Test de débit", Sym.Speed, Sym.History),
    Upnp(Network.Wifi, "Scanner UPnP", Sym.Router, Sym.Refresh),
    Bonjour(Network.Wifi, "Scanner Bonjour", Sym.Cast, Sym.Refresh),
    Whois(Network.Wifi, "Whois", Sym.TravelExplore, Sym.IosShare),
    EvilTwin(Network.Wifi, "Faux points d'accès", Sym.WifiTetheringError, Sym.Refresh),
    Audit(Network.Wifi, "Audit du réseau", Sym.Shield, Sym.Refresh),
    PortalDns(Network.Wifi, "Portail captif et DNS", Sym.Language, Sym.Refresh),
    Mitm(Network.Wifi, "Homme du milieu", Sym.SwapHoriz, Sym.RestartAlt),

    BleScan(Network.Bluetooth, "Scanner BLE", Sym.BluetoothSearching, implemented = true),
    Gatt(Network.Bluetooth, "Appareil BLE", Sym.AccountTree, Sym.MoreVert),
    Paired(Network.Bluetooth, "Appareil appairé", Sym.Headphones, Sym.MoreVert, ToolParent.Dashboard),
    Tracker(Network.Bluetooth, "Chaud/Froid", Sym.MyLocation),
    UnknownTrackers(Network.Bluetooth, "Traqueurs inconnus", Sym.GppMaybe, Sym.RestartAlt),

    Neighbors(Network.Cellular, "Cellules voisines", Sym.CellTower, implemented = true),
    DataUsage(Network.Cellular, "Données mobiles", Sym.DataUsage, Sym.CalendarMonth),
    TowerMap(Network.Cellular, "Carte des antennes", Sym.Map, Sym.Refresh),
    CellDetail(Network.Cellular, "Détail de la cellule", Sym.CellTower, Sym.ContentCopy, ToolParent.Other(Neighbors)),

    Sky(Network.Gnss, "Ciel GNSS", Sym.SatelliteAlt, implemented = true),
    PositionCompare(Network.Gnss, "Comparer les positions", Sym.ShareLocation, Sym.Refresh),
    Passes(Network.Gnss, "Passages de satellites", Sym.Orbit, Sym.Refresh),

    NfcReader(Network.Nfc, "Lecteur NFC", Sym.Nfc, implemented = true),
    NfcWrite(Network.Nfc, "Écrire un tag", Sym.Edit),
    NfcErase(Network.Nfc, "Effacer et verrouiller", Sym.Delete),
    NfcRange(Network.Nfc, "Test de lecture", Sym.ContactlessPayment),
    ;

    val isDockShortcut: Boolean get() = network.dockShortcut == this
}
