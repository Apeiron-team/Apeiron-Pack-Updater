package com.Apeiron.globalpackupdater;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.nio.file.Path;

/**
 * Intercepts the vanilla TitleScreen (main menu) opening.
 * If an update is available (pending .apepack file exists), it shows the
 * UpdateSplashScreen instead of the main menu.
 */
public class UpdateSplashScreenInterceptor {

    @SubscribeEvent
    public void onScreenOpening(ScreenEvent.Opening event) {
        // Only intercept when opening the TitleScreen
        if (!(event.getScreen() instanceof TitleScreen)) return;
        
        // Don't show if the user explicitly skipped
        if (UpdateSplashScreen.skipNextSplash) {
            UpdateSplashScreen.skipNextSplash = false;
            return;
        }
        
        // Check if there's a pending .apepack file
        Path apepackFile = PackUpdaterMod.pendingApepackFile.get();
        if (apepackFile == null || !java.nio.file.Files.exists(apepackFile)) {
            // No pending update, proceed to main menu
            return;
        }
        
        // Get version info
        String currentVersion = ConfigManager.modpackVersion;
        if (currentVersion == null || currentVersion.isEmpty()) {
            currentVersion = "v0.0";
        }
        
        String newVersion = PackUpdaterMod.latestVersion.get();
        if (newVersion == null || newVersion.isEmpty()) {
            newVersion = "unknown";
        }
        
        String changelog = PackUpdaterMod.latestChangelog.get();
        if (changelog == null) {
            changelog = "";
        }
        
        // Cancel the TitleScreen and show the splash screen instead
        event.setCanceled(true);
        Minecraft.getInstance().setScreen(new UpdateSplashScreen(apepackFile, currentVersion, newVersion, changelog));
    }
}
