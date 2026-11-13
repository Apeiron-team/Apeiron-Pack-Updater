# PackUpdater Changelog

All notable changes to the Apeiron PackUpdater mod will be documented in this file.

---


## [1.3.11-26.1.2] - Config Screen Tab Reorganization - 2026-11-13

### Changed
- **Config screen reorganized** — Restructured from 3 tabs (Apepacks, Generate, Settings) to 3 clean tabs (Info, Apepacks, Settings)
- **New Info tab** — Shows modpack name, modpack version, mod version, "Check for Update" button, and "View Changelog" button that fetches release notes from GitHub
- **New Apepacks tab** — Merged Generate tab content with Open Apepacks and Browse buttons: pack name field, pack version field, generate button, open apepacks folder button, and browse .apepack button — all in one place
- **Settings tab cleaned** — Removed duplicate pack version display; kept repo, token, channel, self-update, and save settings
- **Changelog feature** — Info tab can load and display the last 5 GitHub release notes with scroll support
- **Removed old Apepacks file list** — The downloaded .apepack file list with scroll was removed from the config screen since .apepack files are auto-extracted and deleted on startup

### Changed
- **PackUpdaterConfigScreen.java** — Complete tab reorganization: new `buildInfoTab()`, `renderInfoTab()`, `loadChangelog()` methods; merged Generate into Apepacks tab; updated `TabId` enum, tab buttons, render switch, and mouse scroll handler
- **build.gradle** — Version bumped to `1.3.11-26.1.2`
- **ConfigManager.java** — `CURRENT_VERSION` synced to `1.3.11-26.1.2`

---


## [1.3.10-26.1.2] - Config Screen Render Fix - 2026-11-13

### Fixed
- **Gray config screen bug** — Config screen was completely gray because the opaque background was drawn after widget rendering, covering all buttons, text fields, and tab content. Fixed by drawing the background before `super.extractRenderState()` so widgets render on top

### Changed
- **PackUpdaterConfigScreen.java** — Render order fixed in `extractRenderState()`: background fill draws first, then `super.extractRenderState()` renders widgets on top
- **build.gradle** — Version bumped to `1.3.10-26.1.2`
- **ConfigManager.java** — `CURRENT_VERSION` synced to `1.3.10-26.1.2`

---


## [1.3.9-26.1.2] - Mod Self-Update Feature - 2026-11-13

### Added
- **Mod self-update system** — New opt-in feature in the Settings tab lets the player enable automatic PackUpdater mod self-updates. When enabled, a "Check for Mod Update" button appears that queries GitHub for the latest release, downloads the new JAR, replaces all old PackUpdater JARs in the mods/ folder, and deletes the old versions automatically
- **Self-update toggle** — "Self-update: ON/OFF" button in Settings tab. Disabled by default — the player must explicitly enable it. The preference persists across sessions
- **Download progress display** — Progress bar and status text shown in the Settings tab during mod update download
- **Version comparison** — Smart version comparison that handles the MC version suffix (e.g., "1.3.9-26.1.2" vs "1.3.8-26.1.2")
- **Old JAR cleanup** — All existing PackUpdater JARs are automatically removed when a new version is installed, preventing duplicate mod conflicts

### Changed
- **ConfigManager.java** — Added `selfUpdateEnabled` boolean field with load/save support
- **PackUpdaterConfigScreen.java** — Added self-update toggle, check button, progress display, `handleCheckForModUpdate()` method, and `compareVersions()` helper
- **build.gradle** — Version bumped to `1.3.9-26.1.2`
- **ConfigManager.java** — `CURRENT_VERSION` synced to `1.3.9-26.1.2`

---


## [1.3.8-26.1.2] - Bug Fixes and New Features - 2026-11-13

### Fixed
- **Player data loss on restart** — Game relauncher now reads the current player's session data (username, UUID, access token, session ID) and passes them to the new JVM process, so your account is preserved after restart
- **Default instance loads wrong modpack** — Relauncher now reads the instance name from `minecraftinstance.json` and passes it as `${instance_name}` so the correct CurseForge instance loads
- **Old mod versions not cleaned up** — After installing an .apepack, old mod JARs that are no longer in the new pack are automatically removed (e.g., v1.1.7 mods are deleted when updating to v1.1.8)

### Added
- **Changelog mechanism** — When generating an .apepack, the mod compares against the previous version's manifest and generates a changelog (`config/apepack_changelog.txt`) showing added/updated/removed mods and configs. The changelog is excluded from compression.
- **Auto-set modpack version** — Generating an .apepack now automatically sets the modpack version and name in the PackUpdater config, so you don't have to manually update them
- **Version naming with game version** — All JARs now include the Minecraft version in their name (e.g., `packupdater-1.3.8-26.1.2.jar`) to distinguish between different MC version builds

### Changed
- **GameRelauncher.java** — Added `Minecraft` and `Session` imports; `resolveGameVariable()` now reads player session data; added `readInstanceName()` method
- **ApePackLoaderV2.java** — Added `cleanupOldModVersions()` method that removes old mod versions after installation
- **ApePackGeneratorV2.java** — Added changelog generation with `generateChangelog()` and `saveCurrentManifest()` methods; auto-sets `ConfigManager.modpackVersion` and `modpackName` on generation
- **ConfigManager.java** — Version bumped to `1.3.8-26.1.2`
- **build.gradle** — Version bumped to `1.3.8-26.1.2`

---


## [1.3.7] - Bug Fixes for NeoForge 26.1.2 - 2026-11-13

### Fixed
- **Downloaded unrelated modpack** — Default GitHub repo was hardcoded to `Apeiron-team/Apeiron-Vanilla-plus-v1`, causing the mod to download the wrong modpack in other instances. Default repo is now empty — user must configure the correct modpack repo in Settings tab
- **Config screen buttons** — "Check for Update" and "Download Update" now show a toast notification when no repo is configured instead of making a broken API request
- **Blurry background text** — Config screen background changed from semi-transparent to fully opaque so parent screen content doesn't show through as blur
- **Blurry toast notifications** — Toast notifications now use fully opaque colors for crisp, clear text

### Changed
- **ConfigManager.java** — Default `repo` changed from `"Apeiron-team/Apeiron-Vanilla-plus-v1"` to `""` (empty)
- **PackUpdaterMod.java** — `checkAndDownloadUpdate()` now skips the GitHub check if `repo` is empty
- **PackUpdaterConfigScreen.java** — Opaque background (`0xFF101010`), `super.extractRenderState()` moved to the start of render method

### Notes
- This version targets NeoForge 26.1.2 and requires Java 25

---


## [1.3.1] - Dead Code Cleanup - 2026-11-10

### Removed
- **ApePackGenerator.java** (499 lines) — v1 generator, fully replaced by ApePackGeneratorV2
- **ApePackImportScreen.java** (424 lines) — never instantiated; preload flow auto-restarts, config screen uses diff screen
- **UpdateSplashScreen.java** (~200 lines) — splash screen replaced by preload flow in v1.2.7
- **UpdateSplashScreenInterceptor.java** (~50 lines) — interceptor for removed splash screen
- **Unused fields** — Removed `pendingApepackFile`, `latestChangelog`, `latestVersion` from PackUpdaterMod
- **Unused config fields** — Removed `lastReleaseUrl`, `downloadResumeUrl`, `downloadResumeOffset` from ConfigManager
- **Unused methods** — Removed `scanAndInstall()` from ApePackLoader and ApePackLoaderV2
- **Deprecated constants** — Removed `COMPRESSION_ZSTD`, `ZSTD_COMPRESSION_LEVEL`, `GZIP_COMPRESSION_LEVEL` from ApePackV2
- **Unused helpers** — Removed `getFileName()`, `formatSize()` from ApePackFilePicker

### Notes
- Net reduction: ~1,850 lines of dead code removed
- All active features preserved: ApePack v2 generation/loading, preload auto-update, diff screen, file picker, game relauncher, options menu button

---

## [1.3.0] - Auto-Restart After Update - 2026-11-10

### Changed
- **Automatic restart after extraction** — The preload update flow now automatically restarts the game after extracting an .apepack2 file. No more diff screen on startup, no more manual "Restart to Apply" click. The entire update is fully automatic: check GitHub → download → extract → restart → new mods load
- **Startup flow** — When the mod finds a newer .apepack2 on GitHub, it downloads, extracts, cleans up the .apepack file, and restarts the game without any user interaction
- **Diff screen preserved for manual installs** — The comparison screen still works when manually browsing and installing an .apepack2 file from the config screen

### Fixed
- **Restart sequence** — Game now properly restarts after extraction during preload using `GameRelauncher.relaunch()` with a short delay to let FML finish initializing

---

## [1.2.7] - Preload Update Sequence - 2026-11-10

### Changed
- **Entire update flow runs during FML preload** — Replaced async background threads with `event.enqueueWork()` in `FMLClientSetupEvent`. The mod now checks GitHub, downloads the .apepack2, and extracts it all during NeoForge's preloading sequence with the FML progress bar visible
- **Simplified startup flow** — Single synchronous task: Check GitHub → Download if newer → Extract → Show diff screen. No more CountDownLatch coordination between threads
- **Removed UpdateSplashScreenInterceptor** — No longer needed since the entire update happens during preload before the main menu appears
- **Diff screen after preload** — After extraction completes during preload, the diff screen is scheduled to open via `Minecraft.getInstance().execute()` so the player can review changes before restarting

### Fixed
- **Diff screen file handling** — ApePackDiffScreen now handles the case where the .apepack file was already deleted (uses config version as fallback)
- **Back to Main Menu button** — Only tries to delete the .apepack if it still exists, preventing errors when the file was already cleaned up

### Removed
- **UpdateSplashScreenInterceptor.java** — Replaced by the preload-based approach
- **Async background threads** — All update logic now runs synchronously during FML preload

---

## [1.2.6] - Preload Update Check - 2026-11-10

### Added
- **Update splash screen** — Custom pre-main-menu screen that appears when an update is available. Intercepts the vanilla TitleScreen and shows version info, changelog summary, and "Install Update" / "Skip for Now" buttons
- **UpdateSplashScreen.java** — Full-screen update notification with installation progress bar, file counts (new/skipped/protected), and real-time feedback
- **UpdateSplashScreenInterceptor.java** — NeoForge ScreenEvent.Opening hook that replaces the main menu with the splash screen when a pending .apepack2 file exists
- **Changelog display** — Fetches release notes from GitHub and displays them on the splash screen so players know what's changing before installing
- **Skip protection** — Once a user skips an update, the splash screen won't show again for that .apepack file

### Changed
- **Startup flow** — When the mod downloads an update from GitHub, it now stores the .apepack file path, changelog, and version for the splash screen instead of auto-extracting immediately
- **Scan thread** — Skips auto-extraction for downloaded .apepack files, letting the splash screen handle installation
- **Pending update tracking** — Added `pendingApepackFile`, `latestChangelog`, and `latestVersion` atomic references in PackUpdaterMod for splash screen data sharing

---

## [1.2.3] - Fix On-Launch Auto Update Check - 2026-11-09

### Fixed
- **On-launch auto update check not working** — Added GitHub release check on game startup. The mod now queries the configured GitHub repo for the latest `.apepack2` release, compares it with the installed pack version, and automatically downloads any newer update before scanning for `.apepack` files to extract
- **Startup flow** — GitHub check thread runs first, then the scan thread waits (up to 30 seconds) for the download to complete before scanning `mods/` and `apepacks/` directories

### Added
- **`extractVersionFromApePackName()`** helper method in PackUpdaterMod for parsing version from `.apepack2` asset filenames
- **`CountDownLatch` coordination** between GitHub check and scan threads to ensure proper ordering

---

## [1.2.2] - Diff Screen Fix & Update Flow Overhaul - 2026-11-09

### Fixed
- **Diff screen blank content** — Complete rewrite of rendering using a flat item list with proper scroll offset clamping. The content area now correctly displays all mods, configs, and summary information with working scroll
- **Update flow restructured** — The entire update flow now works correctly: download → extract immediately → show comparison screen → user chooses to restart or go to main menu

### Changed
- **Download flow** — After downloading an .apepack2, the mod extracts it immediately in the background, then shows the comparison screen
- **Startup flow** — On game launch, if an unprocessed .apepack is found, it extracts silently then shows the comparison screen instead of auto-restarting
- **Browse .apepack flow** — Manual .apepack installation now extracts first, then shows the comparison screen
- **Diff screen buttons** — "Update Now (Restart)" renamed to "Restart to Apply" since extraction is already done

---

## [1.2.1] - Startup Extraction Fix - 2026-11-08

### Fixed
- **3-restart bug** — .apepack files now extract immediately on startup in the background and auto-restart the game in one go. No more import screen on startup, no more waiting for user input. The flow is now: download → extract+restart → mods loaded (2 restarts total instead of 3)
- **Open Apepacks folder path** — Fixed `instance\.\apepacks` bug by normalizing `INSTANCE_ROOT` to an absolute path at initialization, so all derived paths (including `apepacks/`) resolve correctly

### Changed
- Removed unused `installSilently()` method — startup extraction now uses the same inline approach
- Removed unused `Screen` import from PackUpdaterMod

---

## [1.2.0] - Major Feature Update - 2026-11-08

### Added
- **Mod list comparison screen** — New `ApePackDiffScreen` shows added, updated, and removed mods before installing an .apepack. Player can choose "Update Now (Restart)" or "Back to Main Menu"
- **Auto-update notification on launch** — When an .apepack is found on startup, the import progress screen is shown automatically instead of silent background install. If the game is in-session, it installs silently in the background
- **Download statistics** — Progress bar now shows download speed (KB/s), ETA, and percentage during .apepack downloads
- **Download pause/resume** — Downloads can be paused and resumed from the config screen
- **Backup before install** — Mods folder is backed up before extraction; on failure, the backup is restored automatically
- **Changelog display** — Fetches and displays the release changelog from GitHub before downloading

### Fixed
- **Config persistence** — GitHub repo URL and token now persist across config screen close/reopen. Values are always loaded from `ConfigManager` instead of `lastReleaseUrl`
- **Open Apepacks folder path** — Fixed `instance\.\apepacks` bug by using `toAbsolutePath().normalize()`
- **.apepack2 download check** — Update checker now looks for both `.apepack2` and `.apepack` assets on GitHub releases
- **Clear Processed button removed** — Removed the fundamentally useless "Clear Processed" button; the mod now automatically deletes .apepack files after successful install
- **Version check from GitHub** — Compares `.apepack2` version from GitHub release against local `modpackVersion` in config

### Changed
- **Default repo** — Changed from `Apeiron-team/Apeiron-Pack-Updater` to `Apeiron-team/Apeiron-Vanilla-plus-v1` (the modpack repo)
- **Browse .apepack flow** — Now shows the diff comparison screen before installing, letting the player review changes first

---

## [1.1.9] - ApePack v2 File Extension Change - 2026-11-08

### Changed
- **v2 file extension** — ApePack v2 files now use the `.apepack2` extension instead of `.apepack`. This clearly distinguishes v2 files (XZ/LZMA2 compression, chunk-based storage) from v1 files (standard ZIP).
- **Config screen** — Generate button now creates `.apepack2` files with updated UI text.
- **File picker** — Accepts both `.apepack` (v1) and `.apepack2` (v2) files for full backward compatibility.
- **Startup scanner** — Scans for both `.apepack` and `.apepack2` files in `mods/` and `apepacks/` directories.
- **README** — Updated all documentation to reflect `.apepack2` extension for v2 format.

### Fixed
- **File format confusion** — v1 and v2 files now have distinct extensions, making it clear which format is which.

### Notes
- Full backward compatibility: the mod still reads and installs `.apepack` (v1) files.
- This is a testing release — not published to GitHub yet.

---

## [1.1.8] - ApePack v2 Auto-Restart Fix - 2026-11-08

### Fixed
- **Game doesn't restart after extraction** — The `onComplete(true, null)` callback was never called after a successful v2 `.apepack` extraction in `installV2Pack()`. This meant `installResult[0]` in `PackUpdaterMod` stayed `false`, so the game relauncher was never triggered. Now the completion callback fires after all files are extracted, allowing the auto-restart to work as intended.

### Notes
- This is a testing release — not published to GitHub yet.

---

## [1.1.7] - ApePack v2 Generation Lock and Progress Feedback - 2026-11-08

### Fixed
- **Generation restarts on button click** — Added a `generating` lock flag that prevents the "Generate .apepack" button from being clicked multiple times. Each click no longer spawns a new thread that conflicts with the previous run's temp files.
- **Stale temp directory conflicts** — Before starting generation, any leftover `apepack_chunks_tmp` directory from a previous failed run is cleaned up to prevent file conflicts.

### Added
- **Real-time progress feedback** — Config screen now shows live status updates during generation: "Scanning mods...", "Computing file hashes...", "Compressing chunk_001...", "Building archive...", etc.
- **Button disabling during generation** — Generate button is disabled and shows "Generating..." text while compression is in progress. Input fields are also locked to prevent changes mid-generation.
- **Progress callback system** — `ApePackGeneratorV2.generate()` now accepts an optional progress callback that reports each major step to the UI.

### Changed
- **Version** — Bumped from 1.1.6 to 1.1.7

### Notes
- This is a testing release — not published to GitHub yet.

---

## [1.1.6] - ApePack v2 Temp File Retention Fix - 2026-11-08

### Fixed
- **Temp files deleted too early** — Each chunk's uncompressed `.tmp` file was deleted immediately after XZ compression, causing `NoSuchFileException` when subsequent chunks tried to access shared temp directory resources. All temp files (`.tmp` and `.xz`) now remain until the entire .apepack archive is fully built.
- **Premature chunk file deletion** — Compressed `.xz` chunk files were deleted one-by-one as they were written to the archive. Now all chunk files stay intact until the archive is complete, then a single cleanup pass removes everything.

### Changed
- **Cleanup timing** — Moved all temp file cleanup to after the ZIP archive is closed and verified, with logging to confirm cleanup success.
- **createChunkFile()** — No longer deletes the temporary uncompressed file; cleanup is centralized in `generate()`.

### Notes
- This is a testing release — not published to GitHub yet.

---

## [1.1.4] - ApePack v2 Runtime Fix - 2026-11-08

### Fixed
- **XZ library runtime error** — Added `jar` task in `build.gradle` to bundle the XZ compression library (`org.tukaani:xz:1.10`) and commons-compress into the mod JAR at runtime, so ApePack v2 generation works without `NoClassDefFoundError`.

### Changed
- **JAR packaging** — Third-party libraries are now included inside the mod JAR with `duplicatesStrategy = 'exclude'` and exclusions for signature files and module-info.

### Notes
- This is a testing release — not published to GitHub yet.

---

## [1.1.5] - ApePack v2 Temp File Path Fix - 2026-11-08

### Fixed
- **NoSuchFileException during chunk compression** — Temp chunk files (`.tmp`) were created with relative paths that didn't resolve correctly during in-game execution. All temp file paths now use absolute paths (`toAbsolutePath()`) to ensure the compression step can find the files it created.
- **Missing temp file validation** — Added existence check before compressing each chunk, with a clear error message if the file disappears.
- **Duplicate log messages** — Removed duplicate "Using temp directory" log output.

### Changed
- **Temp file path resolution** — All paths in `createChunkFile()` now use absolute paths for reading and writing.
- **Debug logging** — Added temp file size and path logging before compression for easier debugging.

### Notes
- This is a testing release — not published to GitHub yet.

---

## [1.1.3] - ApePack v2 Extreme Compression - 2026-11-08

### Fixed
- **Poor GZIP compression ratio** — GZIP only achieved 8% compression (160 MB → 147 MB) because mod JAR files are already ZIP-compressed. Switched to XZ/LZMA2 compression (preset 9) which achieves 60-80% better ratios for already-compressed files.

### Changed
- **Compression algorithm** — Replaced GZIP with XZ/LZMA2 via `org.tukaani:xz:1.10` dependency. Expected compression ratio: 160 MB → ~20-40 MB (75-87% reduction).
- **Chunk file format** — v2 chunks now use `.xz` extension instead of `.gz`.
- **Backward compatibility** — Loader automatically detects compression type and falls back to GZIP for older v2 files.
- **Larger buffer** — XZ compression uses 64KB buffer for better performance.

### Notes
- XZ/LZMA2 compression is slower than GZIP but produces much smaller files.
- Extraction speed is maintained through parallel thread pools.
- This is a testing release — not published to GitHub yet.

---

## [1.1.2] - ApePack v2 Compression Fix - 2026-11-08

### Fixed
- **Zstd NoClassDefFoundError** — Replaced Zstd compression with Java's built-in GZIP compression, eliminating the external native library dependency that caused `NoClassDefFoundError` at runtime
- **Generation output directory** — Generated .apepack files now save in the instance folder instead of a random temp directory in AppData
- **Chunk file handling** — Chunk files are now created in the instance folder during generation and properly cleaned up after the archive is built

### Changed
- **Compression** — Switched from Zstd (level 22) to GZIP (level 9) — still lossless, no external dependencies required
- **Version** — Bumped from 1.1.1 to 1.1.2 (patch release for v2 compression fix)

---

## [1.1.1] - ApePack v2 Patch - 2026-11-08

### Fixed
- **Zstd compression not working** — Chunk files now use Java's temp directory instead of the current working directory, fixing in-game generation
- **Format labeling** — v2 .apepack files are now labeled as "apepackv2" in apepack.json for clear format identification
- **Script count** — Fixed scriptCount not being returned from scanKubeJS() method
- **Compression progress** — Added logging for chunk compression progress and ratio

### Changed
- **Version** — Bumped from 1.1.0 to 1.1.1 (patch release for v2 fixes)

---

## [1.1.0] - ApePack v2 Beta - 2026-11-08

### Added
- **ApePack v2 format** — New generation and loading system with Zstd compression (level 22) for smaller archive sizes
- **Parallel extraction** — Multi-threaded file extraction using thread pools for faster installation
- **Archive integrity verification** — SHA-256 checksum of the entire archive plus per-file verification
- **Dependency detection** — Automatically detects mod dependencies from mod metadata
- **Config conflict resolution** — Strategies for handling config conflicts: overwrite, merge, skip, backup
- **Resource pack support** — Includes resource packs in generated .apepack files
- **Data pack support** — Includes data packs in generated .apepack files
- **Chunk-based storage** — Files are stored in compressed chunks for efficient extraction
- **Backward compatibility** — v2 loader can still read and install v1 .apepack files

### Changed
- **Zstd compression library** — Added `zstd-jni:1.5.7-22` dependency for maximum compression ratio
- **ApePackLoader helper methods** — Changed package-private visibility for v2 access

### Notes
- ApePack v2 is still being refined — this release is for testing only
- World template support is not included (reserved for future skyblock modpack fork)
- v2 files are fully compatible with v1 files (backward compatible)

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
