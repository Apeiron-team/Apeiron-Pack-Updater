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
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

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
    private static final Path PROCESSED_HASHES_PATH = Paths.get("config", "processed_apepacks.json");

    /**
     * Loads the set of already-processed .apepack file hashes.
     */
    private static Set<String> loadProcessedHashes() {
        Set<String> hashes = new HashSet<>();
        try {
            if (Files.exists(PROCESSED_HASHES_PATH)) {
                String content = Files.readString(PROCESSED_HASHES_PATH);
                com.google.gson.JsonArray array = com.google.gson.JsonParser.parseString(content).getAsJsonArray();
                for (com.google.gson.JsonElement elem : array) {
                    hashes.add(elem.getAsString());
                }
            }
        } catch (Exception e) {
            System.err.println("[PackUpdater] Failed to load processed hashes: " + e.getMessage());
        }
        return hashes;
    }

    /**
     * Saves the set of processed .apepack file hashes.
     */
    private static void saveProcessedHashes(Set<String> hashes) {
        try {
            Files.createDirectories(PROCESSED_HASHES_PATH.getParent());
            com.google.gson.JsonArray array = new com.google.gson.JsonArray();
            for (String hash : hashes) {
                array.add(hash);
            }
            Files.writeString(PROCESSED_HASHES_PATH, array.toString());
        } catch (Exception e) {
            System.err.println("[PackUpdater] Failed to save processed hashes: " + e.getMessage());
        }
    }

    /**
     * Checks if an .apepack file has already been processed based on its SHA-256 hash.
     */
    public static boolean isApePackAlreadyProcessed(Path apepackFile) {
        try {
            String hash = computeSha256(apepackFile);
            if (hash == null) return false;
            Set<String> hashes = loadProcessedHashes();
            return hashes.contains(hash);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Marks an .apepack file as processed by storing its SHA-256 hash.
     */
    public static void markApePackAsProcessed(Path apepackFile) {
        try {
            String hash = computeSha256(apepackFile);
            if (hash != null) {
                Set<String> hashes = loadProcessedHashes();
                hashes.add(hash);
                saveProcessedHashes(hashes);
                System.out.println("[PackUpdater] Marked .apepack as processed: " + apepackFile.getFileName());
            }
        } catch (Exception e) {
            System.err.println("[PackUpdater] Failed to mark .apepack as processed: " + e.getMessage());
        }
    }

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
     * Extracts the base mod name from a JAR filename by removing version suffixes.
     * E.g., "sodium-0.6.13+mc26.1.2.jar" -> "sodium"
     * E.g., "c2me-neoforge-mc26.1.2-0.3.0.jar" -> "c2me-neoforge-mc26.1.2"
     */
    static String extractModBaseName(String jarFileName) {
        // Remove .jar extension
        String name = jarFileName;
        if (name.toLowerCase().endsWith(".jar")) {
            name = name.substring(0, name.length() - 4);
        }
        // Try to find the version pattern at the end: -VERSION or +mcVERSION-VERSION
        // Common patterns: "modname-1.2.3", "modname-mc1.21.1-1.2.3", "modname-1.2.3+mc1.21.1"
        // We look for the last -digit pattern that looks like a version
        int lastDash = -1;
        boolean foundVersionStart = false;
        for (int i = name.length() - 1; i >= 0; i--) {
            char c = name.charAt(i);
            if (Character.isDigit(c) || c == '.' || c == '+' || c == '_') {
                if (!foundVersionStart && (Character.isDigit(c) || c == '.')) {
                    foundVersionStart = true;
                }
            } else if (c == '-' && foundVersionStart) {
                lastDash = i;
                break;
            } else if (foundVersionStart) {
                break;
            }
        }
        if (lastDash > 0) {
            return name.substring(0, lastDash);
        }
        return name;
    }

    /**
     * Extracts the version string from a JAR filename.
     * E.g., "sodium-0.6.13+mc26.1.2.jar" -> "0.6.13+mc26.1.2"
     * E.g., "c2me-neoforge-mc26.1.2-0.3.0.jar" -> "0.3.0"
     */
    static String extractModVersion(String jarFileName) {
        String name = jarFileName;
        if (name.toLowerCase().endsWith(".jar")) {
            name = name.substring(0, name.length() - 4);
        }
        // Find the version part after the last dash
        int lastDash = name.lastIndexOf('-');
        if (lastDash > 0) {
            return name.substring(lastDash + 1);
        }
        // Try to find version after +mc pattern
        int mcIndex = name.indexOf("+mc");
        if (mcIndex > 0) {
            return name.substring(mcIndex);
        }
        return "0";
    }

    /**
     * Compares two version strings. Returns positive if v1 > v2, negative if v1 < v2, 0 if equal.
     * Handles versions like "1.2.3", "0.6.13+mc26.1.2", "alpha.0.89", etc.
     */
    static int compareVersions(String v1, String v2) {
        if (v1 == null || v1.isEmpty()) return -1;
        if (v2 == null || v2.isEmpty()) return 1;
        if (v1.equals(v2)) return 0;

        // Split by common delimiters: . + - _
        String[] parts1 = v1.split("[.+\\-_]");
        String[] parts2 = v2.split("[.+\\-_]");

        int maxLen = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < maxLen; i++) {
            String p1 = i < parts1.length ? parts1[i] : "0";
            String p2 = i < parts2.length ? parts2[i] : "0";

            // Try to parse as numbers first
            try {
                long num1 = Long.parseLong(p1);
                long num2 = Long.parseLong(p2);
                if (num1 != num2) return Long.compare(num1, num2);
            } catch (NumberFormatException e) {
                // Not a number, compare as strings
                int cmp = p1.compareTo(p2);
                if (cmp != 0) return cmp;
            }
        }
        return 0;
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
                    // For mods, check version before replacing
                    if ("mod".equals(entry.type)) {
                        String existingHash = computeSha256(targetPath);
                        if (existingHash != null && existingHash.equalsIgnoreCase(entry.sha256)) {
                            // Same file, skip
                            skipped.add(entry.name + " (" + entry.type + ")");
                            isDuplicate = true;
                            if (progress != null) progress.onFileSkipped(entry.name);
                        } else {
                            // Different file — check if it's the same mod with a lower version
                            String existingFileName = targetPath.getFileName().toString();
                            String existingBaseName = extractModBaseName(existingFileName);
                            String newBaseName = extractModBaseName(entry.name);

                            // If base names match, compare versions
                            if (existingBaseName.equalsIgnoreCase(newBaseName)) {
                                // Extract version from the existing installed file's filename
                                String existingVersion = extractModVersion(existingFileName);
                                // Use manifest version if available, otherwise extract from the new file's name
                                String newVersion = (entry.version != null && !entry.version.equals("unknown"))
                                        ? entry.version : extractModVersion(entry.filePath);

                                int versionCompare = compareVersions(newVersion, existingVersion);
                                if (versionCompare <= 0) {
                                    // New version is same or older, skip
                                    skipped.add(entry.name + " (" + entry.type + ", version " + newVersion + " <= " + existingVersion + ")");
                                    isDuplicate = true;
                                    if (progress != null) progress.onFileSkipped(entry.name);
                                    System.out.println("[PackUpdater] Skipped older/same version: " + entry.name +
                                            " (new: " + newVersion + ", installed: " + existingVersion + ")");
                                } else {
                                    // New version is higher, replace and remove old
                                    System.out.println("[PackUpdater] Updating mod: " + entry.name +
                                            " (" + existingVersion + " -> " + newVersion + ")");
                                }
                            }
                            // If base names don't match, proceed with install (different mod)
                        }
                    } else {
                        // Non-mod files: use hash comparison
                        String existingHash = computeSha256(targetPath);
                        if (existingHash != null && existingHash.equalsIgnoreCase(entry.sha256)) {
                            skipped.add(entry.name + " (" + entry.type + ")");
                            isDuplicate = true;
                            if (progress != null) progress.onFileSkipped(entry.name);
                        }
                    }
                }

                if (!isDuplicate) {
                    // Ensure parent directory exists
                    if (targetPath.getParent() != null) {
                        Files.createDirectories(targetPath.getParent());
                    }

                    // If replacing a mod, delete old versions with the same base name first
                    if ("mod".equals(entry.type) && Files.exists(targetPath)) {
                        String existingFileName = targetPath.getFileName().toString();
                        String existingBaseName = extractModBaseName(existingFileName);
                        String newBaseName = extractModBaseName(entry.name);

                        if (existingBaseName.equalsIgnoreCase(newBaseName)) {
                            // Delete the old mod file
                            try {
                                Files.delete(targetPath);
                                System.out.println("[PackUpdater] Removed old mod: " + existingFileName);
                            } catch (IOException e) {
                                System.err.println("[PackUpdater] Failed to remove old mod: " + existingFileName +
                                        ": " + e.getMessage());
                            }
                        }
                    }

                    // Extract the file from the zip
                    ZipArchiveEntry fileEntry = zipFile.getEntry(entry.filePath);
                    if (fileEntry != null) {
                        try (InputStream is = zipFile.getInputStream(fileEntry)) {
                            Files.copy(is, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            installed.add(entry.name + " (" + entry.type + ")");
                            System.out.println("[PackUpdater] Installed: " + entry.name + " -> " + targetPath.toAbsolutePath());
                            if (progress != null) progress.onFileInstalled(entry.name);
                        }
                    } else {
                        System.err.println("[PackUpdater] File not found in archive: " + entry.filePath +
                                " (expected in zip for: " + entry.name + ")");
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
