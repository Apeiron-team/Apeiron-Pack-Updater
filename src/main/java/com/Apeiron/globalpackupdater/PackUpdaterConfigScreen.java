package com.Apeiron.globalpackupdater;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PackUpdaterConfigScreen extends Screen {
    private final Screen parent;
    private boolean hasStable = false;
    private boolean hasBeta = false;
    private boolean hasNightly = false;

    private Button channelButton;
    private EditBox urlField;
    private EditBox tokenField;
    private Button checkButton;
    private Button downloadButton = null; // The "Download Update" button (created dynamically)
    private Component statusText = Component.literal("");

    // Generate .apepack fields
    private EditBox packNameField;
    private EditBox packVersionField;
    private Button generateButton;
    // Import .apepack fields
    private Button browseButton;

    // Toast notification state
    private String toastMessage = null;
    private int toastTimer = 0;
    private static final int TOAST_DURATION = 120; // 2 seconds at 60fps

    public PackUpdaterConfigScreen(Screen parent) {
        super(Component.literal("Apeiron Pack Updater"));
        this.parent = parent;
    }

    /**
     * Extracts a GitHub repo (user/repo) from a URL or direct repo string.
     */
    private String extractRepoFromUrl(String input) {
        if (input == null || input.trim().isEmpty()) return null;
        String trimmed = input.trim();

        if (trimmed.contains("/") && !trimmed.contains("github.com") && !trimmed.startsWith("http")) {
            Pattern shortPattern = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]*(/[a-zA-Z0-9][a-zA-Z0-9._-]*)$");
            if (shortPattern.matcher(trimmed).matches()) {
                return trimmed;
            }
        }

        Pattern pattern = Pattern.compile("github\\.com\\/([^/]+)\\/([^/]+)");
        Matcher matcher = pattern.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group(1) + "/" + matcher.group(2);
        }
        return null;
    }

    private void showToast(String message) {
        this.toastMessage = message;
        this.toastTimer = TOAST_DURATION;
    }

    /**
     * Checks for updates from a GitHub repo and returns the result.
     */
    private UpdateCheckResult checkForUpdates(String repo, String channel) {
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
                    errorMsg = "GitHub API rate limit exceeded!\nAdd a GitHub token below to increase your limit to 5,000 requests/hour.\nGet one at: https://github.com/settings/tokens";
                }
                return new UpdateCheckResult(false, errorMsg, null, false);
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            JsonArray releases = JsonParser.parseReader(reader).getAsJsonArray();
            reader.close();

            if (releases.isEmpty()) {
                return new UpdateCheckResult(false, "No releases found", null, false);
            }

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
                    return new UpdateCheckResult(true, "Latest version: " + tagName, cleanTag, isNewer);
                }
            }

            return new UpdateCheckResult(false, "No release found for channel: " + channel, null, false);

        } catch (Exception e) {
            return new UpdateCheckResult(false, "Error: " + e.getMessage(), null, false);
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

    private void checkAvailableChannels() {
        try {
            // Save config first so the GitHub token is available
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
                        if (tagName.contains("nightly") || tagName.contains("dev") || tagName.contains("snapshot")) {
                            hasNightly = true;
                        } else if (tagName.contains("beta") || tagName.contains("rc")) {
                            hasBeta = true;
                        }
                    } else {
                        hasStable = true;
                    }
                }
            }
        } catch (Exception e) {
            hasStable = true;
        }
    }

    @Override
    protected void init() {
        super.init();

        // Check available channels on a background thread (only on first init)
        if (this.channelButton == null) {
            Thread channelThread = new Thread(() -> {
                checkAvailableChannels();
                Minecraft.getInstance().execute(() -> {
                    if (this.channelButton != null) {
                        this.channelButton.setMessage(Component.literal("Channel: " + ConfigManager.channel.toUpperCase()));
                    }
                });
            }, "PackUpdater-Channels");
            channelThread.setDaemon(true);
            channelThread.start();
        }

        // Reset download manager if it was in a transient state from a previous session
        PackUpdaterMod.DownloadManager dm = PackUpdaterMod.downloadManager;
        if (dm.status == PackUpdaterMod.DownloadStatus.CHECKING 
                || dm.status == PackUpdaterMod.DownloadStatus.DOWNLOADING
                || dm.status == PackUpdaterMod.DownloadStatus.VERIFYING) {
            dm.setIdle();
        }

        buildWidgets();
    }

    /**
     * Called when the screen is first created or when the window resolution changes.
     * Rebuilds all widgets at the correct positions for the current resolution.
     */
    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        // Preserve user input before rebuilding widgets
        String urlValue = this.urlField != null ? this.urlField.getValue() : "";
        String tokenValue = this.tokenField != null ? this.tokenField.getValue() : "";
        String packNameValue = this.packNameField != null ? this.packNameField.getValue() : "";
        String packVersionValue = this.packVersionField != null ? this.packVersionField.getValue() : "";

        buildWidgets();

        // Restore user input after rebuilding
        if (this.urlField != null) this.urlField.setValue(urlValue);
        if (this.tokenField != null) this.tokenField.setValue(tokenValue);
        if (this.packNameField != null) this.packNameField.setValue(packNameValue);
        if (this.packVersionField != null) this.packVersionField.setValue(packVersionValue);
    }

    /**
     * Builds all widgets for the config screen at the current resolution.
     */
    private void buildWidgets() {
        int centerX = this.width / 2;
        int panelWidth = 340;
        int panelX = centerX - panelWidth / 2;
        int startY = 50;

        // ===================== UPDATE SECTION =====================
        int updatePanelY = startY;
        int updatePanelHeight = 160;

        // URL input field
        this.urlField = new EditBox(this.font, panelX + 20, updatePanelY + 30, panelWidth - 40, 20, Component.literal("GitHub Repo"));
        this.urlField.setMaxLength(Integer.MAX_VALUE);
        this.urlField.setHint(Component.literal("user/repo or https://github.com/user/repo"));
        this.urlField.setValue(ConfigManager.lastReleaseUrl != null ? ConfigManager.lastReleaseUrl : ConfigManager.repo);
        this.urlField.setResponder(text -> {
            String repo = extractRepoFromUrl(text);
            if (repo != null) {
                ConfigManager.repo = repo;
            }
        });
        this.addRenderableWidget(this.urlField);

        // GitHub Token field (optional, for higher API rate limits)
        this.tokenField = new EditBox(this.font, panelX + 20, updatePanelY + 55, panelWidth - 40, 20, Component.literal("GitHub Token"));
        this.tokenField.setMaxLength(Integer.MAX_VALUE);
        this.tokenField.setHint(Component.literal("Optional: increases API limit to 5,000/hr"));
        this.tokenField.setValue(ConfigManager.githubToken);
        this.tokenField.setResponder(text -> {
            ConfigManager.githubToken = text.trim();
        });
        this.addRenderableWidget(this.tokenField);

        // Check for Update button
        this.checkButton = this.addRenderableWidget(Button.builder(
                Component.literal("Check for Update"),
                button -> handleCheckForUpdate()
        ).bounds(panelX + 20, updatePanelY + 80, panelWidth - 40, 20).build());

        // Channel cycling button
        this.channelButton = this.addRenderableWidget(Button.builder(
                Component.literal("Channel: " + ConfigManager.channel.toUpperCase()),
                button -> {
                    cycleChannel();
                    button.setMessage(Component.literal("Channel: " + ConfigManager.channel.toUpperCase()));
                }
        ).bounds(panelX + 20, updatePanelY + 105, panelWidth / 2 - 10, 20).build());

        // Version info button (read-only display)
        this.addRenderableWidget(Button.builder(
                Component.literal("v" + ConfigManager.CURRENT_VERSION),
                button -> {}
        ).bounds(panelX + panelWidth / 2 + 10, updatePanelY + 105, panelWidth / 2 - 10, 20)
                .build());

        // ===================== GENERATE SECTION =====================
        int genPanelY = updatePanelY + updatePanelHeight + 10;
        int genPanelHeight = 120;

        // Pack name field
        this.packNameField = new EditBox(this.font, panelX + 20, genPanelY + 30, panelWidth - 40, 20, Component.literal("Pack Name"));
        this.packNameField.setHint(Component.literal("My Modpack"));
        this.addRenderableWidget(this.packNameField);

        // Pack version field
        this.packVersionField = new EditBox(this.font, panelX + 20, genPanelY + 55, panelWidth - 40, 20, Component.literal("Pack Version"));
        this.packVersionField.setHint(Component.literal("1.0.0"));
        this.addRenderableWidget(this.packVersionField);

        // Generate button
        this.generateButton = this.addRenderableWidget(Button.builder(
                Component.literal("Generate .apepack"),
                button -> handleGenerateApePack()
        ).bounds(panelX + 20, genPanelY + 85, panelWidth - 40, 20).build());

        // ===================== IMPORT SECTION =====================
        int importPanelY = genPanelY + genPanelHeight + 10;

        // Browse .apepack button
        this.browseButton = this.addRenderableWidget(Button.builder(
                Component.literal("Browse .apepack"),
                button -> {
                    ApePackFilePicker filePicker = new ApePackFilePicker(this, selectedPath -> {
                        Path instanceRoot = java.nio.file.Paths.get(".");
                        ApePackImportScreen importScreen = new ApePackImportScreen(this, selectedPath, instanceRoot);
                        this.minecraft.setScreen(importScreen);
                        importScreen.startImport();
                    });
                    this.minecraft.setScreen(filePicker);
                }
        ).bounds(panelX + 20, importPanelY + 30, panelWidth - 40, 20).build());

        // ===================== DOWNLOAD PROGRESS SECTION =====================
        // Only shown when a download is in progress
        // (Progress is drawn in extractRenderState, no widgets needed here)

        // ===================== DONE BUTTON =====================
        this.addRenderableWidget(Button.builder(
                Component.literal("Done"),
                button -> {
                    ConfigManager.saveConfig();
                    this.minecraft.setScreen(this.parent);
                }
        ).bounds(centerX - 100, this.height - 30, 200, 20).build());
    }

    /**
     * Blocks all interactive buttons while a download or verification is in progress.
     */
    private void blockButtons(boolean block) {
        if (this.checkButton != null) this.checkButton.active = !block;
        if (this.downloadButton != null) this.downloadButton.active = !block;
        if (this.generateButton != null) this.generateButton.active = !block;
        if (this.browseButton != null) this.browseButton.active = !block;
        if (this.channelButton != null) this.channelButton.active = !block;
        if (this.urlField != null) this.urlField.setEditable(!block);
        if (this.tokenField != null) this.tokenField.setEditable(!block);
        if (this.packNameField != null) this.packNameField.setEditable(!block);
        if (this.packVersionField != null) this.packVersionField.setEditable(!block);
    }

    /**
     * Handles the "Check for Update" button click — uses global DownloadManager.
     */
    private void handleCheckForUpdate() {
        PackUpdaterMod.DownloadManager dm = PackUpdaterMod.downloadManager;

        // Prevent concurrent downloads
        if (dm.downloading) {
            this.statusText = Component.literal("Download already in progress...");
            showToast("⚠️ Download already in progress");
            return;
        }

        String input = this.urlField.getValue().trim();
        if (input.isEmpty()) {
            this.statusText = Component.literal("Please enter a GitHub repo.");
            showToast("⚠️ Please enter a GitHub repo");
            return;
        }

        String repo = extractRepoFromUrl(input);
        if (repo == null) {
            this.statusText = Component.literal("Invalid repo format. Use 'user/repo'.");
            showToast("⚠️ Invalid repo format");
            return;
        }

        ConfigManager.repo = repo;
        if (ConfigManager.lastReleaseUrl == null || !ConfigManager.lastReleaseUrl.equals(input)) {
            ConfigManager.lastReleaseUrl = input;
        }
        // Save config so the GitHub token is available for API calls
        ConfigManager.saveConfig();

        // Block all buttons during check
        blockButtons(true);

        this.statusText = Component.literal("Checking for updates...");
        this.checkButton.active = false;
        this.checkButton.setMessage(Component.literal("Checking..."));

        Thread checkThread = new Thread(() -> {
            dm.setChecking();
            dm.currentRepo = repo;
            UpdateCheckResult result = checkForUpdates(repo, ConfigManager.channel);

            Minecraft.getInstance().execute(() -> {
                if (result.success) {
                    dm.latestVersion = result.version;
                    dm.updateAvailable = result.updateAvailable;

                    if (result.updateAvailable) {
                        this.statusText = Component.literal("Update available: " + result.message);
                        showToast("🔄 Update available: v" + result.version);
                        dm.setIdle(); // Ready for download

                        // Change button to "Download Update"
                        int btnX = this.checkButton.getX();
                        int btnY = this.checkButton.getY();
                        int btnW = this.checkButton.getWidth();
                        int btnH = this.checkButton.getHeight();
                        this.removeWidget(checkButton);
                        this.downloadButton = this.addRenderableWidget(Button.builder(
                                Component.literal("Download Update"),
                                b -> handleDownloadUpdate(repo)
                        ).bounds(btnX, btnY, btnW, btnH).build());
                        this.checkButton = this.downloadButton; // Keep checkButton in sync
                        // Unblock buttons so user can click Download
                        blockButtons(false);
                    } else {
                        this.statusText = Component.literal("You're up to date! " + result.message);
                        showToast("✅ You're up to date!");
                        dm.setIdle();
                        // Restore check button
                        int btnX = this.checkButton.getX();
                        int btnY = this.checkButton.getY();
                        int btnW = this.checkButton.getWidth();
                        int btnH = this.checkButton.getHeight();
                        this.removeWidget(checkButton);
                        this.downloadButton = null;
                        this.checkButton = this.addRenderableWidget(Button.builder(
                                Component.literal("Check for Update"),
                                b -> handleCheckForUpdate()
                        ).bounds(btnX, btnY, btnW, btnH).build());
                        blockButtons(false);
                    }
                } else {
                    this.statusText = Component.literal(result.message);
                    showToast("❌ " + result.message);
                    dm.setError(result.message);
                    this.downloadButton = null;
                    blockButtons(false);
                }
            });
        }, "PackUpdater-Check");
        checkThread.setDaemon(true);
        checkThread.start();
    }

    /**
     * Handles the "Download Update" button click — uses global DownloadManager.
     */
    private void handleDownloadUpdate(String repo) {
        PackUpdaterMod.DownloadManager dm = PackUpdaterMod.downloadManager;

        // Prevent concurrent downloads
        if (dm.downloading) {
            this.statusText = Component.literal("Download already in progress...");
            showToast("⚠️ Download already in progress");
            return;
        }

        this.statusText = Component.literal("Downloading update...");
        // Block ALL buttons during download and verification
        blockButtons(true);

        Thread downloadThread = new Thread(() -> {
            boolean apePackDownloaded = PackUpdaterMod.downloadLatestApePack(repo);

            Minecraft.getInstance().execute(() -> {
                if (apePackDownloaded) {
                    this.statusText = Component.literal("✅ Update downloaded & verified! Saved to apepacks/ — restart to install.");
                    showToast("✅ Update downloaded and verified! Restart to install.");
                    // Keep dm.status as DONE (already set by downloadLatestApePack)
                    // Show completion status for a few seconds, then reset
                    final int[] countdown = {180}; // 3 seconds at 60fps
                    final PackUpdaterConfigScreen[] self = {this};
                    final Runnable[] resetTask = {null};
                    resetTask[0] = () -> {
                        if (countdown[0] > 0) {
                            countdown[0]--;
                            if (countdown[0] == 0) {
                                // Reset to idle state after showing completion
                                dm.setIdle();
                                if (self[0] != null) {
                                    self[0].downloadButton = null;
                                    self[0].blockButtons(false);
                                    // Restore check button
                                    if (self[0].checkButton != null) {
                                        int btnX = self[0].checkButton.getX();
                                        int btnY = self[0].checkButton.getY();
                                        int btnW = self[0].checkButton.getWidth();
                                        int btnH = self[0].checkButton.getHeight();
                                        self[0].removeWidget(self[0].checkButton);
                                        self[0].checkButton = self[0].addRenderableWidget(Button.builder(
                                                Component.literal("Check for Update"),
                                                b -> self[0].handleCheckForUpdate()
                                        ).bounds(btnX, btnY, btnW, btnH).build());
                                    }
                                }
                            } else {
                                Minecraft.getInstance().execute(resetTask[0]);
                            }
                        }
                    };
                    Minecraft.getInstance().execute(resetTask[0]);
                } else {
                    String errorMsg = dm.errorMessage != null ? dm.errorMessage : "Download failed";
                    this.statusText = Component.literal("Download failed: " + errorMsg);
                    showToast("❌ Download failed: " + errorMsg);
                    // dm.setError already called by downloadLatestApePack
                    this.downloadButton = null;
                    blockButtons(false);
                    // Restore check button
                    if (this.checkButton != null) {
                        int btnX = this.checkButton.getX();
                        int btnY = this.checkButton.getY();
                        int btnW = this.checkButton.getWidth();
                        int btnH = this.checkButton.getHeight();
                        this.removeWidget(checkButton);
                        this.checkButton = this.addRenderableWidget(Button.builder(
                                Component.literal("Check for Update"),
                                b -> handleCheckForUpdate()
                        ).bounds(btnX, btnY, btnW, btnH).build());
                    }
                }
            });
        }, "PackUpdater-Download");
        downloadThread.setDaemon(true);
        downloadThread.start();
    }

    /**
     * Handles the "Generate .apepack" button click.
     */
    private void handleGenerateApePack() {
        String packName = this.packNameField.getValue().trim();
        String packVersion = this.packVersionField.getValue().trim();

        if (packName.isEmpty()) {
            this.statusText = Component.literal("Please enter a pack name.");
            return;
        }
        if (packVersion.isEmpty()) {
            this.statusText = Component.literal("Please enter a pack version.");
            return;
        }

        this.statusText = Component.literal("Generating .apepack file...");
        this.generateButton.active = false;
        this.generateButton.setMessage(Component.literal("Generating..."));

        Thread genThread = new Thread(() -> {
            try {
                java.nio.file.Path instanceRoot = java.nio.file.Paths.get(".");
                java.nio.file.Path outputFile = instanceRoot.resolve("generated_" + packName.replace(" ", "_") + "_" + packVersion + ".apepack");

                String mcVersion = "26.1.2";

                ApePackGenerator.GenerateResult result = ApePackGenerator.generate(
                        instanceRoot, outputFile, packName, packVersion, mcVersion);

                Minecraft.getInstance().execute(() -> {
                    if (result.success) {
                        this.statusText = Component.literal(
                                ".apepack generated! " + result.modCount + " mods, " +
                                result.configCount + " configs, " + result.scriptCount + " scripts.");
                        showToast("✅ .apepack generated: " + outputFile.getFileName());

                        // After generation, check for updates if repo is configured
                        if (ConfigManager.repo != null && !ConfigManager.repo.isEmpty()) {
                            Thread updateCheckThread = new Thread(() -> {
                                UpdateCheckResult updateResult = checkForUpdates(ConfigManager.repo, ConfigManager.channel);
                                Minecraft.getInstance().execute(() -> {
                                    if (updateResult.success && updateResult.updateAvailable) {
                                        showToast("🔄 Update available: v" + updateResult.version);
                                    } else if (updateResult.success) {
                                        showToast("✅ Pack up to date!");
                                    }
                                });
                            }, "PackUpdater-PostGenCheck");
                            updateCheckThread.setDaemon(true);
                            updateCheckThread.start();
                        }
                    } else {
                        this.statusText = Component.literal("Error: " + result.errorMessage);
                        showToast("❌ Generation failed: " + result.errorMessage);
                    }
                    this.generateButton.active = true;
                    this.generateButton.setMessage(Component.literal("Generate .apepack"));
                });
            } catch (Exception e) {
                Minecraft.getInstance().execute(() -> {
                    this.statusText = Component.literal("Error: " + e.getMessage());
                    showToast("❌ Error: " + e.getMessage());
                    this.generateButton.active = true;
                    this.generateButton.setMessage(Component.literal("Generate .apepack"));
                });
            }
        }, "PackUpdater-Generate");
        genThread.setDaemon(true);
        genThread.start();
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

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Dark semi-transparent background
        guiGraphics.fill(0, 0, this.width, this.height, 0xCC101010);

        // Title
        guiGraphics.centeredText(this.font, "⚙ Apeiron Pack Updater", this.width / 2, 18, 0x88CCFF);

        int centerX = this.width / 2;
        int panelWidth = 340;
        int panelX = centerX - panelWidth / 2;
        int startY = 50;

        // ===================== UPDATE PANEL =====================
        int updatePanelY = startY;
        int updatePanelHeight = 130;
        drawPanel(guiGraphics, panelX, updatePanelY, panelWidth, updatePanelHeight);
        guiGraphics.centeredText(this.font, "🔄 Check for Updates", this.width / 2, updatePanelY + 12, 0x88CCFF);

        // ===================== GENERATE PANEL =====================
        int genPanelY = updatePanelY + updatePanelHeight + 10;
        int genPanelHeight = 120;
        drawPanel(guiGraphics, panelX, genPanelY, panelWidth, genPanelHeight);
        guiGraphics.centeredText(this.font, "📦 Generate .apepack", this.width / 2, genPanelY + 12, 0x88CCFF);

        // ===================== IMPORT PANEL =====================
        int importPanelY = genPanelY + genPanelHeight + 10;
        int importPanelHeight = 70;
        drawPanel(guiGraphics, panelX, importPanelY, panelWidth, importPanelHeight);
        guiGraphics.centeredText(this.font, "📥 Import .apepack", this.width / 2, importPanelY + 12, 0x88CCFF);

        // ===================== DOWNLOAD PROGRESS =====================
        PackUpdaterMod.DownloadManager dm = PackUpdaterMod.downloadManager;
        boolean showProgress = dm.status == PackUpdaterMod.DownloadStatus.DOWNLOADING
                || dm.status == PackUpdaterMod.DownloadStatus.CHECKING
                || dm.status == PackUpdaterMod.DownloadStatus.VERIFYING
                || dm.status == PackUpdaterMod.DownloadStatus.DONE
                || dm.status == PackUpdaterMod.DownloadStatus.ERROR;
        if (showProgress) {
            int progressPanelY = importPanelY + importPanelHeight + 10;
            int progressPanelHeight = 60;
            drawPanel(guiGraphics, panelX, progressPanelY, panelWidth, progressPanelHeight);

            String statusText;
            int statusColor = 0x88CCFF;
            switch (dm.status) {
                case CHECKING -> {
                    statusText = "🔍 Checking for updates...";
                    statusColor = 0x88CCFF;
                }
                case DOWNLOADING -> {
                    statusText = "⬇ Downloading update...";
                    statusColor = 0x88CCFF;
                }
                case VERIFYING -> {
                    statusText = "🔐 Verifying download integrity...";
                    statusColor = 0xFFAA00;
                }
                case DONE -> {
                    statusText = "✅ Download complete!";
                    statusColor = 0x55FF55;
                }
                case ERROR -> {
                    statusText = "❌ Download failed!";
                    statusColor = 0xFF5555;
                }
                default -> {
                    statusText = "Idle";
                    statusColor = 0x888888;
                }
            }
            guiGraphics.centeredText(this.font, statusText, this.width / 2, progressPanelY + 12, statusColor);

            // Progress bar background
            int barX = panelX + 20;
            int barY = progressPanelY + 28;
            int barWidth = panelWidth - 40;
            int barHeight = 14;
            guiGraphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0x404040);

            // Progress bar fill (color changes based on status)
            int fillWidth = (int) (barWidth * dm.progress);
            if (fillWidth > 0) {
                int fillColor;
                switch (dm.status) {
                    case DONE -> fillColor = 0x55FF55;
                    case ERROR -> fillColor = 0xFF5555;
                    case VERIFYING -> fillColor = 0xFFAA00;
                    default -> fillColor = 0x33AA33;
                }
                guiGraphics.fill(barX, barY, barX + fillWidth, barY + barHeight, fillColor);
            }

            // Progress percentage
            int percent = (int) (dm.progress * 100);
            String progressStr = percent + "%";
            if (dm.totalBytes > 0) {
                double downloadedMB = dm.downloadedBytes / (1024.0 * 1024.0);
                double totalMB = dm.totalBytes / (1024.0 * 1024.0);
                progressStr = String.format("%d%% (%.1f/%.1f MB)", percent, downloadedMB, totalMB);
            }
            guiGraphics.centeredText(this.font, progressStr, this.width / 2, barY + 1, 0xFFFFFF);

            // Show error message if applicable
            if (dm.status == PackUpdaterMod.DownloadStatus.ERROR && dm.errorMessage != null) {
                String errorMsg = dm.errorMessage;
                if (errorMsg.length() > 80) errorMsg = errorMsg.substring(0, 77) + "...";
                guiGraphics.centeredText(this.font, errorMsg, this.width / 2, progressPanelY + 45, 0xFF5555);
            }
        }

        // ===================== STATUS MESSAGE =====================
        if (this.statusText.getString() != null && !this.statusText.getString().isEmpty()) {
            int color = 0xFFFF55;
            String msg = this.statusText.getString();
            if (msg.contains("Update") || msg.contains("prepared") || msg.contains("downloaded") || msg.contains("generated") || msg.contains("installed") || msg.contains("up to date") || msg.contains("saved")) color = 0x55FF55;
            else if (msg.contains("Error") || msg.contains("Invalid") || msg.contains("No downloadable") || msg.contains("No update") || msg.contains("No release") || msg.contains("failed")) color = 0xFF5555;
            guiGraphics.centeredText(this.font, msg, this.width / 2, startY - 15, color);
        }

        // ===================== CHANNEL INFO =====================
        String availableInfo = String.format("Tiers → Stable: %s | Beta: %s | Nightly: %s",
                hasStable, hasBeta, hasNightly);
        guiGraphics.centeredText(this.font, availableInfo, this.width / 2, this.height - 50, 0x666666);

        // ===================== TOAST NOTIFICATION =====================
        if (this.toastMessage != null && this.toastTimer > 0) {
            this.toastTimer--;
            if (this.toastTimer <= 0) {
                this.toastMessage = null;
            } else {
                int alpha;
                if (this.toastTimer > TOAST_DURATION - 20) {
                    alpha = (TOAST_DURATION - this.toastTimer) * 255 / 20;
                } else if (this.toastTimer < 20) {
                    alpha = this.toastTimer * 255 / 20;
                } else {
                    alpha = 255;
                }
                alpha = Math.min(255, alpha);

                int toastWidth = this.font.width(this.toastMessage) + 24;
                int toastX = (this.width - toastWidth) / 2;
                int toastY = 35;

                // Background with rounded corners effect
                guiGraphics.fill(toastX - 2, toastY - 2, toastX + toastWidth + 2, toastY + 16, (alpha << 24) | 0x1A1A2E);
                // Top border highlight
                guiGraphics.fill(toastX - 2, toastY - 2, toastX + toastWidth + 2, toastY - 1, (alpha << 24) | 0x4466AA);
                // Bottom border
                guiGraphics.fill(toastX - 2, toastY + 15, toastX + toastWidth + 2, toastY + 16, (alpha << 24) | 0x333355);

                // Text
                guiGraphics.centeredText(this.font, this.toastMessage, this.width / 2, toastY + 2, (alpha << 24) | 0xFFFFFF);
            }
        }

        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
    }

    /**
     * Draws a styled panel with border and background.
     */
    private void drawPanel(GuiGraphicsExtractor guiGraphics, int x, int y, int width, int height) {
        // Panel background
        guiGraphics.fill(x, y, x + width, y + height, 0xAA1A1A2E);
        // Top border (highlight)
        guiGraphics.fill(x, y, x + width, y + 1, 0x4466AA);
        // Left border
        guiGraphics.fill(x, y, x + 1, y + height, 0x334488);
        // Right border
        guiGraphics.fill(x + width - 1, y, x + width, y + height, 0x334488);
        // Bottom border (shadow)
        guiGraphics.fill(x, y + height - 1, x + width, y + height, 0x222244);
    }

    @Override
    public void onClose() {
        ConfigManager.saveConfig();
        this.minecraft.setScreen(this.parent);
    }
}
