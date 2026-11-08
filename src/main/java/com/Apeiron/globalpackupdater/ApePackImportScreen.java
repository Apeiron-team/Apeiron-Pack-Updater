package com.Apeiron.globalpackupdater;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Loading screen shown during .apepack import.
 * Displays a progress bar, current file being processed, and a summary on completion.
 * After completion, prompts the user to restart the game.
 * Blocks ALL exit attempts until extraction is fully complete.
 */
public class ApePackImportScreen extends Screen {

    private final Screen parent;
    private final java.nio.file.Path apepackFile;
    private final java.nio.file.Path instanceRoot;

    // Latch to block exit until import thread finishes
    private final CountDownLatch importLatch = new CountDownLatch(1);

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
    private Button relaunchButton;

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
            // "Restart Game" button — spawns a new JVM to relaunch Minecraft
            this.relaunchButton = this.addRenderableWidget(Button.builder(
                    Component.literal("Restart Game"),
                    button -> {
                        // Block the button to prevent double-clicks
                        button.active = false;
                        button.setMessage(Component.literal("Starting..."));

                        // Run relaunch on a background thread to avoid blocking the UI
                        Thread relaunchThread = new Thread(() -> {
                            boolean success = GameRelauncher.relaunch();
                            Minecraft.getInstance().execute(() -> {
                                if (success) {
                                    // Exit the current JVM — the new process will take over
                                    System.out.println("[PackUpdater] Exiting current JVM after successful relaunch...");
                                    System.exit(0);
                                } else {
                                    System.err.println("[PackUpdater] Relaunch failed, falling back to title screen.");
                                    button.active = true;
                                    button.setMessage(Component.literal("Restart Failed"));
                                }
                            });
                        }, "PackUpdater-Relaunch");
                        relaunchThread.setDaemon(true);
                        relaunchThread.start();
                    }
            ).bounds(centerX - 160, this.height / 6 + 250, 115, 20).build());

            this.restartButton = this.addRenderableWidget(Button.builder(
                    Component.literal("Back to Title"),
                    button -> {
                        // Navigate to title screen (no full restart)
                        Minecraft.getInstance().setScreen(new net.minecraft.client.gui.screens.TitleScreen());
                    }
            ).bounds(centerX - 40, this.height / 6 + 250, 115, 20).build());

            this.doneButton = this.addRenderableWidget(Button.builder(
                    Component.literal("Stay in Game"),
                    button -> {
                        Minecraft.getInstance().setScreen(this.parent);
                    }
            ).bounds(centerX + 80, this.height / 6 + 250, 115, 20).build());
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
     * The import latch blocks all exit attempts until the thread finishes.
     */
    public void startImport() {
        Thread importThread = new Thread(() -> {
            try {
                System.out.println("[PackUpdater] Starting .apepack import: " + apepackFile.getFileName());
                System.out.println("[PackUpdater] Extracting to: " + instanceRoot.toAbsolutePath());

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
                                    // Do NOT clear packName/packVersion here - they are set by onPackInfo
                                    // and must persist until onComplete uses them to update the modpack version
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
                                    // Mark the .apepack as processed and update modpack version
                                    if (success) {
                                        ApePackLoader.markApePackAsProcessed(apepackFile);
                                        // Update the modpack version in the config
                                        if (!packVersion.isEmpty()) {
                                            ConfigManager.updateModpackVersion(packVersion);
                                        }
                                        // Delete the .apepack file after successful install
                                        try {
                                            java.nio.file.Files.deleteIfExists(apepackFile);
                                            System.out.println("[PackUpdater] Removed processed .apepack file");
                                        } catch (Exception e) {
                                            System.err.println("[PackUpdater] Failed to remove .apepack file: " + e.getMessage());
                                        }
                                        System.out.println("[PackUpdater] .apepack installation complete! Restart recommended.");
                                        // Show system toast notification for successful update
                                        if (!packName.isEmpty() && !packVersion.isEmpty()) {
                                            PackUpdaterMod.showUpdateSuccessToast(packName, packVersion);
                                        }
                                    } else {
                                        System.err.println("[PackUpdater] .apepack installation failed: " + error);
                                    }
                                    // Reset the global installation flag so future installs can proceed
                                    PackUpdaterMod.installing.set(false);
                                    // Rebuild buttons
                                    clearWidgets();
                                    init();
                                });
                            }
                        });

                if (result != null && result.success) {
                    System.out.println("[PackUpdater] Import finished: " + result.newFilesInstalled + " installed, " +
                            result.skippedDuplicates + " skipped, " + result.skippedProtected + " protected");
                }
            } catch (Exception e) {
                System.err.println("[PackUpdater] Import thread exception: " + e.getMessage());
                e.printStackTrace();
                Minecraft.getInstance().execute(() -> {
                    importComplete = true;
                    importSuccess = false;
                    errorMessage = e.getMessage();
                    currentFile = "";
                    currentFileType = "";
                    clearWidgets();
                    init();
                });
            } finally {
                // ALWAYS release the latch so the screen can close
                System.out.println("[PackUpdater] Import thread finishing, releasing exit latch...");
                importLatch.countDown();
            }
        }, "PackUpdater-Import");
        importThread.setDaemon(false); // Non-daemon so it doesn't get killed on exit
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
            int barHeight = 10;

            // Draw background (dark gray track)
            guiGraphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFF333333);
            
            // Draw border
            guiGraphics.fill(barX, barY, barX + barWidth, barY, 0xFF666666);
            guiGraphics.fill(barX, barY + barHeight - 1, barX + barWidth, barY + barHeight, 0xFF666666);
            guiGraphics.fill(barX, barY, barX, barY + barHeight, 0xFF666666);
            guiGraphics.fill(barX + barWidth - 1, barY, barX + barWidth, barY + barHeight, 0xFF666666);

            // Draw progress fill (blue bar, scaled by progress 0.0 to 1.0)
            if (totalFiles > 0) {
                double progress = (double) filesProcessed / totalFiles;
                int filledWidth = (int) (barWidth * progress);
                if (filledWidth > 0) {
                    // Fill with blue color, leaving 1px border
                    guiGraphics.fill(barX + 1, barY + 1, barX + filledWidth, barY + barHeight - 1, 0xFF3399FF);
                }
            }

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

            // Warning message - do not close
            guiGraphics.centeredText(this.font, "Do not close the game until extraction is complete", centerX, barY + barHeight + 75, 0xFF9933);

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
        // Block closing until import thread is truly finished
        if (!importComplete) {
            System.out.println("[PackUpdater] Blocked close attempt — import still running...");
            return;
        }
        // Wait for the import thread to fully finish (in case there's cleanup)
        try {
            importLatch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Minecraft.getInstance().setScreen(this.parent);
    }

    // Block all mouse input during import
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!importComplete) {
            return false;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (!importComplete) {
            return false;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (!importComplete) {
            return false;
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    // Block all keyboard input during import
    @Override
    public boolean charTyped(CharacterEvent event) {
        if (!importComplete) {
            return false;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Block ESC during import
        if (!importComplete && event.key() == GLFW.GLFW_KEY_ESCAPE) {
            return true;
        }
        if (!importComplete) {
            return false;
        }
        return super.keyPressed(event);
    }

}
