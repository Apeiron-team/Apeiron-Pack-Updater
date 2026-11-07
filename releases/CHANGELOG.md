# PackUpdater Changelog

All notable changes to the Apeiron PackUpdater mod will be documented in this file.

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
