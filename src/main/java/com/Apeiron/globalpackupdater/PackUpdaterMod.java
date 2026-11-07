package com.Apeiron.globalpackupdater;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforgededu.client.gui.screens.config.IConfigScreenFactory; // or ModLoadingContext integration

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.*;
import java.util.stream.Stream;

@Mod("packupdater")
public class PackUpdaterMod {
    private static final String CURRENT_VERSION = "1.0.0";
    private static final Path INSTANCE_ROOT = Paths.get(".");

    public PackUpdaterMod(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::onClientSetup);

        // Load local configuration on boot
        ConfigManager.loadConfig();

        // Register the in-game options menu / config screen factory
        modContainer.registerExtensionPoint(net.neoforged.fml.ModLoadingContext.class, () -> null); // Standard NeoForge hook registration
        // Alternatively, register config screen factory directly:
        net.neoforged.fml.ModLoadingContext.get().registerExtensionPoint(
                net.neoforged.neoclient.api.gui.screens.IConfigScreenFactory.class,
                () -> (container, parentScreen) -> new PackUpdaterConfigScreen(parentScreen)
        );
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        performCleanup();

        Thread updateThread = new Thread(() -> {
            try {
                if (isUpdateAvailable(ConfigManager.repo, ConfigManager.channel)) {
                    prepareUpdate(ConfigManager.repo);
                }
            } catch (Exception e) {
                System.err.println("[PackUpdater] Update check skipped: " + e.getMessage());
            }
        }, "PackUpdater-Thread");

        updateThread.setDaemon(true);
        updateThread.start();
    }

    private boolean isUpdateAvailable(String repo, String targetChannel) throws Exception {
        String apiUrl = "https://api.github.com/repos/" + repo + "/releases";
        URL url = URI.create(apiUrl).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "NeoForge-PackUpdater");
        conn.setConnectTimeout(3000);

        if (conn.getResponseCode() != 200) return false;

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        com.google.gson.JsonArray releases = JsonParser.parseReader(reader).getAsJsonArray();
        reader.close();

        for (com.google.gson.JsonElement element : releases) {
            JsonObject release = element.getAsJsonObject();
            String tagName = release.get("tag_name").getAsString();
            boolean isPrerelease = release.get("prerelease").getAsBoolean();

            // Match release based on channel filter
            boolean match = false;
            if (targetChannel.equals("stable") && !isPrerelease) match = true;
            if (targetChannel.equals("beta") && isPrerelease && tagName.toLowerCase().contains("beta")) match = true;
            if (targetChannel.equals("nightly") && isPrerelease && tagName.toLowerCase().contains("nightly")) match = true;

            if (match) {
                String cleanTag = tagName.replace("v", "");
                return !cleanTag.equals(CURRENT_VERSION);
            }
        }
        return false;
    }

    private void prepareUpdate(String repo) {
        try {
            Path stagingDir = INSTANCE_ROOT.resolve("update_staging");
            Files.createDirectories(stagingDir);

            String downloadUrl = "https://github.com/" + repo + "/releases/latest/download/pack-update.zip";
            Path targetZip = stagingDir.resolve("update.zip");

            ReadableByteChannel channel = Channels.newChannel(URI.create(downloadUrl).toURL().openStream());
            try (FileOutputStream fos = new FileOutputStream(targetZip.toFile())) {
                fos.getChannel().transferFrom(channel, 0, Long.MAX_VALUE);
            }

            generateUpdaterScript();
            Files.writeString(INSTANCE_ROOT.resolve("update_success.json"), "{\"version\":\"latest\"}");
            System.exit(0);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void generateUpdaterScript() throws IOException {
        String scriptContent =
                "@echo off\n" +
                        "echo Waiting for Minecraft to exit...\n" +
                        "timeout /t 3 /nobreak >nul\n" +
                        "echo Backing up protected local configs (options.txt)...\n" +
                        "if exist options.txt copy /y options.txt update_staging\\options.txt.bak >nul\n" +
                        "echo Applying update package...\n" +
                        "powershell -Command \"Expand-Archive -Path 'update_staging\\update.zip' -DestinationPath '.' -Force\"\n" +
                        "echo Restoring local configs...\n" +
                        "if exist update_staging\\options.txt.bak copy /y update_staging\\options.txt.bak options.txt >nul\n" +
                        "rmdir /s /q update_staging\n" +
                        "echo Restarting game...\n" +
                        "exit";

        Files.writeString(INSTANCE_ROOT.resolve("apply_update.bat"), scriptContent);
    }

    private void performCleanup() {
        try {
            Path breadcrumb = INSTANCE_ROOT.resolve("update_success.json");
            if (Files.exists(breadcrumb)) {
                Files.delete(breadcrumb);
            }

            Path stagingDir = INSTANCE_ROOT.resolve("update_staging");
            if (Files.exists(stagingDir)) {
                try (Stream<Path> paths = Files.walk(stagingDir)) {
                    paths.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                        try { Files.delete(path); } catch (IOException ignored) {}
                    });
                }
            }

            Files.deleteIfExists(INSTANCE_ROOT.resolve("apply_update.bat"));
            Files.deleteIfExists(INSTANCE_ROOT.resolve("apply_update.sh"));
        } catch (Exception ignored) {}
    }
}