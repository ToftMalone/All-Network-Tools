# 📡 All Radio Tools

**Tout ce qui émet autour de vous, dans une seule application Android.**

All Radio Tools réunit en un seul endroit les outils pour explorer le Wi-Fi, le Bluetooth, le réseau mobile, le GNSS et, avec un HackRF, les ondes radio : avions, ballons-sondes, navires, drones, satellites météo…

> 🇫🇷 Application entièrement en français, écrite en Kotlin avec Jetpack Compose et Material 3.

---

## 📲 Installer

1. Téléchargez le dernier `AllRadioTools-vX.Y.apk` depuis la page [Releases](https://github.com/ToftMalone/All-Radio-Tools/releases).
2. Autorisez l'installation depuis cette source quand Android le demande.
3. Les mises à jour suivantes sont proposées **dans l'application** : elle compare sa version à la dernière release GitHub.

**Prérequis :** Android 12 ou plus (API 31). L'application vise Android 17.

---

## 🧰 Les outils

L'écran d'accueil propose six réseaux. Chacun a son tableau de bord, son outil principal dans le dock flottant et une page « Outils ».

### 📶 Wi-Fi

| Outil | À quoi ça sert |
| --- | --- |
| Scanner Wi-Fi | Réseaux alentour, canaux, occupation |
| Appareils du LAN | Découvrir les appareils du réseau |
| Ping (illimité si 0 paquet), Traceroute, Scan de ports (courants ou 0 – 65535), DNS, Test de débit | Les classiques du diagnostic réseau |
| Whois | Propriétaire d'un domaine ou d'une adresse IP |
| Wi-Fi Direct | Appareils Wi-Fi Direct à proximité |
| Audit du réseau | Chiffrement, WPS, PMF, box |
| Faux points d'accès | « Evil twin » du réseau Wi-Fi connecté |
| Homme du milieu | Détection d'ARP spoofing et de DHCP pirate |

### 🔵 Bluetooth

- **Scanner BLE** avec filtres et identification des appareils (fabricant, modèle, services).
- **Appareil BLE** : exploration GATT d'un appareil.
- **Chaud/Froid** : retrouver un appareil au signal, l'écran devient plus chaud à mesure que vous vous en approchez.
- **Traqueurs inconnus** : détecte les traqueurs qui pourraient vous suivre (AirTag, AirPods, accessoires Localiser, tags Google, Galaxy SmartTag, Tile), dans une liste façon [AirGuard](https://github.com/seemoo-lab/AirGuard) avec une icône par type d'appareil, triée par date de détection. L'application se connecte brièvement aux appareils Apple pour lire leur modèle, sans rien y écrire.

### 📱 Réseau mobile

- **Cellule de service** : identifiants et mesures de la cellule connectée (LTE, NR).
- **Cellules voisines** : cellules détectées par le modem.
- Carte des antennes à partir des données ouvertes de l'ANFR.

### 🛰️ GNSS

- **Ciel GNSS** : sky plot et carte du monde des satellites.
- **Comparer les positions** : GNSS, réseau et position fusionnée.

### 📻 SDR (HackRF, en réception uniquement)

| Outil | Contenu |
| --- | --- |
| Analyseur de spectre | Spectre et chute d'eau, de 1 MHz à 6 GHz |
| Avions (ADS-B) | Carte des avions sur 1090 MHz |
| Ballons-sondes | Sondes Vaisala RS41 et Meteomodem M10 / M20 vers 403 MHz, avec prédiction du point d'atterrissage |
| Radio FM | Écoute et RDS |
| Navires (AIS) | Carte des navires sur 162 MHz |
| Radioamateurs (APRS) | Balises sur 144,800 MHz |
| Satellites météo (Meteor-M) | Images LRPT sur 137 MHz |
| Drones | FPV analogique, DJI (DroneID) et radiocommandes (ExpressLRS, Crossfire, FrSky, FlySky, Spektrum), identifiés par leur signature radio |

Les décodeurs fonctionnent avec le **HackRF seul** : ni le Wi-Fi ni le Bluetooth du téléphone ne sont utilisés. La carte SDR reste grisée tant qu'aucun HackRF n'est branché en USB.

### 🔌 Talkie-walkie

Lire, modifier et réécrire les mémoires d'un **Baofeng UV-5R**, d'un **Radtel RT-470X** ou d'un **Quansheng UV-K5 / UV-K6 / UV-5R Plus** par câble de programmation USB (puces CH340, CP210x, FTDI, PL2303), avec des préréglages (PMR446, relais, marine…). Sans câble, la carte reste consultable mais les outils ne se lancent pas.

### 📤 Exports

Les avions, ballons-sondes, navires et balises APRS s'exportent en **CSV** ; les images Meteor-M en **PNG**. Vous choisissez l'emplacement du fichier, l'application n'en garde aucune copie.

---

## 🔐 Autorisations

| Autorisation | Pourquoi |
| --- | --- |
| Position précise | Scans Wi-Fi, cellules, GNSS (imposée par Android) |
| Appareils à proximité | Scan et connexion Bluetooth |
| Téléphone | Opérateur, cellules, double SIM |
| Réseau local (Android 17) | Scan du LAN, ping, scan de ports |
| Installer des applications | Mise à jour intégrée |

Aucun compte, aucune publicité, aucun traceur. Les données restent sur le téléphone ; Internet n'est utilisé que pour les mises à jour, les éléments de satellites (CelesTrak) et les données d'antennes.

---

## ⚖️ Usage responsable

- Le SDR est utilisé **en réception seulement**. Respectez la législation de votre pays sur l'écoute des fréquences.
- Les outils de sécurité Wi-Fi (audit, faux points d'accès, homme du milieu, scan de ports) sont faits pour **vos propres réseaux** ou ceux que vous avez l'autorisation de tester.
- « Traqueurs inconnus » sert à vous protéger d'un suivi non voulu, pas à pister les autres.

---

## 🛠️ Compiler

```bash
./gradlew :app:assembleDebug        # APK de debug
./gradlew :app:testDebugUnitTest    # tests (Robolectric)
./gradlew :app:lintDebug            # lint
```

Il faut un JDK récent et le SDK Android (API 37). Les scripts `scripts/` servent à récupérer les polices et à générer la carte du monde.

### 🚀 Publier une version

1. Mettre à jour `versionCode` et `versionName` dans `app/build.gradle.kts`.
2. Écrire le tag dans `.github/release-version` (par ex. `v1.0`) et les notes dans `.github/release-notes/v1.0.md`.
3. Pousser sur `main` : le workflow GitHub Actions construit l'APK signé et crée la release avec `AllRadioTools-v1.0.apk`.

Le workflow a besoin des secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` et `KEY_PASSWORD`.

---

## 🙏 Remerciements

- [AirGuard](https://github.com/seemoo-lab/AirGuard) (SEEMOO, TU Darmstadt) pour la référence sur la détection des traqueurs.
- [rs1729/RS](https://github.com/rs1729/RS) pour la documentation des formats de sondes.
- Roboto Flex, Google Sans Flex et Material Symbols pour l'interface.
