package com.Apeiron.globalpackupdater;

import com.google.gson.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Spawns a new JVM process to relaunch Minecraft automatically.
 * Reads the NeoForge and base Minecraft version JSONs to reconstruct
 * the full launch command (libraries, JVM args, game args, natives).
 */
public class GameRelauncher {

    /**
     * The CurseForge Install directory (where libraries and versions are stored).
     * CurseForge stores at C:\Users\<user>\curseforge\minecraft\Install
     * Falls back to %APPDATA%\.minecraft for vanilla installations.
     */
    private static final Path INSTALL_DIR;

    static {
        Path installDir = null;
        // Try CurseForge Install directory first
        String userHome = System.getProperty("user.home");
        Path curseForgeInstall = Paths.get(userHome, "curseforge", "minecraft", "Install");
        if (Files.exists(curseForgeInstall.resolve("libraries"))) {
            installDir = curseForgeInstall;
        }
        // Fall back to vanilla .minecraft
        if (installDir == null) {
            String appData = System.getenv("APPDATA");
            if (appData != null) {
                Path candidate = Paths.get(appData, ".minecraft");
                if (Files.exists(candidate.resolve("libraries"))) {
                    installDir = candidate;
                }
            }
        }
        if (installDir == null) {
            Path candidate = Paths.get(userHome, ".minecraft");
            if (Files.exists(candidate.resolve("libraries"))) {
                installDir = candidate;
            }
        }
        INSTALL_DIR = installDir;
    }

    /**
     * Returns the current OS name for library rule filtering.
     */
    private static String getCurrentOsName() {
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) return "windows";
        if (osName.contains("mac") || osName.contains("osx")) return "osx";
        if (osName.contains("nix") || osName.contains("nux") || osName.contains("aix")) return "linux";
        return "unknown";
    }

    /**
     * Checks if a library with OS rules should be included for the current OS.
     */
    private static boolean libraryAllowedForOs(JsonObject lib) {
        String currentOs = getCurrentOsName();
        if (!lib.has("rules") || !lib.get("rules").isJsonArray()) {
            return true; // No rules = always include
        }
        boolean allowed = false;
        for (JsonElement ruleElem : lib.get("rules").getAsJsonArray()) {
            JsonObject rule = ruleElem.getAsJsonObject();
            if (!rule.has("action")) continue;
            String action = rule.get("action").getAsString();
            if (rule.has("os") && rule.get("os").isJsonObject()) {
                JsonObject os = rule.get("os").getAsJsonObject();
                if (os.has("name")) {
                    String ruleOs = os.get("name").getAsString();
                    if (action.equals("allow") && ruleOs.equals(currentOs)) {
                        allowed = true;
                    } else if (action.equals("disallow") && ruleOs.equals(currentOs)) {
                        allowed = false;
                    }
                } else {
                    // No OS specified in rule
                    if (action.equals("allow")) allowed = true;
                    else if (action.equals("disallow")) allowed = false;
                }
            } else {
                // No OS object in rule
                if (action.equals("allow")) allowed = true;
                else if (action.equals("disallow")) allowed = false;
            }
        }
        return allowed;
    }

    /**
     * Attempts to relaunch Minecraft by spawning a new JVM process.
     * Returns true if the new process was started successfully.
     */
    public static boolean relaunch() {
        if (INSTALL_DIR == null) {
            System.err.println("[PackUpdater] Cannot relaunch: Install directory not found.");
            return false;
        }

        try {
            System.out.println("[PackUpdater] Attempting to relaunch Minecraft...");
            System.out.println("[PackUpdater] Install directory: " + INSTALL_DIR.toAbsolutePath());

            // 1. Detect the NeoForge version being used
            String neoForgeVersion = detectNeoForgeVersion();
            if (neoForgeVersion == null) {
                System.err.println("[PackUpdater] Cannot relaunch: NeoForge version not detected.");
                return false;
            }
            System.out.println("[PackUpdater] Detected NeoForge version: " + neoForgeVersion);

            // 2. Read the NeoForge version JSON
            Path neoForgeJson = INSTALL_DIR.resolve("versions").resolve(neoForgeVersion).resolve(neoForgeVersion + ".json");
            if (!Files.exists(neoForgeJson)) {
                System.err.println("[PackUpdater] NeoForge version JSON not found: " + neoForgeJson);
                return false;
            }

            JsonObject neoForgeData = parseJson(neoForgeJson);
            String baseVersion = neoForgeData.get("inheritsFrom").getAsString();
            System.out.println("[PackUpdater] Base Minecraft version: " + baseVersion);

            // 3. Read the base Minecraft version JSON
            Path baseJson = INSTALL_DIR.resolve("versions").resolve(baseVersion).resolve(baseVersion + ".json");
            if (!Files.exists(baseJson)) {
                System.err.println("[PackUpdater] Base version JSON not found: " + baseJson);
                return false;
            }

            JsonObject baseData = parseJson(baseJson);

            // 4. Get the instance root (where mods/, config/ are)
            Path instanceRoot = Paths.get(".");

            // 5. Extract natives first (needed for JVM args resolution)
            Path nativesDir = instanceRoot.resolve("natives_relaunch_" + System.currentTimeMillis());
            Files.createDirectories(nativesDir);

            // 6. Merge libraries (filtered by OS), JVM args, and game args
            List<String> allLibraries = mergeLibraries(baseData, neoForgeData);
            System.out.println("[PackUpdater] Libraries count (after OS filter): " + allLibraries.size());
            setNativesDir(nativesDir);
            List<String> jvmArgs = mergeJvmArgs(baseData, neoForgeData, instanceRoot, neoForgeVersion);
            List<String> gameArgs = mergeGameArgs(baseData, neoForgeData, instanceRoot, neoForgeVersion);
            extractNatives(allLibraries, nativesDir);
            System.out.println("[PackUpdater] Natives extracted to: " + nativesDir.toAbsolutePath());

            // 7. Build classpath
            String classpath = buildClasspath(allLibraries, instanceRoot, neoForgeVersion);
            System.out.println("[PackUpdater] Classpath length: " + classpath.length() + " chars");
            if (classpath.isEmpty()) {
                System.err.println("[PackUpdater] ERROR: Classpath is empty! Libraries dir: " + INSTALL_DIR.resolve("libraries"));
                return false;
            }

            // 8. Get main class
            String mainClass = neoForgeData.has("mainClass") ? neoForgeData.get("mainClass").getAsString() :
                    baseData.has("mainClass") ? baseData.get("mainClass").getAsString() : "net.minecraft.client.main.Main";

            // 9. Build the full command
            List<String> command = new ArrayList<>();

            // Find Java executable
            String javaHome = System.getProperty("java.home");
            Path javaExe = Paths.get(javaHome, "bin", "java.exe");
            if (!Files.exists(javaExe)) {
                javaExe = Paths.get(javaHome, "..", "bin", "java.exe");
            }
            if (!Files.exists(javaExe)) {
                System.err.println("[PackUpdater] Java executable not found at: " + javaExe);
                return false;
            }
            command.add(javaExe.toString());
            System.out.println("[PackUpdater] Using Java: " + javaExe);

            // Add JVM args
            command.addAll(jvmArgs);

            // Add classpath
            command.add("-cp");
            command.add(classpath);

            // Add main class
            command.add(mainClass);

            // Add game args
            command.addAll(gameArgs);

            // 10. Log the command (truncated for readability)
            StringBuilder cmdLog = new StringBuilder("[PackUpdater] Launch command: ");
            cmdLog.append(command.get(0)).append(" ");
            for (int i = 1; i < command.size() && i < 10; i++) {
                cmdLog.append(command.get(i)).append(" ");
            }
            if (command.size() > 10) cmdLog.append("... (").append(command.size()).append(" args total)");
            System.out.println(cmdLog);

            // 11. Spawn the new JVM process
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(instanceRoot.toFile());
            pb.redirectErrorStream(true);

            // Detach the process so it survives our exit
            Process process = pb.start();

            System.out.println("[PackUpdater] New Minecraft process started with PID: " + process.pid());

            // Give the new process a moment to start
            Thread.sleep(2000);

            // Check if the process is still running
            if (process.isAlive()) {
                System.out.println("[PackUpdater] ✅ Relaunch successful! Exiting current JVM...");
                return true;
            } else {
                System.err.println("[PackUpdater] ❌ New process exited immediately. Relaunch failed.");
                // Try to read any error output
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    int lineCount = 0;
                    while ((line = reader.readLine()) != null && lineCount < 20) {
                        System.err.println("[PackUpdater] [New JVM] " + line);
                        lineCount++;
                    }
                }
                return false;
            }

        } catch (Exception e) {
            System.err.println("[PackUpdater] Relaunch failed: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Detects the NeoForge version by scanning the versions directory.
     * Looks for the most recent NeoForge version folder.
     */
    private static String detectNeoForgeVersion() {
        Path versionsDir = INSTALL_DIR.resolve("versions");
        if (!Files.exists(versionsDir)) return null;

        String latestVersion = null;
        long latestTime = 0;

        try (Stream<Path> paths = Files.list(versionsDir)) {
            for (Path dir : paths.toArray(Path[]::new)) {
                if (Files.isDirectory(dir)) {
                    String name = dir.getFileName().toString();
                    if (name.toLowerCase().startsWith("neoforge")) {
                        Path versionJson = dir.resolve(name + ".json");
                        if (Files.exists(versionJson)) {
                            long lastModified = Files.getLastModifiedTime(dir).toMillis();
                            if (lastModified > latestTime) {
                                latestTime = lastModified;
                                latestVersion = name;
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[PackUpdater] Failed to scan versions directory: " + e.getMessage());
        }

        return latestVersion;
    }

    /**
     * Merges libraries from base and NeoForge version JSONs.
     */
    private static List<String> mergeLibraries(JsonObject baseData, JsonObject neoForgeData) {
        Set<String> libraryPaths = new LinkedHashSet<>();

        // Add base libraries (filtered by OS rules)
        if (baseData.has("libraries") && baseData.get("libraries").isJsonArray()) {
            for (JsonElement elem : baseData.get("libraries").getAsJsonArray()) {
                JsonObject lib = elem.getAsJsonObject();
                if (!libraryAllowedForOs(lib)) continue;
                if (lib.has("downloads") && lib.get("downloads").isJsonObject()) {
                    JsonObject downloads = lib.get("downloads").getAsJsonObject();
                    if (downloads.has("artifact") && downloads.get("artifact").isJsonObject()) {
                        JsonObject artifact = downloads.get("artifact").getAsJsonObject();
                        if (artifact.has("path")) {
                            libraryPaths.add(artifact.get("path").getAsString());
                        }
                    }
                }
            }
        }

        // Add NeoForge libraries (filtered by OS rules, overrides duplicates)
        if (neoForgeData.has("libraries") && neoForgeData.get("libraries").isJsonArray()) {
            for (JsonElement elem : neoForgeData.get("libraries").getAsJsonArray()) {
                JsonObject lib = elem.getAsJsonObject();
                if (!libraryAllowedForOs(lib)) continue;
                if (lib.has("downloads") && lib.get("downloads").isJsonObject()) {
                    JsonObject downloads = lib.get("downloads").getAsJsonObject();
                    if (downloads.has("artifact") && downloads.get("artifact").isJsonObject()) {
                        JsonObject artifact = downloads.get("artifact").getAsJsonObject();
                        if (artifact.has("path")) {
                            String path = artifact.get("path").getAsString();
                            libraryPaths.remove(path); // Remove duplicate
                            libraryPaths.add(path); // Re-add at end
                        }
                    }
                }
            }
        }

        return new ArrayList<>(libraryPaths);
    }

    /**
     * Merges JVM arguments from base and NeoForge version JSONs, resolving variables.
     */
    private static List<String> mergeJvmArgs(JsonObject baseData, JsonObject neoForgeData, Path instanceRoot, String neoForgeVersion) {
        List<String> args = new ArrayList<>();

        // Base JVM args
        if (baseData.has("arguments") && baseData.get("arguments").isJsonObject()) {
            JsonObject arguments = baseData.get("arguments").getAsJsonObject();
            if (arguments.has("jvm") && arguments.get("jvm").isJsonArray()) {
                for (JsonElement elem : arguments.get("jvm").getAsJsonArray()) {
                    if (elem.isJsonPrimitive()) {
                        String raw = elem.getAsString();
                        // Skip -cp and ${classpath} entries from the version JSON - we handle classpath ourselves
                        if (raw.equals("-cp") || raw.equals("-classpath") || raw.equals("${classpath}")) {
                            continue;
                        }
                        if (raw.startsWith("-")) {
                            String arg = resolveJvmVariable(raw, instanceRoot, neoForgeVersion);
                            args.add(arg);
                        }
                    } else if (elem.isJsonObject()) {
                        // JVM args with rules (OS-specific)
                        String arg = resolveJvmArgWithRules(elem.getAsJsonObject(), instanceRoot, neoForgeVersion);
                        if (arg != null) args.add(arg);
                    }
                }
            }
        }

        // NeoForge JVM args
        if (neoForgeData.has("arguments") && neoForgeData.get("arguments").isJsonObject()) {
            JsonObject arguments = neoForgeData.get("arguments").getAsJsonObject();
            if (arguments.has("jvm") && arguments.get("jvm").isJsonArray()) {
                for (JsonElement elem : arguments.get("jvm").getAsJsonArray()) {
                    if (elem.isJsonPrimitive()) {
                        String raw = elem.getAsString();
                        // Skip -cp and ${classpath} entries
                        if (raw.equals("-cp") || raw.equals("-classpath") || raw.equals("${classpath}")) {
                            continue;
                        }
                        String arg = resolveJvmVariable(raw, instanceRoot, neoForgeVersion);
                        args.add(arg);
                    }
                }
            }
        }

        return args;
    }

    /**
     * Resolves a JVM argument that may have OS-specific rules.
     */
    private static String resolveJvmArgWithRules(JsonObject argObj, Path instanceRoot, String neoForgeVersion) {
        // Check if this arg applies to the current OS
        if (argObj.has("rules") && argObj.get("rules").isJsonArray()) {
            boolean allowed = false;
            String osName = System.getProperty("os.name").toLowerCase();
            for (JsonElement ruleElem : argObj.get("rules").getAsJsonArray()) {
                JsonObject rule = ruleElem.getAsJsonObject();
                if (rule.has("action") && rule.get("action").getAsString().equals("allow")) {
                    if (rule.has("os") && rule.get("os").isJsonObject()) {
                        JsonObject os = rule.get("os").getAsJsonObject();
                        if (os.has("name")) {
                            String ruleOs = os.get("name").getAsString();
                            if (ruleOs.equals("windows") && osName.contains("win")) allowed = true;
                            else if (ruleOs.equals("osx") && osName.contains("mac")) allowed = true;
                            else if (ruleOs.equals("linux") && osName.contains("nux")) allowed = true;
                        } else {
                            allowed = true; // No OS restriction
                        }
                    } else {
                        allowed = true; // No OS rule
                    }
                } else if (rule.has("action") && rule.get("action").getAsString().equals("disallow")) {
                    if (rule.has("os") && rule.get("os").isJsonObject()) {
                        JsonObject os = rule.get("os").getAsJsonObject();
                        if (os.has("name")) {
                            String ruleOs = os.get("name").getAsString();
                            if (ruleOs.equals("windows") && osName.contains("win")) allowed = false;
                            else if (ruleOs.equals("osx") && osName.contains("mac")) allowed = false;
                            else if (ruleOs.equals("linux") && osName.contains("nux")) allowed = false;
                        }
                    }
                }
            }
            if (!allowed) return null;
        }

        // Extract the value
        if (argObj.has("value")) {
            if (argObj.get("value").isJsonArray()) {
                // Value is an array of strings - filter out -cp and ${classpath}
                StringBuilder sb = new StringBuilder();
                boolean skipNext = false;
                for (JsonElement val : argObj.get("value").getAsJsonArray()) {
                    String s = val.getAsString();
                    if (s.equals("-cp") || s.equals("-classpath")) {
                        skipNext = true;
                        continue;
                    }
                    if (skipNext && s.equals("${classpath}")) {
                        skipNext = false;
                        continue;
                    }
                    skipNext = false;
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(resolveJvmVariable(s, instanceRoot, neoForgeVersion));
                }
                return sb.toString();
            } else {
                String val = argObj.get("value").getAsString();
                // Skip -cp and ${classpath} values
                if (val.equals("-cp") || val.equals("-classpath") || val.equals("${classpath}")) {
                    return null;
                }
                return resolveJvmVariable(val, instanceRoot, neoForgeVersion);
            }
        }
        return null;
    }

    /**
     * Resolves variables in JVM arguments.
     * Note: nativesDir is set dynamically during relaunch() and stored in a thread-local.
     */
    private static final ThreadLocal<Path> NATIVES_DIR_THREAD = new ThreadLocal<>();

    private static void setNativesDir(Path dir) { NATIVES_DIR_THREAD.set(dir); }
    private static Path getNativesDir() { return NATIVES_DIR_THREAD.get(); }

    private static String resolveJvmVariable(String arg, Path instanceRoot, String neoForgeVersion) {
        String librariesDir = INSTALL_DIR.resolve("libraries").toString().replace("\\", "/");
        String gameDir = instanceRoot.toString().replace("\\", "/");
        String nativesDir = (getNativesDir() != null) ? getNativesDir().toString().replace("\\", "/") : "";

        return arg
                .replace("${library_directory}", librariesDir)
                .replace("${game_directory}", gameDir)
                .replace("${natives_directory}", nativesDir)
                .replace("${version_name}", neoForgeVersion)
                .replace("${version}", neoForgeVersion)
                .replace("${classpath}", "")
                .replace("${launcher_name}", "ApeironPackUpdater")
                .replace("${launcher_version}", "1.0");
    }

    /**
     * Merges game arguments from base and NeoForge version JSONs, resolving variables.
     */
    private static List<String> mergeGameArgs(JsonObject baseData, JsonObject neoForgeData, Path instanceRoot, String neoForgeVersion) {
        List<String> args = new ArrayList<>();

        // Base game args
        if (baseData.has("arguments") && baseData.get("arguments").isJsonObject()) {
            JsonObject arguments = baseData.get("arguments").getAsJsonObject();
            if (arguments.has("game") && arguments.get("game").isJsonArray()) {
                for (JsonElement elem : arguments.get("game").getAsJsonArray()) {
                    if (elem.isJsonPrimitive()) {
                        String arg = resolveGameVariable(elem.getAsString(), instanceRoot, neoForgeVersion);
                        // Skip --demo which causes Minecraft to launch in demo mode
                        if (arg.equals("--demo")) continue;
                        args.add(arg);
                    } else if (elem.isJsonObject()) {
                        // Game args with rules - skip --demo
                        String arg = resolveGameArgWithRules(elem.getAsJsonObject(), instanceRoot, neoForgeVersion);
                        if (arg != null && !arg.equals("--demo")) args.add(arg);
                    }
                }
            }
        }

        // NeoForge game args
        if (neoForgeData.has("arguments") && neoForgeData.get("arguments").isJsonObject()) {
            JsonObject arguments = neoForgeData.get("arguments").getAsJsonObject();
            if (arguments.has("game") && arguments.get("game").isJsonArray()) {
                for (JsonElement elem : arguments.get("game").getAsJsonArray()) {
                    if (elem.isJsonPrimitive()) {
                        String arg = resolveGameVariable(elem.getAsString(), instanceRoot, neoForgeVersion);
                        if (arg.equals("--demo")) continue;
                        args.add(arg);
                    }
                }
            }
        }

        return args;
    }

    /**
     * Resolves a game argument that may have OS-specific rules.
     */
    private static String resolveGameArgWithRules(JsonObject argObj, Path instanceRoot, String neoForgeVersion) {
        if (argObj.has("rules") && argObj.get("rules").isJsonArray()) {
            boolean allowed = false;
            String osName = System.getProperty("os.name").toLowerCase();
            for (JsonElement ruleElem : argObj.get("rules").getAsJsonArray()) {
                JsonObject rule = ruleElem.getAsJsonObject();
                if (rule.has("action") && rule.get("action").getAsString().equals("allow")) {
                    if (rule.has("os") && rule.get("os").isJsonObject()) {
                        JsonObject os = rule.get("os").getAsJsonObject();
                        if (os.has("name")) {
                            String ruleOs = os.get("name").getAsString();
                            if (ruleOs.equals("windows") && osName.contains("win")) allowed = true;
                            else if (ruleOs.equals("osx") && osName.contains("mac")) allowed = true;
                            else if (ruleOs.equals("linux") && osName.contains("nux")) allowed = true;
                        } else {
                            allowed = true;
                        }
                    } else {
                        allowed = true;
                    }
                } else if (rule.has("action") && rule.get("action").getAsString().equals("disallow")) {
                    if (rule.has("os") && rule.get("os").isJsonObject()) {
                        JsonObject os = rule.get("os").getAsJsonObject();
                        if (os.has("name")) {
                            String ruleOs = os.get("name").getAsString();
                            if (ruleOs.equals("windows") && osName.contains("win")) allowed = false;
                            else if (ruleOs.equals("osx") && osName.contains("mac")) allowed = false;
                            else if (ruleOs.equals("linux") && osName.contains("nux")) allowed = false;
                        }
                    }
                }
            }
            if (!allowed) return null;
        }

        if (argObj.has("value")) {
            if (argObj.get("value").isJsonArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonElement val : argObj.get("value").getAsJsonArray()) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(resolveGameVariable(val.getAsString(), instanceRoot, neoForgeVersion));
                }
                return sb.toString();
            } else {
                return resolveGameVariable(argObj.get("value").getAsString(), instanceRoot, neoForgeVersion);
            }
        }
        return null;
    }

    /**
     * Resolves variables in game arguments.
     */
    private static String resolveGameVariable(String arg, Path instanceRoot, String neoForgeVersion) {
        String librariesDir = INSTALL_DIR.resolve("libraries").toString().replace("\\", "/");
        String gameDir = instanceRoot.toString().replace("\\", "/");
        String userName = "Player"; // Offline mode username

        return arg
                .replace("${auth_player_name}", userName)
                .replace("${auth_session}", "")
                .replace("${auth_access_token}", "")
                .replace("${user_type}", "msa")
                .replace("${version_name}", neoForgeVersion)
                .replace("${version_type}", "modded")
                .replace("${game_directory}", gameDir)
                .replace("${assets_root}", INSTALL_DIR.resolve("assets").toString().replace("\\", "/"))
                .replace("${assets_index_name}", "unknown")
                .replace("${auth_uuid}", "00000000-0000-0000-0000-000000000000")
                .replace("${auth_xuid}", "")
                .replace("${user_properties}", "{}")
                .replace("${clientid}", "")
                .replace("${library_directory}", librariesDir)
                .replace("${version}", neoForgeVersion);
    }

    /**
     * Builds the classpath string from the list of library paths.
     */
    private static String buildClasspath(List<String> libraryPaths, Path instanceRoot, String neoForgeVersion) {
        StringBuilder sb = new StringBuilder();
        String librariesDir = INSTALL_DIR.resolve("libraries").toString();

        for (String libPath : libraryPaths) {
            Path libFile = Paths.get(librariesDir, libPath);
            if (Files.exists(libFile)) {
                if (sb.length() > 0) sb.append(File.pathSeparator);
                sb.append(libFile.toString());
            } else {
                System.err.println("[PackUpdater] Warning: Library not found: " + libFile);
            }
        }

        // Add the patched client JAR if it exists (for vanilla NeoForge installs)
        // CurseForge/FancyModLoader doesn't use a patched client JAR, so this is optional
        Path versionsDir = INSTALL_DIR.resolve("versions");
        if (Files.exists(versionsDir)) {
            try (Stream<Path> paths = Files.list(versionsDir)) {
                for (Path dir : paths.toArray(Path[]::new)) {
                    if (Files.isDirectory(dir)) {
                        String name = dir.getFileName().toString();
                        if (name.equals(neoForgeVersion)) {
                            Path patchedJar = dir.resolve(name + "-client.jar");
                            if (Files.exists(patchedJar)) {
                                if (sb.length() > 0) sb.append(File.pathSeparator);
                                sb.append(patchedJar.toString());
                                System.out.println("[PackUpdater] Found patched client JAR: " + patchedJar);
                            }
                            break;
                        }
                    }
                }
            } catch (IOException e) {
                // Ignore
            }
        }

        return sb.toString();
    }

    /**
     * Extracts native libraries to the specified directory.
     */
    private static void extractNatives(List<String> libraryPaths, Path nativesDir) {
        String librariesDir = INSTALL_DIR.resolve("libraries").toString();

        for (String libPath : libraryPaths) {
            Path libFile = Paths.get(librariesDir, libPath);
            if (!Files.exists(libFile)) continue;

            try (java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(libFile.toFile())) {
                // Check if this JAR has a natives/ directory
                java.util.zip.ZipEntry nativesEntry = zipFile.getEntry("natives");
                if (nativesEntry != null) {
                    System.out.println("[PackUpdater] Extracting natives from: " + libPath);
                    zipFile.stream()
                            .filter(e -> !e.isDirectory() && e.getName().startsWith("natives/"))
                            .forEach(e -> {
                                try {
                                    String name = e.getName().substring("natives/".length());
                                    Path target = nativesDir.resolve(name);
                                    Files.createDirectories(target.getParent());
                                    try (InputStream is = zipFile.getInputStream(e)) {
                                        Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                                    }
                                } catch (IOException ex) {
                                    System.err.println("[PackUpdater] Failed to extract native: " + e.getName());
                                }
                            });
                }
            } catch (IOException e) {
                // Not a ZIP or already extracted
            }
        }
    }

    /**
     * Parses a JSON file into a JsonObject.
     */
    private static JsonObject parseJson(Path path) throws IOException {
        String content = Files.readString(path);
        return JsonParser.parseString(content).getAsJsonObject();
    }
}
