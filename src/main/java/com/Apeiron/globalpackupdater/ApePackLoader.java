package com.Apeiron.globalpackupdater;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Handles .apepack files — a custom modpack update format.
 *
 * An .apepack is a ZIP archive containing:
 * - apepack.json: Pack metadata (name, version, MC version, mod count, config count, script count)
 * - manifest.json: Full file list with names, types, paths, and SHA-256 hashes
 * - mods/*.jar: Mod files
 * - config/*: Config files (excluding user settings)
 * - kubejs/*: KubeJS scripts and assets (if present)
 *
 * On load, the loader compares all files against the manifest
 * and only installs files that are new or have a different hash.
 * User settings (options.txt, keybinds, video settings) are never overwritten.
 */
public class ApePackLoader {

    private static final String APEPACK_MANIFEST = "apepack.json";
    private static final String MODS_MANIFEST = "manifest.json";

    /**
     * Files that must never be overwritten during installation (user settings).
     */
    private static final Set<String> PROTECTED_FILES = Set.of(
            "options.txt",
            "optionschunkblending.txt",
            "optionsof.txt",
            "options.txt.bak",
            "serverlist.pak",
            "serverconfig.txt",
            "realmsnotificationsettings.json",
            "realmsinfo.json",
            "usercache.json",
            "launcher_profiles.json",
            "userdata.json"
    );

    /**
     * Represents a file entry in the manifest.
     */
    public static class ModEntry {
        public final String name;
        public final String type; // "mod", "config", "script", "asset"
        public final String version;
        public final String sha256;
        public final String filePath; // Path inside the .apepack zip
        public final String installPath; // Where to install (relative to instance root)

        ModEntry(String name, String type, String version, String sha256, String filePath, String installPath) {
            this.name = name;
            this.type = type;
            this.version = version;
            this.sha256 = sha256;
            this.filePath = filePath;
            this.installPath = installPath;
        }
    }

    /**
     * Represents the top-level apepack metadata.
     */
    public static class ApePackInfo {
        public final String packName;
        public final String packVersion;
        public final String mcVersion;
        public final int modCount;
        public final int configCount;
        public final int scriptCount;

        ApePackInfo(String packName, String packVersion, String mcVersion, int modCount,
                     int configCount, int scriptCount) {
            this.packName = packName;
            this.packVersion = packVersion;
            this.mcVersion = mcVersion;
            this.modCount = modCount;
            this.configCount = configCount;
            this.scriptCount = scriptCount;
        }
    }

    /**
     * Callback interface for reporting import progress.
     */
    public interface ImportProgress {
        /** Called when starting to process a file. */
        void onFileStart(String fileName, String fileType, int current, int total);
        /** Called when a file is successfully installed. */
        void onFileInstalled(String fileName);
        /** Called when a file is skipped (duplicate). */
        void onFileSkipped(String fileName);
        /** Called when a file is protected (user settings). */
        void onFileProtected(String fileName);
        /** Called when pack info is read. */
        void onPackInfo(String name, String version);
        /** Called when the entire import completes. */
        void onComplete(boolean success, String error);
    }

    /**
     * Result of an .apepack installation.
     */
    public static class InstallResult {
        public final ApePackInfo packInfo;
        public final int totalFiles;
        public final int newFilesInstalled;
        public final int skippedDuplicates;
        public final int skippedProtected;
        public final List<String> installedNames;
        public final List<String> skippedNames;
        public final List<String> protectedNames;
        public final boolean success;
        public final String errorMessage;

        InstallResult(ApePackInfo packInfo, int totalFiles, int newFilesInstalled,
                      int skippedDuplicates, int skippedProtected,
                      List<String> installed, List<String> skipped, List<String> protectedFiles,
                      boolean success, String error) {
            this.packInfo = packInfo;
            this.totalFiles = totalFiles;
            this.newFilesInstalled = newFilesInstalled;
            this.skippedDuplicates = skippedDuplicates;
            this.skippedProtected = skippedProtected;
            this.installedNames = installed;
            this.skippedNames = skipped;
            this.protectedNames = protectedFiles;
            this.success = success;
            this.errorMessage = error;
        }
    }

    /**
     * Scans the mods directory for any .apepack files and installs them.
     * Removes processed .apepack files after successful installation.
     * Installs to the instance root directory (parent of mods/).
     */
    public static void scanAndInstall(Path modsDir) {
        if (!Files.exists(modsDir)) return;

        // Instance root is the parent of the mods directory
        Path instanceRoot = modsDir.getParent();
        if (instanceRoot == null) instanceRoot = Path.of(".");

        try (Stream<Path> paths = Files.list(modsDir)) {
            List<Path> apepackFiles = paths
                    .filter(p -> p.toString().toLowerCase().endsWith(".apepack"))
                    .collect(Collectors.toList());

            for (Path apepackFile : apepackFiles) {
                System.out.println("[PackUpdater] Found .apepack file: " + apepackFile.getFileName());
                try {
                    InstallResult result = installApePack(apepackFile, instanceRoot);
                    if (result.success) {
                        System.out.println("[PackUpdater] Installed " + result.newFilesInstalled +
                                " new file(s), skipped " + result.skippedDuplicates +
                                " duplicate(s), protected " + result.skippedProtected + " user setting(s)");
                        System.out.println("[PackUpdater] Pack: " + result.packInfo.packName +
                                " v" + result.packInfo.packVersion +
                                " (MC " + result.packInfo.mcVersion + ")");
                        System.out.println("[PackUpdater] Mods: " + result.packInfo.modCount +
                                ", Configs: " + result.packInfo.configCount +
                                ", Scripts: " + result.packInfo.scriptCount);
                        // Remove the .apepack file after successful install
                        Files.delete(apepackFile);
                        System.out.println("[PackUpdater] Removed processed .apepack file");
                    } else {
                        System.err.println("[PackUpdater] Failed to install " + apepackFile.getFileName() +
                                ": " + result.errorMessage);
                    }
                } catch (Exception e) {
                    System.err.println("[PackUpdater] Error processing " + apepackFile.getFileName() +
                            ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[PackUpdater] Error scanning mods directory: " + e.getMessage());
        }
    }

    /**
     * Installs a single .apepack file with progress callbacks.
     * Only installs files that are new or have a different hash.
     * Never overwrites user settings (options.txt, keybinds, video settings, etc.).
     */
    public static InstallResult installApePackWithProgress(Path apepackFile, Path instanceRoot, ImportProgress progress) {
        try (ZipFile zipFile = new ZipFile(apepackFile.toFile())) {
            // Read apepack.json
            ZipArchiveEntry apepackEntry = zipFile.getEntry(APEPACK_MANIFEST);
            if (apepackEntry == null) {
                if (progress != null) progress.onComplete(false, "Missing " + APEPACK_MANIFEST);
                return new InstallResult(null, 0, 0, 0, 0, Collections.emptyList(),
                        Collections.emptyList(), Collections.emptyList(), false, "Missing " + APEPACK_MANIFEST);
            }

            ApePackInfo packInfo;
            try (InputStream is = zipFile.getInputStream(apepackEntry)) {
                JsonObject apepackJson = JsonParser.parseReader(
                        new java.io.InputStreamReader(is)).getAsJsonObject();
                packInfo = new ApePackInfo(
                        apepackJson.has("packName") ? apepackJson.get("packName").getAsString() : "Unknown",
                        apepackJson.has("packVersion") ? apepackJson.get("packVersion").getAsString() : "0.0.0",
                        apepackJson.has("mcVersion") ? apepackJson.get("mcVersion").getAsString() : "unknown",
                        apepackJson.has("modCount") ? apepackJson.get("modCount").getAsInt() : 0,
                        apepackJson.has("configCount") ? apepackJson.get("configCount").getAsInt() : 0,
                        apepackJson.has("scriptCount") ? apepackJson.get("scriptCount").getAsInt() : 0
                );
            }

            // Report pack info
            if (progress != null) {
                progress.onPackInfo(packInfo.packName, packInfo.packVersion);
            }

            // Read manifest.json
            ZipArchiveEntry manifestEntry = zipFile.getEntry(MODS_MANIFEST);
            if (manifestEntry == null) {
                if (progress != null) progress.onComplete(false, "Missing " + MODS_MANIFEST);
                return new InstallResult(packInfo, 0, 0, 0, 0, Collections.emptyList(),
                        Collections.emptyList(), Collections.emptyList(), false, "Missing " + MODS_MANIFEST);
            }

            List<ModEntry> fileEntries;
            try (InputStream is = zipFile.getInputStream(manifestEntry)) {
                JsonObject manifestJson = JsonParser.parseReader(
                        new java.io.InputStreamReader(is)).getAsJsonObject();
                fileEntries = parseManifest(manifestJson);
            }

            // Compare with installed files and install only new/updated ones
            List<String> installed = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            List<String> protectedFiles = new ArrayList<>();

            for (int i = 0; i < fileEntries.size(); i++) {
                ModEntry entry = fileEntries.get(i);

                // Report progress
                if (progress != null) {
                    progress.onFileStart(entry.name, entry.type, i + 1, fileEntries.size());
                }

                // Check if this is a protected user settings file
                String fileName = entry.name.toLowerCase();
                if (isProtectedFile(fileName)) {
                    protectedFiles.add(entry.name);
                    System.out.println("[PackUpdater] Protected user setting: " + entry.name);
                    if (progress != null) progress.onFileProtected(entry.name);
                    continue;
                }

                Path targetPath = instanceRoot.resolve(entry.installPath);
                boolean isDuplicate = false;

                if (Files.exists(targetPath)) {
                    String existingHash = computeSha256(targetPath);
                    if (existingHash != null && existingHash.equalsIgnoreCase(entry.sha256)) {
                        skipped.add(entry.name + " (" + entry.type + ")");
                        isDuplicate = true;
                        if (progress != null) progress.onFileSkipped(entry.name);
                    }
                }

                if (!isDuplicate) {
                    // Ensure parent directory exists
                    Files.createDirectories(targetPath.getParent());

                    // Extract the file from the zip
                    ZipArchiveEntry fileEntry = zipFile.getEntry(entry.filePath);
                    if (fileEntry != null) {
                        try (InputStream is = zipFile.getInputStream(fileEntry)) {
                            Files.copy(is, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            installed.add(entry.name + " (" + entry.type + ")");
                            if (progress != null) progress.onFileInstalled(entry.name);
                        }
                    } else {
                        System.err.println("[PackUpdater] File not found in archive: " + entry.filePath);
                    }
                }
            }

            InstallResult result = new InstallResult(packInfo, fileEntries.size(), installed.size(),
                    skipped.size(), protectedFiles.size(), installed, skipped, protectedFiles, true, null);

            if (progress != null) {
                progress.onComplete(true, null);
            }
            return result;

        } catch (IOException e) {
            String error = e.getMessage();
            if (progress != null) {
                progress.onComplete(false, error);
            }
            return new InstallResult(null, 0, 0, 0, 0, Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(), false, error);
        }
    }

    /**
     * Installs a single .apepack file to the instance root directory.
     * Only installs files that are new or have a different hash.
     * Never overwrites user settings (options.txt, keybinds, video settings, etc.).
     */
    public static InstallResult installApePack(Path apepackFile, Path instanceRoot) {
        return installApePackWithProgress(apepackFile, instanceRoot, null);
    }

    /**
     * Checks if a file is a protected user settings file that should never be overwritten.
     */
    private static boolean isProtectedFile(String fileName) {
        return PROTECTED_FILES.contains(fileName.toLowerCase());
    }

    /**
     * Parses the manifest.json file list into ModEntry objects.
     * Supports both old format (mods array) and new format (files array with types).
     */
    private static List<ModEntry> parseManifest(JsonObject manifest) {
        List<ModEntry> entries = new ArrayList<>();

        // New format: "files" array with type and installPath
        if (manifest.has("files") && manifest.get("files").isJsonArray()) {
            JsonArray filesArray = manifest.get("files").getAsJsonArray();
            for (com.google.gson.JsonElement elem : filesArray) {
                JsonObject file = elem.getAsJsonObject();
                String name = file.has("name") ? file.get("name").getAsString() : "unknown";
                String type = file.has("type") ? file.get("type").getAsString() : "mod";
                String version = file.has("version") ? file.get("version").getAsString() : "unknown";
                String sha256 = file.has("sha256") ? file.get("sha256").getAsString() : "";
                String filePath = file.has("file") ? file.get("file").getAsString() : name;
                String installPath = file.has("installPath") ? file.get("installPath").getAsString() : filePath;
                entries.add(new ModEntry(name, type, version, sha256, filePath, installPath));
            }
            return entries;
        }

        // Old format: "mods" array (backward compatibility)
        if (manifest.has("mods") && manifest.get("mods").isJsonArray()) {
            JsonArray modsArray = manifest.get("mods").getAsJsonArray();
            for (com.google.gson.JsonElement elem : modsArray) {
                JsonObject mod = elem.getAsJsonObject();
                String name = mod.has("name") ? mod.get("name").getAsString() : "unknown";
                String version = mod.has("version") ? mod.get("version").getAsString() : "unknown";
                String sha256 = mod.has("sha256") ? mod.get("sha256").getAsString() : "";
                String filePath = mod.has("file") ? mod.get("file").getAsString() : name + ".jar";
                String zipPath = "mods/" + filePath;
                entries.add(new ModEntry(name, "mod", version, sha256, zipPath, "mods/" + filePath));
            }
        }

        return entries;
    }

    /**
     * Computes the SHA-256 hash of a file.
     */
    public static String computeSha256(Path filePath) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(filePath));
            return bytesToHex(hash);
        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
