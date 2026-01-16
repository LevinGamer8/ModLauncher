# ModLauncher

Ein **moderner, eigenständiger Minecraft Mod Launcher für Windows**, der sicherstellt,  
dass **alle Spieler automatisch die gleiche Client-Umgebung verwenden** – ohne manuelle Mod- oder Config-Pflege.

Der ModLauncher unterscheidet dabei klar zwischen:
- **Launcher-Updates** (Programm selbst)
- **Modpack-Updates** (Mods, Configs, Projektdateien)

---

## Warum ModLauncher?

Typische Probleme bei Mod-Projekten:
- Fehlende oder falsche Mod-Versionen
- Manuelle Config-Anpassungen auf jedem Client
- Verpasste Discord-Ankündigungen
- Fehlgeschlagene Server-Joins und Support-Aufwand

**ModLauncher löst diese Probleme konsequent.**

---

## Update-Konzept (wichtig)

### 🔄 Launcher-Updates
- Betreffen **nur den Launcher selbst**
- Prüfung **beim Start**
- Update per:
  - Klick auf **„Launcher aktualisieren“**
  - oder Bestätigung per Dialog
- Self-Update über **GitHub Releases**
- Keine Neuinstallation nötig

➡️ Diese Updates sind **selten** und betreffen nur Funktionen, UI oder Bugfixes des Launchers.

---

### 📦 Modpack-Updates
- Betreffen:
  - Mods
  - Configs
  - weitere projektbezogene Dateien
- Werden **vor jedem Spielstart automatisch geprüft**
- Fehlende oder geänderte Dateien werden:
  - heruntergeladen
  - aktualisiert
  - ersetzt

➡️ Spieler müssen **nichts manuell tun**.  
➡️ Joinen auf den Server funktioniert danach sofort.

---

## Kernfunktionen

- 🧩 Unterstützung für **Vanilla, Forge, Fabric**  
  *(Quilt / NeoForge geplant)*
- 🔄 Automatische **Modpack-Synchronisation vor jedem Start**
- 🔁 **Self-Updating Launcher**
- 📦 Zentrale Bereitstellung von Mods & Configs durch den Projektleiter
- 🚀 Eigene **Java Runtime integriert**
- 🎨 Moderne **JavaFX UI** (AtlantaFX / PrimerDark)
- 🪟 **Windows MSI Installer**
- 🛠 Saubere, modulare **Maven-Projektstruktur**

---

## Zielgruppe

- Minecraft-Projektleiter
- Modpack- & Server-Administratoren
- Communities, die **keinen Client-Support** mehr wollen

---

## Installation (Spieler)

1. MSI aus den **GitHub Releases** herunterladen
2. Installer ausführen
3. Launcher starten

➡️ **Kein Java erforderlich**

---

## Entwicklung

### Voraussetzungen
- **JDK 24**
- **Maven**
- **Windows** (für MSI-Build via jpackage)

### Build

```bash
./mvnw clean package
./mvnw jpackage:jpackage
