package com.Apeiron.globalpackupdater;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A Minecraft-native file picker screen that allows browsing the filesystem
 * and selecting .apepack files. Opens as a separate window from the config screen.
 */
public class ApePackFilePicker extends Screen {

    private final Screen parent;
    private final FileSelectedCallback callback;

    // Current directory being browsed
    private File currentDir;
    private List<FileInfo> files = new ArrayList<>();

    // Path input field for manual navigation
    private EditBox pathField;

    // Selected file
    private File selectedFile = null;

    // Scroll state
    private int scrollOffset = 0;
    private int panelTop = 75;
    private int panelHeight = 0;
    private int panelLeft = 20;
    private int panelWidth = 0;
    private static final int ROW_HEIGHT = 22;
    private static final int SCROLLBAR_WIDTH = 8;

    // Scrollbar interaction
    private boolean draggingScrollbar = false;
    private int dragStartY = 0;
    private int dragScrollOffset = 0;

    // Track file buttons for removal
    private List<Button> fileButtons = new ArrayList<>();

    public interface FileSelectedCallback {
        void onFileSelected(Path filePath);
    }

    public ApePackFilePicker(Screen parent, FileSelectedCallback callback) {
        super(Component.literal("Select .apepack File"));
        this.parent = parent;
        this.callback = callback;
        this.currentDir = new File(System.getProperty("user.home"));
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;
        this.panelWidth = this.width - 40;
        this.panelHeight = this.height - this.panelTop - 100;

        // Path input field at the top (moved up to y=28)
        this.pathField = new EditBox(this.font, centerX - 150, 28, 300, 20, Component.literal("Path"));
        this.pathField.setHint(Component.literal("Browse or type a folder path"));
        this.pathField.setValue(this.currentDir.getAbsolutePath());
        this.addRenderableWidget(this.pathField);

        // Go button next to path field
        this.addRenderableWidget(Button.builder(
                Component.literal("Go"),
                button -> {
                    String path = this.pathField.getValue().trim();
                    if (!path.isEmpty()) {
                        File dir = new File(path);
                        if (dir.exists() && dir.isDirectory()) {
                            this.currentDir = dir;
                            this.scrollOffset = 0;
                            refreshFiles();
                        }
                    }
                }
        ).bounds(centerX + 155, 28, 40, 20).build());

        // Navigation buttons row (y=52, below path field)
        int navY = 52;

        // Back button
        this.addRenderableWidget(Button.builder(
                Component.literal("← Back"),
                button -> {
                    if (this.currentDir.getParentFile() != null) {
                        this.currentDir = this.currentDir.getParentFile();
                        this.scrollOffset = 0;
                        refreshFiles();
                    }
                }
        ).bounds(centerX - 200, navY, 50, 20).build());

        // Up button (go to parent)
        this.addRenderableWidget(Button.builder(
                Component.literal("↑ Up"),
                button -> {
                    if (this.currentDir.getParentFile() != null) {
                        this.currentDir = this.currentDir.getParentFile();
                        this.scrollOffset = 0;
                        refreshFiles();
                    }
                }
        ).bounds(centerX - 145, navY, 50, 20).build());

        // Quick access: Home folder
        this.addRenderableWidget(Button.builder(
                Component.literal("🏠 Home"),
                button -> {
                    this.currentDir = new File(System.getProperty("user.home"));
                    this.scrollOffset = 0;
                    refreshFiles();
                }
        ).bounds(centerX - 90, navY, 60, 20).build());

        // Quick access: Downloads folder
        this.addRenderableWidget(Button.builder(
                Component.literal("📥 Downloads"),
                button -> {
                    File downloads = new File(System.getProperty("user.home"), "Downloads");
                    if (downloads.exists()) {
                        this.currentDir = downloads;
                        this.scrollOffset = 0;
                        refreshFiles();
                    }
                }
        ).bounds(centerX - 25, navY, 80, 20).build());

        // Quick access: Desktop
        this.addRenderableWidget(Button.builder(
                Component.literal("🖥 Desktop"),
                button -> {
                    File desktop = new File(System.getProperty("user.home"), "Desktop");
                    if (desktop.exists()) {
                        this.currentDir = desktop;
                        this.scrollOffset = 0;
                        refreshFiles();
                    }
                }
        ).bounds(centerX + 60, navY, 80, 20).build());

        // Select button
        this.addRenderableWidget(Button.builder(
                Component.literal("Select File"),
                button -> {
                    if (this.selectedFile != null && this.selectedFile.exists()) {
                        this.callback.onFileSelected(this.selectedFile.toPath());
                    }
                }
        ).bounds(centerX - 100, this.height - 40, 100, 20).build());

        // Cancel button
        this.addRenderableWidget(Button.builder(
                Component.literal("Cancel"),
                button -> {
                    Minecraft.getInstance().setScreen(this.parent);
                }
        ).bounds(centerX + 10, this.height - 40, 100, 20).build());

        refreshFiles();
    }

    private void refreshFiles() {
        // Save scroll offset before clearing
        int savedScrollOffset = 0;
        
        // Remove old file buttons
        for (Button btn : this.fileButtons) {
            this.removeWidget(btn);
        }
        this.fileButtons.clear();

        this.files.clear();
        this.selectedFile = null;
        this.scrollOffset = 0;

        if (this.currentDir == null || !this.currentDir.exists() || !this.currentDir.isDirectory()) {
            return;
        }

        File[] allFiles = this.currentDir.listFiles();
        if (allFiles == null) return;

        // Separate directories and files, sort directories first
        List<File> directories = new ArrayList<>();
        List<File> apepackFiles = new ArrayList<>();
        List<File> otherFiles = new ArrayList<>();

        for (File file : allFiles) {
            if (file.isDirectory()) {
                directories.add(file);
            } else if (file.getName().toLowerCase().endsWith(".apepack")) {
                apepackFiles.add(file);
            } else {
                otherFiles.add(file);
            }
        }

        Collections.sort(directories, (a, b) -> a.getName().toLowerCase().compareTo(b.getName().toLowerCase()));
        Collections.sort(apepackFiles, (a, b) -> a.getName().toLowerCase().compareTo(b.getName().toLowerCase()));
        Collections.sort(otherFiles, (a, b) -> a.getName().toLowerCase().compareTo(b.getName().toLowerCase()));

        for (File dir : directories) {
            this.files.add(new FileInfo(dir, true));
        }
        for (File file : apepackFiles) {
            this.files.add(new FileInfo(file, false));
        }
        for (File file : otherFiles) {
            this.files.add(new FileInfo(file, false));
        }

        this.pathField.setValue(this.currentDir.getAbsolutePath());

        // Add buttons for each file with visible names
        refreshFileButtons();
    }

    // Refresh only the file buttons without resetting scroll or selected file
    private void refreshFileButtons() {
        // Remove old file buttons
        for (Button btn : this.fileButtons) {
            this.removeWidget(btn);
        }
        this.fileButtons.clear();

        int visibleRows = this.panelHeight / ROW_HEIGHT;
        int startIndex = this.scrollOffset / ROW_HEIGHT;
        for (int i = 0; i < visibleRows + 1 && startIndex + i < this.files.size(); i++) {
            int index = startIndex + i;
            FileInfo info = this.files.get(index);
            int y = this.panelTop + i * ROW_HEIGHT - (this.scrollOffset % ROW_HEIGHT);

            String displayName;
            if (info.isDirectory) {
                displayName = "📁 " + info.file.getName();
            } else {
                displayName = info.file.getName();
            }

            final FileInfo finalInfo = info;
            Button fileButton = Button.builder(
                    Component.literal(displayName),
                    btn -> {
                        if (finalInfo.isDirectory) {
                            this.currentDir = finalInfo.file;
                            this.scrollOffset = 0;
                            refreshFiles();
                        } else if (finalInfo.file.getName().toLowerCase().endsWith(".apepack")) {
                            this.selectedFile = finalInfo.file;
                        }
                    }
            ).bounds(this.panelLeft + 2, y + 1, this.panelWidth - SCROLLBAR_WIDTH - 4, ROW_HEIGHT - 2).build();
            this.addRenderableWidget(fileButton);
            this.fileButtons.add(fileButton);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // Check if scroll is over the panel area
        if (mouseX >= this.panelLeft && mouseX <= this.panelLeft + this.panelWidth &&
            mouseY >= this.panelTop && mouseY <= this.panelTop + this.panelHeight) {
            int maxScroll = Math.max(0, this.files.size() * ROW_HEIGHT - this.panelHeight);
            this.scrollOffset = Math.max(0, Math.min((int)(this.scrollOffset - scrollY * ROW_HEIGHT), maxScroll));
            // Refresh only the file buttons, not the entire file list
            refreshFileButtons();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // Check if clicking on the scrollbar
        if (event.buttonInfo().isLeft()) {
            double mouseX = event.x();
            double mouseY = event.y();
            int totalContentHeight = this.files.size() * ROW_HEIGHT;
            if (totalContentHeight > this.panelHeight) {
                int maxScroll = totalContentHeight - this.panelHeight;
                int scrollbarX = this.panelLeft + this.panelWidth - SCROLLBAR_WIDTH;
                int scrollbarHeight = Math.max(20, (this.panelHeight * this.panelHeight) / totalContentHeight);
                int scrollbarY = this.panelTop + (int)((this.scrollOffset * (this.panelHeight - scrollbarHeight)) / maxScroll);

                if (mouseX >= scrollbarX && mouseX <= scrollbarX + SCROLLBAR_WIDTH &&
                    mouseY >= this.panelTop && mouseY <= this.panelTop + this.panelHeight) {
                    draggingScrollbar = true;
                    dragStartY = (int) mouseY;
                    dragScrollOffset = this.scrollOffset;
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.buttonInfo().isLeft() && draggingScrollbar) {
            draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        if (draggingScrollbar) {
            int totalContentHeight = this.files.size() * ROW_HEIGHT;
            int maxScroll = totalContentHeight - this.panelHeight;
            int scrollbarHeight = Math.max(20, (this.panelHeight * this.panelHeight) / totalContentHeight);
            int panelUsableHeight = this.panelHeight - scrollbarHeight;

            int deltaY = (int) mouseY - dragStartY;
            int scrollDelta = (int)((deltaY * maxScroll) / panelUsableHeight);

            this.scrollOffset = Math.max(0, Math.min(dragScrollOffset + scrollDelta, maxScroll));
            refreshFileButtons();
        }
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Background
        guiGraphics.fill(0, 0, this.width, this.height, 0xF0000000);

        // Title
        guiGraphics.centeredText(this.font, "Select .apepack File", this.width / 2, 8, 0xFFFFFF);

        // Current directory label
        guiGraphics.centeredText(this.font, "Current: " + this.currentDir.getName(), this.width / 2, this.panelTop - 5, 0xAAAAAA);

        // Draw panel background
        guiGraphics.fill(this.panelLeft, this.panelTop, this.panelLeft + this.panelWidth, this.panelTop + this.panelHeight, 0x80000000);

        // Draw panel border
        guiGraphics.fill(this.panelLeft, this.panelTop, this.panelLeft + this.panelWidth, this.panelTop, 0x88888888);
        guiGraphics.fill(this.panelLeft, this.panelTop + this.panelHeight - 1, this.panelLeft + this.panelWidth, this.panelTop + this.panelHeight, 0x88888888);
        guiGraphics.fill(this.panelLeft, this.panelTop, this.panelLeft, this.panelTop + this.panelHeight, 0x88888888);
        guiGraphics.fill(this.panelLeft + this.panelWidth - 1, this.panelTop, this.panelLeft + this.panelWidth, this.panelTop + this.panelHeight, 0x88888888);

        // Draw file entries - highlight backgrounds behind buttons
        int visibleRows = this.panelHeight / ROW_HEIGHT;
        int startIndex = this.scrollOffset / ROW_HEIGHT;

        for (int i = 0; i < visibleRows + 1 && startIndex + i < this.files.size(); i++) {
            int index = startIndex + i;
            FileInfo info = this.files.get(index);
            int y = this.panelTop + i * ROW_HEIGHT - (this.scrollOffset % ROW_HEIGHT);

            if (y < this.panelTop || y + ROW_HEIGHT > this.panelTop + this.panelHeight) continue;

            boolean isApepack = !info.isDirectory && info.file.getName().toLowerCase().endsWith(".apepack");

            // Highlight selected file
            boolean isSelected = this.selectedFile != null && this.selectedFile.equals(info.file);
            boolean isHovered = mouseX >= this.panelLeft && mouseX <= this.panelLeft + this.panelWidth &&
                    mouseY >= y && mouseY < y + ROW_HEIGHT;

            // Draw background for hover/selection
            if (isSelected) {
                guiGraphics.fill(this.panelLeft + 2, y + 1, this.panelLeft + this.panelWidth - 2, y + ROW_HEIGHT - 1, 0x4000AA00);
            } else if (isHovered && isApepack) {
                guiGraphics.fill(this.panelLeft + 2, y + 1, this.panelLeft + this.panelWidth - 2, y + ROW_HEIGHT - 1, 0x200000FF);
            }
        }

        // Draw scrollbar if needed
        int totalContentHeight = this.files.size() * ROW_HEIGHT;
        if (totalContentHeight > this.panelHeight) {
            int maxScroll = totalContentHeight - this.panelHeight;
            int scrollbarX = this.panelLeft + this.panelWidth - SCROLLBAR_WIDTH;
            int scrollbarHeight = Math.max(20, (this.panelHeight * this.panelHeight) / totalContentHeight);
            int scrollbarY = this.panelTop + (int)((this.scrollOffset * (this.panelHeight - scrollbarHeight)) / maxScroll);

            // Check if mouse is hovering over the scrollbar thumb
            boolean scrollbarHovered = mouseX >= scrollbarX && mouseX <= scrollbarX + SCROLLBAR_WIDTH &&
                    mouseY >= scrollbarY && mouseY <= scrollbarY + scrollbarHeight;

            // Scrollbar track background (darker groove)
            guiGraphics.fill(scrollbarX, this.panelTop, scrollbarX + SCROLLBAR_WIDTH, this.panelTop + this.panelHeight, 0x60000000);
            // Scrollbar track border
            guiGraphics.fill(scrollbarX, this.panelTop, scrollbarX + 1, this.panelTop + this.panelHeight, 0x40404040);
            guiGraphics.fill(scrollbarX + SCROLLBAR_WIDTH - 1, this.panelTop, scrollbarX + SCROLLBAR_WIDTH, this.panelTop + this.panelHeight, 0x40404040);

            // Scrollbar thumb - brighter when hovered or dragging
            int thumbColor;
            if (draggingScrollbar) {
                thumbColor = 0xFFAAAAAA;
            } else if (scrollbarHovered) {
                thumbColor = 0xFF888888;
            } else {
                thumbColor = 0xFF666666;
            }
            guiGraphics.fill(scrollbarX + 1, scrollbarY, scrollbarX + SCROLLBAR_WIDTH - 1, scrollbarY + scrollbarHeight, thumbColor);
            // Thumb highlight
            guiGraphics.fill(scrollbarX + 1, scrollbarY, scrollbarX + 2, scrollbarY + scrollbarHeight, 0x30FFFFFF);
        }

        // Selected file indicator
        if (this.selectedFile != null) {
            guiGraphics.centeredText(this.font, "Selected: " + this.selectedFile.getName(), this.width / 2, this.height - 55, 0x55FF55);
        } else {
            guiGraphics.centeredText(this.font, "No file selected", this.width / 2, this.height - 55, 0x888888);
        }

        // Instructions
        guiGraphics.centeredText(this.font, "Click a folder to open, click a .apepack to select, scroll to browse", this.width / 2, this.panelTop + this.panelHeight + 12, 0x777777);

        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(this.parent);
    }

    /**
     * Represents a file or directory entry in the file list.
     */
    public static class FileInfo {
        public final File file;
        public final boolean isDirectory;

        public FileInfo(File file, boolean isDirectory) {
            this.file = file;
            this.isDirectory = isDirectory;
        }
    }

    private String getFileName(File file) {
        String name = file.getName();
        if (name.length() > 40) {
            name = "..." + name.substring(name.length() - 37);
        }
        return name;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)) + " MB";
        return (bytes / (1024 * 1024 * 1024)) + " GB";
    }
}

