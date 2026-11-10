package com.Apeiron.globalpackupdater;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * PackUpdater config screen with a clean tabbed interface.
 * 
 * Tabs:
 * - Apepacks: Browse and install .apepack files, check for updates
 * - Generate: Create .apepack files from the current instance
 * - Settings: GitHub repo, token, channel selection
 */
public class PackUpdaterConfigScreen extends Screen {

    enum TabId { APEPACKS, GENERATE, SETTINGS }
    
    private final Screen parent;
    private TabId activeTab = TabId.APEPACKS;
    
    // Tab buttons
    private Button btnApepacks;
    private Button btnGenerate;
    private Button btnSettings;
    private List<Button> tabButtons = new ArrayList<>();

    // Shared state across tabs
    private Component statusText = Component.literal("");
    private String toastMessage = null;
    private int toastTimer = 0;
    private static final int TOAST_DURATION = 120;

    // Settings state
    private EditBox repoField;
    private EditBox tokenField;
    private Button channelButton;
    private boolean hasStable = true;
    private boolean hasBeta = false;
    private boolean hasNightly = false;

    // Generate state
    private EditBox packNameField;
    private EditBox packVersionField;
    private Button generateButton;
    private boolean generating = false;
    private String generateProgress = "";

    // Apepacks tab state
    private List<ApePackFileInfo> apepackFiles = new ArrayList<>();
    private int apepackScrollOffset = 0;
    private static final int APEPACK_ROW_HEIGHT = 22;

    // Install failure state
    private String installError = null;

    // Update check state (bug 1 - manual update check)
    private Button checkPackButton;
    private Button downloadPackButton = null;
    private boolean checkingUpdate = false;

    // Download state for startup update check (bug 14)
    private double downloadProgress = 0.0;
    private String downloadStatus = "";
    private boolean downloading = false;
    private boolean downloadPaused = false;
    private long downloadStartTime = 0;
    private long downloadStartBytes = 0;
    private long downloadTotalBytes = 0;
    private String downloadSpeed = "";
    
    // Changelog display
    private String changelogText = "";
    private int changelogScrollOffset = 0;
    
    // Statistics
    private String statsText = "";
    
    // Download connection for pause/resume
    private java.net.HttpURLConnection activeDownloadConn = null;
    private java.io.RandomAccessFile activeDownloadFile = null;
    private Path activeDownloadTarget = null;
    private String activeDownloadName = "";
    
    // Apepack file for diff screen
    private Path pendingApepackFile = null;

    public PackUpdaterConfigScreen(Screen parent) {
        super(Component.literal("Apeiron Pack Updater"));
        this.parent = parent;
        // Check for any install errors from startup
        this.installError = PackUpdaterMod.lastInstallError.get();
    }

    @Override
    protected void init() {
        super.init();

        // Check channels on background thread
        Thread channelThread = new Thread(() -> {
            checkAvailableChannels();
            Minecraft.getInstance().execute(() -> {
                if (channelButton != null) {
                    channelButton.setMessage(Component.literal("Channel: " + ConfigManager.channel.toUpperCase()));
                    // Bug 15: hide channel button if only one channel available
                    int channelCount = 0;
                    if (hasStable) channelCount++;
                    if (hasBeta) channelCount++;
                    if (hasNightly) channelCount++;
                    if (channelCount <= 1) {
                        // Hide the channel button if only one channel
                        channelButton.active = false;
                        channelButton.visible = false;
                    }
                }
            });
        }, "PackUpdater-Channels");
        channelThread.setDaemon(true);
        channelThread.start();

        // Build tab buttons
        buildTabButtons();
        
        // Build active tab content
        buildTabContent();
        
        // Bug 11: Add Done button at bottom
        int centerX = this.width / 2;
        addRenderableWidget(Button.builder(
                Component.literal("Done"),
                b -> onClose()
        ).bounds(centerX - 50, this.height - 30, 100, 20).build());
    }

    private void buildTabButtons() {
        tabButtons.clear();
        int tabBarY = 25;
        int tabWidth = 80;
        int tabHeight = 20;
        int gap = 4;
        int totalWidth = 3 * tabWidth + 2 * gap;
        int startX = (this.width - totalWidth) / 2;

        int[] positions = new int[3];
        for (int i = 0; i < 3; i++) {
            positions[i] = startX + i * (tabWidth + gap);
        }

        btnApepacks = addRenderableWidget(Button.builder(
                Component.literal("Apepacks"),
                b -> switchTab(TabId.APEPACKS)
        ).bounds(positions[0], tabBarY, tabWidth, tabHeight).build());
        tabButtons.add(btnApepacks);

        btnGenerate = addRenderableWidget(Button.builder(
                Component.literal("Generate"),
                b -> switchTab(TabId.GENERATE)
        ).bounds(positions[1], tabBarY, tabWidth, tabHeight).build());
        tabButtons.add(btnGenerate);

        btnSettings = addRenderableWidget(Button.builder(
                Component.literal("Settings"),
                b -> switchTab(TabId.SETTINGS)
        ).bounds(positions[2], tabBarY, tabWidth, tabHeight).build());
        tabButtons.add(btnSettings);

        updateTabButtonStyles();
    }

    private void switchTab(TabId tab) {
        if (activeTab == tab) return;
        activeTab = tab;
        updateTabButtonStyles();
        
        // Save field values before rebuilding
        String repoVal = repoField != null ? repoField.getValue() : "";
        String tokenVal = tokenField != null ? tokenField.getValue() : "";
        String nameVal = packNameField != null ? packNameField.getValue() : "";
        String verVal = packVersionField != null ? packVersionField.getValue() : "";

        // Remove all non-tab widgets (including Done button)
        List<AbstractWidget> toRemove = new ArrayList<>();
        for (var w : this.children()) {
            if (w instanceof AbstractWidget aw && !tabButtons.contains(aw)) {
                toRemove.add(aw);
            }
        }
        for (AbstractWidget w : toRemove) {
            removeWidget(w);
        }

        // Rebuild content
        buildTabContent();

        // Restore field values
        if (repoField != null) repoField.setValue(repoVal);
        if (tokenField != null) tokenField.setValue(tokenVal);
        if (packNameField != null) packNameField.setValue(nameVal);
        if (packVersionField != null) packVersionField.setValue(verVal);
        
        // Re-add Done button
        int centerX = this.width / 2;
        addRenderableWidget(Button.builder(
                Component.literal("Done"),
                b -> onClose()
        ).bounds(centerX - 50, this.height - 30, 100, 20).build());
    }

    private void updateTabButtonStyles() {
        for (Button btn : tabButtons) {
            btn.active = true;
        }
    }

    private void buildTabContent() {
        switch (activeTab) {
            case APEPACKS -> buildApepacksTab();
            case GENERATE -> buildGenerateTab();
            case SETTINGS -> buildSettingsTab();
        }
    }

    @Override
    public void resize(net.minecraft.client.Minecraft minecraft, int width, int height) {
        // Save state
        String repoValue = repoField != null ? repoField.getValue() : "";
        String tokenValue = tokenField != null ? tokenField.getValue() : "";
        String packNameValue = packNameField != null ? packNameField.getValue() : "";
        String packVersionValue = packVersionField != null ? packVersionField.getValue() : "";
        
        super.resize(minecraft, width, height);
        
        // Restore state after rebuild
        Minecraft.getInstance().execute(() -> {
            if (repoField != null) repoField.setValue(repoValue);
            if (tokenField != null) tokenField.setValue(tokenValue);
            if (packNameField != null) packNameField.setValue(packNameValue);
            if (packVersionField != null) packVersionField.setValue(packVersionValue);
        });
    }

    private void showToast(String message) {
        this.toastMessage = message;
        this.toastTimer = TOAST_DURATION;
    }

    private String extractRepoFromUrl(String input) {
        if (input == null || input.trim().isEmpty()) return null;
        String trimmed = input.trim();
        if (trimmed.contains("/") && !trimmed.contains("github.com") && !trimmed.startsWith("http")) {
            java.util.regex.Pattern shortPattern = java.util.regex.Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]*(/[a-zA-Z0-9][a-zA-Z0-9._-]*)$");
            if (shortPattern.matcher(trimmed).matches()) return trimmed;
        }
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("github\\.com\\/([^/]+)\\/([^/]+)");
        java.util.regex.Matcher matcher = pattern.matcher(trimmed);
        if (matcher.find()) return matcher.group(1) + "/" + matcher.group(2);
        return null;
    }

    private void checkAvailableChannels() {
        try {
            ConfigManager.saveConfig();
            String apiUrl = "https://api.github.com/repos/" + ConfigManager.repo + "/releases";
            URL url = URI.create(apiUrl).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            ConfigManager.setupGitHubConnection(conn);
            conn.setConnectTimeout(3000);
            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                JsonArray releases = JsonParser.parseReader(reader).getAsJsonArray();
                reader.close();
                for (JsonElement element : releases) {
                    JsonObject release = element.getAsJsonObject();
                    String tagName = release.get("tag_name").getAsString().toLowerCase();
                    boolean isPrerelease = release.get("prerelease").getAsBoolean();
                    if (isPrerelease) {
                        if (tagName.contains("nightly") || tagName.contains("dev") || tagName.contains("snapshot")) hasNightly = true;
                        else if (tagName.contains("beta") || tagName.contains("rc")) hasBeta = true;
                    } else {
                        hasStable = true;
                    }
                }
            }
        } catch (Exception e) {
            hasStable = true;
        }
    }

    private void cycleChannel() {
        if (ConfigManager.channel.equals("stable")) {
            if (hasBeta) ConfigManager.channel = "beta";
            else if (hasNightly) ConfigManager.channel = "nightly";
        } else if (ConfigManager.channel.equals("beta")) {
            if (hasNightly) ConfigManager.channel = "nightly";
            else if (hasStable) ConfigManager.channel = "stable";
        } else {
            if (hasStable) ConfigManager.channel = "stable";
            else if (hasBeta) ConfigManager.channel = "beta";
        }
    }

    private int compareVersions(String v1, String v2) {
        if (v1 == null || v1.isEmpty()) return -1;
        if (v2 == null || v2.isEmpty()) return 1;
        if (v1.equals(v2)) return 0;
        String[] parts1 = v1.split("[.+\\-_]");
        String[] parts2 = v2.split("[.+\\-_]");
        int maxLen = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < maxLen; i++) {
            String p1 = i < parts1.length ? parts1[i] : "0";
            String p2 = i < parts2.length ? parts2[i] : "0";
            try {
                long num1 = Long.parseLong(p1);
                long num2 = Long.parseLong(p2);
                if (num1 != num2) return Long.compare(num1, num2);
            } catch (NumberFormatException e) {
                int cmp = p1.compareTo(p2);
                if (cmp != 0) return cmp;
            }
        }
        return 0;
    }

    private static class UpdateCheckResult {
        final boolean success;
        final String message;
        final String version;
        final boolean updateAvailable;
        UpdateCheckResult(boolean success, String message, String version, boolean updateAvailable) {
            this.success = success;
            this.message = message;
            this.version = version;
            this.updateAvailable = updateAvailable;
        }
    }

    /**
     * Checks for pack (.apepack) updates from GitHub release assets.
     */
    private UpdateCheckResult checkForPackUpdates(String repo, String channel) {
        String localVersion = ConfigManager.modpackVersion;
        if (localVersion == null || localVersion.isEmpty()) {
            localVersion = "v0.0";
        }
        String localClean = localVersion.replaceFirst("^[vV]", "");

        try {
            String apiUrl = "https://api.github.com/repos/" + repo + "/releases";
            URL url = URI.create(apiUrl).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            ConfigManager.setupGitHubConnection(conn);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            if (conn.getResponseCode() != 200) {
                String errorMsg = "GitHub API error: " + conn.getResponseCode();
                if (conn.getResponseCode() == 403) errorMsg = "Rate limit exceeded. Add a GitHub token in Settings.";
                return new UpdateCheckResult(false, errorMsg, null, false);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            JsonArray releases = JsonParser.parseReader(reader).getAsJsonArray();
            reader.close();
            if (releases.isEmpty()) return new UpdateCheckResult(false, "No releases found", null, false);

            String latestApePackVersion = null;
            String latestReleaseTag = null;

            for (JsonElement element : releases) {
                JsonObject release = element.getAsJsonObject();
                String tagName = release.get("tag_name").getAsString();
                boolean isPrerelease = release.has("prerelease") && release.get("prerelease").getAsBoolean();
                boolean match = false;
                if (channel.equals("stable") && !isPrerelease) match = true;
                else if (channel.equals("beta") && isPrerelease && tagName.toLowerCase().contains("beta")) match = true;
                else if (channel.equals("nightly") && isPrerelease && (tagName.toLowerCase().contains("nightly") || tagName.toLowerCase().contains("dev"))) match = true;

                if (match) {
                    if (release.has("assets") && release.get("assets").isJsonArray()) {
                        JsonArray assets = release.get("assets").getAsJsonArray();
                        for (JsonElement assetElem : assets) {
                            JsonObject asset = assetElem.getAsJsonObject();
                            String name = asset.get("name").getAsString();
                            if (name.toLowerCase().endsWith(".apepack2") || name.toLowerCase().endsWith(".apepack")) {
                                String apePackVer = extractVersionFromApePackName(name);
                                if (apePackVer != null) {
                                    if (latestApePackVersion == null || compareVersions(apePackVer, latestApePackVersion) > 0) {
                                        latestApePackVersion = apePackVer;
                                        latestReleaseTag = tagName;
                                    }
                                }
                                break;
                            }
                        }
                    }
                }
            }

            if (latestApePackVersion == null) {
                return new UpdateCheckResult(true, "No .apepack on GitHub", localClean, false);
            }

            boolean isNewer = compareVersions(latestApePackVersion, localClean) > 0;
            return new UpdateCheckResult(true, "Latest: " + (latestReleaseTag != null ? latestReleaseTag : latestApePackVersion), latestApePackVersion, isNewer);

        } catch (Exception e) {
            return new UpdateCheckResult(false, "Error: " + e.getMessage(), null, false);
        }
    }

    private String extractVersionFromApePackName(String name) {
        String base = name.toLowerCase().replaceAll("\\.apepack2$", "").replaceAll("\\.apepack$", "");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+[\\.\\-]\\d+(?:[\\.\\-]\\d+)*)$").matcher(base);
        if (m.find()) return m.group(1).replace("-", ".");
        return null;
    }

    /**
     * Downloads the latest .apepack from GitHub releases.
     */
    private Path downloadLatestApePack(String repo) {
        try {
            String apiUrl = "https://api.github.com/repos/" + repo + "/releases";
            URL url = URI.create(apiUrl).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            ConfigManager.setupGitHubConnection(conn);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(30000);
            if (conn.getResponseCode() != 200) return null;

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            JsonArray releases = JsonParser.parseReader(reader).getAsJsonArray();
            reader.close();

            for (JsonElement element : releases) {
                JsonObject release = element.getAsJsonObject();
                if (release.has("assets") && release.get("assets").isJsonArray()) {
                    JsonArray assets = release.get("assets").getAsJsonArray();
                    for (JsonElement assetElem : assets) {
                        JsonObject asset = assetElem.getAsJsonObject();
                        String name = asset.get("name").getAsString();
                        if (name.toLowerCase().endsWith(".apepack2") || name.toLowerCase().endsWith(".apepack")) {
                            String downloadUrl = asset.get("browser_download_url").getAsString();
                            Path apepacksDir = PackUpdaterMod.getApepacksDir();
                            Files.createDirectories(apepacksDir);
                            Path target = apepacksDir.resolve(name);

                            // Download with progress
                            HttpURLConnection dlConn = (HttpURLConnection) URI.create(downloadUrl).toURL().openConnection();
                            ConfigManager.setupGitHubConnection(dlConn);
                            dlConn.setConnectTimeout(5000);
                            dlConn.setReadTimeout(60000);
                            int responseCode = dlConn.getResponseCode();
                            if (responseCode != 200) return null;

                            long totalSize = dlConn.getContentLengthLong();
                            long downloaded = 0;

                            try (java.io.InputStream is = dlConn.getInputStream();
                                 java.io.OutputStream os = Files.newOutputStream(target)) {
                                byte[] buffer = new byte[8192];
                                int bytesRead;
                                final long[] dlProgress = {0};
                                while ((bytesRead = is.read(buffer)) != -1) {
                                    os.write(buffer, 0, bytesRead);
                                    downloaded += bytesRead;
                                    dlProgress[0] = downloaded;
                                    if (totalSize > 0) {
                                        double progress = (double) downloaded / totalSize;
                                        final double p = progress;
                                        final long d = downloaded;
                                        Minecraft.getInstance().execute(() -> {
                                            downloadProgress = p;
                                            downloadStatus = "Downloading: " + formatSize(d) + " / " + formatSize(totalSize);
                                        });
                                    }
                                }
                            }

                            Minecraft.getInstance().execute(() -> {
                                downloadProgress = 1.0;
                                downloadStatus = "Download complete!";
                                showToast("Update downloaded: " + name);
                            });

                            return target;
                        }
                    }
                }
            }
            return null;
        } catch (Exception e) {
            System.err.println("[PackUpdater] Download failed: " + e.getMessage());
            return null;
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)) + " MB";
        return (bytes / (1024 * 1024 * 1024)) + " GB";
    }

    // ==================== TAB: APEPACKS ====================

    private void buildApepacksTab() {
        apepackFiles = scanApepacks();
        
        int centerX = this.width / 2;
        int panelWidth = 340;
        int panelX = centerX - panelWidth / 2;
        int startY = 55;

        // Show install error if present
        if (installError != null) {
            addRenderableWidget(Button.builder(
                    Component.literal("View Error Details"),
                    button -> {
                        statusText = Component.literal("Install failed: " + installError);
                        showToast("See error details below");
                    }
            ).bounds(panelX + 20, startY + 10, panelWidth - 40, 20).build());

            addRenderableWidget(Button.builder(
                    Component.literal("Report Bug on GitHub"),
                    button -> {
                        try {
                            java.awt.Desktop.getDesktop().browse(
                                    java.net.URI.create("https://github.com/Apeiron-team/Apeiron-Pack-Updater/issues"));
                            showToast("Opening GitHub issues page...");
                        } catch (Exception e) {
                            showToast("Could not open browser");
                        }
                    }
            ).bounds(panelX + 20, startY + 38, panelWidth - 40, 20).build());

            addRenderableWidget(Button.builder(
                    Component.literal("Dismiss Error"),
                    button -> {
                        installError = null;
                        PackUpdaterMod.lastInstallError.set(null);
                        switchTab(TabId.APEPACKS);
                        showToast("Error dismissed");
                    }
            ).bounds(panelX + 20, startY + 66, panelWidth - 40, 20).build());
        } else {
            // Bug 12+13: Pack version and name display
            String packDisplay = ConfigManager.modpackName.isEmpty() ? "No modpack installed" : ConfigManager.modpackName;
            addRenderableWidget(Button.builder(
                    Component.literal("Pack: " + packDisplay + " (" + ConfigManager.modpackVersion + ")"),
                    button -> {}
            ).bounds(panelX + 20, startY + 10, panelWidth - 40, 20).build());

            // Bug 1: Check for Update button
            checkPackButton = addRenderableWidget(Button.builder(
                    Component.literal("Check for Update"),
                    button -> handleCheckForPackUpdate()
            ).bounds(panelX + 20, startY + 38, panelWidth - 40, 20).build());

            // Open Apepacks folder button (Bug 3: fixed path)
            addRenderableWidget(Button.builder(
                    Component.literal("Open Apepacks Folder"),
                    button -> openApepacksFolder()
            ).bounds(panelX + 20, startY + 66, panelWidth - 40, 20).build());

            // Browse and install button
            addRenderableWidget(Button.builder(
                    Component.literal("Browse .apepack"),
                    button -> {
                        final PackUpdaterConfigScreen configScreen = this;
                        ApePackFilePicker filePicker = new ApePackFilePicker(configScreen, selectedPath -> {
                            // Extract first, then show diff screen
                            final Path apepackForDiff = selectedPath;
                            Thread extractThread = new Thread(() -> {
                                try {
                                    Path instanceRoot = PackUpdaterMod.INSTANCE_ROOT;
                                    ApePackLoaderV2.installApePackWithProgress(
                                        apepackForDiff, instanceRoot,
                                        new ApePackLoaderV2.ImportProgress() {
                                            @Override public void onFileStart(String f, String t, int c, int total) {}
                                            @Override public void onFileInstalled(String f) {}
                                            @Override public void onFileSkipped(String f) {}
                                            @Override public void onFileProtected(String f) {}
                                            @Override public void onPackInfo(String n, String v) {
                                                ConfigManager.updateModpackName(n);
                                            }
                                            @Override public void onComplete(boolean success, String error) {
                                                if (success) {
                                                    try {
                                                        String version = PackUpdaterMod.extractApePackVersion(apepackForDiff);
                                                        if (version != null) ConfigManager.updateModpackVersion(version);
                                                    } catch (Exception ignored) {}
                                                }
                                                Minecraft.getInstance().execute(() -> {
                                                    if (success) {
                                                        ApePackDiffScreen diffScreen = new ApePackDiffScreen(
                                                            configScreen, apepackForDiff, instanceRoot);
                                                        minecraft.setScreen(diffScreen);
                                                    } else {
                                                        configScreen.showToast("Extraction failed: " + error);
                                                    }
                                                });
                                            }
                                        });
                                } catch (Exception e) {
                                    System.err.println("[PackUpdater] Browse extract exception: " + e.getMessage());
                                    Minecraft.getInstance().execute(() -> {
                                        configScreen.showToast("Extraction failed: " + e.getMessage());
                                    });
                                }
                            }, "PackUpdater-BrowseExtract");
                            extractThread.start();
                        });
                        minecraft.setScreen(filePicker);
                    }
            ).bounds(panelX + 20, startY + 94, panelWidth - 40, 20).build());
        }
    }

    private void handleCheckForPackUpdate() {
        if (checkingUpdate) {
            showToast("Already checking for updates");
            return;
        }
        checkingUpdate = true;
        statusText = Component.literal("Checking for updates...");
        if (checkPackButton != null) {
            checkPackButton.setMessage(Component.literal("Checking..."));
            checkPackButton.active = false;
        }
        if (downloadPackButton != null) {
            downloadPackButton.active = false;
        }

        Thread checkThread = new Thread(() -> {
            UpdateCheckResult result = checkForPackUpdates(ConfigManager.repo, ConfigManager.channel);
            Minecraft.getInstance().execute(() -> {
                checkingUpdate = false;
                if (result.success && result.updateAvailable) {
                    statusText = Component.literal("Update available: v" + result.version);
                    showToast("Update available: v" + result.version);
                    // Replace check button with download button
                    if (checkPackButton != null) {
                        int bx = checkPackButton.getX(), by = checkPackButton.getY(), bw = checkPackButton.getWidth(), bh = checkPackButton.getHeight();
                        removeWidget(checkPackButton);
                        checkPackButton = null;
                        downloadPackButton = addRenderableWidget(Button.builder(
                                Component.literal("Download Update"),
                                b -> handleDownloadPackUpdate()
                        ).bounds(bx, by, bw, bh).build());
                    }
                } else if (result.success) {
                    statusText = Component.literal("Pack up to date: " + ConfigManager.modpackVersion);
                    showToast("Pack up to date");
                    if (checkPackButton != null) {
                        checkPackButton.setMessage(Component.literal("Check for Update"));
                        checkPackButton.active = true;
                    }
                    if (downloadPackButton != null) {
                        removeWidget(downloadPackButton);
                        downloadPackButton = null;
                    }
                } else {
                    statusText = Component.literal("Error: " + result.message);
                    showToast("Error: " + result.message);
                    if (checkPackButton != null) {
                        checkPackButton.setMessage(Component.literal("Check for Update"));
                        checkPackButton.active = true;
                    }
                    if (downloadPackButton != null) {
                        removeWidget(downloadPackButton);
                        downloadPackButton = null;
                    }
                }
            });
        }, "PackUpdater-PackCheck");
        checkThread.setDaemon(true);
        checkThread.start();
    }

    private void handleDownloadPackUpdate() {
        if (downloading && !downloadPaused) {
            showToast("Download already in progress");
            return;
        }
        
        // If paused, resume
        if (downloadPaused) {
            downloadPaused = false;
            statusText = Component.literal("Resuming download...");
            Thread downloadThread = new Thread(() -> {
                resumeDownload();
            }, "PackUpdater-Resume");
            downloadThread.setDaemon(true);
            downloadThread.start();
            return;
        }
        
        downloading = true;
        downloadPaused = false;
        downloadProgress = 0.0;
        downloadStatus = "Starting download...";
        downloadStartTime = System.currentTimeMillis();
        downloadStartBytes = 0;
        downloadTotalBytes = 0;
        downloadSpeed = "";
        statusText = Component.literal("Downloading update...");
        if (downloadPackButton != null) {
            downloadPackButton.active = false;
            downloadPackButton.setMessage(Component.literal("Downloading..."));
        }

        Thread downloadThread = new Thread(() -> {
            final PackUpdaterConfigScreen configScreen = PackUpdaterConfigScreen.this;
            Path downloadedFile = downloadLatestApePackWithStatsAndReturn(ConfigManager.repo);
            if (downloadedFile != null && Files.exists(downloadedFile)) {
                // Extract immediately in background, then show diff screen
                final Path apepackForDiff = downloadedFile;
                configScreen.statusText = Component.literal("Extracting update...");
                configScreen.downloadStatus = "Extracting...";
                
                Thread extractThread = new Thread(() -> {
                    try {
                        Path instanceRoot = PackUpdaterMod.INSTANCE_ROOT;
                        ApePackLoaderV2.installApePackWithProgress(
                            apepackForDiff, instanceRoot,
                            new ApePackLoaderV2.ImportProgress() {
                                @Override public void onFileStart(String f, String t, int c, int total) {
                                    Minecraft.getInstance().execute(() -> {
                                        configScreen.downloadStatus = "Extracting: " + f + " (" + c + "/" + total + ")";
                                        configScreen.downloadProgress = (double) c / total;
                                    });
                                }
                                @Override public void onFileInstalled(String f) {}
                                @Override public void onFileSkipped(String f) {}
                                @Override public void onFileProtected(String f) {}
                                @Override public void onPackInfo(String n, String v) {
                                    ConfigManager.updateModpackName(n);
                                }
                                @Override public void onComplete(boolean success, String error) {
                                    if (success) {
                                        ApePackLoaderV2.markApePackAsProcessed(apepackForDiff);
                                        try {
                                            String version = PackUpdaterMod.extractApePackVersion(apepackForDiff);
                                            if (version != null) ConfigManager.updateModpackVersion(version);
                                        } catch (Exception ignored) {}
                                    }
                                    // Show diff screen on main thread (don't delete .apepack yet, diff screen needs it)
                                    final boolean extractSuccess = success;
                                    final String extractError = error;
                                    Minecraft.getInstance().execute(() -> {
                                        configScreen.downloading = false;
                                        configScreen.downloadPaused = false;
                                        if (extractSuccess) {
                                            // Show diff screen
                                            ApePackDiffScreen diffScreen = new ApePackDiffScreen(
                                                configScreen, apepackForDiff, instanceRoot);
                                            minecraft.setScreen(diffScreen);
                                        } else {
                                            configScreen.downloadStatus = "Extraction failed: " + extractError;
                                            configScreen.statusText = Component.literal("Extraction failed");
                                            configScreen.showToast("Extraction failed: " + extractError);
                                            if (configScreen.downloadPackButton != null) {
                                                configScreen.downloadPackButton.setMessage(Component.literal("Download Update"));
                                                configScreen.downloadPackButton.active = true;
                                            }
                                        }
                                    });
                                }
                            });
                    } catch (Exception e) {
                        System.err.println("[PackUpdater] Extraction exception: " + e.getMessage());
                        Minecraft.getInstance().execute(() -> {
                            configScreen.downloading = false;
                            configScreen.downloadStatus = "Extraction failed: " + e.getMessage();
                            configScreen.statusText = Component.literal("Extraction failed");
                            configScreen.showToast("Extraction failed");
                            if (configScreen.downloadPackButton != null) {
                                configScreen.downloadPackButton.setMessage(Component.literal("Download Update"));
                                configScreen.downloadPackButton.active = true;
                            }
                        });
                    }
                }, "PackUpdater-Extract");
                extractThread.setDaemon(false);
                extractThread.start();
            } else {
                Minecraft.getInstance().execute(() -> {
                    downloading = false;
                    downloadPaused = false;
                    downloadStatus = "Download failed";
                    statusText = Component.literal("Download failed");
                    showToast("Download failed");
                    if (downloadPackButton != null) {
                        downloadPackButton.setMessage(Component.literal("Download Update"));
                        downloadPackButton.active = true;
                    }
                });
            }
        }, "PackUpdater-Download");
        downloadThread.setDaemon(true);
        downloadThread.start();
    }
    
    private Path downloadLatestApePackWithStatsAndReturn(String repo) {
        try {
            String apiUrl = "https://api.github.com/repos/" + repo + "/releases";
            URL url = URI.create(apiUrl).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            ConfigManager.setupGitHubConnection(conn);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(30000);
            if (conn.getResponseCode() != 200) return null;

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            JsonArray releases = JsonParser.parseReader(reader).getAsJsonArray();
            reader.close();

            for (JsonElement element : releases) {
                JsonObject release = element.getAsJsonObject();
                if (release.has("assets") && release.get("assets").isJsonArray()) {
                    JsonArray assets = release.get("assets").getAsJsonArray();
                    for (JsonElement assetElem : assets) {
                        JsonObject asset = assetElem.getAsJsonObject();
                        String name = asset.get("name").getAsString();
                        if (name.toLowerCase().endsWith(".apepack2") || name.toLowerCase().endsWith(".apepack")) {
                            String downloadUrl = asset.get("browser_download_url").getAsString();
                            Path apepacksDir = PackUpdaterMod.getApepacksDir();
                            Files.createDirectories(apepacksDir);
                            Path target = apepacksDir.resolve(name);

                            // Download with progress and stats
                            HttpURLConnection dlConn = (HttpURLConnection) URI.create(downloadUrl).toURL().openConnection();
                            ConfigManager.setupGitHubConnection(dlConn);
                            dlConn.setConnectTimeout(5000);
                            dlConn.setReadTimeout(60000);
                            int responseCode = dlConn.getResponseCode();
                            if (responseCode != 200) return null;

                            long totalSize = dlConn.getContentLengthLong();
                            long downloaded = 0;
                            downloadStartTime = System.currentTimeMillis();
                            downloadStartBytes = 0;
                            downloadTotalBytes = totalSize;

                            try (java.io.InputStream is = dlConn.getInputStream();
                                 java.io.OutputStream os = Files.newOutputStream(target)) {
                                byte[] buffer = new byte[8192];
                                int bytesRead;
                                while ((bytesRead = is.read(buffer)) != -1) {
                                    if (downloadPaused) {
                                        Thread.sleep(100);
                                        continue;
                                    }
                                    os.write(buffer, 0, bytesRead);
                                    downloaded += bytesRead;
                                    if (totalSize > 0) {
                                        double progress = (double) downloaded / totalSize;
                                        long elapsed = (System.currentTimeMillis() - downloadStartTime) / 1000;
                                        long speed = elapsed > 0 ? downloaded / elapsed / 1024 : 0;
                                        final double p = progress;
                                        final long d = downloaded;
                                        final long spd = speed;
                                        Minecraft.getInstance().execute(() -> {
                                            downloadProgress = p;
                                            downloadStatus = formatSize(d) + " / " + formatSize(totalSize) + " (" + (int)(p * 100) + "%)";
                                            downloadSpeed = speed > 0 ? speed + " KB/s" : "";
                                            statsText = "Speed: " + downloadSpeed + " | ETA: " + (speed > 0 ? ((totalSize - d) / speed / 1024) + "s" : "calculating...");
                                        });
                                    }
                                }
                            }

                            Minecraft.getInstance().execute(() -> {
                                downloadProgress = 1.0;
                                downloadStatus = "Download complete! Extracting...";
                                statsText = "Total: " + formatSize(totalSize);
                                showToast("Update downloaded: " + name);
                            });

                            return target;
                        }
                    }
                }
            }
            return null;
        } catch (Exception e) {
            System.err.println("[PackUpdater] Download failed: " + e.getMessage());
            return null;
        }
    }
    
    private void resumeDownload() {
        // Simplified resume - restarts download from beginning for reliability
        Thread downloadThread = new Thread(() -> {
            Path downloadedFile = downloadLatestApePackWithStatsAndReturn(ConfigManager.repo);
            Minecraft.getInstance().execute(() -> {
                downloading = false;
                downloadPaused = false;
                if (downloadedFile != null && Files.exists(downloadedFile)) {
                    downloadProgress = 1.0;
                    downloadStatus = "Download complete!";
                    showToast("Download complete!");
                }
            });
        }, "PackUpdater-Resume");
        downloadThread.setDaemon(true);
        downloadThread.start();
    }

    private List<ApePackFileInfo> scanApepacks() {
        List<ApePackFileInfo> files = new ArrayList<>();
        Path apepacksDir = PackUpdaterMod.getApepacksDir();
        if (!Files.exists(apepacksDir)) return files;
        try (var paths = Files.list(apepacksDir)) {
            files = paths
                    .filter(p -> p.toString().toLowerCase().endsWith(".apepack") || p.toString().toLowerCase().endsWith(".apepack2"))
                    .sorted(Comparator.comparingLong((Path p) -> {
                        try { return Files.getLastModifiedTime(p).toMillis(); }
                        catch (Exception e) { return 0L; }
                    }).reversed())
                    .map(p -> {
                        boolean processed = ApePackLoader.isApePackAlreadyProcessed(p);
                        String version = "?";
                        try {
                            version = extractApePackVersionFromPath(p);
                        } catch (Exception ignored) {}
                        try {
                            long size = Files.size(p);
                            return new ApePackFileInfo(p, processed, version, size);
                        } catch (Exception e) {
                            return new ApePackFileInfo(p, processed, "?", 0);
                        }
                    })
                    .toList();
        } catch (Exception e) {
            // Ignore
        }
        return files;
    }

    private String extractApePackVersionFromPath(Path apepackFile) {
        try {
            java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(apepackFile.toFile());
            java.util.zip.ZipEntry entry = zipFile.getEntry("apepack.json");
            if (entry != null) {
                try (java.io.InputStream is = zipFile.getInputStream(entry);
                     java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is))) {
                    String content = reader.lines().reduce("", (a, b) -> a + b);
                    JsonObject json = JsonParser.parseString(content).getAsJsonObject();
                    if (json.has("packVersion")) {
                        zipFile.close();
                        return json.get("packVersion").getAsString();
                    }
                }
            }
            zipFile.close();
        } catch (Exception e) {
            // Ignore
        }
        return "?";
    }

    private void openApepacksFolder() {
        Path apepacksDir = PackUpdaterMod.getApepacksDir();
        if (!Files.exists(apepacksDir)) {
            try {
                Files.createDirectories(apepacksDir);
            } catch (Exception e) {
                showToast("Failed to create apepacks folder");
                return;
            }
        }
        try {
            // Use absolute path to avoid instance\.\apepacks bug
            Path absPath = apepacksDir.toAbsolutePath().normalize();
            java.awt.Desktop.getDesktop().open(absPath.toFile());
            showToast("Opened apepacks folder");
        } catch (Exception e) {
            showToast("Could not open folder: " + apepacksDir.toAbsolutePath());
        }
    }

    private void renderApepacksTab(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int centerX = this.width / 2;
        int panelWidth = 340;
        int panelX = centerX - panelWidth / 2;
        int startY = 96;
        int panelHeight = this.height - startY - 30;

        // Show install error message
        if (installError != null) {
            guiGraphics.drawCenteredString(font, "Installation Failed!", centerX, startY + 10, 0xFF5555);
            guiGraphics.drawCenteredString(font, "The .apepack update failed to install.", centerX, startY + 30, 0xCCCCCC);
            guiGraphics.drawCenteredString(font, "Please check latest.log and report the bug on GitHub.", centerX, startY + 48, 0xAAAAAA);
            startY += 70;
        }

        // Bug 14: Download progress bar with statistics
        if (downloading || !downloadStatus.isEmpty()) {
            int barX = panelX + 20;
            int barY = startY;
            int barW = panelWidth - 40;
            int barH = 10;
            
            // Background track
            guiGraphics.fill(barX, barY, barX + barW, barY + barH, 0xFF333333);
            // Border
            guiGraphics.fill(barX, barY, barX + barW, barY, 0xFF666666);
            guiGraphics.fill(barX, barY + barH - 1, barX + barW, barY + barH, 0xFF666666);
            guiGraphics.fill(barX, barY, barX, barY + barH, 0xFF666666);
            guiGraphics.fill(barX + barW - 1, barY, barX + barW, barY + barH, 0xFF666666);
            
            // Progress fill
            int filledWidth = (int) (barW * downloadProgress);
            if (filledWidth > 0) {
                guiGraphics.fill(barX + 1, barY + 1, barX + filledWidth, barY + barH - 1, 0xFF3399FF);
            }
            
            // Status text
            guiGraphics.drawCenteredString(font, downloadStatus, centerX, barY + barH + 8, 0xFFFFFF);
            
            // Statistics
            if (!statsText.isEmpty()) {
                guiGraphics.drawCenteredString(font, statsText, centerX, barY + barH + 22, 0xAAAAAA);
            }
            
            startY += barH + 35;
        }

        if (installError == null) {
            guiGraphics.drawCenteredString(font, "Downloaded .apepack Files", centerX, startY + 10, 0x88CCFF);
        }

        if (apepackFiles.isEmpty() && installError == null) {
            guiGraphics.drawCenteredString(font, "No .apepack files found", centerX, startY + 40, 0x666666);
            guiGraphics.drawCenteredString(font, "Updates will be saved here", centerX, startY + 58, 0x444444);
            return;
        }

        // Render file list
        int fileStartY = startY + 25;
        int visibleRows = (panelHeight - 30) / APEPACK_ROW_HEIGHT;
        int maxScroll = Math.max(0, apepackFiles.size() * APEPACK_ROW_HEIGHT - (panelHeight - 30));
        apepackScrollOffset = Math.max(0, Math.min(apepackScrollOffset, maxScroll));

        int startIndex = apepackScrollOffset / APEPACK_ROW_HEIGHT;
        for (int i = 0; i < visibleRows && startIndex + i < apepackFiles.size(); i++) {
            int index = startIndex + i;
            ApePackFileInfo info = apepackFiles.get(index);
            int y = fileStartY + (i * APEPACK_ROW_HEIGHT) - (apepackScrollOffset % APEPACK_ROW_HEIGHT);

            if (y < fileStartY || y + APEPACK_ROW_HEIGHT > fileStartY + (panelHeight - 30)) continue;

            // Background
            boolean isHovered = mouseX >= panelX + 5 && mouseX <= panelX + panelWidth - 5 && mouseY >= y && mouseY < y + APEPACK_ROW_HEIGHT;
            if (isHovered) {
                guiGraphics.fill(panelX + 5, y, panelX + panelWidth - 5, y + APEPACK_ROW_HEIGHT, 0x200000FF);
            }

            // File name
            String name = info.path.getFileName().toString();
            if (name.length() > 35) name = "..." + name.substring(name.length() - 32);
            String prefix = info.processed ? "[OK] " : "[  ] ";
            guiGraphics.drawCenteredString(font, prefix + name, panelX + 80, y + 3, info.processed ? 0x888888 : 0xFFFFFF);

            // Version and size
            String sizeStr = formatSize(info.size);
            guiGraphics.drawCenteredString(font, "v" + info.version + " (" + sizeStr + ")", panelX + panelWidth - 80, y + 3, 0xAAAAAA);
        }

        // Instructions
        if (installError == null) {
            guiGraphics.drawCenteredString(font, "Click 'Browse .apepack' to install a file manually", centerX, this.height - 15, 0x555555);
        }
    }

    // ==================== TAB: GENERATE ====================

    private void buildGenerateTab() {
        int centerX = this.width / 2;
        int panelWidth = 340;
        int panelX = centerX - panelWidth / 2;
        int startY = 55;

        // Pack name field
        packNameField = new EditBox(font, panelX + 20, startY + 30, panelWidth - 40, 20, Component.literal("Pack Name"));
        packNameField.setHint(Component.literal("My Modpack"));
        addRenderableWidget(packNameField);

        // Pack version field
        packVersionField = new EditBox(font, panelX + 20, startY + 60, panelWidth - 40, 20, Component.literal("Pack Version"));
        packVersionField.setHint(Component.literal("1.0.0"));
        addRenderableWidget(packVersionField);

        // Generate button
        generateButton = Button.builder(
                Component.literal("Generate .apepack"),
                button -> handleGenerateApePack()
        ).bounds(panelX + 20, startY + 90, panelWidth - 40, 20).build();
        addRenderableWidget(generateButton);
    }

    private void handleGenerateApePack() {
        // Prevent concurrent generation runs
        if (generating) {
            statusText = Component.literal("Generation already in progress, please wait...");
            showToast("Already generating, please wait");
            return;
        }

        String packName = packNameField != null ? packNameField.getValue().trim() : "";
        String packVersion = packVersionField != null ? packVersionField.getValue().trim() : "";

        if (packName.isEmpty()) {
            statusText = Component.literal("Please enter a pack name.");
            showToast("Please enter a pack name");
            return;
        }
        if (packVersion.isEmpty()) {
            statusText = Component.literal("Please enter a pack version.");
            showToast("Please enter a pack version");
            return;
        }

        // Lock the generation process
        generating = true;
        generateProgress = "";
        if (generateButton != null) {
            generateButton.active = false;
            generateButton.setMessage(Component.literal("Generating..."));
        }
        packNameField.active = false;
        packVersionField.active = false;

        statusText = Component.literal("Starting .apepack2 generation...");
        showToast("Generating .apepack2...");

        Thread genThread = new Thread(() -> {
            try {
                Path instanceRoot = Path.of(".");
                
                // Clean up any stale temp directory from a previous failed run
                Path staleTempDir = instanceRoot.toAbsolutePath().resolve("apepack_chunks_tmp");
                if (Files.exists(staleTempDir)) {
                    updateProgress("Cleaning up previous temp files...");
                    try {
                        Files.walk(staleTempDir)
                            .sorted(Comparator.reverseOrder())
                            .forEach(path -> {
                                try { Files.delete(path); } catch (IOException ignored) {}
                            });
                    } catch (IOException ignored) {}
                }

                Path outputFile = instanceRoot.resolve("generated_" + packName.replace(" ", "_") + "_" + packVersion + ".apepack2");
                String mcVersion = "26.1.2";
                String modLoader = "neoforge-26.1.2.122";

                // Progress callback for real-time updates
                java.util.function.Consumer<String> progressCallback = msg -> {
                    updateProgress(msg);
                };

                ApePackV2.V2GenerateResult result = ApePackGeneratorV2.generate(
                        instanceRoot, outputFile, packName, packVersion, mcVersion, modLoader, progressCallback);

                Minecraft.getInstance().execute(() -> {
                    generating = false;
                    if (generateButton != null) {
                        generateButton.active = true;
                        generateButton.setMessage(Component.literal("Generate .apepack2"));
                    }
                    if (packNameField != null) packNameField.active = true;
                    if (packVersionField != null) packVersionField.active = true;

                    if (result.success) {
                        statusText = Component.literal(
                                ".apepack2 generated! " + result.modCount + " mods, " +
                                result.configCount + " configs, " + result.scriptCount + " scripts. " +
                                String.format("(%.1f%% compressed)", result.compressionRatio));
                        showToast("Generated: " + outputFile.getFileName());
                    } else {
                        statusText = Component.literal("Error: " + result.errorMessage);
                        showToast("Generation failed");
                    }
                });
            } catch (Exception e) {
                Minecraft.getInstance().execute(() -> {
                    generating = false;
                    if (generateButton != null) {
                        generateButton.active = true;
                        generateButton.setMessage(Component.literal("Generate .apepack2"));
                    }
                    if (packNameField != null) packNameField.active = true;
                    if (packVersionField != null) packVersionField.active = true;
                    statusText = Component.literal("Error: " + e.getMessage());
                    showToast("Error: " + e.getMessage());
                });
            }
        }, "PackUpdater-Generate");
        genThread.setDaemon(true);
        genThread.start();
    }

    private void updateProgress(String msg) {
        generateProgress = msg;
        Minecraft.getInstance().execute(() -> {
            statusText = Component.literal(msg);
        });
    }

    private void renderGenerateTab(GuiGraphics guiGraphics) {
        int centerX = this.width / 2;
        
        // Info text
        guiGraphics.drawCenteredString(font, "Create an .apepack2 from your current instance", centerX, 50, 0x88CCFF);
        guiGraphics.drawCenteredString(font, "Includes mods, configs, and KubeJS scripts", centerX, 67, 0x666666);

        // Status
        if (statusText.getString() != null && !statusText.getString().isEmpty()) {
            int color = 0xFFFF55;
            String msg = statusText.getString();
            if (msg.contains("generated") || msg.contains("mods")) color = 0x55FF55;
            else if (msg.contains("Error") || msg.contains("failed")) color = 0xFF5555;
            guiGraphics.drawCenteredString(font, msg, centerX, 130, color);
        }
    }

    // ==================== TAB: SETTINGS ====================

    private void buildSettingsTab() {
        int centerX = this.width / 2;
        int panelWidth = 340;
        int panelX = centerX - panelWidth / 2;
        int startY = 55;

        // Bug 12+13: Pack name and version display
        String packDisplay = ConfigManager.modpackName.isEmpty() ? "No modpack installed" : ConfigManager.modpackName;
        addRenderableWidget(Button.builder(
                Component.literal("Pack: " + packDisplay + " (" + ConfigManager.modpackVersion + ")"),
                button -> {}
        ).bounds(panelX + 20, startY + 10, panelWidth - 40, 20).build());

        // Mod version display
        addRenderableWidget(Button.builder(
                Component.literal("Mod: v" + ConfigManager.CURRENT_VERSION),
                button -> {}
        ).bounds(panelX + 20, startY + 38, panelWidth - 40, 20).build());

        // GitHub Repo field
        repoField = new EditBox(font, panelX + 20, startY + 70, panelWidth - 40, 20, Component.literal("GitHub Repo"));
        repoField.setMaxLength(Integer.MAX_VALUE);
        repoField.setHint(Component.literal("user/repo or https://github.com/user/repo"));
        // Always load from ConfigManager, not lastReleaseUrl
        repoField.setValue(ConfigManager.repo);
        repoField.setResponder(text -> {
            String repo = extractRepoFromUrl(text);
            if (repo != null) ConfigManager.repo = repo;
        });
        addRenderableWidget(repoField);

        // GitHub Token field
        tokenField = new EditBox(font, panelX + 20, startY + 100, panelWidth - 40, 20, Component.literal("GitHub Token"));
        tokenField.setMaxLength(Integer.MAX_VALUE);
        tokenField.setHint(Component.literal("Optional: increases API limit to 5,000/hr"));
        tokenField.setValue(ConfigManager.githubToken);
        tokenField.setResponder(text -> ConfigManager.githubToken = text.trim());
        addRenderableWidget(tokenField);

        // Bug 15: Channel button - only visible if multiple channels available
        channelButton = addRenderableWidget(Button.builder(
                Component.literal("Channel: " + ConfigManager.channel.toUpperCase()),
                button -> {
                    cycleChannel();
                    button.setMessage(Component.literal("Channel: " + ConfigManager.channel.toUpperCase()));
                }
        ).bounds(panelX + 20, startY + 130, panelWidth - 40, 20).build());
        // Will be hidden after channel check completes if only one channel

        // Save button
        addRenderableWidget(Button.builder(
                Component.literal("Save Settings"),
                button -> {
                    ConfigManager.saveConfig();
                    showToast("Settings saved");
                }
        ).bounds(panelX + 20, startY + 160, panelWidth - 40, 20).build());
    }

    private void renderSettingsTab(GuiGraphics guiGraphics) {
        int centerX = this.width / 2;
        
        // Info text
        guiGraphics.drawCenteredString(font, "Configure update source and preferences", centerX, 50, 0x88CCFF);

        // Token info
        guiGraphics.drawCenteredString(font, "Get a token at: https://github.com/settings/tokens", centerX, 170, 0x444444);
    }

    // ==================== DATA CLASS ====================

    private static class ApePackFileInfo {
        final Path path;
        final boolean processed;
        final String version;
        final long size;
        ApePackFileInfo(Path path, boolean processed, String version, long size) {
            this.path = path;
            this.processed = processed;
            this.version = version;
            this.size = size;
        }
    }

    // ==================== MOUSE SCROLL ====================

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (activeTab == TabId.APEPACKS) {
            int centerX = this.width / 2;
            int panelWidth = 340;
            int panelX = centerX - panelWidth / 2;
            int startY = 96;
            int panelHeight = this.height - startY - 30;
            if (mouseX >= panelX && mouseX <= panelX + panelWidth && mouseY >= startY && mouseY <= startY + panelHeight) {
                int maxScroll = Math.max(0, apepackFiles.size() * APEPACK_ROW_HEIGHT - panelHeight);
                apepackScrollOffset = Math.max(0, Math.min((int)(apepackScrollOffset - scrollY * APEPACK_ROW_HEIGHT), maxScroll));
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ==================== RENDER ====================

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Background
        guiGraphics.fill(0, 0, this.width, this.height, 0xCC101010);

        // Title
        guiGraphics.drawCenteredString(this.font, "Apeiron Pack Updater", this.width / 2, 8, 0x88CCFF);

        // Draw active tab highlight
        int tabBarY = 25;
        int tabWidth = 80;
        int tabHeight = 20;
        int gap = 4;
        int totalWidth = 3 * tabWidth + 2 * gap;
        int startX = (this.width - totalWidth) / 2;
        
        // Highlight active tab button background
        int activeIndex = activeTab.ordinal();
        int activeX = startX + activeIndex * (tabWidth + gap);
        guiGraphics.fill(activeX - 1, tabBarY - 1, activeX + tabWidth + 1, tabBarY + tabHeight + 1, 0x4466AA);

        // Render active tab content
        switch (activeTab) {
            case APEPACKS -> renderApepacksTab(guiGraphics, mouseX, mouseY);
            case GENERATE -> renderGenerateTab(guiGraphics);
            case SETTINGS -> renderSettingsTab(guiGraphics);
        }

        // Toast notification
        if (toastMessage != null && toastTimer > 0) {
            toastTimer--;
            if (toastTimer <= 0) {
                toastMessage = null;
            } else {
                int alpha;
                if (toastTimer > TOAST_DURATION - 20) {
                    alpha = (TOAST_DURATION - toastTimer) * 255 / 20;
                } else if (toastTimer < 20) {
                    alpha = toastTimer * 255 / 20;
                } else {
                    alpha = 255;
                }
                alpha = Math.min(255, alpha);
                int toastWidth = font.width(toastMessage) + 24;
                int toastX = (width - toastWidth) / 2;
                int toastY = 12;
                guiGraphics.fill(toastX - 2, toastY - 2, toastX + toastWidth + 2, toastY + 16, (alpha << 24) | 0x1A1A2E);
                guiGraphics.fill(toastX - 2, toastY - 2, toastX + toastWidth + 2, toastY - 1, (alpha << 24) | 0x4466AA);
                guiGraphics.fill(toastX - 2, toastY + 15, toastX + toastWidth + 2, toastY + 16, (alpha << 24) | 0x333355);
                guiGraphics.drawCenteredString(font, toastMessage, width / 2, toastY + 2, (alpha << 24) | 0xFFFFFF);
            }
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        ConfigManager.saveConfig();
        minecraft.setScreen(parent);
    }
}
