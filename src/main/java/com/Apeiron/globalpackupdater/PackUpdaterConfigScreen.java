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
import net.minecraft.network.chat.MutableComponent;

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
    private Button checkButton;
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

    // Update channel state
    private String latestVersion = null;
    private String currentVersion = ConfigManager.CURRENT_VERSION;
    private boolean updateAvailable = false;

    public PackUpdaterConfigScreen(Screen parent) {
        super(Component.literal("Global Pack Updater Settings"));
        this.parent = parent;
        // Don't block the main thread in the constructor — check channels in init() instead
    }

    /**
     * Extracts a GitHub repo (user/repo) from a URL or direct repo string.
     * Supports:
     * - Full URLs: https://github.com/user/repo/releases/tag/v1.0.0
     * - Short format: user/repo
     * - With or without https:// prefix
     */
    private String extractRepoFromUrl(String input) {
        if (input == null || input.trim().isEmpty()) return null;
        String trimmed = input.trim();
        
        // If it's already a short repo format (user/repo), validate and return it
        if (trimmed.contains("/") && !trimmed.contains("github.com") && !trimmed.startsWith("http")) {
            // Validate: should be two segments, no spaces, alphanumeric with hyphens/underscores
            Pattern shortPattern = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]*(/[a-zA-Z0-9][a-zA-Z0-9._-]*)$");
            if (shortPattern.matcher(trimmed).matches()) {
                return trimmed;
            }
        }
        
        // Match patterns like https://github.com/user/repo/releases/tag/v1.0.0
        // or https://github.com/user/repo/releases/latest
        Pattern pattern = Pattern.compile("github\\.com\\/([^/]+)\\/([^/]+)");
        Matcher matcher = pattern.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group(1) + "/" + matcher.group(2);
        }
        return null;
    }

    /**
     * Shows a toast notification at the top of the screen.
     */
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
            conn.setRequestProperty("User-Agent", "NeoForge-PackUpdater");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);

            if (conn.getResponseCode() != 200) {
                return new UpdateCheckResult(false, "GitHub API error: " + conn.getResponseCode(), null, false);
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            JsonArray releases = JsonParser.parseReader(reader).getAsJsonArray();
            reader.close();

            if (releases.isEmpty()) {
                return new UpdateCheckResult(false, "No releases found", null, false);
            }

            // Find the latest release matching the channel
            for (JsonElement element : releases) {
                JsonObject release = element.getAsJsonObject();
                String tagName = release.get("tag_name").getAsString();
                boolean isPrerelease = release.has("prerelease") && release.get("prerelease").getAsBoolean();

                // Match release based on channel filter
                boolean match = false;
                if (channel.equals("stable") && !isPrerelease) match = true;
                else if (channel.equals("beta") && isPrerelease && tagName.toLowerCase().contains("beta")) match = true;
                else if (channel.equals("nightly") && isPrerelease && (tagName.toLowerCase().contains("nightly") || tagName.toLowerCase().contains("dev"))) match = true;
                // For stable, also accept the first non-prerelease
                else if (channel.equals("stable") && !isPrerelease) match = true;

                if (match) {
                    String cleanTag = tagName.replace("v", "").replace("V", "");
                    boolean isNewer = compareVersions(cleanTag, currentVersion) > 0;
                    
                    // Check for .apepack asset
                    boolean hasApepack = false;
                    if (release.has("assets") && release.get("assets").isJsonArray()) {
                        JsonArray assets = release.get("assets").getAsJsonArray();
                        for (JsonElement asset : assets) {
                            String name = asset.getAsJsonObject().get("name").getAsString();
                            if (name.toLowerCase().endsWith(".apepack")) {
                                hasApepack = true;
                                break;
                            }
                        }
                    }
                    
                    return new UpdateCheckResult(true, "Latest version: " + tagName, cleanTag, isNewer);
                }
            }
            
            return new UpdateCheckResult(false, "No release found for channel: " + channel, null, false);

        } catch (Exception e) {
            return new UpdateCheckResult(false, "Error: " + e.getMessage(), null, false);
        }
    }

    /**
     * Compares two version strings. Returns positive if v1 > v2, negative if v1 < v2, 0 if equal.
     */
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

    /**
     * Result of an update check operation.
     */
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

    private String extractDownloadUrl(String releaseUrl) {
        // Given a release page URL, find the latest asset download URL
        try {
            // If it's a release page, get the assets from the API
            String repo = extractRepoFromUrl(releaseUrl);
            if (repo == null) return null;

            // Determine if this is a specific release or latest
            String tagName = null;
            Pattern tagPattern = Pattern.compile("/tag/([^/]+)");
            Matcher tagMatcher = tagPattern.matcher(releaseUrl);
            if (tagMatcher.find()) {
                tagName = tagMatcher.group(1);
            }

            String apiUrl;
            if (tagName != null) {
                apiUrl = "https://api.github.com/repos/" + repo + "/releases/tags/" + tagName;
            } else {
                apiUrl = "https://api.github.com/repos/" + repo + "/releases/latest";
            }

            URL url = URI.create(apiUrl).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "NeoForge-PackUpdater");
            conn.setConnectTimeout(5000);

            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                JsonObject release = JsonParser.parseReader(reader).getAsJsonObject();
                reader.close();

                // Return the zipball_url or browser_download_url of the first asset
                if (release.has("zipball_url")) {
                    return release.get("zipball_url").getAsString();
                }
                if (release.has("assets") && release.get("assets").isJsonArray()) {
                    JsonArray assets = release.get("assets").getAsJsonArray();
                    if (assets.size() > 0) {
                        return assets.get(0).getAsJsonObject().get("browser_download_url").getAsString();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private void checkAvailableChannels() {
        try {
            String apiUrl = "https://api.github.com/repos/" + ConfigManager.repo + "/releases";
            URL url = URI.create(apiUrl).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "NeoForge-PackUpdater");
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
            // Default to stable if offline
            hasStable = true;
        }
    }

    @Override
    protected void init() {
        super.init();

        // Check available channels on a background thread so we don't block the UI
        Thread channelThread = new Thread(() -> {
            checkAvailableChannels();
            Minecraft.getInstance().execute(() -> {
                // Update the channel button text after channels are detected
                if (this.channelButton != null) {
                    this.channelButton.setMessage(Component.literal("Update Channel: " + ConfigManager.channel.toUpperCase()));
                }
            });
        }, "PackUpdater-Channels");
        channelThread.setDaemon(true);
        channelThread.start();

        int centerX = this.width / 2;
        // Use fixed spacing instead of height/6 to ensure proper scaling at all GUI scales
        int startY = 50;

        // URL input field - accepts full URLs or short repo names (user/repo)
        this.urlField = new EditBox(this.font, centerX - 100, startY, 200, 20, Component.literal("GitHub Repo"));
        this.urlField.setResponder(text -> {
            // Update repo config when URL changes
            String repo = extractRepoFromUrl(text);
            if (repo != null) {
                ConfigManager.repo = repo;
            }
        });
        this.urlField.setHint(Component.literal("user/repo or https://github.com/user/repo"));
        this.urlField.setValue(ConfigManager.lastReleaseUrl != null ? ConfigManager.lastReleaseUrl : ConfigManager.repo);
        this.addRenderableWidget(this.urlField);

        // Check for Update button
        this.checkButton = this.addRenderableWidget(Button.builder(
                Component.literal("Check for Update"),
                button -> {
                    String input = this.urlField.getValue().trim();
                    if (input.isEmpty()) {
                        this.statusText = Component.literal("Please enter a GitHub repo (user/repo) or URL.");
                        showToast("⚠️ Please enter a GitHub repo");
                        return;
                    }

                    String repo = extractRepoFromUrl(input);
                    if (repo == null) {
                        this.statusText = Component.literal("Invalid input. Use 'user/repo' or a GitHub URL.");
                        showToast("⚠️ Invalid repo format");
                        return;
                    }

                    ConfigManager.repo = repo;
                    if (ConfigManager.lastReleaseUrl == null || !ConfigManager.lastReleaseUrl.equals(input)) {
                        ConfigManager.lastReleaseUrl = input;
                    }

                    this.statusText = Component.literal("Checking for updates...");
                    button.active = false;
                    button.setMessage(Component.literal("Checking..."));

                    // Run update check on a background thread
                    Thread checkThread = new Thread(() -> {
                        UpdateCheckResult result = checkForUpdates(repo, ConfigManager.channel);
                        
                        Minecraft.getInstance().execute(() -> {
                            if (result.success) {
                                this.latestVersion = result.version;
                                this.updateAvailable = result.updateAvailable;
                                
                                if (result.updateAvailable) {
                                    this.statusText = Component.literal("Update available! " + result.message);
                                    showToast("🔄 Update available: v" + result.version);
                                    
                                    // Try to download .apepack automatically
                                    button.setMessage(Component.literal("Download Update"));
                                    button.active = true;
                                    
                                    // Change button behavior to download
                                    this.removeWidget(checkButton);
                                    this.checkButton = this.addRenderableWidget(Button.builder(
                                            Component.literal("Download Update"),
                                            btn -> {
                                                this.statusText = Component.literal("Downloading update...");
                                                btn.active = false;
                                                btn.setMessage(Component.literal("Downloading..."));
                                                
                                                Thread downloadThread = new Thread(() -> {
                                                    boolean apePackDownloaded = PackUpdaterMod.downloadLatestApePack(repo);
                                                    Minecraft.getInstance().execute(() -> {
                                                        if (apePackDownloaded) {
                                                            this.statusText = Component.literal(".apepack downloaded! Restart to install.");
                                                            showToast("✅ Update downloaded! Restart to install.");
                                                        } else {
                                                            this.statusText = Component.literal("No .apepack found. Trying zip...");
                                                            String downloadUrl = extractDownloadUrl("https://github.com/" + repo + "/releases/latest");
                                                            if (downloadUrl != null) {
                                                                PackUpdaterMod.downloadAndPrepareUpdate(downloadUrl);
                                                                this.statusText = Component.literal("Update prepared! Restart to apply.");
                                                                showToast("✅ Update prepared! Restart to apply.");
                                                            } else {
                                                                this.statusText = Component.literal("No update assets found.");
                                                                showToast("❌ No update assets found");
                                                            }
                                                        }
                                                        btn.active = true;
                                                        btn.setMessage(Component.literal("Check for Update"));
                                                        
                                                        // Reset to check button
                                                        this.removeWidget(checkButton);
                                                        this.checkButton = this.addRenderableWidget(Button.builder(
                                                                Component.literal("Check for Update"),
                                                                b -> {} // Will be replaced on next init
                                                        ).bounds(centerX - 100, startY + 30, 200, 20).build());
                                                    });
                                                }, "PackUpdater-Download");
                                                downloadThread.setDaemon(true);
                                                downloadThread.start();
                                            }
                                    ).bounds(centerX - 100, startY + 30, 200, 20).build());
                                } else {
                                    this.statusText = Component.literal("You're up to date! " + result.message);
                                    showToast("✅ You're up to date!");
                                    button.active = true;
                                    button.setMessage(Component.literal("Check for Update"));
                                }
                            } else {
                                this.statusText = Component.literal(result.message);
                                showToast("❌ " + result.message);
                                button.active = true;
                                button.setMessage(Component.literal("Check for Update"));
                            }
                        });
                    }, "PackUpdater-Check");
                    checkThread.setDaemon(true);
                    checkThread.start();
                }
        ).bounds(centerX - 100, startY + 30, 200, 20).build());

        // Channel cycling button (dynamically reflects current selection)
        this.channelButton = this.addRenderableWidget(Button.builder(
                Component.literal("Update Channel: " + ConfigManager.channel.toUpperCase()),
                button -> {
                    cycleChannel();
                    button.setMessage(Component.literal("Update Channel: " + ConfigManager.channel.toUpperCase()));
                }
        ).bounds(centerX - 100, startY + 60, 200, 20).build());

        // Generate .apepack section - use relative positioning from startY
        int genY = startY + 120;

        // Pack name field
        this.packNameField = new EditBox(this.font, centerX - 100, genY, 200, 20, Component.literal("Pack Name"));
        this.packNameField.setHint(Component.literal("My Modpack"));
        this.packNameField.setValue("V++ (high Q)");
        this.addRenderableWidget(this.packNameField);

        // Pack version field
        this.packVersionField = new EditBox(this.font, centerX - 100, genY + 25, 200, 20, Component.literal("Pack Version"));
        this.packVersionField.setHint(Component.literal("1.0.0"));
        this.packVersionField.setValue("1.0.0");
        this.addRenderableWidget(this.packVersionField);

        // Generate button
        this.generateButton = this.addRenderableWidget(Button.builder(
                Component.literal("Generate .apepack"),
                button -> {
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
                    button.active = false;
                    button.setMessage(Component.literal("Generating..."));

                    // Run generation on a background thread
                    Thread genThread = new Thread(() -> {
                        try {
                            java.nio.file.Path instanceRoot = java.nio.file.Paths.get(".");
                            java.nio.file.Path outputFile = instanceRoot.resolve("generated_" + packName.replace(" ", "_") + "_" + packVersion + ".apepack");

                            // Get Minecraft version
                            String mcVersion = "26.1.2";

                            ApePackGenerator.GenerateResult result = ApePackGenerator.generate(
                                    instanceRoot, outputFile, packName, packVersion, mcVersion);

                            Minecraft.getInstance().execute(() -> {
                                if (result.success) {
                                    this.statusText = Component.literal(
                                            ".apepack generated! " + result.modCount + " mods, " +
                                            result.configCount + " configs, " + result.scriptCount + " scripts. Saved to: " +
                                            outputFile.getFileName());
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
                                button.active = true;
                                button.setMessage(Component.literal("Generate .apepack"));
                            });
                        } catch (Exception e) {
                            Minecraft.getInstance().execute(() -> {
                                this.statusText = Component.literal("Error: " + e.getMessage());
                                showToast("❌ Error: " + e.getMessage());
                                button.active = true;
                                button.setMessage(Component.literal("Generate .apepack"));
                            });
                        }
                    }, "PackUpdater-Generate");
                    genThread.setDaemon(true);
                    genThread.start();
                }
        ).bounds(centerX - 100, genY + 55, 200, 20).build());

        // Import .apepack section
        int importY = genY + 85;

        // Browse .apepack button - opens the file picker window
        this.browseButton = this.addRenderableWidget(Button.builder(
                Component.literal("Browse .apepack"),
                button -> {
                    // Open the file picker screen
                    ApePackFilePicker filePicker = new ApePackFilePicker(this, selectedPath -> {
                        // File selected callback - launch the import screen
                        Path instanceRoot = java.nio.file.Paths.get(".");
                        ApePackImportScreen importScreen = new ApePackImportScreen(this, selectedPath, instanceRoot);
                        this.minecraft.setScreen(importScreen);
                        importScreen.startImport();
                    });
                    this.minecraft.setScreen(filePicker);
                }
        ).bounds(centerX - 100, importY + 5, 200, 20).build());

        // Done button - position at bottom of screen with padding
        this.addRenderableWidget(Button.builder(
                Component.literal("Done"),
                button -> {
                    ConfigManager.saveConfig();
                    this.minecraft.setScreen(this.parent);
                }
        ).bounds(centerX - 100, this.height - 30, 200, 20).build());
    }


    private void cycleChannel() {
        // Cycle through available channels only, skipping missing tiers (no ghost buttons!)
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
        // Draw a solid background instead of extractBackground() which triggers blur
        // (Minecraft 26.1.2 only allows one blur per frame)
        guiGraphics.fill(0, 0, this.width, this.height, 0xF0000000);
        guiGraphics.centeredText(this.font, this.title, this.width / 2, 20, 0xFFFFFF);

        // Draw instructions above the URL field - use fixed startY
        int startY = 50;
        guiGraphics.centeredText(this.font, "Enter a GitHub repo (user/repo) or full URL:", this.width / 2, startY - 15, 0xCCCCCC);

        // Draw Generate .apepack section header
        int genY = startY + 120;
        guiGraphics.centeredText(this.font, "Generate .apepack Pack File", this.width / 2, genY - 20, 0xCCCCCC);

        // Draw Import .apepack section header
        int importY = genY + 85;
        guiGraphics.centeredText(this.font, "Import .apepack Pack File", this.width / 2, importY - 15, 0xCCCCCC);


        // Draw status message - position between check button and channel button
        if (this.statusText.getString() != null && !this.statusText.getString().isEmpty()) {
            int color = 0xFFFF55;
            String msg = this.statusText.getString();
            if (msg.contains("Update found") || msg.contains("prepared") || msg.contains("downloaded") || msg.contains("generated") || msg.contains("installed") || msg.contains("up to date")) color = 0x55FF55;
            else if (msg.contains("Error") || msg.contains("Invalid") || msg.contains("No downloadable") || msg.contains("No update") || msg.contains("No release")) color = 0xFF5555;
            guiGraphics.centeredText(this.font, msg, this.width / 2, startY + 90, color);
        }

        // Show status of detected channels
        String availableInfo = String.format("Auto-update Tiers -> Stable: %s | Beta: %s | Nightly: %s",
                hasStable, hasBeta, hasNightly);
        guiGraphics.centeredText(this.font, availableInfo, this.width / 2, startY + 110, 0xAAAAAA);

        // Draw toast notification
        if (this.toastMessage != null && this.toastTimer > 0) {
            this.toastTimer--;
            if (this.toastTimer <= 0) {
                this.toastMessage = null;
            } else {
                // Calculate alpha based on timer (fade in/out)
                int alpha;
                if (this.toastTimer > TOAST_DURATION - 20) {
                    // Fade in
                    alpha = (TOAST_DURATION - this.toastTimer) * 255 / 20;
                } else if (this.toastTimer < 20) {
                    // Fade out
                    alpha = this.toastTimer * 255 / 20;
                } else {
                    alpha = 255;
                }
                alpha = Math.min(255, alpha);
                
                int toastWidth = this.font.width(this.toastMessage) + 20;
                int toastX = (this.width - toastWidth) / 2;
                int toastY = 35;
                
                // Background
                guiGraphics.fill(toastX - 2, toastY - 2, toastX + toastWidth + 2, toastY + 14, (alpha << 24) | 0x202020);
                // Border
                guiGraphics.fill(toastX - 2, toastY - 2, toastX + toastWidth + 2, toastY - 1, (alpha << 24) | 0x404040);
                guiGraphics.fill(toastX - 2, toastY + 13, toastX + toastWidth + 2, toastY + 14, (alpha << 24) | 0x404040);
                guiGraphics.fill(toastX - 2, toastY - 1, toastX - 1, toastY + 13, (alpha << 24) | 0x404040);
                guiGraphics.fill(toastX + toastWidth + 1, toastY - 1, toastX + toastWidth + 2, toastY + 13, (alpha << 24) | 0x404040);
                
                // Text
                guiGraphics.centeredText(this.font, this.toastMessage, this.width / 2, toastY, (alpha << 24) | 0xFFFFFF);
            }
        }

        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
    }

    
    @Override
    public void onClose() {
        ConfigManager.saveConfig();
        this.minecraft.setScreen(this.parent);
    }
}