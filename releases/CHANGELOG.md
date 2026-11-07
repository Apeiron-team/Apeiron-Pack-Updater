# PackUpdater Changelog

All notable changes to the Apeiron PackUpdater mod will be documented in this file.

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
