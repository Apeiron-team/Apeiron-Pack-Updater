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
    private static final Path INSTANCE_ROOT = Paths.get(".");
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

        // Auto-scan for .apepack files on startup and auto-install + auto-restart
        Thread scanThread = new Thread(() -> {
            try {
                System.out.println("[PackUpdater] Starting .apepack scan...");

                // Scan for .apepack files in mods/ and apepacks/ directories
                Path modsDir = INSTANCE_ROOT.resolve("mods");
                Path apepacksDir = getApepacksDir();

                Path apepackFile = null;
                if (Files.exists(modsDir)) {
                    try (Stream<Path> paths = Files.list(modsDir)) {
                        apepackFile = paths
                                .filter(p -> p.toString().toLowerCase().endsWith(".apepack"))
                                .filter(p -> !ApePackLoaderV2.isApePackAlreadyProcessed(p))
                                .findFirst()
                                .orElse(null);
                    }
                }
                if (apepackFile == null && Files.exists(apepacksDir)) {
                    try (Stream<Path> paths = Files.list(apepacksDir)) {
                        List<Path> apepacks = paths
                                .filter(p -> p.toString().toLowerCase().endsWith(".apepack"))
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
                        // Auto-install the .apepack silently (no UI)
                        final Path finalApepackFile = apepackFile;
                        final boolean[] installResult = {false};
                        final String[] installError = {null};

                        // Run installation on this background thread (no UI updates needed)
                        try {
                            ApePackLoaderV2.InstallResult result = ApePackLoaderV2.installApePackWithProgress(
                                    finalApepackFile, INSTANCE_ROOT,
                                    new ApePackLoaderV2.ImportProgress() {
                                        @Override
                                        public void onFileStart(String fileName, String fileType, int current, int total) {
                                            System.out.println("[PackUpdater] Installing: " + fileName + " (" + fileType + ") [" + current + "/" + total + "]");
                                        }

                                        @Override
                                        public void onFileInstalled(String fileName) {
                                            System.out.println("[PackUpdater] Installed: " + fileName);
                                        }

                                        @Override
                                        public void onFileSkipped(String fileName) {
                                            System.out.println("[PackUpdater] Skipped (duplicate): " + fileName);
                                        }

                                        @Override
                                        public void onFileProtected(String fileName) {
                                            System.out.println("[PackUpdater] Protected (user setting): " + fileName);
                                        }

                                        @Override
                                        public void onPackInfo(String name, String version) {
                                            System.out.println("[PackUpdater] Pack info: " + name + " v" + version);
                                            // Save modpack name
                                            ConfigManager.updateModpackName(name);
                                        }

                                        @Override
                                        public void onComplete(boolean success, String error) {
                                            installResult[0] = success;
                                            installError[0] = error;
                                            if (success) {
                                                // Mark as processed, update version, delete .apepack
                                                ApePackLoaderV2.markApePackAsProcessed(finalApepackFile);
                                                // Update modpack version from the apepack
                                                try {
                                                    String version = extractApePackVersion(finalApepackFile);
                                                    if (version != null) {
                                                        ConfigManager.updateModpackVersion(version);
                                                    }
                                                } catch (Exception ignored) {}
                                                try {
                                                    java.nio.file.Files.deleteIfExists(finalApepackFile);
                                                } catch (Exception ignored) {}
                                                System.out.println("[PackUpdater] Installation complete, auto-restarting game...");
                                            } else {
                                                System.err.println("[PackUpdater] Installation failed: " + error);
                                                lastInstallError.set(error);
                                            }
                                            PackUpdaterMod.installing.set(false);
                                        }
                                    });

                            if (installResult[0]) {
                                // Auto-restart the game
                                Minecraft.getInstance().execute(() -> {
                                    System.out.println("[PackUpdater] Relaunching game after successful install...");
                                    GameRelauncher.relaunch();
                                    // If relaunch fails, exit anyway
                                    System.exit(0);
                                });
                            }
                        } catch (Exception e) {
                            System.err.println("[PackUpdater] Auto-install exception: " + e.getMessage());
                            lastInstallError.set(e.getMessage());
                            PackUpdaterMod.installing.set(false);
                        }
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
     * Extracts the pack version from an .apepack file by reading apepack.json inside the ZIP.
     */
    private static String extractApePackVersion(Path apepackFile) {
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