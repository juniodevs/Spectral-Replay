package com.spectralreplay;

import com.spectralreplay.command.SpectralCommand;
import com.spectralreplay.database.DatabaseManager;
import com.spectralreplay.listener.PlayerListener;
import com.spectralreplay.manager.ReplayManager;
import org.bukkit.plugin.java.JavaPlugin;

public class SpectralReplay extends JavaPlugin {

    private ReplayManager replayManager;
    private DatabaseManager databaseManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (getServer().getPluginManager().getPlugin("Citizens") == null || !getServer().getPluginManager().getPlugin("Citizens").isEnabled()) {
            getLogger().severe("Citizens 2.0 not found or not enabled! Disabling Spectral Replay.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.databaseManager = new DatabaseManager(this);

        this.replayManager = new ReplayManager(this, databaseManager);
        
        this.replayManager.startRecording();
        
        this.replayManager.startRandomReplayTask();

        getServer().getPluginManager().registerEvents(new PlayerListener(this, replayManager), this);

        getCommand("spectral").setExecutor(new SpectralCommand(this, replayManager, databaseManager));

        getLogger().info("Spectral Replay has been enabled!");
    }

    @Override
    public void onDisable() {
        try {
            if (replayManager != null) {
                replayManager.shutdown();
            }
        } catch (Exception e) {
            getLogger().warning("Error shutting down ReplayManager: " + e.getMessage());
        }
        
        try {
            if (databaseManager != null) {
                databaseManager.close();
            }
        } catch (Exception e) {
            getLogger().warning("Error closing DatabaseManager: " + e.getMessage());
        }
        getLogger().info("Spectral Replay has been disabled!");
    }

    public ReplayManager getReplayManager() {
        return replayManager;
    }
}
