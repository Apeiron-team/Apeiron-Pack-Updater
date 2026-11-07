package com.Apeiron.globalpackupdater;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Loading screen shown during .apepack import.
 * Displays a progress bar, current file being processed, and a summary on completion.
 * After completion, prompts the user to restart the game.
 */
public class ApePackImportScreen extends Screen {

    private final Screen parent;
    private final java.nio.file.Path apepackFile;
    private final java.nio.file.Path instanceRoot;

    // Progress state
    private String currentFile = "";
    private String currentFileType = "";
    private int filesProcessed = 0;
    private int totalFiles = 0;
    private int newFilesInstalled = 0;
    private int skippedDuplicates = 0;
    private int skippedProtected = 0;
    private boolean importComplete = false;
    private boolean importSuccess = false;
    private String errorMessage = "";

    // Pack info
    private String packName = "";
    private String packVersion = "";

    // UI elements
    private Button restartButton;
    private Button doneButton;

    public ApePackImportScreen(Screen parent, java.nio.file.Path apepackFile, java.nio.file.Path instanceRoot) {
        super(Component.literal("Importing .apepack..."));
        this.parent = parent;
        this.apepackFile = apepackFile;
        this.instanceRoot = instanceRoot;
        // Show file name immediately so the screen isn't blank
        this.currentFile = apepackFile.getFileName().toString();
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;

        if (!importComplete) {
            // Running state — no buttons, just progress
            return;
        }

        // Complete state — show restart and done buttons
        if (importSuccess) {
            this.restartButton = this.addRenderableWidget(Button.builder(
                    Component.literal("Back to Title"),
                    button -> {
                        // Navigate to title screen (full restart requires launcher)
                        Minecraft.getInstance().setScreen(new net.minecraft.client.gui.screens.TitleScreen());
                    }
            ).bounds(centerX - 120, this.height / 6 + 250, 115, 20).build());

            this.doneButton = this.addRenderableWidget(Button.builder(
                    Component.literal("Stay in Game"),
                    button -> {
                        Minecraft.getInstance().setScreen(this.parent);
                    }
            ).bounds(centerX + 5, this.height / 6 + 250, 115, 20).build());
        } else {
            // Failed — just a back button
            this.addRenderableWidget(Button.builder(
                    Component.literal("Back"),
                    button -> {
                        Minecraft.getInstance().setScreen(this.parent);
                    }
            ).bounds(centerX - 50, this.height / 6 + 250, 100, 20).build());
        }
    }

    /**
     * Start the import process on a background thread.
     */
    public void startImport() {
        Thread importThread = new Thread(() -> {
            try {
                ApePackLoader.InstallResult result = ApePackLoader.installApePackWithProgress(
                        apepackFile, instanceRoot,
                        new ApePackLoader.ImportProgress() {
                            @Override
                            public void onFileStart(String fileName, String fileType, int current, int total) {
                                Minecraft.getInstance().execute(() -> {
                                    currentFile = fileName;
                                    currentFileType = fileType;
                                    filesProcessed = current;
                                    totalFiles = total;
                                    packName = "Loading...";
                                    packVersion = "";
                                });
                            }

                            @Override
                            public void onFileInstalled(String fileName) {
                                Minecraft.getInstance().execute(() -> {
                                    newFilesInstalled++;
                                });
                            }

                            @Override
                            public void onFileSkipped(String fileName) {
                                Minecraft.getInstance().execute(() -> {
                                    skippedDuplicates++;
                                });
                            }

                            @Override
                            public void onFileProtected(String fileName) {
                                Minecraft.getInstance().execute(() -> {
                                    skippedProtected++;
                                });
                            }

                            @Override
                            public void onPackInfo(String name, String version) {
                                Minecraft.getInstance().execute(() -> {
                                    packName = name;
                                    packVersion = version;
                                });
                            }

                            @Override
                            public void onComplete(boolean success, String error) {
                                Minecraft.getInstance().execute(() -> {
                                    importComplete = true;
                                    importSuccess = success;
                                    errorMessage = error != null ? error : "";
                                    currentFile = "";
                                    currentFileType = "";
                                    // Rebuild buttons
                                    clearWidgets();
                                    init();
                                });
                            }
                        });
            } catch (Exception e) {
                Minecraft.getInstance().execute(() -> {
                    importComplete = true;
                    importSuccess = false;
                    errorMessage = e.getMessage();
                    currentFile = "";
                    currentFileType = "";
                    clearWidgets();
                    init();
                });
            }
        }, "PackUpdater-Import");
        importThread.setDaemon(true);
        importThread.start();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Draw background - use proper dark gray so text is visible
        guiGraphics.fill(0, 0, this.width, this.height, 0xCC000000);

        int centerX = this.width / 2;
        int startY = this.height / 6;

        // Title
        if (importComplete) {
            if (importSuccess) {
                guiGraphics.centeredText(this.font, "Import Complete!", centerX, startY, 0x55FF55);
            } else {
                guiGraphics.centeredText(this.font, "Import Failed!", centerX, startY, 0xFF5555);
            }
        } else {
            guiGraphics.centeredText(this.font, "Importing .apepack...", centerX, startY, 0xFFFFFF);
        }

        // Pack name and version
        if (!packName.isEmpty() && !packName.equals("Loading...")) {
            guiGraphics.centeredText(this.font, packName + " v" + packVersion, centerX, startY + 20, 0xCCCCCC);
        }

        if (!importComplete) {
            // Progress bar
            int barX = centerX - 100;
            int barY = startY + 50;
            int barWidth = 200;
            int barHeight = 12;

            // Background
            guiGraphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0x404040);

            // Fill
            if (totalFiles > 0) {
                int fillWidth = (int) ((filesProcessed / (float) totalFiles) * barWidth);
                guiGraphics.fill(barX, barY, barX + fillWidth, barY + barHeight, 0x55AAFF);
            }

            // Border
            guiGraphics.fill(barX, barY, barX + barWidth, barY, 0x888888);
            guiGraphics.fill(barX, barY + barHeight - 1, barX + barWidth, barY + barHeight, 0x888888);
            guiGraphics.fill(barX, barY, barX, barY + barHeight, 0x888888);
            guiGraphics.fill(barX + barWidth - 1, barY, barX + barWidth, barY + barHeight, 0x888888);

            // Progress text - show "Preparing..." when total is still 0
            if (totalFiles > 0) {
                String progressText = filesProcessed + " / " + totalFiles + " files";
                guiGraphics.centeredText(this.font, progressText, centerX, barY + barHeight + 10, 0xFFFFFF);
            } else {
                guiGraphics.centeredText(this.font, "Preparing...", centerX, barY + barHeight + 10, 0xAAAAAA);
            }

            // Current file being processed - always show the .apepack file name at minimum
            if (!currentFile.isEmpty()) {
                String typeLabel = currentFileType.isEmpty() ? "" : " (" + currentFileType + ")";
                guiGraphics.centeredText(this.font, "Processing: " + currentFile + typeLabel, centerX, barY + barHeight + 30, 0xAAAAAA);
            }

            // Live counts
            guiGraphics.centeredText(this.font,
                    "New: " + newFilesInstalled + " | Skipped: " + skippedDuplicates + " | Protected: " + skippedProtected,
                    centerX, barY + barHeight + 50, 0xCCCCCC);

        } else {
            // Summary
            int summaryY = startY + 50;

            if (importSuccess) {
                guiGraphics.centeredText(this.font, "Installation Summary:", centerX, summaryY, 0xFFFFFF);
                summaryY += 20;

                guiGraphics.centeredText(this.font, "Files installed: " + newFilesInstalled, centerX, summaryY, 0x55FF55);
                summaryY += 18;

                guiGraphics.centeredText(this.font, "Files skipped (already installed): " + skippedDuplicates, centerX, summaryY, 0xFFFF55);
                summaryY += 18;

                guiGraphics.centeredText(this.font, "User settings protected: " + skippedProtected, centerX, summaryY, 0x55FFFF);
                summaryY += 18;

                guiGraphics.centeredText(this.font, "", centerX, summaryY, 0xFFFFFF);
                summaryY += 18;

                guiGraphics.centeredText(this.font, "A restart is recommended to apply all changes.", centerX, summaryY, 0xFFFF55);
                summaryY += 18;

                guiGraphics.centeredText(this.font, "Click 'Back to Title' to reload, or stay in game and restart manually.", centerX, summaryY, 0xAAAAAA);
            } else {
                guiGraphics.centeredText(this.font, "Error:", centerX, summaryY, 0xFF5555);
                summaryY += 20;

                // Wrap error message if it's long
                String error = errorMessage;
                if (error.length() > 40) {
                    int lineCount = (int) Math.ceil(error.length() / 40.0);
                    for (int i = 0; i < lineCount; i++) {
                        int end = Math.min((i + 1) * 40, error.length());
                        guiGraphics.centeredText(this.font, error.substring(i * 40, end), centerX, summaryY, 0xFF5555);
                        summaryY += 18;
                    }
                } else {
                    guiGraphics.centeredText(this.font, error, centerX, summaryY, 0xFF5555);
                }
            }
        }

        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(this.parent);
    }
}
