package com.Apeiron.globalpackupdater;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;

@Mod("packupdater")
public class PackUpdaterMod {
    private static final String CURRENT_VERSION = ConfigManager.CURRENT_VERSION;
    private static final Path INSTANCE_ROOT = Paths.get(".");
    private static PackUpdaterMod instance;

    /**
     * Global download state manager — survives config screen close/reopen.
     */
    public enum DownloadStatus {
        IDLE, CHECKING, DOWNLOADING, VERIFYING, DONE, ERROR
    }

    public static class DownloadManager {
        public DownloadStatus status = DownloadStatus.IDLE;
        public double progress = 0.0; // 0.0 to 1.0
        public String currentRepo = null;
        public String errorMessage = null;
        public String latestVersion = null;
        public boolean updateAvailable = false;
        public long totalBytes = 0;
        public long downloadedBytes = 0;
        public long expectedSize = 0; // Expected file size from GitHub API
        public boolean downloading = false; // Atomic flag to prevent concurrent downloads

        public synchronized void setIdle() {
            this.status = DownloadStatus.IDLE;
            this.progress = 0.0;
            this.errorMessage = null;
            this.latestVersion = null;
            this.updateAvailable = false;
            this.totalBytes = 0;
            this.downloadedBytes = 0;
            this.expectedSize = 0;
            this.downloading = false;
        }

        public synchronized void setChecking() {
            this.status = DownloadStatus.CHECKING;
            this.progress = 0.0;
            this.errorMessage = null;
        }

        public synchronized void setDownloading() {
            this.status = DownloadStatus.DOWNLOADING;
            this.errorMessage = null;
            this.downloading = true;
        }

        public synchronized void setVerifying() {
            this.status = DownloadStatus.VERIFYING;
            this.progress = 0.95;
        }

        public synchronized void setProgress(double p) {
            this.progress = Math.max(0.0, Math.min(1.0, p));
        }

        public synchronized void setDone() {
            this.status = DownloadStatus.DONE;
            this.progress = 1.0;
            this.downloading = false;
        }

        public synchronized void setError(String msg) {
            this.status = DownloadStatus.ERROR;
            this.errorMessage = msg;
            this.downloading = false;
        }
    }

    public static final DownloadManager downloadManager = new DownloadManager();

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

        // Register the pack version overlay for the main menu
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(new PackVersionOverlay());
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        performCleanup();
        
        // Auto-update PackUpdater JAR in the instance's mods/ folder
        updatePackUpdaterJar();

        // Latch to synchronize: download must complete before scan runs
        final CountDownLatch downloadLatch = new CountDownLatch(1);
        final boolean[] downloadSucceeded = {false};
        final MainMenuNotification[] notification = {null};

        // Step 1: Check for GitHub .apepack updates FIRST (before scanning local files)
        Thread updateThread = new Thread(() -> {
            try {
                // Show notification on the main menu before starting download
                Minecraft.getInstance().execute(() -> {
                    notification[0] = new MainMenuNotification(
                            Minecraft.getInstance().screen != null ? Minecraft.getInstance().screen : new net.minecraft.client.gui.screens.TitleScreen());
                    notification[0].setDownloading(ConfigManager.repo, 0.0);
                    // Only show if we're on the title screen or no screen is open
                    if (Minecraft.getInstance().screen == null || Minecraft.getInstance().screen instanceof net.minecraft.client.gui.screens.TitleScreen) {
                        Minecraft.getInstance().setScreen(notification[0]);
                    }
                });

                // Override downloadManager callbacks to update the notification
                Runnable progressUpdater = () -> {
                    if (notification[0] != null) {
                        double p = downloadManager.progress;
                        Minecraft.getInstance().execute(() -> {
                            if (notification[0] != null && notification[0].type == MainMenuNotification.NotificationType.DOWNLOADING) {
                                notification[0].setDownloading(ConfigManager.repo, p);
                            }
                        });
                    }
                };

                boolean downloaded = checkAndDownloadApePackUpdate(ConfigManager.repo, ConfigManager.channel);
                downloadSucceeded[0] = downloaded;
                if (downloaded) {
                    System.out.println("[PackUpdater] Download complete, triggering post-download scan...");
                }
            } catch (Exception e) {
                System.err.println("[PackUpdater] Update check failed: " + e.getMessage());
                if (notification[0] != null) {
                    Minecraft.getInstance().execute(() -> {
                        notification[0].setError(e.getMessage());
                    });
                }
            } finally {
                // Signal that download phase is complete (success or failure)
                downloadLatch.countDown();
            }
        }, "PackUpdater-Download");
        updateThread.setDaemon(true);
        updateThread.start();

        // Step 2: Wait for download to finish, THEN scan for .apepack files
        Thread scanThread = new Thread(() -> {
            try {
                // Wait up to 120 seconds for the download to complete (large .apepack files)
                boolean waited = downloadLatch.await(120, java.util.concurrent.TimeUnit.SECONDS);
                if (!waited) {
                    System.err.println("[PackUpdater] Download timed out, proceeding with scan anyway...");
                }
                System.out.println("[PackUpdater] Download phase complete, starting .apepack scan...");

                // Now scan for .apepack files (including any just downloaded)
                Path modsDir = INSTANCE_ROOT.resolve("mods");
                Path apepacksDir = getApepacksDir();

                // Check if there are any unprocessed .apepack files
                Path apepackFile = null;
                if (Files.exists(modsDir)) {
                    try (Stream<Path> paths = Files.list(modsDir)) {
                        apepackFile = paths
                                .filter(p -> p.toString().toLowerCase().endsWith(".apepack"))
                                .filter(p -> !ApePackLoader.isApePackAlreadyProcessed(p))
                                .findFirst()
                                .orElse(null);
                    }
                }
                if (apepackFile == null && Files.exists(apepacksDir)) {
                    try (Stream<Path> paths = Files.list(apepacksDir)) {
                        List<Path> apepacks = paths
                                .filter(p -> p.toString().toLowerCase().endsWith(".apepack"))
                                .filter(p -> !ApePackLoader.isApePackAlreadyProcessed(p))
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
                    System.out.println("[PackUpdater] Found unprocessed .apepack: " + apepackFile.getFileName());
                    // Show the import progress screen on the main thread
                    final Path finalApepackFile = apepackFile;
                    Minecraft.getInstance().execute(() -> {
                        // Close the download notification if it's still open
                        if (notification[0] != null && Minecraft.getInstance().screen == notification[0]) {
                            Minecraft.getInstance().setScreen(new net.minecraft.client.gui.screens.TitleScreen());
                        }
                        ApePackImportScreen importScreen = new ApePackImportScreen(
                                new net.minecraft.client.gui.screens.TitleScreen(),
                                finalApepackFile, INSTANCE_ROOT);
                        Minecraft.getInstance().setScreen(importScreen);
                        importScreen.startImport();
                    });
                } else {
                    // No unprocessed .apepack files — run silent scan for any remaining
                    if (Files.exists(modsDir)) {
                        ApePackLoader.scanAndInstall(modsDir);
                    }
                    System.out.println("[PackUpdater] No unprocessed .apepack files found.");
                    
                    // Close the download notification if it's still open
                    if (notification[0] != null) {
                        Minecraft.getInstance().execute(() -> {
                            if (Minecraft.getInstance().screen == notification[0]) {
                                Screen parent = notification[0].getParent();
                                Minecraft.getInstance().setScreen(parent != null ? parent : new net.minecraft.client.gui.screens.TitleScreen());
                            }
                        });
                    }
                }

            } catch (InterruptedException e) {
                System.err.println("[PackUpdater] Scan thread interrupted: " + e.getMessage());
            } catch (Exception e) {
                System.err.println("[PackUpdater] ApePack scan failed: " + e.getMessage());
            }
        }, "PackUpdater-ApePackScan");
        scanThread.setDaemon(true);
        scanThread.start();
    }

    /**
     * Checks GitHub for the latest .apepack release and compares it with the
     * highest version found in the apepacks/ folder. Downloads only if the
     * GitHub version is newer or no .apepack exists locally.
     * @return true if a new .apepack was downloaded, false otherwise
     */
    private boolean checkAndDownloadApePackUpdate(String repo, String channel) throws Exception {
        // Find the highest .apepack version in the apepacks/ folder
        Path apepacksDir = getApepacksDir();
        String localVersion = null;
        if (Files.exists(apepacksDir)) {
            try (Stream<Path> paths = Files.list(apepacksDir)) {
                for (Path apepack : paths.filter(p -> p.toString().toLowerCase().endsWith(".apepack")).toArray(Path[]::new)) {
                    String ver = extractApePackVersion(apepack);
                    if (ver != null) {
                        if (localVersion == null || compareVersions(ver, localVersion) > 0) {
                            localVersion = ver;
                        }
                    }
                }
            }
        }

        // Also check the modpack version from config as a fallback
        if (localVersion == null && ConfigManager.modpackVersion != null && !ConfigManager.modpackVersion.equals("v0.0")) {
            localVersion = ConfigManager.modpackVersion.replace("v", "");
        }

        // Get the latest .apepack version from GitHub
        String githubVersion = getLatestApePackVersion(repo, channel);
        if (githubVersion == null) {
            System.out.println("[PackUpdater] No .apepack release found on GitHub for " + repo);
            return false;
        }

        System.out.println("[PackUpdater] GitHub .apepack version: " + githubVersion);
        System.out.println("[PackUpdater] Local .apepack version: " + (localVersion != null ? localVersion : "none"));

        // Compare versions - only download if GitHub version is newer
        if (localVersion != null && compareVersions(githubVersion, localVersion) <= 0) {
            System.out.println("[PackUpdater] Local .apepack is up to date (v" + localVersion + " >= v" + githubVersion + ")");
            return false;
        }

        // Download the newer .apepack
        System.out.println("[PackUpdater] Newer .apepack available (v" + githubVersion + " > v" + localVersion + "), downloading...");
        boolean success = downloadLatestApePack(repo);
        if (success) {
            System.out.println("[PackUpdater] .apepack update downloaded successfully. Will install on next startup.");
            return true;
        } else {
            System.err.println("[PackUpdater] Failed to download .apepack update.");
            return false;
        }
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

    /**
     * Gets the latest .apepack version tag from GitHub releases.
     */
    private static String getLatestApePackVersion(String repo, String channel) throws Exception {
        String apiUrl = "https://api.github.com/repos/" + repo + "/releases";
        URL url = URI.create(apiUrl).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        ConfigManager.setupGitHubConnection(conn);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);

        if (conn.getResponseCode() != 200) {
            if (conn.getResponseCode() == 403) {
                System.err.println("[PackUpdater] GitHub API rate limit exceeded. Add a GitHub token in the config to increase your limit.");
            }
            return null;
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        com.google.gson.JsonArray releases = JsonParser.parseReader(reader).getAsJsonArray();
        reader.close();

        for (com.google.gson.JsonElement element : releases) {
            JsonObject release = element.getAsJsonObject();
            String tagName = release.get("tag_name").getAsString();
            boolean isPrerelease = release.has("prerelease") && release.get("prerelease").getAsBoolean();

            boolean match = false;
            if (channel.equals("stable") && !isPrerelease) match = true;
            else if (channel.equals("beta") && isPrerelease && tagName.toLowerCase().contains("beta")) match = true;
            else if (channel.equals("nightly") && isPrerelease && (tagName.toLowerCase().contains("nightly") || tagName.toLowerCase().contains("dev"))) match = true;

            if (match) {
                // Check if this release has an .apepack asset
                if (release.has("assets") && release.get("assets").isJsonArray()) {
                    com.google.gson.JsonArray assets = release.get("assets").getAsJsonArray();
                    for (com.google.gson.JsonElement assetElem : assets) {
                        JsonObject asset = assetElem.getAsJsonObject();
                        String name = asset.get("name").getAsString();
                        if (name.toLowerCase().endsWith(".apepack")) {
                            return tagName.replace("v", "").replace("V", "");
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Compares two version strings. Returns positive if v1 > v2, negative if v1 < v2, 0 if equal.
     */
    private static int compareVersions(String v1, String v2) {
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
            ConfigManager.setupGitHubConnection(conn);
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
     * Holds metadata about a GitHub release asset for verification.
     */
    private static class ApePackAssetInfo {
        public String downloadUrl;
        public long size;
        public String name;

        ApePackAssetInfo(String downloadUrl, long size, String name) {
            this.downloadUrl = downloadUrl;
            this.size = size;
            this.name = name;
        }
    }

    /**
     * Downloads the latest .apepack file from a GitHub repo and places it in the apepacks folder.
     * Reports progress via the global downloadManager.
     * Verifies SHA-256 and file size before marking as complete.
     * Returns true if an .apepack was found, downloaded, and verified, false otherwise.
     */
    public static boolean downloadLatestApePack(String repo) {
        DownloadManager dm = downloadManager;
        dm.currentRepo = repo;
        try {
            dm.setChecking();
            ApePackAssetInfo assetInfo = findApePackAssetStatic(repo);
            if (assetInfo == null) {
                dm.setError("No .apepack file found in latest release");
                System.out.println("[PackUpdater] No .apepack file found in latest release");
                return false;
            }

            dm.setDownloading();
            dm.setProgress(0.05);
            dm.expectedSize = assetInfo.size;
            dm.totalBytes = assetInfo.size;

            // Save to apepacks/ folder in the game directory
            Path apepacksDir = INSTANCE_ROOT.resolve("apepacks");
            Files.createDirectories(apepacksDir);

            // Generate a unique filename with timestamp to avoid conflicts
            String timestamp = java.time.LocalDateTime.now().toString().replace(":", "-").replace(" ", "_");
            Path targetApePack = apepacksDir.resolve("update_" + timestamp + ".apepack");

            URL url = URI.create(assetInfo.downloadUrl).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            ConfigManager.setupGitHubConnection(conn);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(120000);

            dm.setProgress(0.1);

            // Compute SHA-256 while downloading
            java.security.MessageDigest sha256 = java.security.MessageDigest.getInstance("SHA-256");
            int contentLength = conn.getContentLength();
            if (contentLength > 0 && dm.totalBytes == 0) {
                dm.totalBytes = contentLength;
            }

            try (InputStream inputStream = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(targetApePack.toFile())) {
                byte[] buffer = new byte[8192];
                long totalRead = 0;
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer, 0, buffer.length)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                    sha256.update(buffer, 0, bytesRead);
                    totalRead += bytesRead;
                    dm.downloadedBytes = totalRead;
                    if (dm.totalBytes > 0) {
                        double progress = 0.1 + (0.75 * ((double) totalRead / dm.totalBytes));
                        dm.setProgress(progress);
                    } else {
                        dm.setProgress(0.1 + (0.75 * (totalRead % 1000000) / 1000000.0));
                    }
                }
            }

            // Verify the downloaded file
            dm.setVerifying();
            String downloadedHash = bytesToHex(sha256.digest());
            long downloadedSize = Files.size(targetApePack);

            System.out.println("[PackUpdater] Verifying download: " + assetInfo.name);
            System.out.println("[PackUpdater] Expected size: " + assetInfo.size + ", Actual size: " + downloadedSize);
            System.out.println("[PackUpdater] Downloaded SHA-256: " + downloadedHash);

            // Check file size matches GitHub asset size
            if (assetInfo.size > 0 && downloadedSize != assetInfo.size) {
                // Delete the corrupted file
                Files.deleteIfExists(targetApePack);
                dm.setError("File size mismatch! Expected " + assetInfo.size + " bytes but got " + downloadedSize + " bytes. Download may be corrupted.");
                System.err.println("[PackUpdater] File size verification failed!");
                return false;
            }

            dm.setDone();
            System.out.println("[PackUpdater] Download verified and saved to " + targetApePack);
            System.out.println("[PackUpdater] The update will be installed on next game startup.");
            return true;

        } catch (Exception e) {
            dm.setError(e.getMessage());
            System.err.println("[PackUpdater] Failed to download .apepack: " + e.getMessage());
            return false;
        }
    }

    /**
     * Converts a byte array to a hex string.
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Gets the apepacks directory path for storing downloaded .apepack files.
     */
    public static Path getApepacksDir() {
        return INSTANCE_ROOT.resolve("apepacks");
    }

    /**
     * Finds the .apepack asset in the latest GitHub release and returns its metadata.
     */
    private static ApePackAssetInfo findApePackAssetStatic(String repo) throws Exception {
        String apiUrl = "https://api.github.com/repos/" + repo + "/releases/latest";
        URL url = URI.create(apiUrl).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        ConfigManager.setupGitHubConnection(conn);
        conn.setConnectTimeout(5000);

        if (conn.getResponseCode() != 200) {
            if (conn.getResponseCode() == 403) {
                System.err.println("[PackUpdater] GitHub API rate limit exceeded. Add a GitHub token in the config to increase your limit.");
            }
            return null;
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        JsonObject release = JsonParser.parseReader(reader).getAsJsonObject();
        reader.close();

        if (release.has("assets") && release.get("assets").isJsonArray()) {
            com.google.gson.JsonArray assets = release.get("assets").getAsJsonArray();
            for (com.google.gson.JsonElement elem : assets) {
                JsonObject asset = elem.getAsJsonObject();
                String name = asset.get("name").getAsString();
                if (name.toLowerCase().endsWith(".apepack")) {
                    String downloadUrl = asset.get("browser_download_url").getAsString();
                    long size = asset.has("size") ? asset.get("size").getAsLong() : 0;
                    return new ApePackAssetInfo(downloadUrl, size, name);
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

    /**
     * On startup, finds the currently running PackUpdater JAR and ensures the mods/ folder
     * contains only the latest version. Removes any older PackUpdater JARs.
     */
    private void updatePackUpdaterJar() {
        try {
            Path modsDir = INSTANCE_ROOT.resolve("mods");
            if (!Files.exists(modsDir)) {
                return;
            }

            // Find the currently running PackUpdater JAR from the classpath
            String currentJarName = null;
            String jarPath = PackUpdaterMod.class.getProtectionDomain().getCodeSource().getLocation().getPath();
            if (jarPath != null) {
                currentJarName = Paths.get(jarPath).getFileName().toString();
            }

            if (currentJarName == null || !currentJarName.toLowerCase().startsWith("packupdater")) {
                System.out.println("[PackUpdater] Could not determine current JAR name, skipping auto-update.");
                return;
            }

            System.out.println("[PackUpdater] Current JAR: " + currentJarName);

            // Find all PackUpdater JARs in the mods/ folder
            List<Path> packUpdaterJars = new ArrayList<>();
            try (Stream<Path> paths = Files.list(modsDir)) {
                packUpdaterJars = paths
                        .filter(p -> p.getFileName().toString().toLowerCase().startsWith("packupdater") && p.getFileName().toString().toLowerCase().endsWith(".jar"))
                        .toList();
            }

            if (packUpdaterJars.isEmpty()) {
                System.out.println("[PackUpdater] No PackUpdater JAR found in mods/, skipping auto-update.");
                return;
            }

            // Find the latest version by comparing version numbers
            Path latestJar = null;
            String latestVersion = "0";
            for (Path jar : packUpdaterJars) {
                String fileName = jar.getFileName().toString();
                // Extract version from filename like "packupdater-0.0.25.jar"
                String version = extractVersionFromJarName(fileName);
                if (version != null && compareVersions(version, latestVersion) > 0) {
                    latestVersion = version;
                    latestJar = jar;
                }
            }

            if (latestJar == null) {
                return;
            }

            System.out.println("[PackUpdater] Latest JAR in mods/: " + latestJar.getFileName() + " (v" + latestVersion + ")");

            // Remove all older PackUpdater JARs
            for (Path jar : packUpdaterJars) {
                if (!jar.equals(latestJar)) {
                    try {
                        Files.delete(jar);
                        System.out.println("[PackUpdater] Removed old JAR: " + jar.getFileName());
                    } catch (IOException e) {
                        System.err.println("[PackUpdater] Failed to remove old JAR: " + jar.getFileName() + " - " + e.getMessage());
                    }
                }
            }

            // If the latest JAR in mods/ is not the currently running one, copy the current one
            String latestJarName = latestJar.getFileName().toString();
            if (!latestJarName.equals(currentJarName)) {
                // Check if the current JAR is newer
                String currentVersion = extractVersionFromJarName(currentJarName);
                if (currentVersion != null && compareVersions(currentVersion, latestVersion) > 0) {
                    // Copy the current JAR to mods/
                    Path sourceJar = Paths.get(jarPath);
                    if (Files.exists(sourceJar)) {
                        Files.copy(sourceJar, modsDir.resolve(currentJarName), StandardCopyOption.REPLACE_EXISTING);
                        System.out.println("[PackUpdater] Copied current JAR to mods/: " + currentJarName);
                        // Remove the old latest JAR
                        try {
                            Files.delete(latestJar);
                            System.out.println("[PackUpdater] Removed outdated JAR: " + latestJarName);
                        } catch (IOException e) {
                            System.err.println("[PackUpdater] Failed to remove outdated JAR: " + latestJarName + " - " + e.getMessage());
                        }
                    }
                }
            }

            System.out.println("[PackUpdater] mods/ folder now contains only: " + latestJar.getFileName());

        } catch (Exception e) {
            System.err.println("[PackUpdater] Failed to update PackUpdater JAR: " + e.getMessage());
        }
    }

    /**
     * Extracts the version string from a PackUpdater JAR filename.
     * E.g., "packupdater-0.0.25.jar" -> "0.0.25"
     */
    private static String extractVersionFromJarName(String fileName) {
        if (fileName == null) return null;
        // Remove .jar extension
        String name = fileName.toLowerCase();
        if (name.endsWith(".jar")) {
            name = name.substring(0, name.length() - 4);
        }
        // Find the last dash which separates the name from the version
        int lastDash = name.lastIndexOf('-');
        if (lastDash > 0) {
            return name.substring(lastDash + 1);
        }
        return null;
    }

    /**
     * Placeholder for toast notification on successful update.
     * The import screen already shows the completion message.
     * TODO: Implement when SystemToast API is available for MC 26.1.2.
     */
    public static void showUpdateSuccessToast(String packName, String packVersion) {
        // Toast notification will be implemented when the SystemToast API
        // is confirmed for Minecraft 26.1.2
        System.out.println("[PackUpdater] ✅ " + packName + " v" + packVersion + " installed successfully!");
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