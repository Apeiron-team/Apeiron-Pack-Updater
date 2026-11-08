# Apeiron Pack Updater

> **⚠ DISCLAIMER: ApePack v2 is coming soon.** The next major version will introduce a completely redesigned `.apepack` format with improved performance, better compression, and enhanced features. The current v1 format will remain supported during the transition period.

A Minecraft mod for **NeoForge 26.1.2** that provides in-game modpack management with a custom `.apepack` file format for seamless pack updates. Download, verify, and install modpack updates directly from GitHub releases — no launcher required.

## Features

- **`.apepack` File Format** — Custom ZIP-based archive containing mods, configs, and KubeJS/KubeGroovy scripts with SHA-256 integrity verification
- **Auto-Extract on Startup** — On game launch, the mod checks for new `.apepack` files in the `apepacks/` folder, extracts them automatically, and restarts the game — no player input required
- **Smart Mod Updates** — Compares mod versions before installing; only replaces older versions and removes the old JAR files
- **Config Preservation** — Packs and installs `.toml`, `.json`, `.cfg`, and `.properties` config files while protecting user settings
- **User Settings Protection** — Never overwrites `options.txt`, keybinds, video settings, server lists, or Realms data
- **KubeJS/KubeGroovy Support** — Automatically detects and packs server scripts, client scripts, Groovy scripts, assets, and data packs
- **Tabbed Config Screen** — Clean, organized config UI with tabs for Apepacks, Generate, and Settings
- **In-Game File Picker** — Native Minecraft file browser for selecting `.apepack` files with search, navigation, and scrollbar
- **Import Progress Screen** — Real-time progress bar showing each file being imported with live counts
- **GitHub Release Integration** — Check for `.apepack` updates from GitHub releases with manual download and progress tracking
- **In-Game Pack Generation** — Generate `.apepack` files from your current instance with a few clicks
- **Auto-Restart** — After installing an update, the game automatically restarts with the new mods loaded
- **Options Menu Button** — Quick access to PackUpdater config from Minecraft's Options menu (Esc → Options)

## Compatibility

| Requirement | Version |
|---|---|
| Minecraft | 26.1.2 |
| Mod Loader | NeoForge 26.1.2.122+ |
| Java | 25 (LTS) |

## Installation

1. Download `packupdater.jar` from the [Releases](https://github.com/Apeiron-team/Apeiron-Pack-Updater/releases) page
2. Place the JAR in your Minecraft `mods/` folder
3. Launch Minecraft with NeoForge 26.1.2

## Usage

### Opening the Config Screen

- **Mods Menu**: Press **Esc** → **Mods** → Find **PackUpdater** → Click the **Wrench icon** (Config)
- **Options Menu**: Press **Esc** → **Options** → Click **"PackUpdater"** button

### Generating an `.apepack` File

1. Open the PackUpdater config screen
2. Enter a **Pack Name** (e.g., "MyModpack")
3. Enter a **Version** (e.g., "1.0.0")
4. Click **"Generate .apepack"**
5. The file saves to your instance folder as `generated_<name>_<version>.apepack`

The generated file includes:
- All mods (excluding PackUpdater itself)
- Config files from `config/`
- KubeJS/KubeGroovy scripts from `kubejs/` (if present)

### Installing an `.apepack` File

**Automatic (Recommended)** — Place the `.apepack` file in the `apepacks/` folder of your instance. On game startup, the mod will automatically extract and install the update, then restart the game.

**Manual** — Open the PackUpdater config screen → Click **"Browse .apepack"** → Navigate to and select the `.apepack` file → The import progress screen shows each file being processed → The game restarts automatically when complete.

### Checking for Updates

1. Open the PackUpdater config screen (Apepacks tab)
2. Click **"Check for Update"** to scan GitHub for newer `.apepack` releases
3. If a newer version is available, click **"Download Update"**
4. The downloaded `.apepack` saves to the `apepacks/` folder
5. On game restart, the update is automatically extracted and installed

## `.apepack` File Format

An `.apepack` file is a ZIP archive with the following structure:

```
my-pack.apepack
├── apepack.json        # Pack metadata (name, version, MC version, mod count)
├── manifest.json       # File list with SHA-256 hashes, versions, and install paths
└── mods/               # Mod JAR files
    ├── mod1.jar
    └── mod2.jar
```

### `apepack.json`

```json
{
  "name": "My Modpack",
  "version": "1.0.0",
  "minecraftVersion": "26.1.2",
  "modCount": 42,
  "configCount": 15,
  "scriptCount": 3
}
```

### `manifest.json`

```json
{
  "formatVersion": "2.0",
  "files": [
    {
      "name": "sodium-0.6.13+mc26.1.2.jar",
      "type": "mod",
      "version": "0.6.13",
      "sha256": "abc123...",
      "filePath": "mods/sodium-0.6.13+mc26.1.2.jar",
      "installPath": "mods"
    },
    {
      "name": "sodium-options.toml",
      "type": "config",
      "sha256": "def456...",
      "filePath": "config/sodium-options.toml",
      "installPath": "config"
    }
  ]
}
```

## Protected Files

The following files are **never** packed or overwritten during installation:

- `options.txt` — Game options
- `keybindings.txt` — Keybind settings
- `options.txt` — Video settings
- `serverlist.pak` — Saved server list
- `Realms*` — Realms data
- `usercache.json` — User session cache

## Building from Source

### Prerequisites

- **Java 25** (LTS)
- **Gradle 8.x** (wrapper included)

### Build Commands

```powershell
# Build the mod
.\gradlew.bat build

# Build and copy JAR to releases folder (with auto-commit)
.\build-and-copy.ps1
```

The built JAR will be in `build/libs/packupdater-1.0.1.jar`.

## Project Structure

```
packupdater/
├── build.gradle              # Gradle build configuration
├── build-and-copy.ps1        # Build script with auto-commit
├── gradle/                   # Gradle wrapper
├── gradlew / gradlew.bat     # Gradle wrapper scripts
├── releases/                 # Built JAR archives
│   ├── CHANGELOG.md          # Version history
│   └── packupdater-*.jar     # Release JARs
└── src/main/
    ├── java/com/Apeiron/globalpackupdater/
    │   ├── PackUpdaterMod.java         # Main mod entry point
    │   ├── ConfigManager.java          # Configuration handling
    │   ├── PackUpdaterConfigScreen.java # Tabbed config GUI screen
    │   ├── ApePackGenerator.java       # .apepack file generation
    │   ├── ApePackLoader.java          # .apepack file installation
    │   ├── ApePackFilePicker.java      # Native file browser
    │   ├── ApePackImportScreen.java    # Import progress screen
    │   ├── GameRelauncher.java         # Auto-restart after update
    │   └── OptionsMenuButton.java      # Options menu config button
    └── resources/META-INF/
        └── neoforge.mods.toml          # Mod metadata
```

## Changelog

See [releases/CHANGELOG.md](releases/CHANGELOG.md) for detailed version history.

## License

This project is provided as-is for personal and community use.
