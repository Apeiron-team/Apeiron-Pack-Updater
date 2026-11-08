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

    private static final Path MINECRAFT_DIR;

    static {
        // Find the .minecraft directory
        // The instance root is Paths.get(".") which is the CurseForge instance folder
        // .minecraft is typically at %APPDATA%\.minecraft or ~/.minecraft
        Path mcDir = null;
        String appData = System.getenv("APPDATA");
        if (appData != null) {
            Path candidate = Paths.get(appData, ".minecraft");
            if (Files.exists(candidate)) {
                mcDir = candidate;
            }
        }
        if (mcDir == null) {
            String userHome = System.getProperty("user.home");
            Path candidate = Paths.get(userHome, ".minecraft");
            if (Files.exists(candidate)) {
                mcDir = candidate;
            }
        }
        MINECRAFT_DIR = mcDir;
    }

    /**
     * Attempts to relaunch Minecraft by spawning a new JVM process.
     * Returns true if the new process was started successfully.
     */
    public static boolean relaunch() {
        if (MINECRAFT_DIR == null) {
            System.err.println("[PackUpdater] Cannot relaunch: .minecraft directory not found.");
            return false;
        }

        try {
            System.out.println("[PackUpdater] Attempting to relaunch Minecraft...");

            // 1. Detect the NeoForge version being used
            String neoForgeVersion = detectNeoForgeVersion();
            if (neoForgeVersion == null) {
                System.err.println("[PackUpdater] Cannot relaunch: NeoForge version not detected.");
                return false;
            }
            System.out.println("[PackUpdater] Detected NeoForge version: " + neoForgeVersion);

            // 2. Read the NeoForge version JSON
            Path neoForgeJson = MINECRAFT_DIR.resolve("versions").resolve(neoForgeVersion).resolve(neoForgeVersion + ".json");
            if (!Files.exists(neoForgeJson)) {
                System.err.println("[PackUpdater] NeoForge version JSON not found: " + neoForgeJson);
                return false;
            }

            JsonObject neoForgeData = parseJson(neoForgeJson);
            String baseVersion = neoForgeData.get("inheritsFrom").getAsString();
            System.out.println("[PackUpdater] Base Minecraft version: " + baseVersion);

            // 3. Read the base Minecraft version JSON
            Path baseJson = MINECRAFT_DIR.resolve("versions").resolve(baseVersion).resolve(baseVersion + ".json");
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

            // 6. Merge libraries, JVM args, and game args
            List<String> allLibraries = mergeLibraries(baseData, neoForgeData);
            setNativesDir(nativesDir);
            List<String> jvmArgs = mergeJvmArgs(baseData, neoForgeData, instanceRoot, neoForgeVersion);
            List<String> gameArgs = mergeGameArgs(baseData, neoForgeData, instanceRoot, neoForgeVersion);
            extractNatives(allLibraries, nativesDir);
            System.out.println("[PackUpdater] Natives extracted to: " + nativesDir.toAbsolutePath());

            // 7. Build classpath
            String classpath = buildClasspath(allLibraries, instanceRoot);

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
        Path versionsDir = MINECRAFT_DIR.resolve("versions");
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

        // Add base libraries
        if (baseData.has("libraries") && baseData.get("libraries").isJsonArray()) {
            for (JsonElement elem : baseData.get("libraries").getAsJsonArray()) {
                JsonObject lib = elem.getAsJsonObject();
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

        // Add NeoForge libraries (overrides duplicates)
        if (neoForgeData.has("libraries") && neoForgeData.get("libraries").isJsonArray()) {
            for (JsonElement elem : neoForgeData.get("libraries").getAsJsonArray()) {
                JsonObject lib = elem.getAsJsonObject();
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
                    if (elem.isJsonPrimitive() && elem.getAsString().startsWith("-")) {
                        String arg = resolveJvmVariable(elem.getAsString(), instanceRoot, neoForgeVersion);
                        args.add(arg);
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
                        String arg = resolveJvmVariable(elem.getAsString(), instanceRoot, neoForgeVersion);
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
                // Value is an array of strings
                StringBuilder sb = new StringBuilder();
                for (JsonElement val : argObj.get("value").getAsJsonArray()) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(resolveJvmVariable(val.getAsString(), instanceRoot, neoForgeVersion));
                }
                return sb.toString();
            } else {
                return resolveJvmVariable(argObj.get("value").getAsString(), instanceRoot, neoForgeVersion);
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
        String librariesDir = MINECRAFT_DIR.resolve("libraries").toString().replace("\\", "/");
        String gameDir = instanceRoot.toString().replace("\\", "/");
        String nativesDir = (getNativesDir() != null) ? getNativesDir().toString().replace("\\", "/") : "";

        return arg
                .replace("${library_directory}", librariesDir)
                .replace("${game_directory}", gameDir)
                .replace("${natives_directory}", nativesDir)
                .replace("${version_name}", neoForgeVersion)
                .replace("${version}", neoForgeVersion);
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
                        args.add(arg);
                    } else if (elem.isJsonObject()) {
                        // Game args with rules
                        String arg = resolveGameArgWithRules(elem.getAsJsonObject(), instanceRoot, neoForgeVersion);
                        if (arg != null) args.add(arg);
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
        String librariesDir = MINECRAFT_DIR.resolve("libraries").toString().replace("\\", "/");
        String gameDir = instanceRoot.toString().replace("\\", "/");
        String userName = "Player"; // Offline mode username

        return arg
                .replace("${auth_player_name}", userName)
                .replace("${auth_session}", "")
                .replace("${auth_access_token}", "")
                .replace("${user_type}", "msa")
                .replace("${version_name}", neoForgeVersion)
                .replace("${game_directory}", gameDir)
                .replace("${assets_root}", MINECRAFT_DIR.resolve("assets").toString().replace("\\", "/"))
                .replace("${assets_index_name}", "unknown")
                .replace("${auth_uuid}", "00000000-0000-0000-0000-000000000000")
                .replace("${user_properties}", "{}")
                .replace("${clientid}", "")
                .replace("${library_directory}", librariesDir)
                .replace("${version}", neoForgeVersion);
    }

    /**
     * Builds the classpath string from the list of library paths.
     */
    private static String buildClasspath(List<String> libraryPaths, Path instanceRoot) {
        StringBuilder sb = new StringBuilder();
        String librariesDir = MINECRAFT_DIR.resolve("libraries").toString();

        for (String libPath : libraryPaths) {
            Path libFile = Paths.get(librariesDir, libPath);
            if (Files.exists(libFile)) {
                if (sb.length() > 0) sb.append(File.pathSeparator);
                sb.append(libFile.toString());
            } else {
                System.err.println("[PackUpdater] Warning: Library not found: " + libFile);
            }
        }

        // Add the patched client JAR (NeoForge patches the Minecraft client JAR)
        Path clientJar = MINECRAFT_DIR.resolve("versions").resolve("neoforge-26.1.2.122").resolve("neoforge-26.1.2.122-client.jar");
        if (Files.exists(clientJar)) {
            if (sb.length() > 0) sb.append(File.pathSeparator);
            sb.append(clientJar.toString());
        }

        // Also try to find the client JAR by scanning versions
        if (!Files.exists(clientJar)) {
            Path versionsDir = MINECRAFT_DIR.resolve("versions");
            if (Files.exists(versionsDir)) {
                try (Stream<Path> paths = Files.list(versionsDir)) {
                    for (Path dir : paths.toArray(Path[]::new)) {
                        if (Files.isDirectory(dir)) {
                            String name = dir.getFileName().toString();
                            if (name.toLowerCase().startsWith("neoforge")) {
                                Path patchedJar = dir.resolve(name + "-client.jar");
                                if (Files.exists(patchedJar)) {
                                    if (sb.length() > 0) sb.append(File.pathSeparator);
                                    sb.append(patchedJar.toString());
                                    System.out.println("[PackUpdater] Found patched client JAR: " + patchedJar);
                                    break;
                                }
                            }
                        }
                    }
                } catch (IOException e) {
                    // Ignore
                }
            }
        }

        return sb.toString();
    }

    /**
     * Extracts native libraries to the specified directory.
     */
    private static void extractNatives(List<String> libraryPaths, Path nativesDir) {
        String librariesDir = MINECRAFT_DIR.resolve("libraries").toString();

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
