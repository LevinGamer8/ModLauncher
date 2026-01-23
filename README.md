# ModLauncher

[Deutsch](README.de.md)

A **modern, standalone Minecraft mod launcher for Windows** that ensures
**all players automatically use the exact same client setup** – without manual mod or config management.

ModLauncher clearly separates:

* **Launcher updates** (the application itself)
* **Modpack updates** (mods, configs, project files)

---

## Why ModLauncher?

Common problems in modded Minecraft projects:

* Missing or wrong mod versions
* Manual config changes on every client
* Discord announcements being missed
* Failed server joins and unnecessary support effort

**ModLauncher solves these issues consistently.**

---

## Update Concept (Important)

### 🔄 Launcher Updates

* Affect **only the launcher itself**
* Checked **on startup**
* Updated via:

  * Clicking **"Update Launcher"**
  * or confirming the update dialog
* Self-updating via **GitHub Releases**
* No reinstallation required

➡️ These updates are **infrequent** and only affect launcher features, UI, or bug fixes.

---

### 📦 Modpack Updates

* Affect:

  * Mods
  * Config files
  * Other project-related files
* Checked **automatically before every game start**
* Missing or changed files are:

  * downloaded
  * updated
  * replaced

➡️ No manual action required by players.
➡️ Server join works immediately after.

---

## Core Features

* 🧩 Support for **Vanilla, Forge, Fabric**
  *(Quilt / NeoForge planned)*
* 🔄 Automatic **modpack synchronization before launch**
* 🔁 **Self-updating launcher**
* 📦 Central hosting of mods & configs by the project owner
* 🚀 Bundled **Java Runtime** (no Java installation required)
* 🎨 Modern **JavaFX UI** (AtlantaFX / PrimerDark)
* 🪟 **Windows MSI installer**
* 🛠 Clean, modular **Maven project structure**

---

## Target Audience

* Minecraft project leads
* Modpack and server administrators
* Communities that want **zero client-side support**

---

## Installation (Players)

1. Download the MSI from **GitHub Releases**
2. Run the installer
3. Start the launcher

➡️ **No Java installation required**

---

## Planned featues

* a user-friendly method to host own projects (currently not really possible for users)
* Quilt and NeoForge support
* (longer microsoft logins)

---

## Development

### Requirements

* **JDK 24**
* **Maven**
* **Windows** (for MSI builds via jpackage)

### Build

```bash
./mvnw clean package
./mvnw jpackage:jpackage
```

---

## Project Status

Actively developed.
Feedback and issues are welcome.

---

## Known bugs

For known bugs and issues see the issues tab here on GitHub.
If you find a new not listed bug or issue please open an issue.

---

## License

Not finalized yet.
