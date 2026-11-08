package com.Apeiron.globalpackupdater;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ConfigManager {
    private static final Path CONFIG_PATH = Paths.get("config", "globalpackupdater.json");

    public static String repo = "Apeiron-team/Apeiron-Pack-Updater";
    public static String channel = "stable"; // stable, beta, nightly
    public static String lastReleaseUrl = null;
    public static String modpackVersion = "v0.0"; // Default modpack version
    public static String modpackName = ""; // Modpack name (set from .apepack)
    public static String githubToken = ""; // Optional GitHub personal access token for higher API rate limits
    
    // Current mod version - synced from build.gradle
    public static final String CURRENT_VERSION = "1.0.1";

    public static void loadConfig() {
        try {
            if (Files.exists(CONFIG_PATH)) {
                String content = Files.readString(CONFIG_PATH);
                JsonObject json = JsonParser.parseString(content).getAsJsonObject();
                if (json.has("repo")) repo = json.get("repo").getAsString();
                if (json.has("channel")) channel = json.get("channel").getAsString();
                if (json.has("lastReleaseUrl")) lastReleaseUrl = json.get("lastReleaseUrl").getAsString();
                if (json.has("modpackVersion")) modpackVersion = json.get("modpackVersion").getAsString();
                if (json.has("modpackName")) modpackName = json.get("modpackName").getAsString();
                if (json.has("githubToken")) githubToken = json.get("githubToken").getAsString();
            } else {
                saveConfig();
            }
        } catch (Exception e) {
            System.err.println("[PackUpdater] Failed to load config, using defaults.");
        }
    }

    public static void saveConfig() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject json = new JsonObject();
            json.addProperty("repo", repo);
            json.addProperty("channel", channel);
            if (lastReleaseUrl != null) {
                json.addProperty("lastReleaseUrl", lastReleaseUrl);
            }
            json.addProperty("modpackVersion", modpackVersion);
            if (!modpackName.isEmpty()) {
                json.addProperty("modpackName", modpackName);
            }
            if (!githubToken.isEmpty()) {
                json.addProperty("githubToken", githubToken);
            }
            Files.writeString(CONFIG_PATH, json.toString());
        } catch (Exception e) {
            System.err.println("[PackUpdater] Failed to save config.");
        }
    }

    /**
     * Updates the modpack version from an installed .apepack file.
     */
    public static void updateModpackVersion(String version) {
        if (version != null && !version.isEmpty()) {
            String cleanVersion = version;
            if (!cleanVersion.startsWith("v")) {
                cleanVersion = "v" + cleanVersion;
            }
            modpackVersion = cleanVersion;
            saveConfig();
            System.out.println("[PackUpdater] Updated modpack version to " + modpackVersion);
        }
    }

    /**
     * Updates the modpack name from an installed .apepack file.
     */
    public static void updateModpackName(String name) {
        if (name != null && !name.isEmpty()) {
            modpackName = name;
            saveConfig();
            System.out.println("[PackUpdater] Updated modpack name to " + modpackName);
        }
    }

    /**
     * Sets up a GitHub API connection with proper headers.
     * Adds Accept header for v3 API and optional authentication token.
     */
    public static void setupGitHubConnection(java.net.HttpURLConnection conn) {
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
        conn.setRequestProperty("User-Agent", "Apeiron-PackUpdater");
        if (!githubToken.isEmpty()) {
            conn.setRequestProperty("Authorization", "token " + githubToken);
        }
    }
}