package com.Apeiron.globalpackupdater;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class PackUpdaterConfigScreen extends Screen {
    private final Screen parent;
    private boolean hasStable = false;
    private boolean hasBeta = false;
    private boolean hasNightly = false;

    private Button channelButton;

    public PackUpdaterConfigScreen(Screen parent) {
        super(Component.literal("Global Pack Updater Settings"));
        this.parent = parent;
        checkAvailableChannels();
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

        int centerX = this.width / 2;
        int startY = this.height / 4;

        // Channel cycling button (dynamically reflects current selection)
        this.channelButton = this.addRenderableWidget(Button.builder(
                Component.literal("Update Channel: " + ConfigManager.channel.toUpperCase()),
                button -> {
                    cycleChannel();
                    button.setMessage(Component.literal("Update Channel: " + ConfigManager.channel.toUpperCase()));
                }
        ).bounds(centerX - 100, startY + 20, 200, 20).build());

        // Done button
        this.addRenderableWidget(Button.builder(
                Component.literal("Done"),
                button -> {
                    ConfigManager.saveConfig();
                    this.minecraft.setScreen(this.parent);
                }
        ).bounds(centerX - 100, startY + 80, 200, 20).build());
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
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 40, 0xFFFFFF);

        // Show status of detected channels below
        String availableInfo = String.format("Available Tiers -> Stable: %s | Beta: %s | Nightly: %s",
                hasStable, hasBeta, hasNightly);
        guiGraphics.drawCenteredString(this.font, availableInfo, this.width / 2, 70, 0xAAAAAA);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        ConfigManager.saveConfig();
        this.minecraft.setScreen(this.parent);
    }
}