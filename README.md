# ModLauncher

Ein moderner, eigenständiger Minecraft-Mod-Launcher für **Windows**, entwickelt mit **Java 24**, **JavaFX** und **jpackage**.

Der Launcher bringt **seine eigene Java-Runtime** mit, benötigt **kein installiertes Java** und kann sich **selbst aktualisieren**.

---

## Features

- Unterstützung für **Vanilla, Forge, Fabric**  
  *(Quilt / NeoForge geplant)*
- Integrierte **Java Runtime** – kein externes Java erforderlich
- **Automatische & manuelle Updates** über GitHub Releases
- Moderne **JavaFX-UI** (AtlantaFX / PrimerDark)
- **Windows-Installer (MSI)** inkl. Startmenü & sauberer Deinstallation
- Saubere, modulare **Maven-Projektstruktur**

---

## Installation

1. **MSI-Installer** aus den GitHub Releases herunterladen
2. Installer ausführen
3. Starten über:
   - Startmenü → **ModLauncher**
   - oder direkt:  
     `C:\Program Files\ModLauncher\ModLauncher.exe`

---

## Updates

Der Launcher:
- prüft **automatisch beim Start** auf neue Versionen
- bietet **manuelle Updates** per Button
- ersetzt sich **selbstständig und sauber**, ohne Neuinstallation

---

## Entwicklung

### Voraussetzungen
- **JDK 24**
- **Maven**
- **Windows** (für MSI-Build mit jpackage)

### Build

```bash
./mvnw clean package
./mvnw jpackage:jpackage
