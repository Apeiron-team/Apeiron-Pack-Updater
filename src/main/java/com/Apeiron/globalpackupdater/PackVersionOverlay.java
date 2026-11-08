package com.Apeiron.globalpackupdater;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;

/**
 * Renders the pack version label on the main menu in the bottom-left corner,
 * similar to how Minecraft shows the version string in the bottom-right.
 * Also adds a config button to open the PackUpdater settings screen.
 */
public class PackVersionOverlay {

    private static boolean renderedThisTick = false;
    private static boolean configButtonAdded = false;

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof TitleScreen) {
            renderedThisTick = false;
            configButtonAdded = false;
        }
    }

    /**
     * Adds a config button to the TitleScreen when it's initialized.
     * Uses reflection to access the protected addRenderableWidget method.
     */
    @SubscribeEvent
    public void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof TitleScreen && !configButtonAdded) {
            configButtonAdded = true;
            Minecraft mc = Minecraft.getInstance();
            int buttonX = mc.getWindow().getGuiScaledWidth() - 100;
            int buttonY = mc.getWindow().getGuiScaledHeight() - 28;
            
            Button configButton = Button.builder(
                    Component.literal("PackUpdater"),
                    button -> {
                        mc.setScreen(new PackUpdaterConfigScreen(event.getScreen()));
                    }
            ).bounds(buttonX, buttonY, 95, 20).build();
            
            // Use reflection to call the protected addRenderableWidget method
            // The method signature is <T extends GuiEventListener> T addRenderableWidget(T widget)
            // We use Object.class as the parameter type to match the generic method
            try {
                java.lang.reflect.Method method = net.minecraft.client.gui.screens.Screen.class
                        .getDeclaredMethod("addRenderableWidget", Object.class);
                method.setAccessible(true);
                method.invoke(event.getScreen(), configButton);
            } catch (Exception e) {
                System.err.println("[PackUpdater] Failed to add config button to main menu: " + e.getMessage());
            }
        }
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGuiLayerEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof TitleScreen && !renderedThisTick) {
            renderedThisTick = true;
            renderPackVersion(event.getGuiGraphics(), mc);
        }
    }

    private void renderPackVersion(GuiGraphicsExtractor guiGraphics, Minecraft mc) {
        String packVersion = ConfigManager.modpackVersion;
        if (packVersion == null || packVersion.isEmpty() || packVersion.equals("v0.0")) {
            return;
        }

        // Draw pack version in bottom-left corner
        String text = "Pack: " + packVersion;
        int x = 2;
        int y = mc.getWindow().getGuiScaledHeight() - 10;

        // Draw with a slight shadow for readability
        guiGraphics.text(mc.font, text, x + 1, y + 1, 0xFF444444);
        guiGraphics.text(mc.font, text, x, y, 0xFFAAAAAA);
    }
}
