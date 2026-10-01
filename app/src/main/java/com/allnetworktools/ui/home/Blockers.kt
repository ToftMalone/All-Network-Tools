package com.allnetworktools.ui.home

import com.allnetworktools.Blocker
import com.allnetworktools.model.Network
import com.allnetworktools.ui.theme.Sym

/** Short copy for a disabled home card. */
data class CardBlock(val icon: String, val title: String, val message: String, val button: String?, val buttonIcon: String)

/** Full-page StatePanel copy. */
data class PageBlock(val icon: String, val title: String, val message: String, val primary: String?, val primaryIcon: String, val secondary: String?)

fun cardBlock(b: Blocker): CardBlock = when (b) {
    Blocker.WifiOff -> CardBlock(Sym.WifiOff, "Wi-Fi désactivé", "Activez-le pour voir la connexion.", "Activer", Sym.PowerSettings)
    Blocker.BluetoothOff -> CardBlock(Sym.BluetoothDisabled, "Bluetooth désactivé", "Requis pour scanner les appareils.", "Activer", Sym.PowerSettings)
    Blocker.NearbyPermission -> CardBlock(Sym.BluetoothDisabled, "Autorisation requise", "Autorisez les appareils à proximité.", "Autoriser", Sym.BluetoothSearching)
    Blocker.Airplane -> CardBlock(Sym.Airplane, "Mode avion", "Aucune mesure radio possible.", "Paramètres", Sym.OpenInNew)
    Blocker.PhonePermission -> CardBlock(Sym.SimCard, "Autorisation requise", "Autorisez l'accès au téléphone.", "Autoriser", Sym.SimCard)
    Blocker.NoSim -> CardBlock(Sym.NoSim, "Aucune SIM", "Insérez une SIM ou activez une eSIM.", "Paramètres", Sym.OpenInNew)
    Blocker.LocationPermission -> CardBlock(Sym.LocationOff, "Position requise", "Autorisez la position précise pour lire les satellites.", "Autoriser", Sym.MyLocation)
    Blocker.LocationOff -> CardBlock(Sym.LocationOff, "Localisation désactivée", "Activez-la pour recevoir les satellites.", "Activer", Sym.PowerSettings)
    Blocker.NoHardware -> CardBlock(Sym.Block, "Non disponible", "Cet appareil n'a pas cette radio.", null, Sym.Block)
    Blocker.SdrMissing -> CardBlock(Sym.Usb, "Aucun HackRF", "Branchez un HackRF en USB-C pour recevoir.", null, Sym.Usb)
}

fun pageBlock(network: Network, b: Blocker, permanentlyDenied: Boolean): PageBlock = when (b) {
    Blocker.WifiOff -> PageBlock(Sym.WifiOff, "Wi-Fi désactivé", "Activez le Wi-Fi pour afficher la connexion en cours et utiliser les outils réseau local.", "Activer le Wi-Fi", Sym.PowerSettings, "Paramètres Wi-Fi")
    Blocker.BluetoothOff -> PageBlock(Sym.BluetoothDisabled, "Bluetooth désactivé", "Activez le Bluetooth pour voir vos appareils et lancer un scan BLE.", "Activer le Bluetooth", Sym.PowerSettings, "Paramètres Bluetooth")
    Blocker.NearbyPermission -> PageBlock(
        Sym.BluetoothDisabled, "Accès aux appareils à proximité requis",
        "L'autorisation « Appareils à proximité » est nécessaire pour lister et scanner les appareils Bluetooth.",
        if (permanentlyDenied) "Ouvrir les paramètres" else "Autoriser", if (permanentlyDenied) Sym.OpenInNew else Sym.BluetoothSearching, null,
    )
    Blocker.Airplane -> PageBlock(Sym.Airplane, "Mode avion activé", "Le modem est coupé. Désactivez le mode avion pour mesurer le signal mobile.", "Désactiver le mode avion", Sym.AirplaneOff, null)
    Blocker.PhonePermission -> PageBlock(
        Sym.SimCard, "Accès au téléphone requis",
        "L'autorisation Téléphone permet de lire l'opérateur, la technologie et les cellules. Rien n'est transmis.",
        if (permanentlyDenied) "Ouvrir les paramètres" else "Autoriser", if (permanentlyDenied) Sym.OpenInNew else Sym.SimCard, null,
    )
    Blocker.NoSim -> PageBlock(Sym.NoSim, "Aucune carte SIM", "Insérez une carte SIM ou activez une eSIM pour mesurer le réseau mobile.", "Paramètres réseau", Sym.OpenInNew, null)
    Blocker.LocationPermission -> PageBlock(
        Sym.LocationOff, "Accès à la position requis",
        "La position précise est nécessaire pour lire l'état des satellites. Les données restent sur l'appareil.",
        if (permanentlyDenied) "Ouvrir les paramètres" else "Autoriser", if (permanentlyDenied) Sym.OpenInNew else Sym.MyLocation,
        if (permanentlyDenied) null else "Ouvrir les paramètres",
    )
    Blocker.LocationOff -> PageBlock(Sym.LocationOff, "Localisation désactivée", "Activez la localisation de l'appareil pour recevoir les signaux GNSS.", "Activer la localisation", Sym.PowerSettings, null)
    Blocker.NoHardware -> PageBlock(Sym.Block, "${network.label} indisponible", "Cet appareil ne possède pas le matériel nécessaire.", null, Sym.Block, null)
    Blocker.SdrMissing -> PageBlock(
        Sym.Usb, "Branchez un HackRF",
        "Reliez un HackRF One (ou rad1o, Jawbreaker, PortaPack en mode HackRF) au téléphone avec un câble USB-C OTG. " +
            "L'application le détecte dès qu'il est branché.",
        null, Sym.Usb, null,
    )
}
