# ModLauncher

Ein **moderner, eigenständiger Minecraft Mod Launcher für Windows**, der ein zentrales Problem löst:  
**Mods, Configs und Versionen müssen nicht mehr manuell gepflegt werden.**

Der ModLauncher stellt sicher, dass **alle Spieler automatisch exakt die gleiche Client-Umgebung** haben –  
ohne Java-Installation, ohne Mod-Chaos, ohne Support-Albtraum.

---

## Warum ModLauncher?

Typische Probleme bei Mod-Projekten:
- Spieler haben Mods nicht oder in falscher Version
- Config-Änderungen müssen manuell erklärt werden
- Discord-Ankündigungen werden übersehen
- Server-Join schlägt fehl → Frust & Support

**ModLauncher löst genau das.**

---

## Kernfunktionen

- 🧩 Unterstützung für **Vanilla, Forge, Fabric**  
  *(Quilt / NeoForge geplant)*
- 🔄 **Automatische Installation & Updates** von:
  - Mods
  - Configs
  - weiteren Projektdateien
- 📦 **Zentrale Projektstruktur**  
  → Host stellt Dateien bereit, Clients synchronisieren automatisch
- 🚀 **Eigene Java Runtime integriert**  
  → kein installiertes Java nötig
- 🔁 **Self-Updater** über GitHub Releases
- 🎨 Moderne **JavaFX UI** (AtlantaFX / PrimerDark)
- 🪟 **Windows MSI Installer**
  - Startmenü-Eintrag
  - Saubere Deinstallation
- 🛠 Sauberes, modulares **Maven-Projekt**

---

## Zielgruppe

- Minecraft-Projektleiter
- Modpack-Entwickler
- Private & öffentliche Mod-Server
- Communities, die **keine Lust auf Client-Support** haben

---

## Installation (Spieler)

1. MSI-Datei aus den **GitHub Releases** herunterladen
2. Installer ausführen
3. Launcher starten über:
   - Startmenü → **ModLauncher**
   - oder  
     `C:\Program Files\ModLauncher\ModLauncher.exe`

➡️ Java muss **nicht** installiert sein.

---

## Updates

- Automatische Update-Prüfung beim Start
- Manuelle Update-Prüfung per Button
- Launcher ersetzt sich selbst **ohne Neuinstallation**

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
