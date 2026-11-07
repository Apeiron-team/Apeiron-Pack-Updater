package com.Apeiron.globalpackupdater;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ConfigManager {
    private static final Path CONFIG_PATH = Paths.get("config", "globalpackupdater.json");

    public static String repo = "SimonArminovski/YourModpackRepo";
    public static String channel = "stable"; // stable, beta, nightly

    public static void loadConfig() {
        try {
            if (Files.exists(CONFIG_PATH)) {
                String content = Files.readString(CONFIG_PATH);
                JsonObject json = JsonParser.parseString(content).getAsJsonObject();
                if (json.has("repo")) repo = json.get("repo").getAsString();
                if (json.has("channel")) channel = json.get("channel").getAsString();
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
            Files.writeString(CONFIG_PATH, json.toString());
        } catch (Exception e) {
            System.err.println("[PackUpdater] Failed to save config.");
        }
    }
}