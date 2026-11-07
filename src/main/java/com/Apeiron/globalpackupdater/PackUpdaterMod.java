package com.Apeiron.globalpackupdater;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;

@Mod("packupdater")
public class PackUpdaterMod {
    private static final String CURRENT_VERSION = "1.0.0";
    private static final Path INSTANCE_ROOT = Paths.get(".");
    private static PackUpdaterMod instance;

    public PackUpdaterMod(IEventBus modEventBus, ModContainer modContainer) {
        instance = this;
        modEventBus.addListener(this::onClientSetup);

        // Load local configuration on boot
        ConfigManager.loadConfig();

        // Register the in-game options menu / config screen factory
        modContainer.registerExtensionPoint(
                IConfigScreenFactory.class,
                (java.util.function.Supplier<IConfigScreenFactory>) () -> new IConfigScreenFactory() {
                    @Override
                    public net.minecraft.client.gui.screens.Screen createScreen(
                            ModContainer container,
                            net.minecraft.client.gui.screens.Screen parentScreen) {
                        return new PackUpdaterConfigScreen(parentScreen);
                    }
                }
        );
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        performCleanup();

        // Scan for .apepack files in the mods directory on startup
        Thread scanThread = new Thread(() -> {
            try {
                Path modsDir = INSTANCE_ROOT.resolve("mods");
                if (Files.exists(modsDir)) {
                    ApePackLoader.scanAndInstall(modsDir);
                }
            } catch (Exception e) {
                System.err.println("[PackUpdater] ApePack scan failed: " + e.getMessage());
            }
        }, "PackUpdater-ApePackScan");
        scanThread.setDaemon(true);
        scanThread.start();

        // Check for GitHub updates in background
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
            if (targetChannel.equals("nightly") && isPrerelease && tagName.toLowerCase().contains("nightly"))
                match = true;

            if (match) {
                String cleanTag = tagName.replace("v", "");
                return !cleanTag.equals(CURRENT_VERSION);
            }
        }
        return false;
    }

    private void prepareUpdate(String repo) {
        try {
            // First try to download .apepack file from GitHub releases
            Path modsDir = INSTANCE_ROOT.resolve("mods");
            Files.createDirectories(modsDir);

            // Look for .apepack asset in the latest release
            String apepackUrl = findApePackUrl(repo);
            if (apepackUrl != null) {
                Path targetApePack = modsDir.resolve("update.apepack");
                downloadFile(apepackUrl, targetApePack);
                System.out.println("[PackUpdater] Downloaded .apepack update to " + targetApePack);
                Files.writeString(INSTANCE_ROOT.resolve("update_success.json"), "{\"version\":\"apepack\"}");
                System.exit(0);
            }

            // Fallback to zip download
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

    /**
     * Finds the .apepack download URL from the latest GitHub release.
     */
    private String findApePackUrl(String repo) throws Exception {
        String apiUrl = "https://api.github.com/repos/" + repo + "/releases/latest";
        URL url = URI.create(apiUrl).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "NeoForge-PackUpdater");
        conn.setConnectTimeout(5000);

        if (conn.getResponseCode() != 200) return null;

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        JsonObject release = JsonParser.parseReader(reader).getAsJsonObject();
        reader.close();

        if (release.has("assets") && release.get("assets").isJsonArray()) {
            com.google.gson.JsonArray assets = release.get("assets").getAsJsonArray();
            for (com.google.gson.JsonElement elem : assets) {
                JsonObject asset = elem.getAsJsonObject();
                String name = asset.get("name").getAsString();
                if (name.toLowerCase().endsWith(".apepack")) {
                    return asset.get("browser_download_url").getAsString();
                }
            }
        }
        return null;
    }

    /**
     * Downloads a file from a URL to the target path.
     */
    private void downloadFile(String downloadUrl, Path targetPath) throws Exception {
        URL url = URI.create(downloadUrl).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "NeoForge-PackUpdater");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(60000);

        ReadableByteChannel channel = Channels.newChannel(conn.getInputStream());
        try (FileOutputStream fos = new FileOutputStream(targetPath.toFile())) {
            fos.getChannel().transferFrom(channel, 0, Long.MAX_VALUE);
        }
    }

    public static PackUpdaterMod getInstance() {
        return instance;
    }

    public static void downloadAndPrepareUpdate(String downloadUrl) {
        try {
            Path stagingDir = INSTANCE_ROOT.resolve("update_staging");
            Files.createDirectories(stagingDir);

            Path targetZip = stagingDir.resolve("update.zip");

            URL url = URI.create(downloadUrl).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "NeoForge-PackUpdater");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(60000);

            ReadableByteChannel channel = Channels.newChannel(conn.getInputStream());
            try (FileOutputStream fos = new FileOutputStream(targetZip.toFile())) {
                fos.getChannel().transferFrom(channel, 0, Long.MAX_VALUE);
            }

            // Generate the updater script
            generateUpdaterScript();
            Files.writeString(INSTANCE_ROOT.resolve("update_success.json"), "{\"version\":\"manual\"}");

            System.out.println("[PackUpdater] Update downloaded to " + targetZip);
            System.out.println("[PackUpdater] Run apply_update.bat after closing the game to apply the update.");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Downloads the latest .apepack file from a GitHub repo and places it in the mods folder.
     * Returns true if an .apepack was found and downloaded, false otherwise.
     */
    public static boolean downloadLatestApePack(String repo) {
        try {
            String apepackUrl = findApePackUrlStatic(repo);
            if (apepackUrl == null) {
                System.out.println("[PackUpdater] No .apepack file found in latest release");
                return false;
            }

            Path modsDir = INSTANCE_ROOT.resolve("mods");
            Files.createDirectories(modsDir);
            Path targetApePack = modsDir.resolve("update.apepack");

            URL url = URI.create(apepackUrl).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "NeoForge-PackUpdater");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(120000);

            ReadableByteChannel channel = Channels.newChannel(conn.getInputStream());
            try (FileOutputStream fos = new FileOutputStream(targetApePack.toFile())) {
                fos.getChannel().transferFrom(channel, 0, Long.MAX_VALUE);
            }

            System.out.println("[PackUpdater] Downloaded .apepack update to " + targetApePack);
            System.out.println("[PackUpdater] Restart the game to install the update.");
            return true;

        } catch (Exception e) {
            System.err.println("[PackUpdater] Failed to download .apepack: " + e.getMessage());
            return false;
        }
    }

    /**
     * Static version of findApePackUrl for use from config screen.
     */
    private static String findApePackUrlStatic(String repo) throws Exception {
        String apiUrl = "https://api.github.com/repos/" + repo + "/releases/latest";
        URL url = URI.create(apiUrl).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "NeoForge-PackUpdater");
        conn.setConnectTimeout(5000);

        if (conn.getResponseCode() != 200) return null;

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        JsonObject release = JsonParser.parseReader(reader).getAsJsonObject();
        reader.close();

        if (release.has("assets") && release.get("assets").isJsonArray()) {
            com.google.gson.JsonArray assets = release.get("assets").getAsJsonArray();
            for (com.google.gson.JsonElement elem : assets) {
                JsonObject asset = elem.getAsJsonObject();
                String name = asset.get("name").getAsString();
                if (name.toLowerCase().endsWith(".apepack")) {
                    return asset.get("browser_download_url").getAsString();
                }
            }
        }
        return null;
    }

    private static void generateUpdaterScript() throws IOException {
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
                        try {
                            Files.delete(path);
                        } catch (IOException ignored) {
                        }
                    });
                }
            }

            Files.deleteIfExists(INSTANCE_ROOT.resolve("apply_update.bat"));
            Files.deleteIfExists(INSTANCE_ROOT.resolve("apply_update.sh"));
        } catch (Exception ignored) {
        }
    }
}