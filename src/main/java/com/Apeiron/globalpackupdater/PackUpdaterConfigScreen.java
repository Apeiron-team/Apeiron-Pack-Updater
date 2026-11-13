package com.Apeiron.globalpackupdater;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
 * - Info: Modpack name, version, check for updates, changelog
 * - Apepacks: Generate, browse, and manage .apepack files
 * - Settings: GitHub repo, token, channel selection
 */
public class PackUpdaterConfigScreen extends Screen {

    enum TabId { INFO, APEPACKS, SETTINGS }
    
    private final Screen parent;
    private TabId activeTab = TabId.INFO;
    
    // Tab buttons
    private Button btnInfo;
    private Button btnApepacks;
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
    
    // Info tab state
    private Button viewChangelogButton;
    private boolean showingChangelog = false;
    
    // Statistics
    private String statsText = "";
    
    // Download connection for pause/resume
    private java.net.HttpURLConnection activeDownloadConn = null;
    private java.io.RandomAccessFile activeDownloadFile = null;
    private Path activeDownloadTarget = null;
    private String activeDownloadName = "";
    
    // Apepack file for diff screen
    private Path pendingApepackFile = null;
    
    // Self-update state
    private Button selfUpdateToggleButton;
    private Button checkModUpdateButton;
    private boolean checkingModUpdate = false;
    private boolean downloadingModUpdate = false;
    private double modUpdateProgress = 0.0;
    private String modUpdateStatus = "";

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

        btnInfo = addRenderableWidget(Button.builder(
                Component.literal("Info"),
                b -> switchTab(TabId.INFO)
        ).bounds(positions[0], tabBarY, tabWidth, tabHeight).build());
        tabButtons.add(btnInfo);

        btnApepacks = addRenderableWidget(Button.builder(
                Component.literal("Apepacks"),
                b -> switchTab(TabId.APEPACKS)
        ).bounds(positions[1], tabBarY, tabWidth, tabHeight).build());
        tabButtons.add(btnApepacks);

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
            case INFO -> buildInfoTab();
            case APEPACKS -> buildApepacksTab();
            case SETTINGS -> buildSettingsTab();
        }
    }

    @Override
    public void resize(int width, int height) {
        // Save state
        String repoValue = repoField != null ? repoField.getValue() : "";
        String tokenValue = tokenField != null ? tokenField.getValue() : "";
        String packNameValue = packNameField != null ? packNameField.getValue() : "";
        String packVersionValue = packVersionField != null ? packVersionField.getValue() : "";
        
        super.resize(width, height);
        
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

    // ==================== TAB: INFO ====================

    private void buildInfoTab() {
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
                        switchTab(TabId.INFO);
                        showToast("Error dismissed");
                    }
            ).bounds(panelX + 20, startY + 66, panelWidth - 40, 20).build());
            return;
        }

        // Modpack name display
        String packNameDisplay = ConfigManager.modpackName.isEmpty() ? "No modpack installed" : ConfigManager.modpackName;
        addRenderableWidget(Button.builder(
                Component.literal("Pack: " + packNameDisplay),
                button -> {}
        ).bounds(panelX + 20, startY + 10, panelWidth - 40, 20).build());

        // Modpack version display
        addRenderableWidget(Button.builder(
                Component.literal("Version: " + ConfigManager.modpackVersion),
                button -> {}
        ).bounds(panelX + 20, startY + 38, panelWidth - 40, 20).build());

        // Mod version display
        addRenderableWidget(Button.builder(
                Component.literal("Mod: v" + ConfigManager.CURRENT_VERSION),
                button -> {}
        ).bounds(panelX + 20, startY + 66, panelWidth - 40, 20).build());

        // Check for Update button
        checkPackButton = addRenderableWidget(Button.builder(
                Component.literal("Check for Update"),
                button -> handleCheckForPackUpdate()
        ).bounds(panelX + 20, startY + 100, panelWidth - 40, 20).build());

        // Changelog button
        viewChangelogButton = addRenderableWidget(Button.builder(
                Component.literal(showingChangelog ? "Hide Changelog" : "View Changelog"),
                button -> {
                    showingChangelog = !showingChangelog;
                    if (showingChangelog) {
                        loadChangelog();
                        button.setMessage(Component.literal("Hide Changelog"));
                    } else {
                        changelogText = "";
                        button.setMessage(Component.literal("View Changelog"));
                    }
                    // Rebuild to show/hide changelog area
                    rebuildWidgets();
                }
        ).bounds(panelX + 20, startY + 128, panelWidth - 40, 20).build());
    }

    private void loadChangelog() {
        changelogText = "";
        if (ConfigManager.repo == null || ConfigManager.repo.isEmpty()) {
            changelogText = "No GitHub repo configured.\nSet your modpack repo in the Settings tab to view changelogs.";
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                String apiUrl = "https://api.github.com/repos/" + ConfigManager.repo + "/releases?per_page=5";
                URL url = URI.create(apiUrl).toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                ConfigManager.setupGitHubConnection(conn);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(30000);
                
                if (conn.getResponseCode() != 200) {
                    changelogText = "Failed to load changelog (HTTP " + conn.getResponseCode() + ")";
                    return;
                }
                
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                JsonArray releases = JsonParser.parseReader(reader).getAsJsonArray();
                reader.close();
                
                StringBuilder sb = new StringBuilder();
                for (JsonElement elem : releases) {
                    JsonObject release = elem.getAsJsonObject();
                    String tag = release.get("tag_name").getAsString();
                    String body = release.has("body") ? release.get("body").getAsString() : "No changelog";
                    sb.append(tag).append("\n");
                    // Truncate body to first 200 chars
                    if (body.length() > 200) body = body.substring(0, 200) + "...";
                    sb.append(body).append("\n\n");
                }
                changelogText = sb.toString().trim();
            } catch (Exception e) {
                changelogText = "Error loading changelog: " + e.getMessage();
            }
        }, "PackUpdater-Changelog");
        thread.setDaemon(true);
        thread.start();
    }

    // ==================== TAB: APEPACKS ====================

    private void buildApepacksTab() {
        apepackFiles = scanApepacks();
        
        int centerX = this.width / 2;
        int panelWidth = 340;
        int panelX = centerX - panelWidth / 2;
        int startY = 55;

        // Pack name field
        packNameField = new EditBox(font, panelX + 20, startY + 25, panelWidth - 40, 20, Component.literal("Pack Name"));
        packNameField.setHint(Component.literal("My Modpack"));
        addRenderableWidget(packNameField);

        // Pack version field
        packVersionField = new EditBox(font, panelX + 20, startY + 50, panelWidth - 40, 20, Component.literal("Pack Version"));
        packVersionField.setHint(Component.literal("1.0.0"));
        addRenderableWidget(packVersionField);

        // Generate button
        generateButton = Button.builder(
                Component.literal("Generate .apepack2"),
                button -> handleGenerateApePack()
        ).bounds(panelX + 20, startY + 75, panelWidth - 40, 20).build();
        addRenderableWidget(generateButton);

        // Open Apepacks folder button
        addRenderableWidget(Button.builder(
                Component.literal("Open Apepacks Folder"),
                button -> openApepacksFolder()
        ).bounds(panelX + 20, startY + 105, panelWidth - 40, 20).build());

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
        ).bounds(panelX + 20, startY + 135, panelWidth - 40, 20).build());
    }

    private void handleCheckForPackUpdate() {
        if (ConfigManager.repo == null || ConfigManager.repo.isEmpty()) {
            showToast("No GitHub repo configured. Set your modpack repo in Settings tab.");
            return;
        }
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
        if (ConfigManager.repo == null || ConfigManager.repo.isEmpty()) {
            showToast("No GitHub repo configured. Set your modpack repo in Settings tab.");
            return;
        }
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

    /**
     * Checks GitHub for a newer PackUpdater mod version and downloads it if found.
     * Only works when self-update is enabled by the player in the config screen.
     * After download, replaces the old JAR and deletes it.
     */
    private void handleCheckForModUpdate() {
        if (checkingModUpdate || downloadingModUpdate) {
            showToast("Already checking or downloading...");
            return;
        }
        
        // Use the PackUpdater mod's own GitHub repo for self-updates
        String modRepo = "Apeiron-team/Apeiron-Pack-Updater";
        
        checkingModUpdate = true;
        modUpdateStatus = "Checking for mod updates...";
        modUpdateProgress = 0.0;
        
        if (checkModUpdateButton != null) {
            checkModUpdateButton.active = false;
            checkModUpdateButton.setMessage(Component.literal("Checking..."));
        }
        
        Thread checkThread = new Thread(() -> {
            try {
                String apiUrl = "https://api.github.com/repos/" + modRepo + "/releases/latest";
                URL url = URI.create(apiUrl).toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                ConfigManager.setupGitHubConnection(conn);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(30000);
                
                if (conn.getResponseCode() != 200) {
                    Minecraft.getInstance().execute(() -> {
                        checkingModUpdate = false;
                        modUpdateStatus = "Failed to check for updates";
                        if (checkModUpdateButton != null) {
                            checkModUpdateButton.active = true;
                            checkModUpdateButton.setMessage(Component.literal("Check for Mod Update"));
                        }
                    });
                    return;
                }
                
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                JsonObject release = JsonParser.parseReader(reader).getAsJsonObject();
                reader.close();
                
                String latestTag = release.get("tag_name").getAsString();
                // Strip "v" prefix for comparison
                String latestVersion = latestTag.startsWith("v") ? latestTag.substring(1) : latestTag;
                String currentVersion = ConfigManager.CURRENT_VERSION;
                // Strip "v" prefix from current version too
                String currentClean = currentVersion.startsWith("v") ? currentVersion.substring(1) : currentVersion;
                
                // Compare versions (compareVersions splits by [.+\\-_] so "1.3.9-26.1.2" works)
                if (compareVersions(latestVersion, currentClean) <= 0) {
                    Minecraft.getInstance().execute(() -> {
                        checkingModUpdate = false;
                        modUpdateStatus = "";
                        showToast("PackUpdater is up to date (v" + currentVersion + ")");
                        if (checkModUpdateButton != null) {
                            checkModUpdateButton.active = true;
                            checkModUpdateButton.setMessage(Component.literal("Check for Mod Update"));
                        }
                    });
                    return;
                }
                
                // Find the JAR asset
                String downloadUrl = null;
                String jarName = null;
                if (release.has("assets") && release.get("assets").isJsonArray()) {
                    for (JsonElement assetElem : release.get("assets").getAsJsonArray()) {
                        JsonObject asset = assetElem.getAsJsonObject();
                        String name = asset.get("name").getAsString();
                        if (name.startsWith("packupdater-") && name.endsWith(".jar")) {
                            downloadUrl = asset.get("browser_download_url").getAsString();
                            jarName = name;
                            break;
                        }
                    }
                }
                
                if (downloadUrl == null) {
                    Minecraft.getInstance().execute(() -> {
                        checkingModUpdate = false;
                        modUpdateStatus = "No JAR found in release";
                        showToast("No JAR found in release v" + latestVersion);
                        if (checkModUpdateButton != null) {
                            checkModUpdateButton.active = true;
                            checkModUpdateButton.setMessage(Component.literal("Check for Mod Update"));
                        }
                    });
                    return;
                }
                
                // Download the new JAR
                checkingModUpdate = false;
                downloadingModUpdate = true;
                modUpdateStatus = "Downloading v" + latestVersion + "...";
                
                Minecraft.getInstance().execute(() -> {
                    if (checkModUpdateButton != null) {
                        checkModUpdateButton.setMessage(Component.literal("Downloading..."));
                    }
                });
                
                // Download to temp file
                Path tempJar = Path.of("packupdater-update-temp.jar");
                HttpURLConnection dlConn = (HttpURLConnection) URI.create(downloadUrl).toURL().openConnection();
                ConfigManager.setupGitHubConnection(dlConn);
                dlConn.setConnectTimeout(5000);
                dlConn.setReadTimeout(60000);
                
                long totalSize = dlConn.getContentLengthLong();
                long downloaded = 0;
                
                try (java.io.InputStream is = dlConn.getInputStream();
                     java.io.OutputStream os = Files.newOutputStream(tempJar)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                        downloaded += bytesRead;
                        if (totalSize > 0) {
                            final double progress = (double) downloaded / totalSize;
                            Minecraft.getInstance().execute(() -> {
                                modUpdateProgress = progress;
                                modUpdateStatus = "Downloading v" + latestVersion + ": " + (int)(progress * 100) + "%";
                            });
                        }
                    }
                }
                
                // Replace old JAR(s) and install new one
                modUpdateStatus = "Installing v" + latestVersion + "...";
                modUpdateProgress = 1.0;
                
                try {
                    // Find the mods directory (where PackUpdater JARs live)
                    Path modsDir = PackUpdaterMod.INSTANCE_ROOT.resolve("mods");
                    if (!Files.exists(modsDir)) {
                        throw new IOException("mods/ directory not found");
                    }
                    
                    // Remove all existing PackUpdater JARs
                    List<Path> oldJars = new ArrayList<>();
                    try (var paths = Files.list(modsDir)) {
                        oldJars = paths
                                .filter(p -> p.toString().toLowerCase().startsWith("packupdater") && p.toString().toLowerCase().endsWith(".jar"))
                                .toList();
                    }
                    
                    for (Path oldJar : oldJars) {
                        try {
                            Files.delete(oldJar);
                            System.out.println("[PackUpdater] Removed old JAR: " + oldJar.getFileName());
                        } catch (IOException e) {
                            System.err.println("[PackUpdater] Failed to remove old JAR: " + oldJar + " - " + e.getMessage());
                        }
                    }
                    
                    // Copy new JAR to mods directory
                    Path newJarPath = modsDir.resolve(jarName != null ? jarName : "packupdater-" + latestVersion + ".jar");
                    Files.copy(tempJar, newJarPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("[PackUpdater] Installed new JAR: " + newJarPath.getFileName());
                    
                    // Clean up temp file
                    try { Files.delete(tempJar); } catch (Exception ignored) {}
                    
                    downloadingModUpdate = false;
                    Minecraft.getInstance().execute(() -> {
                        modUpdateStatus = "";
                        modUpdateProgress = 0.0;
                        showToast("PackUpdater updated to v" + latestVersion + "! Restart to apply.");
                        if (checkModUpdateButton != null) {
                            checkModUpdateButton.active = true;
                            checkModUpdateButton.setMessage(Component.literal("Check for Mod Update"));
                        }
                    });
                    
                } catch (Exception e) {
                    System.err.println("[PackUpdater] Failed to install update: " + e.getMessage());
                    // Clean up temp file on failure
                    try { Files.delete(tempJar); } catch (Exception ignored) {}
                    downloadingModUpdate = false;
                    Minecraft.getInstance().execute(() -> {
                        modUpdateStatus = "Install failed: " + e.getMessage();
                        modUpdateProgress = 0.0;
                        showToast("Update install failed: " + e.getMessage());
                        if (checkModUpdateButton != null) {
                            checkModUpdateButton.active = true;
                            checkModUpdateButton.setMessage(Component.literal("Check for Mod Update"));
                        }
                    });
                }
                
            } catch (Exception e) {
                System.err.println("[PackUpdater] Mod update check failed: " + e.getMessage());
                checkingModUpdate = false;
                downloadingModUpdate = false;
                Minecraft.getInstance().execute(() -> {
                    modUpdateStatus = "Check failed: " + e.getMessage();
                    modUpdateProgress = 0.0;
                    showToast("Update check failed: " + e.getMessage());
                    if (checkModUpdateButton != null) {
                        checkModUpdateButton.active = true;
                        checkModUpdateButton.setMessage(Component.literal("Check for Mod Update"));
                    }
                });
            }
        }, "PackUpdater-ModUpdateCheck");
        checkThread.setDaemon(true);
        checkThread.start();
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

    private void renderInfoTab(GuiGraphicsExtractor guiGraphics) {
        int centerX = this.width / 2;
        int panelWidth = 340;
        int panelX = centerX - panelWidth / 2;
        int startY = 55;

        // Info text
        guiGraphics.centeredText(font, "Modpack Information", centerX, 50, 0x88CCFF);

        // Download progress bar
        if (downloading || !downloadStatus.isEmpty()) {
            int barX = panelX + 20;
            int barY = startY + 160;
            int barW = panelWidth - 40;
            int barH = 10;
            
            guiGraphics.fill(barX, barY, barX + barW, barY + barH, 0xFF333333);
            guiGraphics.fill(barX, barY, barX + barW, barY, 0xFF666666);
            guiGraphics.fill(barX, barY + barH - 1, barX + barW, barY + barH, 0xFF666666);
            guiGraphics.fill(barX, barY, barX, barY + barH, 0xFF666666);
            guiGraphics.fill(barX + barW - 1, barY, barX + barW, barY + barH, 0xFF666666);
            
            int filledWidth = (int) (barW * downloadProgress);
            if (filledWidth > 0) {
                guiGraphics.fill(barX + 1, barY + 1, barX + filledWidth, barY + barH - 1, 0xFF3399FF);
            }
            
            guiGraphics.centeredText(font, downloadStatus, centerX, barY + barH + 8, 0xFFFFFF);
            if (!statsText.isEmpty()) {
                guiGraphics.centeredText(font, statsText, centerX, barY + barH + 22, 0xAAAAAA);
            }
        }

        // Changelog display
        if (showingChangelog && !changelogText.isEmpty()) {
            int changelogStartY = startY + 160;
            if (downloading || !downloadStatus.isEmpty()) changelogStartY += 50;
            
            guiGraphics.centeredText(font, "Changelog", centerX, changelogStartY, 0x88CCFF);
            
            String[] lines = changelogText.split("\n");
            int maxLines = (this.height - changelogStartY - 60) / 9;
            int totalLines = lines.length;
            int maxScroll = Math.max(0, totalLines - maxLines);
            changelogScrollOffset = Math.max(0, Math.min(changelogScrollOffset, maxScroll));
            
            for (int i = 0; i < maxLines && changelogScrollOffset + i < totalLines; i++) {
                int y = changelogStartY + 15 + (i * 9);
                String line = lines[changelogScrollOffset + i];
                if (line.isEmpty()) continue;
                // Truncate long lines
                int maxChars = (panelWidth - 40) / 4; // rough estimate: ~4px per char
                if (line.length() > maxChars) {
                    line = line.substring(0, maxChars) + "...";
                }
                guiGraphics.centeredText(font, line, centerX, y, 0xCCCCCC);
            }
        }

        // Status
        if (statusText.getString() != null && !statusText.getString().isEmpty() && !showingChangelog) {
            int color = 0xFFFF55;
            String msg = statusText.getString();
            if (msg.contains("up to date")) color = 0x55FF55;
            else if (msg.contains("Update available")) color = 0x55FF55;
            else if (msg.contains("Error") || msg.contains("failed")) color = 0xFF5555;
            int statusY = startY + 160;
            if (downloading || !downloadStatus.isEmpty()) statusY += 50;
            guiGraphics.centeredText(font, msg, centerX, statusY, color);
        }
    }

    private void renderApepacksTab(GuiGraphicsExtractor guiGraphics) {
        int centerX = this.width / 2;
        
        // Info text
        guiGraphics.centeredText(font, "Generate and manage .apepack2 files", centerX, 50, 0x88CCFF);
        guiGraphics.centeredText(font, "Create packs, browse, or open the apepacks folder", centerX, 67, 0x666666);

        // Status
        if (statusText.getString() != null && !statusText.getString().isEmpty()) {
            int color = 0xFFFF55;
            String msg = statusText.getString();
            if (msg.contains("generated") || msg.contains("mods")) color = 0x55FF55;
            else if (msg.contains("Error") || msg.contains("failed")) color = 0xFF5555;
            guiGraphics.centeredText(font, msg, centerX, 170, color);
        }
    }

    // ==================== TAB: SETTINGS ====================

    private void buildSettingsTab() {
        int centerX = this.width / 2;
        int panelWidth = 340;
        int panelX = centerX - panelWidth / 2;
        int startY = 55;

        // GitHub Repo field
        repoField = new EditBox(font, panelX + 20, startY + 10, panelWidth - 40, 20, Component.literal("GitHub Repo"));
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
        tokenField = new EditBox(font, panelX + 20, startY + 40, panelWidth - 40, 20, Component.literal("GitHub Token"));
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
        ).bounds(panelX + 20, startY + 70, panelWidth - 40, 20).build());
        // Will be hidden after channel check completes if only one channel

        // Self-update toggle
        selfUpdateToggleButton = addRenderableWidget(Button.builder(
                Component.literal("Self-update: " + (ConfigManager.selfUpdateEnabled ? "ON" : "OFF")),
                button -> {
                    ConfigManager.selfUpdateEnabled = !ConfigManager.selfUpdateEnabled;
                    button.setMessage(Component.literal("Self-update: " + (ConfigManager.selfUpdateEnabled ? "ON" : "OFF")));
                    button.active = ConfigManager.selfUpdateEnabled;
                    ConfigManager.saveConfig();
                    showToast(ConfigManager.selfUpdateEnabled ? "Self-update enabled" : "Self-update disabled");
                    // Rebuild tab to show/hide check button
                    rebuildWidgets();
                }
        ).bounds(panelX + 20, startY + 100, panelWidth - 40, 20).build());
        selfUpdateToggleButton.active = ConfigManager.selfUpdateEnabled;
        
        // Check for mod update button (only visible when self-update is enabled)
        if (ConfigManager.selfUpdateEnabled) {
            checkModUpdateButton = addRenderableWidget(Button.builder(
                    Component.literal("Check for Mod Update"),
                    button -> handleCheckForModUpdate()
            ).bounds(panelX + 20, startY + 130, panelWidth - 40, 20).build());
        }
        
        // Save button
        addRenderableWidget(Button.builder(
                Component.literal("Save Settings"),
                button -> {
                    ConfigManager.saveConfig();
                    showToast("Settings saved");
                }
        ).bounds(panelX + 20, startY + 160, panelWidth - 40, 20).build());
    }

    private void renderSettingsTab(GuiGraphicsExtractor guiGraphics) {
        int centerX = this.width / 2;
        int panelWidth = 340;
        int panelX = centerX - panelWidth / 2;
        int startY = 55;
        
        // Info text
        guiGraphics.centeredText(font, "Configure update source and preferences", centerX, 50, 0x88CCFF);

        // Token info
        guiGraphics.centeredText(font, "Get a token at: https://github.com/settings/tokens", centerX, 170, 0x444444);
        
        // Self-update info
        if (ConfigManager.selfUpdateEnabled) {
            guiGraphics.centeredText(font, "Self-update lets PackUpdater download and install its own updates", centerX, startY + 95, 0x888888);
            
            // Mod update progress
            if (!modUpdateStatus.isEmpty()) {
                guiGraphics.centeredText(font, modUpdateStatus, centerX, startY + 190, 0xFFAA55);
                
                // Progress bar
                if (modUpdateProgress > 0 && modUpdateProgress <= 1.0) {
                    int barWidth = panelWidth - 40;
                    int barHeight = 10;
                    int barX = panelX + 20;
                    int barY = startY + 200;
                    
                    // Background
                    guiGraphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFF333333);
                    // Fill
                    int filledWidth = (int) (barWidth * modUpdateProgress);
                    guiGraphics.fill(barX, barY, barX + filledWidth, barY + barHeight, 0xFF5555FF);
                    // Border
                    guiGraphics.fill(barX, barY, barX + barWidth, barY + 1, 0xFF666666);
                    guiGraphics.fill(barX, barY + barHeight - 1, barX + barWidth, barY + barHeight, 0xFF666666);
                    guiGraphics.fill(barX, barY, barX + 1, barY + barHeight, 0xFF666666);
                    guiGraphics.fill(barX + barWidth - 1, barY, barX + barWidth, barY + barHeight, 0xFF666666);
                }
            }
        }
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
        if (activeTab == TabId.INFO && showingChangelog && !changelogText.isEmpty()) {
            int centerX = this.width / 2;
            int panelWidth = 340;
            int panelX = centerX - panelWidth / 2;
            int startY = 55;
            int changelogStartY = startY + 160;
            if (downloading || !downloadStatus.isEmpty()) changelogStartY += 50;
            int panelHeight = this.height - changelogStartY - 60;
            if (mouseX >= panelX && mouseX <= panelX + panelWidth && mouseY >= changelogStartY && mouseY <= changelogStartY + panelHeight) {
                String[] lines = changelogText.split("\n");
                int maxLines = panelHeight / 9;
                int maxScroll = Math.max(0, lines.length - maxLines);
                changelogScrollOffset = Math.max(0, Math.min(changelogScrollOffset + (scrollY > 0 ? 1 : -1), maxScroll));
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ==================== RENDER ====================

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Opaque background first to prevent parent screen from showing through as blur
        guiGraphics.fill(0, 0, this.width, this.height, 0xFF101010);

        // Render widgets on top of the background
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);

        // Title
        guiGraphics.centeredText(this.font, "Apeiron Pack Updater", this.width / 2, 8, 0x88CCFF);

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
            case INFO -> renderInfoTab(guiGraphics);
            case APEPACKS -> renderApepacksTab(guiGraphics);
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
                guiGraphics.centeredText(font, toastMessage, width / 2, toastY + 2, (alpha << 24) | 0xFFFFFF);
            }
        }
    }

    @Override
    public void onClose() {
        ConfigManager.saveConfig();
        minecraft.setScreen(parent);
    }
}
