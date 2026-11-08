# PackUpdater Changelog

All notable changes to the Apeiron PackUpdater mod will be documented in this file.

---

## [1.0.1] - Patch - 2026-11-08

### Fixed
- **Missing manual update check** — Added "Check for Update" button in Apepacks tab to manually check for and download .apepack updates from GitHub
- **Options menu button missing** — Fixed PackUpdater button in Minecraft Options menu (Esc → Options) using event.addListener
- **Open Apepacks folder path** — Fixed path concatenation bug (instance\.\apepacks → correct path)
- **File picker default directory** — Browser now defaults to instance apepacks folder instead of user Downloads
- **File picker button alignment** — Centered all navigation buttons, fixed Desktop button path detection
- **File extensions not shown** — File picker now displays file extensions for all files
- **Folder icon** — Replaced "[DIR]" text with Unicode folder icon (📁)
- **Instance Folder button** — Added "Instance" button in file picker to navigate back to modpack instance root
- **Mod version display** — Fixed mod version in mods menu to show "v1.0R" instead of "v0.0.12alpha"
- **File picker background** — Fixed broken background rendering in file picker
- **Missing Done button** — Added "Done" button at bottom of config screen
- **Default pack version** — Shows "No modpack installed (v0.0)" instead of "Pack: v1.0" when no modpack is installed
- **Modpack name display** — Config screen now displays modpack name alongside version
- **Startup update check** — Added download progress bar in config screen for manual update downloads
- **Channel button visibility** — Channel button only shows when multiple release channels are available

### Added
- **Modpack name tracking** — Config now stores and displays the modpack name from .apepack files
- **Download progress bar** — Visual progress bar with byte-level progress during .apepack downloads
- **Pack version display** — Apepacks tab shows pack name and version at the top

---

## [1.0.0] - RELEASE - 2026-11-08

### Changed
- **Official stable release** — PackUpdater is now production-ready for Minecraft 26.1.2 NeoForge

### Removed
- **Mod auto-updater** — The PackUpdater mod no longer checks for its own updates. The mod version is managed independently from modpack updates
- **Main menu overlay** — Removed the pack version label from the main menu bottom-left corner
- **Main menu config button** — Removed the PackUpdater button from the main menu
- **Main menu notifications** — Removed all download/install notification screens from the main menu
- **System toast notifications** — Removed all system toast notifications (only config screen toasts remain)
- **PackUpdater JAR auto-update** — Removed automatic JAR version management on startup

### Added
- **OPTIONS menu button** — New "PackUpdater" button in the Minecraft Options menu (Esc → Options) for quick config access
- **Auto-extract on startup** — .apepack files found on startup are now automatically extracted without player input
- **Auto-restart on success** — After a successful .apepack install, the game automatically restarts via the built-in relauncher
- **Install failure handling** — If an .apepack install fails, the config screen shows the error with a "Report Bug on GitHub" button
- **3-tab config screen** — Simplified config screen with Apepacks, Generate, and Settings tabs (Updates tab removed)

### Fixed
- Startup .apepack installation is now fully automatic: extract → update version → restart
- No player input required for successful updates
- Failed updates display clear error messages with bug report link

---

## Beta Versions (0.0.27 – 0.0.38)

These versions introduced the tabbed config UI, game relauncher, and all core features that made up the 1.0.0 release.

### [0.0.38] - Beta - 2026-11-08
- RAM allocation preservation on game relaunch (reads `minecraftinstance.json`)
- Custom JVM args support from instance config

### [0.0.37] - Beta - 2026-11-08
- Fixed main menu background missing after relaunch
- Replaced all Unicode emoji with plain text (Minecraft font doesn't support emoji)

### [0.0.36] - Beta - 2026-11-08
- Fixed game relaunch launching into Minecraft demo mode

### [0.0.35] - Beta - 2026-11-08
- Fixed `-cp requires class path specification` error on relaunch

### [0.0.34] - Beta - 2026-11-08
- Fixed cross-platform library issues on Windows relaunch

### [0.0.33] - Beta - 2026-11-08
- Auto-restart Game feature with new JVM process spawning

### [0.0.32] - Beta - 2026-11-08
- Fixed pack button showing "update available" when already on latest version

### [0.0.31] - Beta - 2026-11-08
- Fixed modpack version not updating after .apepack install
- Fixed dual installations on startup
- Added PackUpdater button on main menu

### [0.0.30] - Beta - 2026-11-08
- Fixed startup crash from abstract `RenderGuiLayerEvent` registration

### [0.0.29] - Beta - 2026-11-08
- Pack version label on main menu bottom-left
- System toast notification for successful updates

### [0.0.28] - Beta - 2026-11-08
- File picker defaults to apepacks/ folder
- Removed character limit on file picker path field
- Removed empty blue boxes from Apepacks tab

### [0.0.27] - Beta - 2026-11-08
- **Tabbed config screen overhaul** — 4 clean tabs: Updates, Apepacks, Generate, Settings
- Open Apepacks folder button
- Apepacks management tab with file list

---

## Alpha Versions (0.0.1 – 0.0.26)

These were early development versions that established the core .apepack system, file picker, import progress screen, and download verification.

### [0.0.26] - Alpha - 2026-11-08
- Check ApePack button for pack version comparison
- Auto-update PackUpdater JAR management

### [0.0.25] - Alpha - 2026-11-08
- Import screen exit blocking during extraction
- Per-file extraction logging

### [0.0.24] - Alpha - 2026-11-08
- Sequential download → scan flow with CountDownLatch
- Main menu notification overlay during updates

### [0.0.23] - Alpha - 2026-11-07
- Launch-time .apepack version check against GitHub releases

### [0.0.22] - Alpha - 2026-11-07
- Fixed download button clickable multiple times

### [0.0.21] - Alpha - 2026-11-07
- Download completion feedback with progress bar
- File integrity verification (SHA-256 + size check)
- Button blocking during download

### [0.0.20] - Alpha - 2026-11-07
- GitHub API proper Accept header fix
- Optional GitHub token field for higher rate limits

### [0.0.19] - Alpha - 2026-11-07
- Config screen resize handling for resolution changes

### [0.0.18] - Alpha - 2026-11-07
- Progress bar rendering fix
- Input blocking during import

### [0.0.17] - Alpha - 2026-11-07
- SHA-256 based .apepack processing tracking
- Default config with modpack version v0.0

### [0.0.16] - Alpha - 2026-11-07
- Global download state manager
- Styled config screen redesign

### [0.0.15] - Alpha - 2026-11-07
- apepacks/ folder system for downloaded updates
- Extraction progress screen on startup

### [0.0.14] - Alpha - 2026-11-07
- Unlimited character limit for GitHub repo text box

### [0.0.13] - Alpha - 2026-11-07
- Toast notification system in config screen
- Short GitHub repo name support (user/repo)
- File picker search field

### [0.0.12] - Alpha - 2026-11-07
- Exclude PackUpdater from generated .apepack files

### [0.0.11] - Alpha - 2026-11-07
- File picker scroll bug fix
- Mod version comparison during .apepack install

### [0.0.10] - Alpha - 2026-11-07
- File picker scroll position fix
- Desktop button path fix
- Config screen GUI scale fix

### [0.0.9] - Alpha - 2026-11-07
- Mouse wheel scrolling in file picker
- Interactive scrollbar with click-and-drag

### [0.0.8] - Alpha - 2026-11-07
- Import progress screen black screen fix

### [0.0.7] - Alpha - 2026-11-07
- File picker visual bug fixes (file names, clipping)

### [0.0.6] - Alpha - 2026-11-07
- Native Minecraft file picker window

### [0.0.5] - Alpha - 2026-11-07
- Fixed HeadlessException crash on file browse

### [0.0.4] - Alpha - 2026-11-07
- Import progress screen with progress bar
- Browse .apepack button

### [0.0.3] - Alpha - 2026-11-07
- Drag-and-drop support for .apepack files

### [0.0.2] - Alpha - 2026-11-07
- Config screen crash fix (blur conflict)

### [0.0.1] - Alpha - 2026-11-07
- Initial release with .apepack generator and loader
- User settings protection
- Auto-scan for .apepack files on startup
