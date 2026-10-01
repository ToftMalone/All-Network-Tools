package com.allnetworktools.model

import com.allnetworktools.ui.theme.Sym

enum class Network(
    val label: String,
    val icon: String,
    val featuredShort: String,
) {
    Wifi("Wi-Fi", Sym.Wifi, "Scan"),
    Bluetooth("Bluetooth", Sym.Bluetooth, "BLE"),
    Cellular("Réseau mobile", Sym.CellBars3, "Cellules"),
    Gnss("GNSS", Sym.SatelliteAlt, "Ciel"),
    Sdr("SDR", Sym.Antenna, "Mesh"),
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
            Sdr -> Tool.Meshtastic
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
    WifiDirect(Network.Wifi, "Wi-Fi Direct", Sym.WifiTethering),

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

    Meshtastic(Network.Sdr, "Meshtastic", Sym.Hub, Sym.Delete, implemented = true),
    Spectrum(Network.Sdr, "Analyseur de spectre", Sym.BarChart),
    Adsb(Network.Sdr, "Avions (ADS-B)", Sym.Flight),
    Sonde(Network.Sdr, "Ballons-sondes", Sym.Cloud),
    Fm(Network.Sdr, "Radio FM", Sym.Radio),
    Ais(Network.Sdr, "Navires (AIS)", Sym.Boat),
    Emitters(Network.Sdr, "Détecteur d'émetteurs", Sym.Radar),

    ;

    val isDockShortcut: Boolean get() = network.dockShortcut == this
}
