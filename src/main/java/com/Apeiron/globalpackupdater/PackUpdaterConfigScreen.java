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
 * Complete overhaul of the PackUpdater config screen with a clean tabbed interface.
 * Uses custom tab system since TabManager is not available in MC 26.1.2.
 * 
 * Tabs:
 * - Updates: Check for and download .apepack updates from GitHub
 * - Apepacks: Browse and manage downloaded .apepack files
 * - Generate: Create .apepack files from the current instance
 * - Settings: GitHub repo, token, channel selection
 */
public class PackUpdaterConfigScreen extends Screen {

    enum TabId { UPDATES, APEPACKS, GENERATE, SETTINGS }
    
    private final Screen parent;
    private TabId activeTab = TabId.UPDATES;
    
    // Tab buttons
    private Button btnUpdates;
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

    // Updates tab state
    private Button checkButton;
    private Button downloadButton = null;

    // Apepacks tab state
    private List<ApePackFileInfo> apepackFiles = new ArrayList<>();
    private int apepackScrollOffset = 0;
    private static final int APEPACK_ROW_HEIGHT = 22;

    public PackUpdaterConfigScreen(Screen parent) {
        super(Component.literal("Apeiron Pack Updater"));
        this.parent = parent;
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
                }
            });
        }, "PackUpdater-Channels");
        channelThread.setDaemon(true);
        channelThread.start();

        // Reset download manager if stuck
        PackUpdaterMod.DownloadManager dm = PackUpdaterMod.downloadManager;
        if (dm.status == PackUpdaterMod.DownloadStatus.CHECKING 
                || dm.status == PackUpdaterMod.DownloadStatus.DOWNLOADING
                || dm.status == PackUpdaterMod.DownloadStatus.VERIFYING) {
            dm.setIdle();
        }

        // Build tab buttons
        buildTabButtons();
        
        // Build active tab content
        buildTabContent();
    }

    private void buildTabButtons() {
        tabButtons.clear();
        int tabBarY = 25;
        int tabWidth = 80;
        int tabHeight = 20;
        int gap = 4;
        int totalWidth = 4 * tabWidth + 3 * gap;
        int startX = (this.width - totalWidth) / 2;

        int[] positions = new int[4];
        for (int i = 0; i < 4; i++) {
            positions[i] = startX + i * (tabWidth + gap);
        }

        btnUpdates = addRenderableWidget(Button.builder(
                Component.literal("🔄 Updates"),
                b -> switchTab(TabId.UPDATES)
        ).bounds(positions[0], tabBarY, tabWidth, tabHeight).build());
        tabButtons.add(btnUpdates);

        btnApepacks = addRenderableWidget(Button.builder(
                Component.literal("📦 Apepacks"),
                b -> switchTab(TabId.APEPACKS)
        ).bounds(positions[1], tabBarY, tabWidth, tabHeight).build());
        tabButtons.add(btnApepacks);

        btnGenerate = addRenderableWidget(Button.builder(
                Component.literal("🛠 Generate"),
                b -> switchTab(TabId.GENERATE)
        ).bounds(positions[2], tabBarY, tabWidth, tabHeight).build());
        tabButtons.add(btnGenerate);

        btnSettings = addRenderableWidget(Button.builder(
                Component.literal("⚙ Settings"),
                b -> switchTab(TabId.SETTINGS)
        ).bounds(positions[3], tabBarY, tabWidth, tabHeight).build());
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

        // Remove all non-tab widgets
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
    }

    private void updateTabButtonStyles() {
        for (Button btn : tabButtons) {
            btn.active = true;
        }
        // Style active tab button (brighter)
        // (handled in render via background drawing)
    }

    private void buildTabContent() {
        switch (activeTab) {
            case UPDATES -> buildUpdatesTab();
            case APEPACKS -> buildApepacksTab();
            case GENERATE -> buildGenerateTab();
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
     * Checks for mod updates — compares GitHub release tags against the installed mod version.
     */
    private UpdateCheckResult checkForUpdates(String repo, String channel) {
        return checkForModUpdates(repo, channel);
    }

    /**
     * Checks for mod updates — compares GitHub release tags against the installed mod version.
     */
    private UpdateCheckResult checkForModUpdates(String repo, String channel) {
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
                if (conn.getResponseCode() == 403) {
                    errorMsg = "Rate limit exceeded. Add a GitHub token in Settings.";
                }
                return new UpdateCheckResult(false, errorMsg, null, false);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            JsonArray releases = JsonParser.parseReader(reader).getAsJsonArray();
            reader.close();
            if (releases.isEmpty()) return new UpdateCheckResult(false, "No releases found", null, false);
            for (JsonElement element : releases) {
                JsonObject release = element.getAsJsonObject();
                String tagName = release.get("tag_name").getAsString();
                boolean isPrerelease = release.has("prerelease") && release.get("prerelease").getAsBoolean();
                boolean match = false;
                if (channel.equals("stable") && !isPrerelease) match = true;
                else if (channel.equals("beta") && isPrerelease && tagName.toLowerCase().contains("beta")) match = true;
                else if (channel.equals("nightly") && isPrerelease && (tagName.toLowerCase().contains("nightly") || tagName.toLowerCase().contains("dev"))) match = true;
                if (match) {
                    String cleanTag = tagName.replace("v", "").replace("V", "");
                    boolean isNewer = compareVersions(cleanTag, ConfigManager.CURRENT_VERSION) > 0;
                    return new UpdateCheckResult(true, "Latest: " + tagName, cleanTag, isNewer);
                }
            }
            return new UpdateCheckResult(false, "No release for channel: " + channel, null, false);
        } catch (Exception e) {
            return new UpdateCheckResult(false, "Error: " + e.getMessage(), null, false);
        }
    }

    /**
     * Checks for pack (.apepack) updates — compares the .apepack version from GitHub release
     * assets against the installed modpack version. Falls back to the release tag if no
     * .apepack asset is found.
     */
    private UpdateCheckResult checkForPackUpdates(String repo, String channel) {
        String localVersion = ConfigManager.modpackVersion;
        if (localVersion == null || localVersion.isEmpty()) {
            localVersion = "v0.0";
        }
        // Strip leading 'v' or 'V' for comparison
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
                if (conn.getResponseCode() == 403) {
                    errorMsg = "Rate limit exceeded. Add a GitHub token in Settings.";
                }
                return new UpdateCheckResult(false, errorMsg, null, false);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            JsonArray releases = JsonParser.parseReader(reader).getAsJsonArray();
            reader.close();
            if (releases.isEmpty()) return new UpdateCheckResult(false, "No releases found", null, false);

            // Track the highest .apepack version found across all matching releases
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
                    // Check for .apepack assets in this release
                    if (release.has("assets") && release.get("assets").isJsonArray()) {
                        JsonArray assets = release.get("assets").getAsJsonArray();
                        for (JsonElement assetElem : assets) {
                            JsonObject asset = assetElem.getAsJsonObject();
                            String name = asset.get("name").getAsString();
                            if (name.toLowerCase().endsWith(".apepack")) {
                                // Extract version from the .apepack filename
                                // Format: "packname-X.Y.Z.apepack" or "update-YYYY-MM-DD.apepack"
                                String apePackVer = extractVersionFromApePackName(name);
                                if (apePackVer != null) {
                                    if (latestApePackVersion == null || compareVersions(apePackVer, latestApePackVersion) > 0) {
                                        latestApePackVersion = apePackVer;
                                        latestReleaseTag = tagName;
                                    }
                                }
                                break; // Use first .apepack found in this release
                            }
                        }
                    }
                    // Do NOT fall back to mod release tag — that would compare mod versions
                    // against pack versions, causing false "update available" notifications.
                    // Only use .apepack asset versions for pack checks.
                }
            }

            if (latestApePackVersion == null) {
                // No .apepack assets on GitHub — the installed pack is the latest
                System.out.println("[PackUpdater] Pack check: no .apepack on GitHub, local=" + localClean + " (up to date)");
                return new UpdateCheckResult(true, "No .apepack on GitHub", localClean, false);
            }

            boolean isNewer = compareVersions(latestApePackVersion, localClean) > 0;
            System.out.println("[PackUpdater] Pack check: GitHub=" + latestApePackVersion + ", local=" + localClean + ", isNewer=" + isNewer);
            return new UpdateCheckResult(true, "Latest: " + (latestReleaseTag != null ? latestReleaseTag : latestApePackVersion), latestApePackVersion, isNewer);

        } catch (Exception e) {
            return new UpdateCheckResult(false, "Error: " + e.getMessage(), null, false);
        }
    }

    /**
     * Extracts version from an .apepack filename like "V-High-Q-1.1.0.apepack" → "1.1.0"
     * or "update-2026-11-08.apepack" → null (date-based names have no version).
     */
    private String extractVersionFromApePackName(String name) {
        // Remove .apepack extension
        String base = name.toLowerCase().replaceAll("\\.apepack$", "");
        // Try to find a version pattern (X.Y.Z or X.Y) at the end
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+[\\.\\-]\\d+(?:[\\.\\-]\\d+)*)$").matcher(base);
        if (m.find()) {
            return m.group(1).replace("-", ".");
        }
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

    // ==================== TAB: UPDATES ====================

    private void buildUpdatesTab() {
        int centerX = this.width / 2;
        int panelWidth = 340;
        int panelX = centerX - panelWidth / 2;
        int startY = 55;

        // Pack version display — checks for .apepack pack updates, NOT mod updates
        addRenderableWidget(Button.builder(
                Component.literal("📦 Pack: " + ConfigManager.modpackVersion),
                button -> {
                    Thread checkThread = new Thread(() -> {
                        UpdateCheckResult result = checkForPackUpdates(ConfigManager.repo, ConfigManager.channel);
                        Minecraft.getInstance().execute(() -> {
                            if (result.success && result.updateAvailable) {
                                showToast("🔄 Pack update: v" + result.version + " (installed: " + ConfigManager.modpackVersion + ")");
                                statusText = Component.literal("Pack update available: v" + result.version);
                            } else if (result.success) {
                                showToast("✅ Pack up to date: " + ConfigManager.modpackVersion);
                                statusText = Component.literal("Pack up to date: " + ConfigManager.modpackVersion);
                            } else {
                                showToast("❌ " + result.message);
                                statusText = Component.literal(result.message);
                            }
                        });
                    }, "PackUpdater-PackCheck");
                    checkThread.setDaemon(true);
                    checkThread.start();
                }
        ).bounds(panelX + 20, startY + 10, panelWidth - 40, 20).build());

        // Mod version display
        addRenderableWidget(Button.builder(
                Component.literal("🔧 Mod: v" + ConfigManager.CURRENT_VERSION),
                button -> {}
        ).bounds(panelX + 20, startY + 38, panelWidth - 40, 20).build());

        // Check for Update button
        checkButton = addRenderableWidget(Button.builder(
                Component.literal("Check for Update"),
                button -> handleCheckForUpdate()
        ).bounds(panelX + 20, startY + 70, panelWidth - 40, 20).build());
    }

    private void handleCheckForUpdate() {
        PackUpdaterMod.DownloadManager dm = PackUpdaterMod.downloadManager;
        if (dm.downloading) {
            showToast("⚠️ Download already in progress");
            return;
        }
        blockUpdateButtons(true);
        statusText = Component.literal("Checking for updates...");
        if (checkButton != null) {
            checkButton.setMessage(Component.literal("Checking..."));
            checkButton.active = false;
        }

        Thread checkThread = new Thread(() -> {
            dm.setChecking();
            UpdateCheckResult result = checkForUpdates(ConfigManager.repo, ConfigManager.channel);
            Minecraft.getInstance().execute(() -> {
                if (result.success) {
                    dm.latestVersion = result.version;
                    dm.updateAvailable = result.updateAvailable;
                    if (result.updateAvailable) {
                        statusText = Component.literal("Update available: v" + result.version);
                        showToast("🔄 Update available: v" + result.version);
                        dm.setIdle();
                        int bx = checkButton.getX(), by = checkButton.getY(), bw = checkButton.getWidth(), bh = checkButton.getHeight();
                        removeWidget(checkButton);
                        checkButton = null;
                        downloadButton = addRenderableWidget(Button.builder(
                                Component.literal("Download Update"),
                                b -> handleDownloadUpdate()
                        ).bounds(bx, by, bw, bh).build());
                        blockUpdateButtons(false);
                    } else {
                        statusText = Component.literal("You're up to date!");
                        showToast("✅ You're up to date!");
                        dm.setIdle();
                        int bx = checkButton.getX(), by = checkButton.getY(), bw = checkButton.getWidth(), bh = checkButton.getHeight();
                        removeWidget(checkButton);
                        checkButton = addRenderableWidget(Button.builder(
                                Component.literal("Check for Update"),
                                b -> handleCheckForUpdate()
                        ).bounds(bx, by, bw, bh).build());
                        downloadButton = null;
                        blockUpdateButtons(false);
                    }
                } else {
                    statusText = Component.literal(result.message);
                    showToast("❌ " + result.message);
                    dm.setError(result.message);
                    downloadButton = null;
                    blockUpdateButtons(false);
                }
            });
        }, "PackUpdater-Check");
        checkThread.setDaemon(true);
        checkThread.start();
    }

    private void handleDownloadUpdate() {
        PackUpdaterMod.DownloadManager dm = PackUpdaterMod.downloadManager;
        if (dm.downloading) {
            showToast("⚠️ Download already in progress");
            return;
        }
        statusText = Component.literal("Downloading update...");
        blockUpdateButtons(true);

        Thread downloadThread = new Thread(() -> {
            boolean success = PackUpdaterMod.downloadLatestApePack(ConfigManager.repo);
            Minecraft.getInstance().execute(() -> {
                if (success) {
                    statusText = Component.literal("✅ Update downloaded! Restart to install.");
                    showToast("✅ Update downloaded! Restart to install.");
                    dm.setIdle();
                    downloadButton = null;
                    blockUpdateButtons(false);
                } else {
                    String errorMsg = dm.errorMessage != null ? dm.errorMessage : "Download failed";
                    statusText = Component.literal("Download failed: " + errorMsg);
                    showToast("❌ " + errorMsg);
                    downloadButton = null;
                    blockUpdateButtons(false);
                }
            });
        }, "PackUpdater-Download");
        downloadThread.setDaemon(true);
        downloadThread.start();
    }

    private void blockUpdateButtons(boolean block) {
        if (checkButton != null) checkButton.active = !block;
        if (downloadButton != null) downloadButton.active = !block;
    }

    private void renderUpdatesTab(GuiGraphicsExtractor guiGraphics) {
        int centerX = this.width / 2;
        int panelWidth = 340;
        int panelX = centerX - panelWidth / 2;
        int startY = 140;

        // Download progress panel
        PackUpdaterMod.DownloadManager dm = PackUpdaterMod.downloadManager;
        boolean showProgress = dm.status != PackUpdaterMod.DownloadStatus.IDLE;
        if (showProgress) {
            int panelH = 70;
            
            String sText;
            int statusColor;
            switch (dm.status) {
                case CHECKING -> { sText = "🔍 Checking..."; statusColor = 0x88CCFF; }
                case DOWNLOADING -> { sText = "⬇ Downloading..."; statusColor = 0x88CCFF; }
                case VERIFYING -> { sText = "🔐 Verifying..."; statusColor = 0xFFAA00; }
                case DONE -> { sText = "✅ Complete!"; statusColor = 0x55FF55; }
                case ERROR -> { sText = "❌ Failed!"; statusColor = 0xFF5555; }
                default -> { sText = "Idle"; statusColor = 0x888888; }
            }
            guiGraphics.centeredText(font, sText, centerX, startY + 12, statusColor);

            // Progress bar
            int barX = panelX + 20, barY = startY + 28, barW = panelWidth - 40, barH = 10;
            guiGraphics.fill(barX, barY, barX + barW, barY + barH, 0xFF333333);
            guiGraphics.fill(barX, barY, barX + barW, barY, 0xFF666666);
            guiGraphics.fill(barX, barY + barH - 1, barX + barW, barY + barH, 0xFF666666);
            int fillW = (int) (barW * dm.progress);
            if (fillW > 0) {
                int fillColor = switch (dm.status) {
                    case DONE -> 0xFF55FF55;
                    case ERROR -> 0xFFFF5555;
                    case VERIFYING -> 0xFFFFAA00;
                    default -> 0xFF3399FF;
                };
                guiGraphics.fill(barX + 1, barY + 1, barX + fillW, barY + barH - 1, fillColor);
            }
            int pct = (int) (dm.progress * 100);
            String pctStr = pct + "%";
            if (dm.totalBytes > 0) {
                double dMB = dm.downloadedBytes / (1024.0 * 1024.0);
                double tMB = dm.totalBytes / (1024.0 * 1024.0);
                pctStr = String.format("%d%% (%.1f/%.1f MB)", pct, dMB, tMB);
            }
            guiGraphics.centeredText(font, pctStr, centerX, barY + 1, 0xFFFFFF);

            if (dm.status == PackUpdaterMod.DownloadStatus.ERROR && dm.errorMessage != null) {
                String err = dm.errorMessage.length() > 80 ? dm.errorMessage.substring(0, 77) + "..." : dm.errorMessage;
                guiGraphics.centeredText(font, err, centerX, startY + 55, 0xFF5555);
            }
        }

        // Status message
        if (statusText.getString() != null && !statusText.getString().isEmpty()) {
            int color = 0xFFFF55;
            String msg = statusText.getString();
            if (msg.contains("Update") || msg.contains("downloaded") || msg.contains("up to date") || msg.contains("saved")) color = 0x55FF55;
            else if (msg.contains("Error") || msg.contains("Invalid") || msg.contains("failed") || msg.contains("Rate limit")) color = 0xFF5555;
            guiGraphics.centeredText(font, msg, centerX, startY - 15, color);
        }
    }

    // ==================== TAB: APEPACKS ====================

    private void buildApepacksTab() {
        apepackFiles = scanApepacks();
        
        int centerX = this.width / 2;
        int panelWidth = 340;
        int panelX = centerX - panelWidth / 2;
        int startY = 55;

        // Open Apepacks folder button
        addRenderableWidget(Button.builder(
                Component.literal("📂 Open Apepacks Folder"),
                button -> openApepacksFolder()
        ).bounds(panelX + 20, startY + 10, panelWidth - 40, 20).build());

        // Browse and install button
        addRenderableWidget(Button.builder(
                Component.literal("📥 Browse .apepack"),
                button -> {
                    ApePackFilePicker filePicker = new ApePackFilePicker(this, selectedPath -> {
                        Path instanceRoot = Path.of(".");
                        ApePackImportScreen importScreen = new ApePackImportScreen(this, selectedPath, instanceRoot);
                        minecraft.setScreen(importScreen);
                        importScreen.startImport();
                    });
                    minecraft.setScreen(filePicker);
                }
        ).bounds(panelX + 20, startY + 38, panelWidth - 40, 20).build());

        // Clear processed button
        addRenderableWidget(Button.builder(
                Component.literal("🗑 Clear Processed"),
                button -> {
                    try {
                        Path processedPath = Path.of("config", "processed_apepacks.json");
                        if (Files.exists(processedPath)) {
                            Files.delete(processedPath);
                            showToast("✅ Cleared processed .apepack history");
                            apepackFiles = scanApepacks();
                        }
                    } catch (Exception e) {
                        showToast("❌ Failed to clear: " + e.getMessage());
                    }
                }
        ).bounds(panelX + 20, startY + 66, panelWidth - 40, 20).build());
    }

    private List<ApePackFileInfo> scanApepacks() {
        List<ApePackFileInfo> files = new ArrayList<>();
        Path apepacksDir = PackUpdaterMod.getApepacksDir();
        if (!Files.exists(apepacksDir)) return files;
        try (var paths = Files.list(apepacksDir)) {
            files = paths
                    .filter(p -> p.toString().toLowerCase().endsWith(".apepack"))
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
                showToast("❌ Failed to create apepacks folder");
                return;
            }
        }
        try {
            java.awt.Desktop.getDesktop().open(apepacksDir.toFile());
            showToast("📂 Opened apepacks folder");
        } catch (Exception e) {
            showToast("❌ Could not open folder: " + apepacksDir.toAbsolutePath());
        }
    }

    private void renderApepacksTab(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY) {
        int centerX = this.width / 2;
        int panelWidth = 340;
        int panelX = centerX - panelWidth / 2;
        int startY = 96;
        int panelHeight = this.height - startY - 30;

        guiGraphics.centeredText(font, "Downloaded .apepack Files", centerX, startY + 10, 0x88CCFF);

        if (apepackFiles.isEmpty()) {
            guiGraphics.centeredText(font, "No .apepack files found", centerX, startY + 40, 0x666666);
            guiGraphics.centeredText(font, "Updates will be saved here", centerX, startY + 58, 0x444444);
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
            String prefix = info.processed ? "✅ " : "📦 ";
            guiGraphics.centeredText(font, prefix + name, panelX + 80, y + 3, info.processed ? 0x888888 : 0xFFFFFF);

            // Version and size
            String sizeStr = formatSize(info.size);
            guiGraphics.centeredText(font, "v" + info.version + " (" + sizeStr + ")", panelX + panelWidth - 80, y + 3, 0xAAAAAA);
        }

        // Instructions
        guiGraphics.centeredText(font, "Click 'Browse .apepack' to install a file manually", centerX, this.height - 15, 0x555555);
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)) + " MB";
        return (bytes / (1024 * 1024 * 1024)) + " GB";
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
        addRenderableWidget(Button.builder(
                Component.literal("Generate .apepack"),
                button -> handleGenerateApePack()
        ).bounds(panelX + 20, startY + 90, panelWidth - 40, 20).build());
    }

    private void handleGenerateApePack() {
        String packName = packNameField != null ? packNameField.getValue().trim() : "";
        String packVersion = packVersionField != null ? packVersionField.getValue().trim() : "";

        if (packName.isEmpty()) {
            statusText = Component.literal("Please enter a pack name.");
            showToast("⚠️ Please enter a pack name");
            return;
        }
        if (packVersion.isEmpty()) {
            statusText = Component.literal("Please enter a pack version.");
            showToast("⚠️ Please enter a pack version");
            return;
        }

        statusText = Component.literal("Generating .apepack file...");
        showToast("⏳ Generating .apepack...");

        Thread genThread = new Thread(() -> {
            try {
                Path instanceRoot = Path.of(".");
                Path outputFile = instanceRoot.resolve("generated_" + packName.replace(" ", "_") + "_" + packVersion + ".apepack");
                String mcVersion = "26.1.2";

                ApePackGenerator.GenerateResult result = ApePackGenerator.generate(
                        instanceRoot, outputFile, packName, packVersion, mcVersion);

                Minecraft.getInstance().execute(() -> {
                    if (result.success) {
                        statusText = Component.literal(
                                ".apepack generated! " + result.modCount + " mods, " +
                                result.configCount + " configs, " + result.scriptCount + " scripts.");
                        showToast("✅ Generated: " + outputFile.getFileName());
                    } else {
                        statusText = Component.literal("Error: " + result.errorMessage);
                        showToast("❌ Generation failed");
                    }
                });
            } catch (Exception e) {
                Minecraft.getInstance().execute(() -> {
                    statusText = Component.literal("Error: " + e.getMessage());
                    showToast("❌ Error: " + e.getMessage());
                });
            }
        }, "PackUpdater-Generate");
        genThread.setDaemon(true);
        genThread.start();
    }

    private void renderGenerateTab(GuiGraphicsExtractor guiGraphics) {
        int centerX = this.width / 2;
        
        // Info text
        guiGraphics.centeredText(font, "Create an .apepack from your current instance", centerX, 50, 0x88CCFF);
        guiGraphics.centeredText(font, "Includes mods, configs, and KubeJS scripts", centerX, 67, 0x666666);

        // Status
        if (statusText.getString() != null && !statusText.getString().isEmpty()) {
            int color = 0xFFFF55;
            String msg = statusText.getString();
            if (msg.contains("generated") || msg.contains("mods")) color = 0x55FF55;
            else if (msg.contains("Error") || msg.contains("failed")) color = 0xFF5555;
            guiGraphics.centeredText(font, msg, centerX, 130, color);
        }
    }

    // ==================== TAB: SETTINGS ====================

    private void buildSettingsTab() {
        int centerX = this.width / 2;
        int panelWidth = 340;
        int panelX = centerX - panelWidth / 2;
        int startY = 55;

        // GitHub Repo field
        repoField = new EditBox(font, panelX + 20, startY + 20, panelWidth - 40, 20, Component.literal("GitHub Repo"));
        repoField.setMaxLength(Integer.MAX_VALUE);
        repoField.setHint(Component.literal("user/repo or https://github.com/user/repo"));
        repoField.setValue(ConfigManager.lastReleaseUrl != null ? ConfigManager.lastReleaseUrl : ConfigManager.repo);
        repoField.setResponder(text -> {
            String repo = extractRepoFromUrl(text);
            if (repo != null) ConfigManager.repo = repo;
        });
        addRenderableWidget(repoField);

        // GitHub Token field
        tokenField = new EditBox(font, panelX + 20, startY + 50, panelWidth - 40, 20, Component.literal("GitHub Token"));
        tokenField.setMaxLength(Integer.MAX_VALUE);
        tokenField.setHint(Component.literal("Optional: increases API limit to 5,000/hr"));
        tokenField.setValue(ConfigManager.githubToken);
        tokenField.setResponder(text -> ConfigManager.githubToken = text.trim());
        addRenderableWidget(tokenField);

        // Channel button
        channelButton = addRenderableWidget(Button.builder(
                Component.literal("Channel: " + ConfigManager.channel.toUpperCase()),
                button -> {
                    cycleChannel();
                    button.setMessage(Component.literal("Channel: " + ConfigManager.channel.toUpperCase()));
                }
        ).bounds(panelX + 20, startY + 80, panelWidth - 40, 20).build());

        // Save button
        addRenderableWidget(Button.builder(
                Component.literal("Save Settings"),
                button -> {
                    ConfigManager.saveConfig();
                    showToast("✅ Settings saved");
                }
        ).bounds(panelX + 20, startY + 110, panelWidth - 40, 20).build());
    }

    private void renderSettingsTab(GuiGraphicsExtractor guiGraphics) {
        int centerX = this.width / 2;
        
        // Info text
        guiGraphics.centeredText(font, "Configure update source and preferences", centerX, 50, 0x88CCFF);

        // Channel info
        String availableInfo = String.format("Tiers → Stable: %s | Beta: %s | Nightly: %s",
                hasStable, hasBeta, hasNightly);
        guiGraphics.centeredText(font, availableInfo, centerX, 150, 0x666666);

        // Token info
        guiGraphics.centeredText(font, "Get a token at: https://github.com/settings/tokens", centerX, 170, 0x444444);
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
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Background
        guiGraphics.fill(0, 0, this.width, this.height, 0xCC101010);

        // Title
        guiGraphics.centeredText(this.font, "⚙ Apeiron Pack Updater", this.width / 2, 8, 0x88CCFF);

        // Draw active tab highlight
        int tabBarY = 25;
        int tabWidth = 80;
        int tabHeight = 20;
        int gap = 4;
        int totalWidth = 4 * tabWidth + 3 * gap;
        int startX = (this.width - totalWidth) / 2;
        
        // Highlight active tab button background
        int[] tabIndices = {0, 1, 2, 3};
        int activeIndex = activeTab.ordinal();
        int activeX = startX + activeIndex * (tabWidth + gap);
        guiGraphics.fill(activeX - 1, tabBarY - 1, activeX + tabWidth + 1, tabBarY + tabHeight + 1, 0x4466AA);

        // Render active tab content
        switch (activeTab) {
            case UPDATES -> renderUpdatesTab(guiGraphics);
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
                guiGraphics.centeredText(font, toastMessage, width / 2, toastY + 2, (alpha << 24) | 0xFFFFFF);
            }
        }

        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
    }

    /**
     * Draws a styled panel with border and background.
     */
    private void drawPanel(GuiGraphicsExtractor guiGraphics, int x, int y, int width, int height) {
        guiGraphics.fill(x, y, x + width, y + height, 0xAA1A1A2E);
        guiGraphics.fill(x, y, x + width, y + 1, 0x4466AA);
        guiGraphics.fill(x, y, x + 1, y + height, 0x334488);
        guiGraphics.fill(x + width - 1, y, x + width, y + height, 0x334488);
        guiGraphics.fill(x, y + height - 1, x + width, y + height, 0x222244);
    }

    @Override
    public void onClose() {
        ConfigManager.saveConfig();
        minecraft.setScreen(parent);
    }
}
