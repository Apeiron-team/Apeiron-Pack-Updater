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

    @SubscribeEvent
    public void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof OptionsScreen) {
            Minecraft mc = Minecraft.getInstance();
            int buttonX = mc.getWindow().getGuiScaledWidth() / 2 - 100;
            int buttonY = mc.getWindow().getGuiScaledHeight() - 48;

            Button configButton = Button.builder(
                    Component.literal("PackUpdater"),
                    button -> {
                        mc.setScreen(new PackUpdaterConfigScreen(event.getScreen()));
                    }
            ).bounds(buttonX, buttonY, 95, 20).build();

            // Use event.getScreen() to add the widget - ScreenEvent.Init.Post allows adding widgets
            event.addListener(configButton);
        }
    }
}
