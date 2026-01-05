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

        getCommand("spectral").setExecutor(new SpectralCommand(replayManager, databaseManager));

        getLogger().info("Spectral Replay has been enabled!");
    }

    @Override
    public void onDisable() {
        if (replayManager != null) {
            replayManager.shutdown();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
        getLogger().info("Spectral Replay has been disabled!");
    }

    public ReplayManager getReplayManager() {
        return replayManager;
    }
}
