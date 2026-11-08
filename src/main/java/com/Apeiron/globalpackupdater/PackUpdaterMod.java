package com.Apeiron.globalpackupdater;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

@Mod("packupdater")
public class PackUpdaterMod {
    private static final String CURRENT_VERSION = ConfigManager.CURRENT_VERSION;
    static final Path INSTANCE_ROOT = Paths.get(".").toAbsolutePath().normalize();
    private static PackUpdaterMod instance;

    /**
     * Atomic flag to prevent dual installations - ensures only one .apepack
     * installation process runs at a time across all entry points.
     */
    public static final AtomicBoolean installing = new AtomicBoolean(false);
    
    /**
     * Last installation error message for the config screen to display.
     */
    public static final AtomicReference<String> lastInstallError = new AtomicReference<>(null);

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

        // Register the options menu button
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(new OptionsMenuButton());
    }

    private void onClientSetup(FMLClientSetupEvent event) {

        // Check GitHub for new .apepack2 releases on startup and download if newer
        java.util.concurrent.CountDownLatch githubCheckDone = new java.util.concurrent.CountDownLatch(1);
        Thread githubCheckThread = new Thread(() -> {
            try {
                System.out.println("[PackUpdater] Checking GitHub for updates...");

                String localVersion = ConfigManager.modpackVersion;
                if (localVersion == null || localVersion.isEmpty()) {
                    localVersion = "v0.0";
                }
                String localClean = localVersion.replaceFirst("^[vV]", "");

                String apiUrl = "https://api.github.com/repos/" + ConfigManager.repo + "/releases";
                java.net.URL url = java.net.URI.create(apiUrl).toURL();
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                ConfigManager.setupGitHubConnection(conn);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000);

                if (conn.getResponseCode() != 200) {
                    System.out.println("[PackUpdater] GitHub API error: " + conn.getResponseCode() + ", skipping update check.");
                    return;
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                com.google.gson.JsonArray releases = JsonParser.parseReader(reader).getAsJsonArray();
                reader.close();

                if (releases.isEmpty()) {
                    System.out.println("[PackUpdater] No releases found on GitHub.");
                    return;
                }

                String latestApePackVersion = null;
                String downloadUrl = null;
                String assetName = null;

                for (com.google.gson.JsonElement element : releases) {
                    com.google.gson.JsonObject release = element.getAsJsonObject();
                    String tagName = release.get("tag_name").getAsString();
                    boolean isPrerelease = release.has("prerelease") && release.get("prerelease").getAsBoolean();

                    // Match channel
                    boolean match = false;
                    if (ConfigManager.channel.equals("stable") && !isPrerelease) match = true;
                    else if (ConfigManager.channel.equals("beta") && isPrerelease && tagName.toLowerCase().contains("beta")) match = true;
                    else if (ConfigManager.channel.equals("nightly") && isPrerelease && (tagName.toLowerCase().contains("nightly") || tagName.toLowerCase().contains("dev"))) match = true;

                    if (match && release.has("assets") && release.get("assets").isJsonArray()) {
                        com.google.gson.JsonArray assets = release.get("assets").getAsJsonArray();
                        for (com.google.gson.JsonElement assetElem : assets) {
                            com.google.gson.JsonObject asset = assetElem.getAsJsonObject();
                            String name = asset.get("name").getAsString();
                            if (name.toLowerCase().endsWith(".apepack2") || name.toLowerCase().endsWith(".apepack")) {
                                String apePackVer = extractVersionFromApePackName(name);
                                if (apePackVer != null) {
                                    if (latestApePackVersion == null || ApePackLoader.compareVersions(apePackVer, latestApePackVersion) > 0) {
                                        latestApePackVersion = apePackVer;
                                        downloadUrl = asset.get("browser_download_url").getAsString();
                                        assetName = name;
                                    }
                                }
                                break;
                            }
                        }
                    }
                }

                if (latestApePackVersion == null) {
                    System.out.println("[PackUpdater] No .apepack found on GitHub releases.");
                    return;
                }

                // Compare versions
                int versionCompare = ApePackLoader.compareVersions(latestApePackVersion, localClean);
                System.out.println("[PackUpdater] Local version: " + localClean + ", GitHub version: " + latestApePackVersion + ", compare: " + versionCompare);

                if (versionCompare <= 0) {
                    System.out.println("[PackUpdater] Pack is up to date (local: " + localClean + ", GitHub: " + latestApePackVersion + ").");
                    return;
                }

                System.out.println("[PackUpdater] New update available: " + latestApePackVersion + " (current: " + localClean + "), downloading...");

                // Download the .apepack2 file
                Path apepacksDir = getApepacksDir();
                Files.createDirectories(apepacksDir);
                Path target = apepacksDir.resolve(assetName != null ? assetName : "update.apepack2");

                java.net.URL dlUrl = java.net.URI.create(downloadUrl).toURL();
                java.net.HttpURLConnection dlConn = (java.net.HttpURLConnection) dlUrl.openConnection();
                ConfigManager.setupGitHubConnection(dlConn);
                dlConn.setConnectTimeout(5000);
                dlConn.setReadTimeout(60000);

                if (dlConn.getResponseCode() != 200) {
                    System.err.println("[PackUpdater] Failed to download update: HTTP " + dlConn.getResponseCode());
                    return;
                }

                try (java.io.InputStream is = dlConn.getInputStream()) {
                    Files.copy(is, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }

                System.out.println("[PackUpdater] Downloaded update to: " + target.getFileName() + " (" + Files.size(target) + " bytes)");

            } catch (Exception e) {
                System.err.println("[PackUpdater] GitHub update check failed: " + e.getMessage());
            } finally {
                githubCheckDone.countDown();
            }
        }, "PackUpdater-GitHubCheck");
        githubCheckThread.setDaemon(true);
        githubCheckThread.start();

        // Wait for GitHub check to finish, then scan for .apepack files
        Thread scanThread = new Thread(() -> {
            try {
                // Wait up to 30 seconds for GitHub check to download
                boolean finished = githubCheckDone.await(30, java.util.concurrent.TimeUnit.SECONDS);
                System.out.println("[PackUpdater] GitHub check " + (finished ? "completed" : "timed out") + ", starting .apepack scan...");

                // Scan for .apepack files in mods/ and apepacks/ directories
                Path modsDir = INSTANCE_ROOT.resolve("mods");
                Path apepacksDir = getApepacksDir();

                Path apepackFile = null;
                if (Files.exists(modsDir)) {
                    try (Stream<Path> paths = Files.list(modsDir)) {
                        apepackFile = paths
                                .filter(p -> p.toString().toLowerCase().endsWith(".apepack") || p.toString().toLowerCase().endsWith(".apepack2"))
                                .filter(p -> !ApePackLoaderV2.isApePackAlreadyProcessed(p))
                                .findFirst()
                                .orElse(null);
                    }
                }
                if (apepackFile == null && Files.exists(apepacksDir)) {
                    try (Stream<Path> paths = Files.list(apepacksDir)) {
                        List<Path> apepacks = paths
                                .filter(p -> p.toString().toLowerCase().endsWith(".apepack") || p.toString().toLowerCase().endsWith(".apepack2"))
                                .filter(p -> !ApePackLoaderV2.isApePackAlreadyProcessed(p))
                                .sorted(Comparator.comparingLong(p -> {
                                    try { return Files.getLastModifiedTime(p).toMillis(); }
                                    catch (IOException e) { return 0L; }
                                }))
                                .toList();
                        if (!apepacks.isEmpty()) {
                            apepackFile = apepacks.get(0);
                        }
                    }
                }

                if (apepackFile != null) {
                    // Guard: prevent dual installation
                    if (installing.get()) {
                        System.out.println("[PackUpdater] Skipping .apepack install - installation already in progress.");
                    } else if (installing.compareAndSet(false, true)) {
                        System.out.println("[PackUpdater] Found unprocessed .apepack: " + apepackFile.getFileName());
                        
                        // Extract immediately in the background, then auto-restart — no UI, no user interaction needed
                        // This ensures only ONE restart is needed: download -> extract+restart -> mods loaded
                        final Path finalApepackFile = apepackFile;
                        Thread installThread = new Thread(() -> {
                            try {
                                // Wait a moment for Minecraft to fully load
                                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                                
                                System.out.println("[PackUpdater] Auto-extracting .apepack in background...");
                                ApePackLoaderV2.InstallResult result = ApePackLoaderV2.installApePackWithProgress(
                                        finalApepackFile, INSTANCE_ROOT,
                                        new ApePackLoaderV2.ImportProgress() {
                                            @Override public void onFileStart(String f, String t, int c, int total) {
                                                System.out.println("[PackUpdater] Installing: " + f + " [" + c + "/" + total + "]");
                                            }
                                            @Override public void onFileInstalled(String f) {
                                                System.out.println("[PackUpdater] Installed: " + f);
                                            }
                                            @Override public void onFileSkipped(String f) {
                                                System.out.println("[PackUpdater] Skipped: " + f);
                                            }
                                            @Override public void onFileProtected(String f) {
                                                System.out.println("[PackUpdater] Protected: " + f);
                                            }
                                            @Override public void onPackInfo(String n, String v) {
                                                ConfigManager.updateModpackName(n);
                                                System.out.println("[PackUpdater] Pack: " + n + " v" + v);
                                            }
                                            @Override public void onComplete(boolean success, String error) {
                                                if (success) {
                                                    System.out.println("[PackUpdater] Extraction complete, showing diff screen...");
                                                    // Don't delete the .apepack yet - diff screen needs it
                                                    // Don't mark as processed yet - diff screen may cancel
                                                    try {
                                                        String version = extractApePackVersion(finalApepackFile);
                                                        if (version != null) ConfigManager.updateModpackVersion(version);
                                                    } catch (Exception ignored) {}
                                                    // Show diff screen so user can see what changed before restarting
                                                    Minecraft.getInstance().execute(() -> {
                                                        ApePackDiffScreen diffScreen = new ApePackDiffScreen(
                                                            null, finalApepackFile, INSTANCE_ROOT);
                                                        Minecraft.getInstance().setScreen(diffScreen);
                                                    });
                                                } else {
                                                    System.err.println("[PackUpdater] Extraction failed: " + error);
                                                    lastInstallError.set(error);
                                                    // On failure, show the config screen so the user can see the error
                                                    Minecraft.getInstance().execute(() -> {
                                                        if (Minecraft.getInstance().screen instanceof net.minecraft.client.gui.screens.TitleScreen || Minecraft.getInstance().screen == null) {
                                                            Minecraft.getInstance().setScreen(new PackUpdaterConfigScreen(null));
                                                        }
                                                    });
                                                }
                                                PackUpdaterMod.installing.set(false);
                                            }
                                        });
                            } catch (Exception e) {
                                System.err.println("[PackUpdater] Auto-extract exception: " + e.getMessage());
                                lastInstallError.set(e.getMessage());
                                PackUpdaterMod.installing.set(false);
                            }
                        }, "PackUpdater-AutoExtract");
                        installThread.setDaemon(false);
                        installThread.start();
                    }
                } else {
                    System.out.println("[PackUpdater] No unprocessed .apepack files found.");
                }

            } catch (Exception e) {
                System.err.println("[PackUpdater] ApePack scan failed: " + e.getMessage());
            }
        }, "PackUpdater-ApePackScan");
        scanThread.setDaemon(true);
        scanThread.start();
    }

    /**
     * Extracts the pack version from an .apepack filename (e.g., "V-High-Q-1.1.0.apepack2" -> "1.1.0").
     */
    static String extractVersionFromApePackName(String name) {
        String base = name.toLowerCase().replaceAll("\\.apepack2$", "").replaceAll("\\.apepack$", "");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+[\\.\\-]\\d+(?:[\\.\\-]\\d+)*)$").matcher(base);
        if (m.find()) return m.group(1).replace("-", ".");
        return null;
    }

    /**
     * Extracts the pack version from an .apepack file by reading apepack.json inside the ZIP.
     */
    static String extractApePackVersion(Path apepackFile) {
        try {
            java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(apepackFile.toFile());
            java.util.zip.ZipEntry entry = zipFile.getEntry("apepack.json");
            if (entry != null) {
                try (InputStream is = zipFile.getInputStream(entry);
                     BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                    String content = reader.lines().reduce("", (a, b) -> a + b);
                    JsonObject json = JsonParser.parseString(content).getAsJsonObject();
                    if (json.has("packVersion")) {
                        return json.get("packVersion").getAsString();
                    }
                }
            }
            zipFile.close();
        } catch (Exception e) {
            System.err.println("[PackUpdater] Failed to read version from " + apepackFile.getFileName() + ": " + e.getMessage());
        }
        return null;
    }

    public static PackUpdaterMod getInstance() {
        return instance;
    }

    /**
     * Gets the apepacks directory path for storing downloaded .apepack files.
     */
    public static Path getApepacksDir() {
        return INSTANCE_ROOT.resolve("apepacks");
    }
    
}