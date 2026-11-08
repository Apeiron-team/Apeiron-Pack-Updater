package com.Apeiron.globalpackupdater;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Adds a "PackUpdater" button to the Minecraft Options menu (Esc → Options)
 * so players can quickly access the mod's configuration screen.
 */
public class OptionsMenuButton {

    private static boolean buttonAdded = false;

    @SubscribeEvent
    public void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof OptionsScreen && !buttonAdded) {
            buttonAdded = true;
            Minecraft mc = Minecraft.getInstance();
            int buttonX = mc.getWindow().getGuiScaledWidth() / 2 - 100;
            int buttonY = mc.getWindow().getGuiScaledHeight() - 48;

            Button configButton = Button.builder(
                    Component.literal("PackUpdater"),
                    button -> {
                        mc.setScreen(new PackUpdaterConfigScreen(event.getScreen()));
                    }
            ).bounds(buttonX, buttonY, 95, 20).build();

            // Use reflection to call the protected addRenderableWidget method
            try {
                java.lang.reflect.Method method = Screen.class
                        .getDeclaredMethod("addRenderableWidget", Object.class);
                method.setAccessible(true);
                method.invoke(event.getScreen(), configButton);
            } catch (Exception e) {
                System.err.println("[PackUpdater] Failed to add button to options menu: " + e.getMessage());
            }
        }
    }
}
