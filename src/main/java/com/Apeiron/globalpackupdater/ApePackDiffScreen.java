package com.Apeiron.globalpackupdater;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Shows a comparison of mods before and after an .apepack update.
 * Allows the player to choose between updating now (restarting) or going to main menu.
 */
public class ApePackDiffScreen extends Screen {

    private final Screen parent;
    private final Path apepackFile;
    private final Path instanceRoot;
    
    // Comparison results
    private final List<String> addedMods = new ArrayList<>();
    private final List<String> removedMods = new ArrayList<>();
    private final List<String> updatedMods = new ArrayList<>();
    private final List<String> unchangedMods = new ArrayList<>();
    
    // Config and script changes
    private final List<String> addedConfigs = new ArrayList<>();
    private final List<String> updatedConfigs = new ArrayList<>();
    
    // Scroll state
    private int scrollOffset = 0;
    private static final int ROW_HEIGHT = 18;
    
    // Pack info
    private String newPackVersion = "";
    private String currentPackVersion = "";
    
    enum Section { MODS, CONFIGS, SUMMARY }
    private Section activeSection = Section.MODS;
    private Button btnMods;
    private Button btnConfigs;
    private Button btnSummary;
    private Button updateNowBtn;
    
    public ApePackDiffScreen(Screen parent, Path apepackFile, Path instanceRoot) {
        super(Component.literal("Update Comparison"));
        this.parent = parent;
        this.apepackFile = apepackFile;
        this.instanceRoot = instanceRoot;
        this.currentPackVersion = ConfigManager.modpackVersion;
        
        // Compute the diff
        computeDiff();
    }
    
    private void computeDiff() {
        // Get current mods
        Set<String> currentModNames = new HashSet<>();
        Set<String> currentModFiles = new HashSet<>();
        Path modsDir = instanceRoot.resolve("mods");
        if (Files.exists(modsDir)) {
            try (Stream<Path> paths = Files.list(modsDir)) {
                for (Path p : paths.toArray(Path[]::new)) {
                    String name = p.getFileName().toString().toLowerCase();
                    if (name.endsWith(".jar") && !name.startsWith("packupdater")) {
                        currentModFiles.add(name);
                        String baseName = ApePackLoader.extractModBaseName(name);
                        currentModNames.add(baseName.toLowerCase());
                    }
                }
            } catch (IOException ignored) {}
        }
        
        // Get new mods from the .apepack
        Set<String> newModNames = new HashSet<>();
        Map<String, String> newModVersions = new HashMap<>();
        try {
            java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(apepackFile.toFile());
            
            // Read apepack.json for version
            var apepackEntry = zipFile.getEntry("apepack.json");
            if (apepackEntry != null) {
                try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(zipFile.getInputStream(apepackEntry)))) {
                    String content = reader.lines().collect(Collectors.joining("\n"));
                    com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(content).getAsJsonObject();
                    if (json.has("packVersion")) {
                        newPackVersion = json.get("packVersion").getAsString();
                    }
                }
            }
            
            // Read manifest.json for file list
            var manifestEntry = zipFile.getEntry("manifest.json");
            if (manifestEntry != null) {
                try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(zipFile.getInputStream(manifestEntry)))) {
                    String content = reader.lines().collect(Collectors.joining("\n"));
                    com.google.gson.JsonObject manifest = com.google.gson.JsonParser.parseString(content).getAsJsonObject();
                    
                    // Try new format first
                    if (manifest.has("files") && manifest.get("files").isJsonArray()) {
                        for (var elem : manifest.get("files").getAsJsonArray()) {
                            com.google.gson.JsonObject file = elem.getAsJsonObject();
                            String type = file.has("type") ? file.get("type").getAsString() : "mod";
                            String name = file.has("name") ? file.get("name").getAsString() : "";
                            String version = file.has("version") ? file.get("version").getAsString() : "";
                            
                            if ("mod".equals(type)) {
                                String baseName = ApePackLoader.extractModBaseName(name).toLowerCase();
                                newModNames.add(baseName);
                                newModVersions.put(baseName, name);
                            } else if ("config".equals(type)) {
                                // Check if config exists
                                String installPath = file.has("installPath") ? file.get("installPath").getAsString() : "";
                                Path configPath = instanceRoot.resolve(installPath);
                                if (Files.exists(configPath)) {
                                    String existingHash = ApePackLoader.computeSha256(configPath);
                                    String newHash = file.has("sha256") ? file.get("sha256").getAsString() : "";
                                    if (existingHash != null && existingHash.equalsIgnoreCase(newHash)) {
                                        // unchanged
                                    } else {
                                        updatedConfigs.add(name);
                                    }
                                } else {
                                    addedConfigs.add(name);
                                }
                            }
                        }
                    }
                }
            }
            zipFile.close();
        } catch (Exception e) {
            System.err.println("[PackUpdater] Failed to compute diff: " + e.getMessage());
        }
        
        // Compute differences
        Set<String> allModNames = new HashSet<>(currentModNames);
        allModNames.addAll(newModNames);
        
        for (String modName : allModNames) {
            boolean inCurrent = currentModNames.contains(modName);
            boolean inNew = newModNames.contains(modName);
            
            if (inNew && !inCurrent) {
                addedMods.add(newModVersions.getOrDefault(modName, modName));
            } else if (inCurrent && !inNew) {
                // Find the current file name for this mod
                for (String currentFile : currentModFiles) {
                    if (ApePackLoader.extractModBaseName(currentFile).toLowerCase().equals(modName)) {
                        removedMods.add(currentFile);
                        break;
                    }
                }
            } else if (inCurrent && inNew) {
                // Check if version changed
                String newFile = newModVersions.get(modName);
                boolean foundDiff = false;
                for (String currentFile : currentModFiles) {
                    if (ApePackLoader.extractModBaseName(currentFile).toLowerCase().equals(modName)) {
                        if (!currentFile.equalsIgnoreCase(newFile)) {
                            updatedMods.add(currentFile + " -> " + newFile);
                            foundDiff = true;
                        } else {
                            unchangedMods.add(currentFile);
                        }
                        break;
                    }
                }
                if (!foundDiff) {
                    unchangedMods.add(newFile);
                }
            }
        }
        
        Collections.sort(addedMods, String.CASE_INSENSITIVE_ORDER);
        Collections.sort(removedMods, String.CASE_INSENSITIVE_ORDER);
        Collections.sort(updatedMods, String.CASE_INSENSITIVE_ORDER);
        Collections.sort(unchangedMods, String.CASE_INSENSITIVE_ORDER);
        Collections.sort(addedConfigs, String.CASE_INSENSITIVE_ORDER);
        Collections.sort(updatedConfigs, String.CASE_INSENSITIVE_ORDER);
    }
    
    @Override
    protected void init() {
        super.init();
        
        int centerX = this.width / 2;
        int tabBarY = 25;
        int tabWidth = 80;
        int tabHeight = 20;
        int gap = 4;
        int totalWidth = 3 * tabWidth + 2 * gap;
        int startX = (this.width - totalWidth) / 2;
        
        // Tab buttons
        btnMods = addRenderableWidget(Button.builder(
                Component.literal("Mods (" + (addedMods.size() + updatedMods.size() + removedMods.size()) + ")"),
                b -> switchSection(Section.MODS)
        ).bounds(startX, tabBarY, tabWidth, tabHeight).build());
        
        btnConfigs = addRenderableWidget(Button.builder(
                Component.literal("Configs (" + (addedConfigs.size() + updatedConfigs.size()) + ")"),
                b -> switchSection(Section.CONFIGS)
        ).bounds(startX + tabWidth + gap, tabBarY, tabWidth, tabHeight).build());
        
        btnSummary = addRenderableWidget(Button.builder(
                Component.literal("Summary"),
                b -> switchSection(Section.SUMMARY)
        ).bounds(startX + 2 * (tabWidth + gap), tabBarY, tabWidth, tabHeight).build());
        
        // Action buttons at bottom
        this.updateNowBtn = Button.builder(
                Component.literal("Update Now (Restart)"),
                b -> {
                    b.active = false;
                    b.setMessage(Component.literal("Starting..."));
                    // Install the .apepack and restart
                    Thread installThread = new Thread(() -> {
                        ApePackLoaderV2.InstallResult result = ApePackLoaderV2.installApePackWithProgress(
                                apepackFile, instanceRoot,
                                new ApePackLoaderV2.ImportProgress() {
                                    @Override public void onFileStart(String f, String t, int c, int total) {}
                                    @Override public void onFileInstalled(String f) {}
                                    @Override public void onFileSkipped(String f) {}
                                    @Override public void onFileProtected(String f) {}
                                    @Override public void onPackInfo(String n, String v) {
                                        ConfigManager.updateModpackName(n);
                                    }
                                    @Override public void onComplete(boolean success, String error) {
                                        if (success) {
                                            ApePackLoaderV2.markApePackAsProcessed(apepackFile);
                                            if (!newPackVersion.isEmpty()) {
                                                ConfigManager.updateModpackVersion(newPackVersion);
                                            }
                                            try { Files.deleteIfExists(apepackFile); } catch (Exception ignored) {}
                                            Minecraft.getInstance().execute(() -> {
                                                GameRelauncher.relaunch();
                                                System.exit(0);
                                            });
                                        } else {
                                            Minecraft.getInstance().execute(() -> {
                                                if (updateNowBtn != null) {
                                                    updateNowBtn.active = true;
                                                    updateNowBtn.setMessage(Component.literal("Update Failed"));
                                                }
                                            });
                                        }
                                    }
                                });
                    }, "PackUpdater-Install");
                    installThread.start();
                }
        ).bounds(centerX - 160, this.height - 40, 150, 20).build();
        addRenderableWidget(this.updateNowBtn);
        
        addRenderableWidget(Button.builder(
                Component.literal("Back to Main Menu"),
                b -> {
                    Minecraft.getInstance().setScreen(new net.minecraft.client.gui.screens.TitleScreen());
                }
        ).bounds(centerX + 10, this.height - 40, 150, 20).build());
        
        updateTabStyles();
    }
    
    private void switchSection(Section section) {
        activeSection = section;
        scrollOffset = 0;
        updateTabStyles();
    }
    
    private void updateTabStyles() {
        int tabBarY = 25;
        int tabWidth = 80;
        int tabHeight = 20;
        int gap = 4;
        int totalWidth = 3 * tabWidth + 2 * gap;
        int startX = (this.width - totalWidth) / 2;
        
        // Highlight active tab
        int[] positions = {startX, startX + tabWidth + gap, startX + 2 * (tabWidth + gap)};
        int[] colors = {0xFF4466AA, 0xFF4466AA, 0xFF4466AA};
        
        switch (activeSection) {
            case MODS -> colors[0] = 0xFF66AAFF;
            case CONFIGS -> colors[1] = 0xFF66AAFF;
            case SUMMARY -> colors[2] = 0xFF66AAFF;
        }
    }
    
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int panelTop = 50;
        int panelBottom = this.height - 50;
        if (mouseY >= panelTop && mouseY <= panelBottom) {
            int totalItems = getTotalItems();
            int visibleRows = (panelBottom - panelTop) / ROW_HEIGHT;
            int maxScroll = Math.max(0, totalItems - visibleRows);
            scrollOffset = Math.max(0, Math.min((int)(scrollOffset - scrollY), maxScroll));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
    
    private int getTotalItems() {
        switch (activeSection) {
            case MODS: return addedMods.size() + updatedMods.size() + removedMods.size() + (unchangedMods.isEmpty() ? 0 : 1);
            case CONFIGS: return addedConfigs.size() + updatedConfigs.size();
            case SUMMARY: return 8;
            default: return 0;
        }
    }
    
    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, this.width, this.height, 0xCC101010);
        
        int centerX = this.width / 2;
        
        // Title
        guiGraphics.centeredText(this.font, "Update Comparison", centerX, 8, 0x88CCFF);
        if (!newPackVersion.isEmpty()) {
            guiGraphics.centeredText(this.font, currentPackVersion + " -> " + newPackVersion, centerX, 22, 0xCCCCCC);
        }
        
        // Highlight active tab
        int tabBarY = 25;
        int tabWidth = 80;
        int tabHeight = 20;
        int gap = 4;
        int totalWidth = 3 * tabWidth + 2 * gap;
        int startX = (this.width - totalWidth) / 2;
        int activeIndex = activeSection.ordinal();
        int activeX = startX + activeIndex * (tabWidth + gap);
        guiGraphics.fill(activeX - 1, tabBarY - 1, activeX + tabWidth + 1, tabBarY + tabHeight + 1, 0x4466AA);
        
        // Panel area
        int panelTop = 50;
        int panelBottom = this.height - 50;
        int panelWidth = this.width - 40;
        int panelLeft = 20;
        
        guiGraphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelBottom, 0x40000000);
        
        int y = panelTop + 5;
        int visibleRows = (panelBottom - panelTop) / ROW_HEIGHT;
        
        switch (activeSection) {
            case MODS -> {
                // Added mods
                if (!addedMods.isEmpty()) {
                    if (scrollOffset == 0 || y >= panelTop) {
                        guiGraphics.centeredText(this.font, "Added Mods (" + addedMods.size() + ")", centerX, y, 0x55FF55);
                        y += ROW_HEIGHT;
                    }
                    int startIdx = Math.max(0, scrollOffset - 1);
                    for (int i = startIdx; i < addedMods.size() && y < panelBottom; i++) {
                        String mod = addedMods.get(i);
                        if (mod.length() > 50) mod = "..." + mod.substring(mod.length() - 47);
                        guiGraphics.centeredText(this.font, "+ " + mod, centerX, y, 0x55FF55);
                        y += ROW_HEIGHT;
                        if (i < scrollOffset) scrollOffset = i + 1;
                    }
                }
                
                // Updated mods
                if (!updatedMods.isEmpty()) {
                    if (y < panelBottom) {
                        guiGraphics.centeredText(this.font, "Updated Mods (" + updatedMods.size() + ")", centerX, y, 0xFFFF55);
                        y += ROW_HEIGHT;
                    }
                    for (String mod : updatedMods) {
                        if (y >= panelBottom) break;
                        String display = mod;
                        if (display.length() > 50) display = "..." + display.substring(display.length() - 47);
                        guiGraphics.centeredText(this.font, "~ " + display, centerX, y, 0xFFFF55);
                        y += ROW_HEIGHT;
                    }
                }
                
                // Removed mods
                if (!removedMods.isEmpty()) {
                    if (y < panelBottom) {
                        guiGraphics.centeredText(this.font, "Removed Mods (" + removedMods.size() + ")", centerX, y, 0xFF5555);
                        y += ROW_HEIGHT;
                    }
                    for (String mod : removedMods) {
                        if (y >= panelBottom) break;
                        String display = mod;
                        if (display.length() > 50) display = "..." + display.substring(display.length() - 47);
                        guiGraphics.centeredText(this.font, "- " + display, centerX, y, 0xFF5555);
                        y += ROW_HEIGHT;
                    }
                }
                
                // Unchanged
                if (!unchangedMods.isEmpty() && y < panelBottom) {
                    guiGraphics.centeredText(this.font, "Unchanged: " + unchangedMods.size() + " mods", centerX, y, 0x888888);
                }
                
                if (addedMods.isEmpty() && updatedMods.isEmpty() && removedMods.isEmpty()) {
                    guiGraphics.centeredText(this.font, "No mod changes detected", centerX, y, 0x888888);
                }
            }
            
            case CONFIGS -> {
                if (!addedConfigs.isEmpty()) {
                    guiGraphics.centeredText(this.font, "New Configs (" + addedConfigs.size() + ")", centerX, y, 0x55FF55);
                    y += ROW_HEIGHT;
                    for (String cfg : addedConfigs) {
                        if (y >= panelBottom) break;
                        guiGraphics.centeredText(this.font, "+ " + cfg, centerX, y, 0x55FF55);
                        y += ROW_HEIGHT;
                    }
                }
                if (!updatedConfigs.isEmpty()) {
                    guiGraphics.centeredText(this.font, "Updated Configs (" + updatedConfigs.size() + ")", centerX, y, 0xFFFF55);
                    y += ROW_HEIGHT;
                    for (String cfg : updatedConfigs) {
                        if (y >= panelBottom) break;
                        guiGraphics.centeredText(this.font, "~ " + cfg, centerX, y, 0xFFFF55);
                        y += ROW_HEIGHT;
                    }
                }
                if (addedConfigs.isEmpty() && updatedConfigs.isEmpty()) {
                    guiGraphics.centeredText(this.font, "No config changes", centerX, y, 0x888888);
                }
            }
            
            case SUMMARY -> {
                guiGraphics.centeredText(this.font, "Update Summary", centerX, y, 0x88CCFF);
                y += ROW_HEIGHT * 2;
                
                guiGraphics.centeredText(this.font, "Mods Added: " + addedMods.size(), centerX, y, 0x55FF55);
                y += ROW_HEIGHT;
                guiGraphics.centeredText(this.font, "Mods Updated: " + updatedMods.size(), centerX, y, 0xFFFF55);
                y += ROW_HEIGHT;
                guiGraphics.centeredText(this.font, "Mods Removed: " + removedMods.size(), centerX, y, 0xFF5555);
                y += ROW_HEIGHT;
                guiGraphics.centeredText(this.font, "Mods Unchanged: " + unchangedMods.size(), centerX, y, 0x888888);
                y += ROW_HEIGHT * 2;
                
                guiGraphics.centeredText(this.font, "Configs Added: " + addedConfigs.size(), centerX, y, 0x55FF55);
                y += ROW_HEIGHT;
                guiGraphics.centeredText(this.font, "Configs Updated: " + updatedConfigs.size(), centerX, y, 0xFFFF55);
                y += ROW_HEIGHT * 2;
                
                guiGraphics.centeredText(this.font, "Choose your action below:", centerX, y, 0xCCCCCC);
            }
        }
        
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
    }
    
    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(this.parent);
    }
}
