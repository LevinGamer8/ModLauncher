# ModLauncher

Ein moderner, eigenständiger Minecraft Mod Launcher für Windows – gebaut mit **Java 24**, **JavaFX** und **jpackage**.  
Der Launcher bringt **seinen eigenen Runtime-Stack mit**, benötigt **kein installiertes Java** und kann sich **selbst aktualisieren**.

---

## Features

- 🧩 Unterstützung für **Vanilla, Forge, Fabric** (erweiterbar)
- 🚀 Eigene **Java Runtime** (kein externes Java nötig)
- 🔄 **Automatisches & manuelles Self-Update** über GitHub Releases
- 🎨 Moderne JavaFX-Oberfläche (AtlantaFX / PrimerDark)
- 📦 Windows-Installer (MSI) inkl. Startmenü & Deinstallation
- 🛠 Modulares, sauberes Java-Projekt (Maven)

---

## Installation

1. Lade die **MSI-Datei** aus den GitHub Releases herunter
2. Installer ausführen
3. Start über:
   - Startmenü **ModLauncher**
   - oder `C:\Program Files\ModLauncher\ModLauncher.exe`

---

## Updates

Der Launcher:
- prüft **automatisch beim Start** auf Updates
- kann **manuell über einen Button** aktualisiert werden
- lädt neue Versionen herunter und ersetzt sich selbst sauber

---

## Entwicklung

### Voraussetzungen
- JDK **24**
- Maven (Wrapper enthalten)
- Windows (jpackage MSI)

### Build
```bash
./mvnw clean package
./mvnw jpackage:jpackage
