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
        // Note: Extraction is already done before showing this screen, so we just restart
        this.updateNowBtn = Button.builder(
                Component.literal("Restart to Apply"),
                b -> {
                    b.active = false;
                    b.setMessage(Component.literal("Starting..."));
                    // Clean up the .apepack file and restart
                    try { ApePackLoaderV2.markApePackAsProcessed(apepackFile); } catch (Exception ignored) {}
                    if (!newPackVersion.isEmpty()) {
                        ConfigManager.updateModpackVersion(newPackVersion);
                    }
                    try { Files.deleteIfExists(apepackFile); } catch (Exception ignored) {}
                    GameRelauncher.relaunch();
                    System.exit(0);
                }
        ).bounds(centerX - 160, this.height - 40, 150, 20).build();
        addRenderableWidget(this.updateNowBtn);
        
        addRenderableWidget(Button.builder(
                Component.literal("Back to Main Menu"),
                b -> {
                    // Clean up the .apepack file
                    try { ApePackLoaderV2.markApePackAsProcessed(apepackFile); } catch (Exception ignored) {}
                    try { Files.deleteIfExists(apepackFile); } catch (Exception ignored) {}
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
        
        // Build a flat list of items for the current section, then render with scroll offset
        List<RenderItem> items = new ArrayList<>();
        
        switch (activeSection) {
            case MODS -> {
                if (!addedMods.isEmpty()) {
                    items.add(new RenderItem("Added Mods (" + addedMods.size() + ")", 0x55FF55, true));
                    for (String mod : addedMods) {
                        String display = mod;
                        if (display.length() > 50) display = "..." + display.substring(display.length() - 47);
                        items.add(new RenderItem("+ " + display, 0x55FF55, false));
                    }
                }
                if (!updatedMods.isEmpty()) {
                    items.add(new RenderItem("Updated Mods (" + updatedMods.size() + ")", 0xFFFF55, true));
                    for (String mod : updatedMods) {
                        String display = mod;
                        if (display.length() > 50) display = "..." + display.substring(display.length() - 47);
                        items.add(new RenderItem("~ " + display, 0xFFFF55, false));
                    }
                }
                if (!removedMods.isEmpty()) {
                    items.add(new RenderItem("Removed Mods (" + removedMods.size() + ")", 0xFF5555, true));
                    for (String mod : removedMods) {
                        String display = mod;
                        if (display.length() > 50) display = "..." + display.substring(display.length() - 47);
                        items.add(new RenderItem("- " + display, 0xFF5555, false));
                    }
                }
                if (!unchangedMods.isEmpty()) {
                    items.add(new RenderItem("Unchanged: " + unchangedMods.size() + " mods", 0x888888, false));
                }
                if (addedMods.isEmpty() && updatedMods.isEmpty() && removedMods.isEmpty()) {
                    items.add(new RenderItem("No mod changes detected", 0x888888, false));
                }
            }
            
            case CONFIGS -> {
                if (!addedConfigs.isEmpty()) {
                    items.add(new RenderItem("New Configs (" + addedConfigs.size() + ")", 0x55FF55, true));
                    for (String cfg : addedConfigs) {
                        items.add(new RenderItem("+ " + cfg, 0x55FF55, false));
                    }
                }
                if (!updatedConfigs.isEmpty()) {
                    items.add(new RenderItem("Updated Configs (" + updatedConfigs.size() + ")", 0xFFFF55, true));
                    for (String cfg : updatedConfigs) {
                        items.add(new RenderItem("~ " + cfg, 0xFFFF55, false));
                    }
                }
                if (addedConfigs.isEmpty() && updatedConfigs.isEmpty()) {
                    items.add(new RenderItem("No config changes", 0x888888, false));
                }
            }
            
            case SUMMARY -> {
                items.add(new RenderItem("Update Summary", 0x88CCFF, true));
                items.add(new RenderItem("", 0xFFFFFF, false)); // spacer
                items.add(new RenderItem("Mods Added: " + addedMods.size(), 0x55FF55, false));
                items.add(new RenderItem("Mods Updated: " + updatedMods.size(), 0xFFFF55, false));
                items.add(new RenderItem("Mods Removed: " + removedMods.size(), 0xFF5555, false));
                items.add(new RenderItem("Mods Unchanged: " + unchangedMods.size(), 0x888888, false));
                items.add(new RenderItem("", 0xFFFFFF, false)); // spacer
                items.add(new RenderItem("Configs Added: " + addedConfigs.size(), 0x55FF55, false));
                items.add(new RenderItem("Configs Updated: " + updatedConfigs.size(), 0xFFFF55, false));
                items.add(new RenderItem("", 0xFFFFFF, false)); // spacer
                items.add(new RenderItem("Choose your action below:", 0xCCCCCC, false));
            }
        }
        
        // Render items with scroll offset
        int visibleRows = (panelBottom - panelTop) / ROW_HEIGHT;
        int maxScroll = Math.max(0, items.size() - visibleRows);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        
        int y = panelTop + 5;
        for (int i = scrollOffset; i < items.size() && y < panelBottom - ROW_HEIGHT; i++) {
            RenderItem item = items.get(i);
            guiGraphics.centeredText(this.font, item.text, centerX, y, item.color);
            y += ROW_HEIGHT;
        }
        
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
    }
    
    private static class RenderItem {
        final String text;
        final int color;
        final boolean isHeader;
        RenderItem(String text, int color, boolean isHeader) {
            this.text = text;
            this.color = color;
            this.isHeader = isHeader;
        }
    }
    
    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(this.parent);
    }
}
