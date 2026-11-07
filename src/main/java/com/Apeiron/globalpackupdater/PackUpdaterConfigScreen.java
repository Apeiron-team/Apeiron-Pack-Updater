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
    private Button checkButton;
    private Component statusText = Component.literal("");

    // Generate .apepack fields
    private EditBox packNameField;
    private EditBox packVersionField;
    private Button generateButton;
    // Import .apepack fields
    private Button browseButton;

    public PackUpdaterConfigScreen(Screen parent) {
        super(Component.literal("Global Pack Updater Settings"));
        this.parent = parent;
        // Don't block the main thread in the constructor — check channels in init() instead
    }

    private String extractRepoFromUrl(String url) {
        // Match patterns like https://github.com/user/repo/releases/tag/v1.0.0
        // or https://github.com/user/repo/releases/latest
        Pattern pattern = Pattern.compile("github\\.com\\/([^/]+)\\/([^/]+)");
        Matcher matcher = pattern.matcher(url);
        if (matcher.find()) {
            return matcher.group(1) + "/" + matcher.group(2);
        }
        return null;
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

        // URL input field
        this.urlField = new EditBox(this.font, centerX - 100, startY, 200, 20, Component.literal("GitHub Release URL"));
        this.urlField.setResponder(text -> {
            // Update repo config when URL changes
            String repo = extractRepoFromUrl(text);
            if (repo != null) {
                ConfigManager.repo = repo;
            }
        });
        this.urlField.setHint(Component.literal("https://github.com/user/repo/releases/tag/v1.0.0"));
        this.urlField.setValue(ConfigManager.lastReleaseUrl != null ? ConfigManager.lastReleaseUrl : "");
        this.addRenderableWidget(this.urlField);

        // Check for Update button (downloads .apepack if available, falls back to zip)
        this.checkButton = this.addRenderableWidget(Button.builder(
                Component.literal("Check for Update"),
                button -> {
                    String url = this.urlField.getValue().trim();
                    if (url.isEmpty()) {
                        this.statusText = Component.literal("Please enter a GitHub release URL.");
                        return;
                    }

                    String repo = extractRepoFromUrl(url);
                    if (repo == null) {
                        this.statusText = Component.literal("Invalid GitHub URL. Must be a GitHub releases link.");
                        return;
                    }

                    ConfigManager.repo = repo;
                    if (ConfigManager.lastReleaseUrl == null || !ConfigManager.lastReleaseUrl.equals(url)) {
                        ConfigManager.lastReleaseUrl = url;
                    }

                    this.statusText = Component.literal("Checking for updates...");
                    button.active = false;
                    button.setMessage(Component.literal("Checking..."));

                    // Run update check on a background thread
                    Thread checkThread = new Thread(() -> {
                        try {
                            // Try .apepack first
                            boolean apePackDownloaded = PackUpdaterMod.downloadLatestApePack(repo);
                            if (apePackDownloaded) {
                                Minecraft.getInstance().execute(() -> {
                                    this.statusText = Component.literal(".apepack update downloaded! Restart to install.");
                                    button.active = true;
                                    button.setMessage(Component.literal("Check for Update"));
                                });
                            } else {
                                // Fallback to zip download
                                String downloadUrl = extractDownloadUrl(url);
                                if (downloadUrl != null) {
                                    PackUpdaterMod.downloadAndPrepareUpdate(downloadUrl);
                                    Minecraft.getInstance().execute(() -> {
                                        this.statusText = Component.literal("Update found and prepared! Restart to apply.");
                                        button.active = true;
                                        button.setMessage(Component.literal("Check for Update"));
                                    });
                                } else {
                                    Minecraft.getInstance().execute(() -> {
                                        this.statusText = Component.literal("No update assets found for this release.");
                                        button.active = true;
                                        button.setMessage(Component.literal("Check for Update"));
                                    });
                                }
                            }
                        } catch (Exception e) {
                            Minecraft.getInstance().execute(() -> {
                                this.statusText = Component.literal("Error: " + e.getMessage());
                                button.active = true;
                                button.setMessage(Component.literal("Check for Update"));
                            });
                        }
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
                                } else {
                                    this.statusText = Component.literal("Error: " + result.errorMessage);
                                }
                                button.active = true;
                                button.setMessage(Component.literal("Generate .apepack"));
                            });
                        } catch (Exception e) {
                            Minecraft.getInstance().execute(() -> {
                                this.statusText = Component.literal("Error: " + e.getMessage());
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
        guiGraphics.centeredText(this.font, "Paste a GitHub release link below:", this.width / 2, startY - 15, 0xCCCCCC);

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
            if (msg.contains("Update found") || msg.contains("prepared") || msg.contains("downloaded") || msg.contains("generated") || msg.contains("installed")) color = 0x55FF55;
            else if (msg.contains("Error") || msg.contains("Invalid") || msg.contains("No downloadable") || msg.contains("No update")) color = 0xFF5555;
            guiGraphics.centeredText(this.font, msg, this.width / 2, startY + 90, color);
        }

        // Show status of detected channels
        String availableInfo = String.format("Auto-update Tiers -> Stable: %s | Beta: %s | Nightly: %s",
                hasStable, hasBeta, hasNightly);
        guiGraphics.centeredText(this.font, availableInfo, this.width / 2, startY + 110, 0xAAAAAA);

        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
    }

    
    @Override
    public void onClose() {
        ConfigManager.saveConfig();
        this.minecraft.setScreen(this.parent);
    }
}