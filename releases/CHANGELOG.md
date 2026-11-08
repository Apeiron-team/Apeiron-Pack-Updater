# PackUpdater Changelog

All notable changes to the Apeiron PackUpdater mod will be documented in this file.

## [0.0.26] - 2026-11-08

### Added
- **Check ApePack button** — Replaced the read-only mod version button with a "📦 Pack" button that shows the installed modpack version from config. Clicking it checks GitHub for a newer .apepack release and shows a toast notification with the result
- **Auto-update PackUpdater JAR** — On startup, the mod now scans the instance's `mods/` folder for all PackUpdater JARs, keeps only the latest version, and removes older ones. If the currently running JAR is newer than what's in `mods/`, it copies itself there

### Changed
- Config screen now displays the modpack version (from `modpackVersion` in config) instead of the mod version on the right-side button
- PackUpdater JAR management is fully automatic — no more duplicate or outdated PackUpdater JARs in the mods folder

## [0.0.25] - 2026-11-08

### Fixed
- **Import screen allows early exit** — Added `CountDownLatch` that blocks ALL exit attempts (ESC, close button, mouse clicks, keyboard input) until the background extraction thread fully finishes. The import thread is now non-daemon so it won't be killed if the game tries to exit
- **Files not extracted to correct locations** — Added detailed logging for each file extraction (shows source file name and absolute target path) so extraction failures are visible in logs. Added null check for `targetPath.getParent()` before creating directories
- **Missing extraction feedback** — Import screen now shows "⚠ Do not close the game until extraction is complete" warning during extraction. Added `shouldClose()` override to prevent Minecraft from auto-closing the screen mid-extraction

### Changed
- Import thread logs the extraction target directory at startup for debugging
- Each installed file logs its absolute path after extraction
- Missing zip entries log both the expected path and the file name
- Import thread prints summary of installed/skipped/protected counts on completion

## [0.0.24] - 2026-11-08

### Fixed
- **Startup install cancels itself** — The download thread and scan thread now run in sequence instead of in parallel. The download thread completes first (using a `CountDownLatch`), then the scan thread processes any newly downloaded .apepack files. Previously both threads ran simultaneously, so the scan finished before the download saved the file, causing the update to be ignored
- **.apepack extraction from apepacks/ folder not working** — The scan thread now properly searches both `mods/` and `apepacks/` directories after the download completes, sorting apepacks/ files by modification time (oldest first) to ensure the most recent download is processed. The import progress screen is shown on the main thread for each unprocessed .apepack
- **No main menu notification during update** — Added `MainMenuNotification` screen that shows on the title screen during download and installation. Displays download progress with a progress bar, installation progress with file counts, and a completion summary with restart prompt. Auto-dismisses after completion

### Added
- **`MainMenuNotification.java`** — New screen overlay for main menu notifications during update download and installation. Shows emoji icons (📥 downloading, 📦 installing, ✅ complete, ❌ error), color-coded progress bars, and auto-dismiss timers for completion/error states
- **`CountDownLatch` synchronization** — Download and scan threads are now synchronized so the scan always waits for the download to complete before running, preventing race conditions
- **Download progress reporting** — The download notification updates in real-time as the .apepack is downloaded from GitHub, showing percentage complete

### Changed
- Startup flow completely restructured: download thread runs first with a 120-second timeout, then scan thread processes all .apepack files
- `checkAndDownloadApePackUpdate()` now returns a boolean to indicate whether a download occurred
- Scan thread waits up to 120 seconds for the download to complete before proceeding
- Download notification is automatically closed when transitioning to the import screen or when no .apepack files need processing

## [0.0.23] - 2026-11-07

### Added
- **Launch-time .apepack version check** — On game startup, the mod now checks the GitHub repo for the latest .apepack release and compares it with the highest version found in the `apepacks/` folder. It reads the `apepack.json` inside each local .apepack to extract the pack version, then only downloads if the GitHub version is newer or no .apepack exists locally
- **`extractApePackVersion()` method** — Opens an .apepack ZIP file and reads the `packVersion` field from `apepack.json` without extracting the entire archive
- **`getLatestApePackVersion()` method** — Queries GitHub releases API, filters by channel (stable/beta/nightly), and returns the version tag of the first release that contains an .apepack asset
- **`compareVersions()` method** — Semantic version comparison that splits by `.`, `+`, `-`, `_` and compares segments numerically when possible
- **Modpack version fallback** — If no .apepack files exist in the apepacks/ folder, the mod falls back to comparing against the `modpackVersion` stored in the config (updated when an .apepack is installed)

### Changed
- Replaced `isUpdateAvailable()` + `prepareUpdate()` with `checkAndDownloadApePackUpdate()` — the new method handles the entire flow: scan local versions, fetch GitHub version, compare, and download only if needed
- Removed unused `isUpdateAvailable()`, `prepareUpdate()`, `findApePackUrl()`, and `downloadFile()` methods to clean up the codebase
- Startup update check now logs detailed version comparison info to the console for debugging

## [0.0.22] - 2026-11-07

### Fixed
- **Download button clickable multiple times** — The "Download Update" button was not included in `blockButtons()`, so it remained clickable even while a download was in progress. Now the download button is tracked as a separate field and properly disabled during download and verification, preventing concurrent download attempts
- **Download button state not reset on completion** — After a successful download, the `downloadButton` reference is now cleared so the next check creates a fresh button instead of reusing the old disabled one

## [0.0.21] - 2026-11-07

### Fixed
- **Download never completes visually** — The download progress panel now stays visible through all stages: Checking → Downloading → Verifying → Complete/Error. Previously the panel disappeared as soon as the download finished, leaving no visual feedback that the process had completed
- **No completion notification** — After a successful download and verification, a clear toast notification appears ("✅ Update downloaded and verified! Restart to install.") and the progress panel shows a green completion bar for 3 seconds before resetting
- **No button blocking during download** — All interactive buttons (Check for Update, Generate .apepack, Browse .apepack, channel selector, text fields) are now disabled during download and verification, preventing accidental double-clicks or concurrent downloads
- **No file integrity verification** — After downloading an .apepack from GitHub, the mod now verifies the downloaded file's size matches the GitHub release asset's reported size. If the sizes don't match, the corrupted file is deleted and a clear error message is shown

### Added
- **`VERIFYING` download status** — New intermediate status between DOWNLOADING and DONE that shows "🔐 Verifying download integrity..." with an orange progress bar
- **`ApePackAssetInfo` class** — Holds GitHub release asset metadata (download URL, file size, name) for verification
- **`findApePackAssetStatic()` method** — Fetches full asset metadata from GitHub API instead of just the download URL
- **SHA-256 hash computation** — Computes SHA-256 hash of the downloaded file during transfer for future verification use
- **`blockButtons()` method** — Centralized method to enable/disable all interactive widgets in the config screen
- **`downloading` flag** — Atomic boolean in DownloadManager to prevent concurrent download attempts
- **Color-coded progress bar** — Progress bar fill color now matches the current status: blue (downloading), orange (verifying), green (complete), red (error)
- **Error message display** — If a download fails, the error message is shown in the progress panel below the progress bar

### Changed
- Progress panel now renders for all non-IDLE statuses (CHECKING, DOWNLOADING, VERIFYING, DONE, ERROR)
- Download progress ranges from 0-85% during transfer, 85-95% during verification, 100% on completion
- Config screen `init()` now resets VERIFYING status from previous sessions
- `handleCheckForUpdate()` and `handleDownloadUpdate()` now check the `downloading` flag before starting

## [0.0.20] - 2026-11-07

### Fixed
- **GitHub API rate limit error** — All GitHub API calls now use the proper `Accept: application/vnd.github.v3+json` header required by GitHub's API. Without this header, GitHub may return HTML or reject the request, causing 403 errors
- **API rate limit exceeded (403)** — Added optional GitHub personal access token field in the config screen. With a token, the API rate limit increases from 60 to 5,000 requests per hour. Token is saved in the config and used for all API calls
- **Unclear error messages** — When a 403 rate limit error occurs, the mod now shows a clear message explaining the issue and directing the user to add a GitHub token at https://github.com/settings/tokens

### Added
- **GitHub Token field** — New input field in the config screen under the GitHub Repo field. Enter a personal access token to increase API rate limits. The token is saved in `config/globalpackupdater.json` and used for all GitHub API requests
- **`ConfigManager.setupGitHubConnection()`** — Centralized method that sets up all required headers for GitHub API calls (Accept, User-Agent, and optional Authorization). All API calls now use this method instead of setting headers individually

### Changed
- Config screen layout adjusted to accommodate the new GitHub Token field
- The `resize()` method now preserves the token field value during window resizes
- Config save/load now includes the `githubToken` field
- All GitHub API calls in `PackUpdaterMod.java` and `PackUpdaterConfigScreen.java` now use `ConfigManager.setupGitHubConnection()` for consistent header setup

## [0.0.19] - 2026-11-07

### Fixed
- **Config screen resets on resolution change** — Added `resize()` override that rebuilds all widgets (buttons, text fields) whenever the Minecraft window resolution changes. This fixes the bug where toggling between windowed and fullscreen, or resizing the window, would leave buttons and text fields at their old positions while the rendered panels moved to the correct position

### Changed
- Extracted widget creation from `init()` into a reusable `buildWidgets()` method so both initial creation and resize use the same layout logic
- The `resize()` method preserves user input in all text fields (GitHub repo URL, pack name, pack version) so typed text is not lost when the window is resized
- Channel check thread now only starts once on the first `init()` call, preventing duplicate threads on resize

## [0.0.18] - 2026-11-07

### Fixed
- **Progress bar not rendering** — Replaced broken `blitSprite` calls with proper `fill()` based progress bar that draws a dark gray track with a blue fill bar and visible border. The previous `blitSprite` approach used the same sprite texture for both background and fill without proper UV coordinates, resulting in nothing being drawn
- **All button presses blocked during import** — Added input blocking during .apepack extraction: mouse clicks, mouse scroll, keyboard input, and ESC key are all ignored while the import is running. The screen cannot be closed during import either

### Changed
- Progress bar now uses a simple but reliable `fill()` approach: dark gray background (0x333333), light gray border (0x666666), and blue fill (0x3399FF) that scales with the import progress
- Import screen overrides `mouseClicked`, `mouseReleased`, `mouseScrolled`, `charTyped`, `keyPressed`, and `onClose` to block all input until `importComplete` is true
- ESC key is explicitly blocked during import to prevent accidentally closing the screen

## [0.0.17] - 2026-11-07

### Fixed
- **Multiple .apepack installations** — Added SHA-256 based tracking of processed .apepack files so the same update is never installed twice on subsequent game startups. Processed hashes are stored in `config/processed_apepacks.json`
- **No installation completion notification** — The import progress screen now shows a clear completion message and marks the .apepack as processed, deletes the file, and updates the modpack version in the config
- **Progress bar rendering** — Replaced custom `fill()` based progress bar with Minecraft's native `blitSprite` using `OverlayElement` sprites (BACKGROUND_SPRITE for the bar background, FILLED_SPRITE for the progress fill)

### Changed
- **Default config creation** — Config now includes a `modpackVersion` field that defaults to `v0.0` on first run. When an .apepack is installed, the modpack version is automatically updated to match the .apepack's version
- **Startup .apepack scan** — Now skips already-processed .apepack files and only shows the import screen for new, unprocessed files
- **Import screen completion flow** — After successful import, the screen marks the .apepack as processed, updates the modpack version, deletes the .apepack file, and shows the restart prompt

## [0.0.16] - 2026-11-07

### Fixed
- **Crash when exiting config during download** — Download state now lives in a global `DownloadManager` instead of the config screen's local variables, so exiting and re-entering the config screen no longer corrupts button state or crashes the game
- **Download progress now visible globally** — A download progress panel with a real-time progress bar appears in the config screen showing percentage and MB downloaded/total, and the download continues even if you close the config screen

### Changed
- **Complete config screen redesign** — Styled panels with dark backgrounds, colored borders, section headers with emoji icons (🔄 Check for Updates, 📦 Generate .apepack, 📥 Import .apepack), and a cleaner layout
- **Toast notifications improved** — Dark themed toasts with blue highlight borders and smooth fade in/out
- **Version display** — Current mod version now shown as a read-only button next to the channel selector
- **Download flow simplified** — "Check for Update" → "Download Update" button transition is now stable and survives screen close/reopen
- **Download progress tracking** — `downloadLatestApePack` now reports byte-level progress to the global DownloadManager with content-length based percentage calculation

## [0.0.15] - 2026-11-07

### Changed
- Downloaded `.apepack` update files now save to a dedicated `apepacks/` folder in the game directory instead of the `mods/` folder, keeping the mods folder clean
- On game startup, the mod automatically scans both `mods/` and `apepacks/` directories for `.apepack` files
- When `.apepack` files are found on startup, an extraction progress screen appears showing real-time progress with a progress bar, file names, and live counts (new/skipped/protected)
- After extraction completes, the user is prompted to restart the game to apply all changes
- Toast notifications now inform the user that the update was saved to the `apepacks/` folder and will be extracted on restart

## [0.0.14] - 2026-11-07

### Fixed
- GitHub repo text box character limit increased to essentially unlimited (Integer.MAX_VALUE) — previously the default EditBox limit truncated long GitHub usernames and URLs, making it impossible to enter repos with very long owner names

## [0.0.13] - 2026-11-07

### Fixed
- URL text box now accepts short GitHub repo names (e.g., "user/repo") in addition to full URLs — previously only full GitHub URLs were accepted, making it impossible to enter short repo identifiers
- Default repo changed from placeholder to "Apeiron-team/Apeiron-Pack-Updater" for proper update checking

### Added
- Toast notification system — visual notifications appear at the top of the config screen for update check results, .apepack generation status, and errors
- Toast notifications fade in and out smoothly over 2 seconds
- Update check toast notifications trigger on both manual "Check for Update" button press and automatically after .apepack generation completes
- Better update channel checker with semantic version comparison — compares version numbers segment by segment (numeric and alphanumeric) to accurately detect newer versions
- "Download Update" button appears when an update is available, allowing one-click download of the latest .apepack
- Search field in the file picker — filter files and folders by name in real time, with result count display
- File picker now shows a filter indicator when searching is active

### Changed
- Config screen URL field hint updated to "user/repo or https://github.com/user/repo" for clarity
- Config screen instructions updated to reflect short repo name support
- Update check flow now shows a two-step process: check → download, with clear status messages at each step
- After .apepack generation, the mod automatically checks for updates if a repo is configured
- File picker layout adjusted to accommodate the search field
- Current version is now centralized in ConfigManager and synced with build.gradle

### Details
- Toast notifications use emoji icons: ✅ for success, 🔄 for updates available, ⚠️ for warnings, ❌ for errors
- Version comparison splits by `.`, `+`, `-`, `_` delimiters and compares each segment numerically when possible
- The search field filters all file types (directories, .apepack files, other files) by name
- The update channel system supports stable, beta, and nightly channels with proper prerelease detection

## [0.0.12] - 2026-11-07

### Fixed
- Generating .apepack files included the PackUpdater mod itself, causing imported packs to overwrite the user's current PackUpdater version — PackUpdater is now excluded from generated .apepack files

### Changed
- When scanning the mods/ directory during .apepack generation, any JAR file starting with "packupdater" is skipped
- The mod count in generated .apepack metadata no longer includes PackUpdater

### Details
- This ensures that importing an .apepack only updates the pack's mods, configs, and scripts without affecting the PackUpdater mod version
- Users can keep the latest PackUpdater version independent of the modpack version they're importing

## [0.0.11] - 2026-11-07

### Fixed
- File picker visual bug where scrolling to the bottom then back to the top (or vice versa) caused the first or last file to be hidden — the Y position clamping logic was using an incorrect boundary check, pushing the edge button outside the visible panel area

### Added
- Mod version comparison during .apepack installation — when importing a mod with the same base name as an already installed mod, the installer now compares versions and only installs the newer one
- Automatic removal of old mod versions — when a newer version replaces an older mod, the old JAR file is deleted from the mods folder
- Version extraction from JAR filenames — supports common naming patterns like `modname-1.2.3.jar`, `modname-mc26.1.2-0.3.0.jar`, and `modname-0.6.13+mc26.1.2.jar`
- Semantic version comparison — handles numeric and alphanumeric version parts, comparing segment by segment

### Changed
- Mod installation now checks the base mod name (without version suffix) to detect same-mod-different-version scenarios
- Skip messages now include version comparison details when a mod is skipped due to having the same or older version
- Non-mod files (configs, scripts, assets) still use SHA-256 hash comparison as before

### Details
- The version comparator splits version strings by `.`, `+`, `-`, and `_` delimiters, then compares each segment numerically when possible
- If a mod named "sodium-0.6.10.jar" is installed and the .apepack contains "sodium-0.6.13.jar", the old file is removed and the new one is installed
- If the installed version is the same or newer, the import is skipped with a log message showing both versions

## [0.0.10] - 2026-11-07

### Fixed
- File picker scroll position resetting to top when refreshing files — `refreshFiles()` was resetting `scrollOffset` to 0, causing the list to jump back to the top
- File picker panel clipping when scrolling too far — button Y positions could go outside the panel bounds, causing the whole selector to appear to move up and down
- Desktop button not working — the Desktop path resolution failed on some systems; now tries multiple paths (user.home/Desktop, USERPROFILE/Desktop, user.dir/../Desktop)
- Scrollbar not draggable — replaced MC 26.1.2 `MouseButtonEvent` API with standard `mouseClicked`/`mouseReleased` methods that work correctly
- Config screen not scaling with in-game GUI scale — all UI elements used `height / 6` for positioning, which broke at different GUI scales; now uses fixed `startY` positioning
- Config screen Done button now positions at bottom of screen with proper padding

### Changed
- File picker scroll offset is now clamped to valid range in `refreshFileButtons()` to prevent out-of-bounds scrolling
- Button Y positions are clamped to panel bounds to prevent visual clipping
- Config screen uses consistent fixed positioning (startY=50) for all elements regardless of GUI scale
- Removed unused `MouseButtonEvent` import from file picker

### Details
- The file picker now properly maintains scroll position when refreshing the file list
- The scrollbar uses standard Minecraft mouse event methods that work correctly in MC 26.1.2
- The config screen layout is consistent across all GUI scale settings

## [0.0.9] - 2026-11-07

### Fixed
- File picker mouse scroll wheel not working — scrolling would immediately reset to the top because `refreshFiles()` reset `scrollOffset` to zero on every scroll event
- Scrollbar was not interactive — could not click or drag the scrollbar to navigate the file list

### Added
- Mouse wheel scrolling in the file picker panel — scroll up and down to browse files and folders
- Interactive scrollbar on the right side of the file panel — click and drag to scroll, with visual hover and drag feedback
- Scrollbar track with visible borders and a highlight on the thumb for better visibility

### Changed
- Separated `refreshFileButtons()` from `refreshFiles()` so scrolling only updates visible buttons without resetting the file list or scroll position
- File buttons now account for scrollbar width to prevent overlap
- Scrollbar uses solid colors with hover/drag states instead of semi-transparent invisible colors

### Details
- The scroll wheel moves one row per notch for precise navigation
- The scrollbar thumb changes color when hovered (lighter gray) or dragged (brightest)
- A subtle white highlight on the left edge of the thumb gives it a 3D appearance

## [0.0.8] - 2026-11-07

### Fixed
- Import progress screen showing a black screen instead of the progress bar and file names — the background was nearly opaque black (`0xF0000000`) with no visible text until the import thread started processing
- No feedback shown while waiting for the import thread to begin — screen appeared completely blank

### Changed
- Import screen background changed from near-opaque black to semi-transparent dark gray (`0xCC000000`) so text is always visible
- Constructor now initializes the current file name with the `.apepack` filename so it displays immediately on screen open
- Progress text shows "Preparing..." when total file count is still 0, instead of showing "0 / 0 files"
- Pack name no longer shows "Loading..." as a placeholder — only displays real pack info once received
- File status label changed from "Installing:" to "Processing:" for clarity during initial load

### Details
- The import screen now shows meaningful content from the moment it opens: the `.apepack` filename, a "Preparing..." status, and live import counts
- Once the import thread starts processing, the progress bar fills and file names update in real time

## [0.0.7] - 2026-11-07

### Fixed
- File picker buttons showing blank gray bars instead of file names — buttons now display actual file/folder names
- Navigation buttons overlapping with the file list panel — repositioned layout so buttons are above the panel
- Title text overlapping with navigation buttons — moved title higher on the screen

### Changed
- File list buttons now use `Component.literal()` with file names instead of `Component.empty()`
- Navigation button row moved to y=52, file panel starts at y=75 with proper spacing
- Path input field moved to y=28 for better layout hierarchy
- Removed redundant file name text rendering from `extractRenderState` (buttons handle their own text now)

### Details
- The file picker now properly shows file and folder names on each button row
- Directory entries show with folder icon prefix (📁)
- Layout is properly organized: title → path input → navigation buttons → file panel → action buttons

## [0.0.6] - 2026-11-07

### Added
- Native Minecraft file picker window for selecting `.apepack` files
- Scrollable file browser with directory navigation
- Quick access buttons: Home, Downloads, Desktop
- Path input field with "Go" button for direct folder navigation
- Up/Back navigation buttons for browsing parent directories
- Visual file list with color-coded entries (blue for .apepack, gray for folders)
- File size display for .apepack files
- Scrollbar and mouse wheel scrolling support
- Click-to-select for .apepack files, click-to-open for directories

### Changed
- Replaced text input field method with proper file picker window
- "Browse .apepack" button now opens the file picker instead of requiring manual path entry

### Details
- The file picker is a full Minecraft-native screen — no external libraries or AWT/Swing
- Files are sorted: directories first, then .apepack files, then other files
- Long file names are truncated for display
- Selected file is shown at the bottom before confirming

## [0.0.5] - 2026-11-07

### Fixed
- Config screen crash when clicking "Browse .apepack" caused by `java.awt.HeadlessException`
- Replaced `javax.swing.JFileChooser` (which requires a native display) with a Minecraft-native file path input field

### Changed
- "Browse .apepack" button replaced with ".apepack Path" text input field + "Install .apepack" button
- Users now paste the file path (e.g., `C:/path/to/pack.apepack`) and click "Install .apepack" to import
- Added validation: checks file path is not empty, ends with `.apepack`, and file exists before launching import

### Details
- Swing/AWT components cannot run inside Minecraft's headless LWJGL environment
- The new approach uses an `EditBox` for the file path and validates before launching the import progress screen

## [0.0.4] - 2026-11-07

### Added
- "Browse .apepack" button in the config screen to select .apepack files via file picker
- Import progress screen showing real-time progress bar, current file being processed, and live counts
- Import completion summary with file counts (installed, skipped, protected)
- "Back to Title" and "Stay in Game" buttons after successful import

### Removed
- Non-functional drag-and-drop zone (replaced with browse button)

### Details
- Clicking "Browse .apepack" opens a native file dialog filtered to .apepack files
- The import screen shows a progress bar that fills as each file is processed
- Live counts show new files installed, duplicates skipped, and user settings protected
- After import completes, the user can return to the title screen or stay in-game

## [0.0.3] - 2026-11-07

### Added
- Drag-and-drop support for `.apepack` files in the config screen
- Visual drop zone with mouse hover feedback (green border when hovered)
- Installation status messages showing new files, skipped duplicates, and protected user settings

### Details
- Users can now drag a `.apepack` file directly onto the PackUpdater config screen to install updates
- The drop zone appears below the "Generate .apepack" section with dashed border and instructional text
- Installation runs on a background thread to keep the UI responsive

## [0.0.2] - 2026-11-07

### Fixed
- Config screen crash caused by `IllegalStateException: Can only blur once per frame`
- Replaced `extractBackground()` with solid fill to avoid blur conflict with other mods
- Moved `checkAvailableChannels()` to background thread to prevent blocking main thread during screen creation
- Replaced `SharedConstants.getCurrentVersion().name()` with hardcoded `"26.1.2"` for MC 26.1.2 compatibility

### Details
- The blur effect in `extractBackground()` conflicted with other mods (like Sodium) that also use blur
- Channel checking now runs asynchronously and updates the button text on the main thread after completion

## [0.0.1] - 2026-11-07

### Added
- Initial release of PackUpdater mod for Minecraft 26.1.2 with NeoForge
- In-game `.apepack` generator that packs mods, configs, and KubeJS/KubeGroovy scripts
- `.apepack` loader that installs only new or updated files, skipping duplicates
- User settings protection (keybinds, video options, server lists are never overwritten)
- GitHub release download with automatic `.apepack` preference over zip
- Config screen with pack name, version fields, and generate button
- Auto-scan of mods/ folder for `.apepack` files on startup

### Details
- Custom `.apepack` file format (ZIP archive) containing `apepack.json`, `manifest.json`, and mod files
- SHA-256 hash comparison to detect new vs duplicate files
- Supports KubeJS server scripts, client scripts, Groovy scripts, assets, and data packs
