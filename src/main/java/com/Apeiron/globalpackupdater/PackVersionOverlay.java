package com.Apeiron.globalpackupdater;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;

/**
 * Renders the pack version label on the main menu in the bottom-left corner,
 * similar to how Minecraft shows the version string in the bottom-right.
 */
public class PackVersionOverlay {

    private static boolean renderedThisTick = false;

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof TitleScreen) {
            renderedThisTick = false;
        }
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGuiLayerEvent event) {
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
