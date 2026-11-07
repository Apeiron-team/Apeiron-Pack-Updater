package com.Apeiron.globalpackupdater;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Generates .apepack files from the current Minecraft instance.
 *
 * An .apepack is a ZIP archive containing:
 * - apepack.json: Pack metadata (name, version, MC version, mod count, config count, script count)
 * - manifest.json: Full file list with names, types, paths, and SHA-256 hashes
 * - mods/*.jar: All mod files
 * - config/*: All config files (excluding user-specific settings)
 * - kubejs/*: KubeJS scripts and assets (if present)
 * - kubejs/groovy/*: KubeJS Groovy scripts (if present)
 *
 * User settings like options.txt, keybinds, video settings, and serverlist are excluded.
 */
public class ApePackGenerator {

    /**
     * Config files that contain user-specific settings and must never be overwritten.
     */
    private static final Set<String> USER_SETTINGS_FILES = Set.of(
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
            "RealmsKey",
            "RealmsKey.1",
            "RealmsKey.2",
            "RealmsKey.3",
            "RealmsKey.4",
            "RealmsKey.5",
            "RealmsKey.6",
            "RealmsKey.7",
            "RealmsKey.8",
            "RealmsKey.9",
            "RealmsKey.10",
            "userdata.json",
            "RealmsFriendList.json",
            "RealmsFriendList.json.1",
            "RealmsFriendList.json.2",
            "RealmsFriendList.json.3",
            "RealmsFriendList.json.4",
            "RealmsFriendList.json.5",
            "RealmsFriendList.json.6",
            "RealmsFriendList.json.7",
            "RealmsFriendList.json.8",
            "RealmsFriendList.json.9",
            "RealmsFriendList.json.10",
            "RealmsFriendList.json.11",
            "RealmsFriendList.json.12",
            "RealmsFriendList.json.13",
            "RealmsFriendList.json.14",
            "RealmsFriendList.json.15",
            "RealmsFriendList.json.16",
            "RealmsFriendList.json.17",
            "RealmsFriendList.json.18",
            "RealmsFriendList.json.19",
            "RealmsFriendList.json.20",
            "RealmsFriendList.json.21",
            "RealmsFriendList.json.22",
            "RealmsFriendList.json.23",
            "RealmsFriendList.json.24",
            "RealmsFriendList.json.25",
            "RealmsFriendList.json.26",
            "RealmsFriendList.json.27",
            "RealmsFriendList.json.28",
            "RealmsFriendList.json.29",
            "RealmsFriendList.json.30",
            "RealmsFriendList.json.31",
            "RealmsFriendList.json.32",
            "RealmsFriendList.json.33",
            "RealmsFriendList.json.34",
            "RealmsFriendList.json.35",
            "RealmsFriendList.json.36",
            "RealmsFriendList.json.37",
            "RealmsFriendList.json.38",
            "RealmsFriendList.json.39",
            "RealmsFriendList.json.40",
            "RealmsFriendList.json.41",
            "RealmsFriendList.json.42",
            "RealmsFriendList.json.43",
            "RealmsFriendList.json.44",
            "RealmsFriendList.json.45",
            "RealmsFriendList.json.46",
            "RealmsFriendList.json.47",
            "RealmsFriendList.json.48",
            "RealmsFriendList.json.49",
            "RealmsFriendList.json.50",
            "RealmsFriendList.json.51",
            "RealmsFriendList.json.52",
            "RealmsFriendList.json.53",
            "RealmsFriendList.json.54",
            "RealmsFriendList.json.55",
            "RealmsFriendList.json.56",
            "RealmsFriendList.json.57",
            "RealmsFriendList.json.58",
            "RealmsFriendList.json.59",
            "RealmsFriendList.json.60",
            "RealmsFriendList.json.61",
            "RealmsFriendList.json.62",
            "RealmsFriendList.json.63",
            "RealmsFriendList.json.64",
            "RealmsFriendList.json.65",
            "RealmsFriendList.json.66",
            "RealmsFriendList.json.67",
            "RealmsFriendList.json.68",
            "RealmsFriendList.json.69",
            "RealmsFriendList.json.70",
            "RealmsFriendList.json.71",
            "RealmsFriendList.json.72",
            "RealmsFriendList.json.73",
            "RealmsFriendList.json.74",
            "RealmsFriendList.json.75",
            "RealmsFriendList.json.76",
            "RealmsFriendList.json.77",
            "RealmsFriendList.json.78",
            "RealmsFriendList.json.79",
            "RealmsFriendList.json.80",
            "RealmsFriendList.json.81",
            "RealmsFriendList.json.82",
            "RealmsFriendList.json.83",
            "RealmsFriendList.json.84",
            "RealmsFriendList.json.85",
            "RealmsFriendList.json.86",
            "RealmsFriendList.json.87",
            "RealmsFriendList.json.88",
            "RealmsFriendList.json.89",
            "RealmsFriendList.json.90",
            "RealmsFriendList.json.91",
            "RealmsFriendList.json.92",
            "RealmsFriendList.json.93",
            "RealmsFriendList.json.94",
            "RealmsFriendList.json.95",
            "RealmsFriendList.json.96",
            "RealmsFriendList.json.97",
            "RealmsFriendList.json.98",
            "RealmsFriendList.json.99",
            "RealmsFriendList.json.100"
    );

    /**
     * Config file extensions that may contain user-specific data and should be excluded.
     */
    private static final Set<String> USER_CONFIG_EXTENSIONS = Set.of(
            ".yml",  // Some mods store user preferences in YAML
            ".yaml"
    );

    /**
     * Config files that are safe to include (default settings, mod configs).
     */
    private static final Set<String> SAFE_CONFIG_PATTERNS = Set.of(
            ".toml",
            ".json",
            ".cfg",
            ".properties"
    );

    /**
     * Represents a file entry in the manifest.
     */
    public static class PackEntry {
        public final String name;
        public final String type; // "mod", "config", "script", "asset"
        public final String sha256;
        public final String filePath; // Relative path inside the .apepack
        public final String installPath; // Relative path where it should be installed
        public final long size;

        PackEntry(String name, String type, String sha256, String filePath, String installPath, long size) {
            this.name = name;
            this.type = type;
            this.sha256 = sha256;
            this.filePath = filePath;
            this.installPath = installPath;
            this.size = size;
        }
    }

    /**
     * Result of an .apepack generation.
     */
    public static class GenerateResult {
        public final Path outputFile;
        public final int modCount;
        public final int configCount;
        public final int scriptCount;
        public final long totalSize;
        public final boolean success;
        public final String errorMessage;

        GenerateResult(Path outputFile, int modCount, int configCount, int scriptCount,
                       long totalSize, boolean success, String error) {
            this.outputFile = outputFile;
            this.modCount = modCount;
            this.configCount = configCount;
            this.scriptCount = scriptCount;
            this.totalSize = totalSize;
            this.success = success;
            this.errorMessage = error;
        }
    }

    /**
     * Generates an .apepack file from the current Minecraft instance.
     *
     * @param instanceRoot The root directory of the Minecraft instance (current working directory)
     * @param outputPath   Where to save the .apepack file
     * @param packName     Name of the modpack
     * @param packVersion  Version string for this pack
     * @param mcVersion    Minecraft version (e.g., "26.1.2")
     * @return GenerateResult with statistics about what was packed
     */
    public static GenerateResult generate(Path instanceRoot, Path outputPath,
                                          String packName, String packVersion, String mcVersion) {
        try {
            List<PackEntry> entries = new ArrayList<>();
            int modCount = 0;
            int configCount = 0;
            int scriptCount = 0;

            // 1. Scan mods directory
            Path modsDir = instanceRoot.resolve("mods");
            if (Files.exists(modsDir)) {
                try (Stream<Path> paths = Files.list(modsDir)) {
                    List<Path> modFiles = paths
                            .filter(p -> p.toString().toLowerCase().endsWith(".jar")
                                    && !p.toString().toLowerCase().endsWith(".apepack"))
                            .collect(Collectors.toList());

                    for (Path modFile : modFiles) {
                        String name = modFile.getFileName().toString();
                        String sha256 = ApePackLoader.computeSha256(modFile);
                        long size = Files.size(modFile);
                        String zipPath = "mods/" + name;
                        entries.add(new PackEntry(name, "mod", sha256, zipPath, "mods/" + name, size));
                        modCount++;
                    }
                }
            }

            // 2. Scan config directory (exclude user settings)
            Path configDir = instanceRoot.resolve("config");
            if (Files.exists(configDir)) {
                try (Stream<Path> paths = Files.walk(configDir)) {
                    List<Path> configFiles = paths
                            .filter(Files::isRegularFile)
                            .filter(ApePackGenerator::isSafeConfigFile)
                            .collect(Collectors.toList());

                    for (Path configFile : configFiles) {
                        String relativePath = configFile.toString().substring("config/".length());
                        String name = configFile.getFileName().toString();
                        String sha256 = ApePackLoader.computeSha256(configFile);
                        long size = Files.size(configFile);
                        String zipPath = "config/" + relativePath;
                        entries.add(new PackEntry(name, "config", sha256, zipPath, "config/" + relativePath, size));
                        configCount++;
                    }
                }
            }

            // 3. Scan KubeJS directory (if present)
            Path kubejsDir = instanceRoot.resolve("kubejs");
            if (Files.exists(kubejsDir)) {
                // Scan KubeJS server scripts
                Path serverScripts = kubejsDir.resolve("server_scripts");
                if (Files.exists(serverScripts)) {
                    try (Stream<Path> paths = Files.walk(serverScripts)) {
                        List<Path> scriptFiles = paths
                                .filter(Files::isRegularFile)
                                .filter(p -> p.toString().toLowerCase().endsWith(".js")
                                        || p.toString().toLowerCase().endsWith(".mjs"))
                                .collect(Collectors.toList());

                        for (Path scriptFile : scriptFiles) {
                            String relativePath = scriptFile.toString().substring("kubejs/".length());
                            String name = scriptFile.getFileName().toString();
                            String sha256 = ApePackLoader.computeSha256(scriptFile);
                            long size = Files.size(scriptFile);
                            String zipPath = "kubejs/" + relativePath;
                            entries.add(new PackEntry(name, "script", sha256, zipPath, "kubejs/" + relativePath, size));
                            scriptCount++;
                        }
                    }
                }

                // Scan KubeJS client scripts
                Path clientScripts = kubejsDir.resolve("client_scripts");
                if (Files.exists(clientScripts)) {
                    try (Stream<Path> paths = Files.walk(clientScripts)) {
                        List<Path> scriptFiles = paths
                                .filter(Files::isRegularFile)
                                .filter(p -> p.toString().toLowerCase().endsWith(".js")
                                        || p.toString().toLowerCase().endsWith(".mjs"))
                                .collect(Collectors.toList());

                        for (Path scriptFile : scriptFiles) {
                            String relativePath = scriptFile.toString().substring("kubejs/".length());
                            String name = scriptFile.getFileName().toString();
                            String sha256 = ApePackLoader.computeSha256(scriptFile);
                            long size = Files.size(scriptFile);
                            String zipPath = "kubejs/" + relativePath;
                            entries.add(new PackEntry(name, "script", sha256, zipPath, "kubejs/" + relativePath, size));
                            scriptCount++;
                        }
                    }
                }

                // Scan KubeJS Groovy scripts
                Path groovyDir = kubejsDir.resolve("groovy");
                if (Files.exists(groovyDir)) {
                    try (Stream<Path> paths = Files.walk(groovyDir)) {
                        List<Path> scriptFiles = paths
                                .filter(Files::isRegularFile)
                                .filter(p -> p.toString().toLowerCase().endsWith(".groovy"))
                                .collect(Collectors.toList());

                        for (Path scriptFile : scriptFiles) {
                            String relativePath = scriptFile.toString().substring("kubejs/".length());
                            String name = scriptFile.getFileName().toString();
                            String sha256 = ApePackLoader.computeSha256(scriptFile);
                            long size = Files.size(scriptFile);
                            String zipPath = "kubejs/" + relativePath;
                            entries.add(new PackEntry(name, "script", sha256, zipPath, "kubejs/" + relativePath, size));
                            scriptCount++;
                        }
                    }
                }

                // Scan KubeJS assets
                Path assetsDir = kubejsDir.resolve("assets");
                if (Files.exists(assetsDir)) {
                    try (Stream<Path> paths = Files.walk(assetsDir)) {
                        List<Path> assetFiles = paths
                                .filter(Files::isRegularFile)
                                .collect(Collectors.toList());

                        for (Path assetFile : assetFiles) {
                            String relativePath = assetFile.toString().substring("kubejs/".length());
                            String name = assetFile.getFileName().toString();
                            String sha256 = ApePackLoader.computeSha256(assetFile);
                            long size = Files.size(assetFile);
                            String zipPath = "kubejs/" + relativePath;
                            entries.add(new PackEntry(name, "asset", sha256, zipPath, "kubejs/" + relativePath, size));
                        }
                    }
                }

                // Scan KubeJS data packs
                Path dataDir = kubejsDir.resolve("data");
                if (Files.exists(dataDir)) {
                    try (Stream<Path> paths = Files.walk(dataDir)) {
                        List<Path> dataFiles = paths
                                .filter(Files::isRegularFile)
                                .collect(Collectors.toList());

                        for (Path dataFile : dataFiles) {
                            String relativePath = dataFile.toString().substring("kubejs/".length());
                            String name = dataFile.getFileName().toString();
                            String sha256 = ApePackLoader.computeSha256(dataFile);
                            long size = Files.size(dataFile);
                            String zipPath = "kubejs/" + relativePath;
                            entries.add(new PackEntry(name, "asset", sha256, zipPath, "kubejs/" + relativePath, size));
                        }
                    }
                }

                // Include kubejs configuration files
                Path kubejsConfig = kubejsDir.resolve("kubejs.json5");
                if (Files.exists(kubejsConfig)) {
                    String name = kubejsConfig.getFileName().toString();
                    String sha256 = ApePackLoader.computeSha256(kubejsConfig);
                    long size = Files.size(kubejsConfig);
                    entries.add(new PackEntry(name, "config", sha256, "kubejs/" + name, "kubejs/" + name, size));
                }

                Path kubejsCommon = kubejsDir.resolve("kubejs.common.json5");
                if (Files.exists(kubejsCommon)) {
                    String name = kubejsCommon.getFileName().toString();
                    String sha256 = ApePackLoader.computeSha256(kubejsCommon);
                    long size = Files.size(kubejsCommon);
                    entries.add(new PackEntry(name, "config", sha256, "kubejs/" + name, "kubejs/" + name, size));
                }
            }

            // 4. Build the .apepack ZIP
            Files.createDirectories(outputPath.getParent());
            long totalSize = 0;

            try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(Files.newOutputStream(outputPath))) {
                // Write apepack.json
                JsonObject apepackJson = new JsonObject();
                apepackJson.addProperty("packName", packName);
                apepackJson.addProperty("packVersion", packVersion);
                apepackJson.addProperty("mcVersion", mcVersion);
                apepackJson.addProperty("modCount", modCount);
                apepackJson.addProperty("configCount", configCount);
                apepackJson.addProperty("scriptCount", scriptCount);
                apepackJson.addProperty("totalFiles", entries.size());
                apepackJson.addProperty("generatedAt", new Date().toString());

                ZipArchiveEntry apepackEntry = new ZipArchiveEntry("apepack.json");
                zos.putArchiveEntry(apepackEntry);
                zos.write(apepackJson.toString().getBytes());
                zos.closeArchiveEntry();

                // Write manifest.json
                JsonObject manifestJson = new JsonObject();
                JsonArray entriesArray = new JsonArray();

                for (PackEntry entry : entries) {
                    JsonObject entryJson = new JsonObject();
                    entryJson.addProperty("name", entry.name);
                    entryJson.addProperty("type", entry.type);
                    entryJson.addProperty("sha256", entry.sha256);
                    entryJson.addProperty("file", entry.filePath);
                    entryJson.addProperty("installPath", entry.installPath);
                    entryJson.addProperty("size", entry.size);
                    entriesArray.add(entryJson);
                }

                manifestJson.add("files", entriesArray);

                ZipArchiveEntry manifestEntry = new ZipArchiveEntry("manifest.json");
                zos.putArchiveEntry(manifestEntry);
                zos.write(manifestJson.toString().getBytes());
                zos.closeArchiveEntry();

                // Write all files
                for (PackEntry entry : entries) {
                    Path sourceFile = instanceRoot.resolve(entry.installPath);
                    if (Files.exists(sourceFile)) {
                        ZipArchiveEntry fileEntry = new ZipArchiveEntry(entry.filePath);
                        zos.putArchiveEntry(fileEntry);
                        Files.copy(sourceFile, zos);
                        zos.closeArchiveEntry();
                        totalSize += entry.size;
                    }
                }
            }

            System.out.println("[PackUpdater] Generated .apepack: " + outputPath.getFileName());
            System.out.println("[PackUpdater] Mods: " + modCount +
                    ", Configs: " + configCount +
                    ", Scripts: " + scriptCount +
                    ", Total files: " + entries.size());
            System.out.println("[PackUpdater] Total size: " + (totalSize / 1024 / 1024) + " MB");

            return new GenerateResult(outputPath, modCount, configCount, scriptCount, totalSize, true, null);

        } catch (IOException e) {
            System.err.println("[PackUpdater] Failed to generate .apepack: " + e.getMessage());
            return new GenerateResult(null, 0, 0, 0, 0, false, e.getMessage());
        }
    }

    /**
     * Checks if a config file is safe to include in the pack (not user-specific).
     */
    private static boolean isSafeConfigFile(Path filePath) {
        String fileName = filePath.getFileName().toString().toLowerCase();

        // Exclude user settings files
        if (USER_SETTINGS_FILES.contains(fileName)) {
            return false;
        }

        // Only include safe config extensions
        for (String ext : SAFE_CONFIG_PATTERNS) {
            if (fileName.endsWith(ext)) {
                return true;
            }
        }

        return false;
    }
}
