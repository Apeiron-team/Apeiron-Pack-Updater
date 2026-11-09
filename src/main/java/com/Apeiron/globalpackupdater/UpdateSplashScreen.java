package com.Apeiron.globalpackupdater;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Custom pre-main-menu splash screen that appears when an update is available.
 * Intercepts the vanilla TitleScreen and gives the player a choice to install
 * the update or skip it and proceed to the main menu.
 */
public class UpdateSplashScreen extends Screen {

    private final Path apepackFile;
    private final String currentVersion;
    private final String newVersion;
    private final String changelog;
    
    // Download state
    private boolean downloading = false;
    private double downloadProgress = 0.0;
    private String downloadStatus = "";
    private String downloadSpeed = "";
    
    // Installation state
    private boolean installing = false;
    private double installProgress = 0.0;
    private String installFile = "";
    private int installNew = 0;
    private int installSkipped = 0;
    private int installProtected = 0;
    
    // Button references for disabling
    private Button installBtn;
    private Button skipBtn;
    
    // Static flag to prevent showing the splash screen multiple times
    public static boolean skipNextSplash = false;
    
    public UpdateSplashScreen(Path apepackFile, String currentVersion, String newVersion, String changelog) {
        super(Component.literal("Pack Update Available"));
        this.apepackFile = apepackFile;
        this.currentVersion = currentVersion;
        this.newVersion = newVersion;
        this.changelog = changelog;
    }
    
    @Override
    protected void init() {
        super.init();
        
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        
        // Install Update button
        this.installBtn = Button.builder(
                Component.literal("Install Update"),
                b -> startInstall()
        ).bounds(centerX - 160, centerY + 60, 150, 20).build();
        addRenderableWidget(this.installBtn);
        
        // Skip for Now button
        this.skipBtn = Button.builder(
                Component.literal("Skip for Now"),
                b -> skipUpdate()
        ).bounds(centerX + 10, centerY + 60, 150, 20).build();
        addRenderableWidget(this.skipBtn);
    }
    
    private void startInstall() {
        if (downloading || installing) return;
        
        // Disable buttons
        if (installBtn != null) installBtn.active = false;
        if (skipBtn != null) skipBtn.active = false;
        
        // Check if the .apepack is already downloaded or needs to be downloaded
        if (apepackFile != null && Files.exists(apepackFile)) {
            // File already exists, start extraction
            startExtraction();
        } else {
            // No file yet, this shouldn't happen in the splash screen flow
            // but handle it gracefully
            installing = true;
            installFile = "No update file found";
            Minecraft.getInstance().setScreen(new net.minecraft.client.gui.screens.TitleScreen());
        }
    }
    
    private void startExtraction() {
        installing = true;
        installFile = "Starting extraction...";
        
        Thread installThread = new Thread(() -> {
            try {
                System.out.println("[PackUpdater] Installing update from splash screen...");
                
                ApePackLoaderV2.InstallResult result = ApePackLoaderV2.installApePackWithProgress(
                        apepackFile, PackUpdaterMod.INSTANCE_ROOT,
                        new ApePackLoaderV2.ImportProgress() {
                            @Override
                            public void onFileStart(String file, String target, int count, int total) {
                                installFile = file;
                                installProgress = (double) count / total;
                            }
                            
                            @Override
                            public void onFileInstalled(String file) {
                                installNew++;
                            }
                            
                            @Override
                            public void onFileSkipped(String file) {
                                installSkipped++;
                            }
                            
                            @Override
                            public void onFileProtected(String file) {
                                installProtected++;
                            }
                            
                            @Override
                            public void onPackInfo(String name, String version) {
                                ConfigManager.updateModpackName(name);
                                if (version != null) ConfigManager.updateModpackVersion(version);
                            }
                            
                            @Override
                            public void onComplete(boolean success, String error) {
                                installProgress = 1.0;
                                installFile = success ? "Installation complete!" : "Installation failed: " + error;
                                
                                if (success) {
                                    // Mark as processed and delete the .apepack
                                    try { ApePackLoaderV2.markApePackAsProcessed(apepackFile); } catch (Exception ignored) {}
                                    try { Files.deleteIfExists(apepackFile); } catch (Exception ignored) {}
                                    
                                    // Show diff screen so user can see what changed before restarting
                                    Minecraft.getInstance().execute(() -> {
                                        ApePackDiffScreen diffScreen = new ApePackDiffScreen(
                                            null, apepackFile, PackUpdaterMod.INSTANCE_ROOT);
                                        Minecraft.getInstance().setScreen(diffScreen);
                                    });
                                } else {
                                    // On failure, show config screen
                                    Minecraft.getInstance().execute(() -> {
                                        Minecraft.getInstance().setScreen(new PackUpdaterConfigScreen(null));
                                    });
                                }
                                
                                PackUpdaterMod.installing.set(false);
                            }
                        });
            } catch (Exception e) {
                System.err.println("[PackUpdater] Splash screen install exception: " + e.getMessage());
                installFile = "Error: " + e.getMessage();
                PackUpdaterMod.installing.set(false);
            }
        }, "PackUpdater-SplashInstall");
        installThread.setDaemon(false);
        installThread.start();
    }
    
    private void skipUpdate() {
        // Mark this .apepack as processed so it won't trigger again
        try { ApePackLoaderV2.markApePackAsProcessed(apepackFile); } catch (Exception ignored) {}
        
        // Proceed to the main menu
        Minecraft.getInstance().setScreen(new net.minecraft.client.gui.screens.TitleScreen());
    }
    
    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Draw background
        guiGraphics.fill(0, 0, this.width, this.height, 0xCC101010);
        
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        
        // Title
        guiGraphics.centeredText(this.font, "Pack Update Available", centerX, centerY - 80, 0x88CCFF);
        
        // Version info
        guiGraphics.centeredText(this.font, "Current version: " + currentVersion, centerX, centerY - 55, 0xCCCCCC);
        guiGraphics.centeredText(this.font, "New version: " + newVersion, centerX, centerY - 40, 0x55FF55);
        
        // Changelog (truncated if too long)
        if (changelog != null && !changelog.isEmpty()) {
            String displayChangelog = changelog;
            if (displayChangelog.length() > 200) {
                displayChangelog = displayChangelog.substring(0, 197) + "...";
            }
            // Split into lines
            String[] lines = displayChangelog.split("\n");
            int lineY = centerY - 20;
            for (String line : lines) {
                if (lineY > centerY + 30) break;
                guiGraphics.centeredText(this.font, line, centerX, lineY, 0x999999);
                lineY += 12;
            }
        }
        
        // Installation progress
        if (installing) {
            // Progress bar background
            guiGraphics.fill(centerX - 160, centerY + 30, centerX + 160, centerY + 38, 0x40000000);
            
            // Progress bar fill
            int filledWidth = (int) ((centerX + 160 - (centerX - 160)) * installProgress);
            guiGraphics.fill(centerX - 160, centerY + 30, centerX - 160 + filledWidth, centerY + 38, 0xFF4466AA);
            
            // Progress text
            guiGraphics.centeredText(this.font, installFile, centerX, centerY + 45, 0xCCCCCC);
            
            // Stats
            if (installNew > 0 || installSkipped > 0 || installProtected > 0) {
                guiGraphics.centeredText(this.font, 
                    "New: " + installNew + " | Skipped: " + installSkipped + " | Protected: " + installProtected,
                    centerX, centerY + 55, 0x999999);
            }
        }
        
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
    }
    
    @Override
    public void onClose() {
        // Don't allow closing during installation
        if (installing) return;
        skipUpdate();
    }
}
