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
import org.bukkit.event.entity.EntityDamageEvent;

public class PlayerListener implements Listener {

    private final SpectralReplay plugin;
    private final ReplayManager replayManager;

    public PlayerListener(SpectralReplay plugin, ReplayManager replayManager) {
        this.plugin = plugin;
        this.replayManager = replayManager;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        try {
            replayManager.clearRecording(event.getPlayer());
        } catch (Exception e) {
            plugin.getLogger().warning("Error clearing recording for " + event.getPlayer().getName() + ": " + e.getMessage());
        }
    }

    @EventHandler
    public void onPlayerAnimation(PlayerAnimationEvent event) {
        try {
            if (event.getAnimationType() == PlayerAnimationType.ARM_SWING) {
                replayManager.setPlayerAction(event.getPlayer(), PlayerAction.SWING_HAND);
            }
        } catch (Exception e) {
            // Ignore minor animation errors
        }
    }

    @EventHandler
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        try {
            Player player = event.getEntity();
            Player killer = player.getKiller();
            
            if (killer != null) {
                EntityDamageEvent lastDamage = player.getLastDamageCause();
                if (lastDamage != null && (lastDamage.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION || lastDamage.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION)) {
                    replayManager.saveDeathReplay(player, ReplayType.DEATH);
                } else {
                    // It's a PVP death
                    long timestamp = System.currentTimeMillis();
                    replayManager.savePVPReplay(player, killer, timestamp);
                }
            } else {
                // Standard PvE death
                replayManager.saveDeathReplay(player, ReplayType.DEATH);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error handling player death event: " + e.getMessage());
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        try {
            EntityType type = event.getEntityType();
            if (type == EntityType.WITHER || type == EntityType.ENDER_DRAGON || type == EntityType.WARDEN || type == EntityType.ELDER_GUARDIAN) {
                Player killer = event.getEntity().getKiller();
                if (killer != null) {
                    replayManager.saveDeathReplay(killer, ReplayType.BOSS_KILL);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error handling entity death event: " + e.getMessage());
        }
    }

    @EventHandler
    public void onEntityDamageByEntity(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        try {
            if (event.getDamager() instanceof Player) {
                Player player = (Player) event.getDamager();
                replayManager.setPlayerAction(player, PlayerAction.ATTACK);
            }
        } catch (Exception e) {
        }
    }

}
