package com.spectralreplay.listener;

import com.spectralreplay.SpectralReplay;
import com.spectralreplay.manager.ReplayManager;
import com.spectralreplay.model.PlayerAction;
import com.spectralreplay.model.ReplayFrame;
import com.spectralreplay.model.ReplayType;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;

import org.bukkit.event.entity.EntityDeathEvent;

public class PlayerListener implements Listener {

    private final SpectralReplay plugin;
    private final ReplayManager replayManager;

    public PlayerListener(SpectralReplay plugin, ReplayManager replayManager) {
        this.plugin = plugin;
        this.replayManager = replayManager;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        replayManager.clearRecording(event.getPlayer());
    }

    @EventHandler
    public void onPlayerAnimation(PlayerAnimationEvent event) {
        if (event.getAnimationType() == PlayerAnimationType.ARM_SWING) {
            replayManager.setPlayerAction(event.getPlayer(), PlayerAction.SWING_HAND);
        }
    }

    @EventHandler
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        replayManager.saveDeathReplay(player, ReplayType.DEATH);
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        EntityType type = event.getEntityType();
        if (type == EntityType.WITHER || type == EntityType.ENDER_DRAGON || type == EntityType.WARDEN || type == EntityType.ELDER_GUARDIAN) {
            Player killer = event.getEntity().getKiller();
            if (killer != null) {
                replayManager.saveDeathReplay(killer, ReplayType.BOSS_KILL);
            }
        }
    }

}
