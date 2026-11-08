package com.Apeiron.globalpackupdater;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Notification overlay shown on the main menu when PackUpdater is downloading
 * or installing .apepack updates. Displays progress, status messages, and
 * completion notifications.
 */
public class MainMenuNotification extends Screen {

    private final Screen parent;
    
    // Notification state
    public enum NotificationType {
        DOWNLOADING,    // Downloading .apepack from GitHub
        INSTALLING,     // Extracting and installing .apepack
        COMPLETE,        // Installation complete, restart recommended
        ERROR            // Error occurred
    }
    
    public NotificationType type = NotificationType.DOWNLOADING;
    private String title = "";
    private String message = "";
    private double progress = 0.0;  // 0.0 to 1.0
    private String currentFile = "";
    private int filesProcessed = 0;
    private int totalFiles = 0;
    
    // Auto-dismiss timer
    private long dismissTime = 0;
    private boolean autoDismiss = false;
    
    public MainMenuNotification(Screen parent) {
        super(Component.literal("PackUpdater Notification"));
        this.parent = parent;
    }
    
    public Screen getParent() {
        return parent;
    }
    
    /**
     * Update the notification state from a background thread.
     * Use Minecraft.getInstance().execute() to call this safely.
     */
    public void setDownloading(String repo, double progress) {
        this.type = NotificationType.DOWNLOADING;
        this.title = "Downloading update...";
        this.message = "Fetching latest pack from GitHub";
        this.progress = progress;
        this.autoDismiss = false;
        this.dismissTime = 0;
    }
    
    public void setInstalling(String fileName, int filesProcessed, int totalFiles) {
        this.type = NotificationType.INSTALLING;
        this.title = "Installing update...";
        this.message = "Extracting: " + fileName;
        this.filesProcessed = filesProcessed;
        this.totalFiles = totalFiles;
        if (totalFiles > 0) {
            this.progress = (double) filesProcessed / totalFiles;
        }
        this.autoDismiss = false;
        this.dismissTime = 0;
    }
    
    public void setComplete(String packName, String packVersion) {
        this.type = NotificationType.COMPLETE;
        this.title = "Update installed!";
        this.message = packName + " v" + packVersion + " installed successfully";
        this.progress = 1.0;
        this.autoDismiss = true;
        this.dismissTime = System.currentTimeMillis() + 5000; // Auto-dismiss after 5 seconds
    }
    
    public void setError(String errorMessage) {
        this.type = NotificationType.ERROR;
        this.title = "Update failed";
        this.message = errorMessage;
        this.progress = 0.0;
        this.autoDismiss = true;
        this.dismissTime = System.currentTimeMillis() + 8000; // Auto-dismiss after 8 seconds
    }
    
    @Override
    protected void init() {
        super.init();
        
        int centerX = this.width / 2;
        int buttonY = this.height / 6 + 180;
        
        if (type == NotificationType.COMPLETE) {
            this.addRenderableWidget(Button.builder(
                    Component.literal("Back to Title"),
                    button -> Minecraft.getInstance().setScreen(new net.minecraft.client.gui.screens.TitleScreen())
            ).bounds(centerX - 120, buttonY, 115, 20).build());
            
            this.addRenderableWidget(Button.builder(
                    Component.literal("Stay in Game"),
                    button -> Minecraft.getInstance().setScreen(this.parent)
            ).bounds(centerX + 5, buttonY, 115, 20).build());
        } else if (type == NotificationType.ERROR) {
            this.addRenderableWidget(Button.builder(
                    Component.literal("OK"),
                    button -> Minecraft.getInstance().setScreen(this.parent)
            ).bounds(centerX - 50, buttonY, 100, 20).build());
        }
        // For DOWNLOADING and INSTALLING, no buttons — auto-dismiss when complete
    }
    
    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Auto-dismiss for COMPLETE and ERROR states
        if (autoDismiss && dismissTime > 0 && System.currentTimeMillis() >= dismissTime) {
            Minecraft.getInstance().setScreen(this.parent);
            return;
        }
        
        // Draw semi-transparent background
        guiGraphics.fill(0, 0, this.width, this.height, 0xCC000000);
        
        int centerX = this.width / 2;
        int startY = this.height / 6;
        
        // Title with icon
        String icon = switch (type) {
            case DOWNLOADING -> "[↓]";
            case INSTALLING -> "[•]";
            case COMPLETE -> "[✓]";
            case ERROR -> "[✗]";
        };
        
        int titleColor = switch (type) {
            case DOWNLOADING -> 0x3399FF;
            case INSTALLING -> 0xFF9933;
            case COMPLETE -> 0x55FF55;
            case ERROR -> 0xFF5555;
        };
        
        guiGraphics.centeredText(this.font, icon + " " + title, centerX, startY, titleColor);
        
        // Message
        if (!message.isEmpty()) {
            guiGraphics.centeredText(this.font, message, centerX, startY + 20, 0xCCCCCC);
        }
        
        // Progress bar for DOWNLOADING and INSTALLING
        if (type == NotificationType.DOWNLOADING || type == NotificationType.INSTALLING) {
            int barX = centerX - 100;
            int barY = startY + 50;
            int barWidth = 200;
            int barHeight = 10;
            
            // Background track
            guiGraphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFF333333);
            
            // Border
            guiGraphics.fill(barX, barY, barX + barWidth, barY, 0xFF666666);
            guiGraphics.fill(barX, barY + barHeight - 1, barX + barWidth, barY + barHeight, 0xFF666666);
            guiGraphics.fill(barX, barY, barX, barY + barHeight, 0xFF666666);
            guiGraphics.fill(barX + barWidth - 1, barY, barX + barWidth, barY + barHeight, 0xFF666666);
            
            // Progress fill
            int filledWidth = (int) (barWidth * Math.max(0.0, Math.min(1.0, progress)));
            if (filledWidth > 0) {
                int fillColor = (type == NotificationType.DOWNLOADING) ? 0xFF3399FF : 0xFFFF9933;
                guiGraphics.fill(barX + 1, barY + 1, barX + filledWidth, barY + barHeight - 1, fillColor);
            }
            
            // Progress text
            if (type == NotificationType.DOWNLOADING) {
                String progressText = String.format("%d%% complete", (int) (progress * 100));
                guiGraphics.centeredText(this.font, progressText, centerX, barY + barHeight + 10, 0xFFFFFF);
            } else if (totalFiles > 0) {
                String progressText = filesProcessed + " / " + totalFiles + " files";
                guiGraphics.centeredText(this.font, progressText, centerX, barY + barHeight + 10, 0xFFFFFF);
            }
            
            // Current file
            if (!currentFile.isEmpty()) {
                guiGraphics.centeredText(this.font, "Processing: " + currentFile, centerX, barY + barHeight + 30, 0xAAAAAA);
            }
        }
        
        // Completion message
        if (type == NotificationType.COMPLETE) {
            guiGraphics.centeredText(this.font, "", centerX, startY + 50, 0xFFFFFF);
            guiGraphics.centeredText(this.font, "A restart is recommended to apply all changes.", centerX, startY + 65, 0xFFFF55);
            guiGraphics.centeredText(this.font, "Click 'Back to Title' to reload, or stay in game.", centerX, startY + 83, 0xAAAAAA);
        }
        
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
    }
    
    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(this.parent);
    }
}
