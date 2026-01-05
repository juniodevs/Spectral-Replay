package com.spectralreplay.manager;

import com.spectralreplay.SpectralReplay;
import com.spectralreplay.database.DatabaseManager;
import com.spectralreplay.model.PlayerAction;
import com.spectralreplay.model.ReplayFrame;
import com.spectralreplay.model.ReplayType;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.trait.SkinTrait;
import org.bukkit.*;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;

public class ReplayManager {

    private final SpectralReplay plugin;
    private final DatabaseManager databaseManager;
    private Team ghostTeam;

    private final Map<UUID, Deque<ReplayFrame>> recordings = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerAction> currentActions = new ConcurrentHashMap<>();
    private final Set<Integer> activeReplays = ConcurrentHashMap.newKeySet();
    private final Map<Integer, org.bukkit.scheduler.BukkitTask> placedReplayTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Integer, Long>> proximityCooldowns = new ConcurrentHashMap<>();
    private final Queue<NPC> npcPool = new ConcurrentLinkedQueue<>();
    private final Set<NPC> activeNPCs = ConcurrentHashMap.newKeySet();
    
    private static final int MAX_FRAMES = 200;

    public ReplayManager(SpectralReplay plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        setupGhostTeam();
        cleanupLeftoverNPCs();
    }

    private void cleanupLeftoverNPCs() {
        net.citizensnpcs.api.npc.NPCRegistry registry = CitizensAPI.getNPCRegistry();
        if (registry == null) return;

        List<NPC> toRemove = new ArrayList<>();
        for (NPC npc : registry) {
            if (npc.getName().startsWith("Ghost-")) {
                toRemove.add(npc);
            }
        }

        for (NPC npc : toRemove) {
            npc.destroy();
            registry.deregister(npc);
            plugin.getLogger().info("Removed leftover ghost NPC: " + npc.getName());
        }
    }

    private void setupGhostTeam() {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        ghostTeam = scoreboard.getTeam("spectral_ghosts");
        if (ghostTeam == null) {
            ghostTeam = scoreboard.registerNewTeam("spectral_ghosts");
        }
        ghostTeam.setCanSeeFriendlyInvisibles(true);
        ghostTeam.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        ghostTeam.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        ghostTeam.setColor(ChatColor.GRAY);
    }

    public void startRecording() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    recordFrame(player);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    public void startRandomReplayTask() {
        scheduleNextReplay(ReplayType.DEATH);
        scheduleNextReplay(ReplayType.BOSS_KILL);
        loadPlacedReplays();
        startProximityCheckTask();
    }

    private void startProximityCheckTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!plugin.getConfig().getBoolean("proximity-replay.enabled", true)) return;

                double radius = plugin.getConfig().getDouble("proximity-replay.radius", 5.0);
                long cooldownSeconds = plugin.getConfig().getLong("proximity-replay.cooldown", 600);
                long cooldownMillis = cooldownSeconds * 1000;

                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getGameMode() == GameMode.SPECTATOR) continue;

                    // Check if it is night (13000 - 23000)
                    long time = player.getWorld().getTime();
                    if (time < 13000 || time > 23000) continue;

                    List<DatabaseManager.ReplayData> nearbyReplays = databaseManager.getNearbyReplayMeta(player.getLocation(), radius, null);
                    
                    for (DatabaseManager.ReplayData replay : nearbyReplays) {
                        if (replay.type != ReplayType.DEATH && replay.type != ReplayType.PVP) continue;
                        if (activeReplays.contains(replay.id)) continue;

                        Map<Integer, Long> playerCooldowns = proximityCooldowns.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
                        long lastPlayed = playerCooldowns.getOrDefault(replay.id, 0L);

                        if (System.currentTimeMillis() - lastPlayed > cooldownMillis) {
                            // Play the replay
                            playGhostReplay(replay);
                            playerCooldowns.put(replay.id, System.currentTimeMillis());
                            
                            // Only trigger one replay per check to avoid chaos
                            break; 
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 100L, 20L); // Check every second (20 ticks), start after 5s
    }

    private void loadPlacedReplays() {
        List<DatabaseManager.PlacedReplay> placedReplays = databaseManager.getAllPlacedReplays();
        for (DatabaseManager.PlacedReplay placed : placedReplays) {
            startPlacedReplayTask(placed);
        }
    }

    public void startPlacedReplayTask(DatabaseManager.PlacedReplay placed) {
        org.bukkit.scheduler.BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        DatabaseManager.ReplayData data = databaseManager.getReplayById(placed.replayId);
                        if (data != null) {
                            new BukkitRunnable() {
                                @Override
                                public void run() {
                                    playGhostReplay(data, placed.location);
                                }
                            }.runTask(plugin);
                        }
                    }
                }.runTaskAsynchronously(plugin);
            }
        }.runTaskTimer(plugin, 100L, 600L); // Start after 5s, repeat every 30s (adjust as needed)
        
        placedReplayTasks.put(placed.id, task);
    }

    public boolean removePlacedReplay(int id) {
        if (placedReplayTasks.containsKey(id)) {
            placedReplayTasks.get(id).cancel();
            placedReplayTasks.remove(id);
            databaseManager.deletePlacedReplay(id);
            return true;
        }
        return false;
    }

    private void scheduleNextReplay(ReplayType type) {
        long minDelay, maxDelay;
        
        if (type == ReplayType.BOSS_KILL) {
            minDelay = plugin.getConfig().getLong("boss-replay.min-delay", 6000L);
            maxDelay = plugin.getConfig().getLong("boss-replay.max-delay", 12000L);
        } else {
            minDelay = plugin.getConfig().getLong("min-delay", 12000L);
            maxDelay = plugin.getConfig().getLong("max-delay", 24000L);
        }
        
        long delay = ThreadLocalRandom.current().nextLong(minDelay, maxDelay + 1);

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    attemptReplay(type);
                } finally {
                    scheduleNextReplay(type);
                }
            }
        }.runTaskLater(plugin, delay);
    }

    private void attemptReplay(ReplayType type) {
        if (!activeReplays.isEmpty()) return;

        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) return;
        Collections.shuffle(players);

        Map<Player, Location> candidates = new HashMap<>();
        for (Player player : players) {
            long time = player.getWorld().getTime();
            if (time < 13000 || time > 23000) continue;
            candidates.put(player, player.getLocation());
        }

        if (candidates.isEmpty()) return;


        new BukkitRunnable() {
            @Override
            public void run() {
                for (Map.Entry<Player, Location> entry : candidates.entrySet()) {
                    if (ThreadLocalRandom.current().nextDouble() > 0.3) continue;

                    Location loc = entry.getValue();
                    List<DatabaseManager.ReplayData> nearbyReplays = databaseManager.getNearbyReplayMeta(loc, 20.0, type);
                    
                    if (nearbyReplays.isEmpty()) continue;

                    List<DatabaseManager.ReplayData> availableReplays = new ArrayList<>();
                    for (DatabaseManager.ReplayData r : nearbyReplays) {
                        if (!activeReplays.contains(r.id)) {
                            availableReplays.add(r);
                        }
                    }

                    if (availableReplays.isEmpty()) continue;

                    DatabaseManager.ReplayData replay = availableReplays.get(ThreadLocalRandom.current().nextInt(availableReplays.size()));
                    
                    // Switch back to main thread to play
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            playGhostReplay(replay);
                        }
                    }.runTask(plugin);
                    return; // Found one, stop searching
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    private void recordFrame(Player player) {
        UUID uuid = player.getUniqueId();
        Deque<ReplayFrame> buffer = recordings.computeIfAbsent(uuid, k -> new ArrayDeque<>(MAX_FRAMES));

        Location loc = player.getLocation();
        PlayerAction action = currentActions.getOrDefault(uuid, PlayerAction.NONE);
        boolean isSneaking = player.isSneaking();
        ItemStack item = player.getInventory().getItemInMainHand();
        ItemStack[] armor = player.getInventory().getArmorContents();

        ReplayFrame frame = new ReplayFrame(loc, action, isSneaking, item, armor);

        buffer.addLast(frame);

        if (buffer.size() > MAX_FRAMES) {
            buffer.removeFirst();
        }

        currentActions.put(uuid, PlayerAction.NONE);
    }

    public void setPlayerAction(Player player, PlayerAction action) {
        currentActions.put(player.getUniqueId(), action);
    }

    public List<ReplayFrame> getSnapshot(Player player) {
        Deque<ReplayFrame> buffer = recordings.get(player.getUniqueId());
        if (buffer == null) return Collections.emptyList();
        return new ArrayList<>(buffer);
    }

    public void savePVPReplay(Player victim, Player killer, long timestamp) {
        saveReplay(victim, ReplayType.PVP, timestamp);
        saveReplay(killer, ReplayType.PVP, timestamp);
    }

    public void saveDeathReplay(Player player, ReplayType type) {
        saveReplay(player, type, System.currentTimeMillis());
    }

    private void saveReplay(Player player, ReplayType type, long timestamp) {
        List<ReplayFrame> frames = getSnapshot(player);
        if (!frames.isEmpty()) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    databaseManager.saveReplay(player.getUniqueId(), player.getLocation(), frames, type, timestamp);
                }
            }.runTaskAsynchronously(plugin);
        }
        clearRecording(player);
    }

    public void clearRecording(Player player) {
        recordings.remove(player.getUniqueId());
        currentActions.remove(player.getUniqueId());
    }

    public void playGhostReplay(DatabaseManager.ReplayData replayData) {
        playGhostReplay(replayData, null);
    }

    public void playGhostReplay(DatabaseManager.ReplayData replayData, Location origin) {
        if (replayData.frames == null || (replayData.type == ReplayType.PVP && origin == null)) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    List<ReplayFrame> frames = replayData.frames;
                    if (frames == null) {
                        frames = databaseManager.getReplayFrames(replayData.id);
                    }
                    
                    if (frames.isEmpty()) return;

                    DatabaseManager.ReplayData fullData = new DatabaseManager.ReplayData(
                            replayData.id, replayData.uuid, replayData.location, frames, replayData.type, replayData.timestamp
                    );

                    DatabaseManager.ReplayData partnerData = null;
                    if (fullData.type == ReplayType.PVP && origin == null) {
                         List<DatabaseManager.ReplayData> partners = databaseManager.getReplaysByTimestamp(fullData.timestamp, fullData.uuid);
                         for (DatabaseManager.ReplayData r : partners) {
                             if (r.id != fullData.id) {
                                 partnerData = r;
                                 break;
                             }
                         }
                    }
                    
                    final DatabaseManager.ReplayData finalPartner = partnerData;

                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            playGhostReplayInternal(fullData, origin, finalPartner);
                        }
                    }.runTask(plugin);
                }
            }.runTaskAsynchronously(plugin);
            return;
        }
        
        playGhostReplayInternal(replayData, origin, null);
    }

    private void playGhostReplayInternal(DatabaseManager.ReplayData replayData, Location origin, DatabaseManager.ReplayData preloadedPartner) {
        if (origin == null && activeReplays.contains(replayData.id)) return;

        DatabaseManager.ReplayData partnerReplay = preloadedPartner;
        
        // Fallback check if partner wasn't preloaded (shouldn't happen with new logic, but keeps compatibility)
        if (replayData.type == ReplayType.PVP && partnerReplay == null && origin == null) {
             // Skip partner to avoid sync DB call
        }

        if (partnerReplay != null && origin == null && activeReplays.contains(partnerReplay.id)) {
            return;
        }

        if (origin == null) {
            int maxConcurrent = plugin.getConfig().getInt("max-concurrent-replays", 5);
            int needed = 1;
            if (partnerReplay != null) needed = 2;
            
            if (activeReplays.size() + needed > maxConcurrent) {
                return;
            }

            activeReplays.add(replayData.id);
            if (partnerReplay != null) {
                activeReplays.add(partnerReplay.id);
            }
        }

        startPlayback(replayData, origin, () -> {
            if (origin == null) activeReplays.remove(replayData.id);
        });

        if (partnerReplay != null) {
            final DatabaseManager.ReplayData finalPartnerReplay = partnerReplay;
            startPlayback(finalPartnerReplay, origin, () -> {
                if (origin == null) activeReplays.remove(finalPartnerReplay.id);
            });
        }
    }

    private NPC getGhostNPC() {
        NPC npc = npcPool.poll();
        if (npc != null && CitizensAPI.getNPCRegistry().getById(npc.getId()) != null) {
            return npc;
        }
        String ghostName = "Ghost-" + UUID.randomUUID().toString().substring(0, 8);
        return CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, ghostName);
    }

    private void releaseGhostNPC(NPC npc) {
        if (npc != null && CitizensAPI.getNPCRegistry().getById(npc.getId()) != null) {
            npc.despawn();
            npcPool.offer(npc);
        }
    }

    public void shutdown() {
        while (!npcPool.isEmpty()) {
            NPC npc = npcPool.poll();
            if (npc != null) {
                npc.destroy();
                CitizensAPI.getNPCRegistry().deregister(npc);
            }
        }

        for (NPC npc : activeNPCs) {
            if (npc != null) {
                npc.destroy();
                CitizensAPI.getNPCRegistry().deregister(npc);
            }
        }
        activeNPCs.clear();
    }

    private void startPlayback(DatabaseManager.ReplayData replayData, Location origin, Runnable onComplete) {
        List<ReplayFrame> frames = replayData.frames;
        if (frames.isEmpty()) {
            onComplete.run();
            return;
        }

        Location startLoc = origin != null ? origin.clone() : frames.get(0).getLocation();
        if (!startLoc.getChunk().isLoaded()) {
            onComplete.run();
            return;
        }

        org.bukkit.util.Vector offset = origin != null 
            ? origin.toVector().subtract(frames.get(0).getLocation().toVector()) 
            : new org.bukkit.util.Vector(0, 0, 0);

        net.citizensnpcs.api.npc.NPCRegistry registry = CitizensAPI.getNPCRegistry();
        if (registry == null) {
            plugin.getLogger().severe("Citizens NPC Registry is null! Is Citizens enabled correctly?");
            onComplete.run();
            return;
        }

        NPC npc = getGhostNPC();
        activeNPCs.add(npc);
        String ghostName = npc.getName();
        
        try {
            String playerName = Bukkit.getOfflinePlayer(replayData.uuid).getName();
            if (playerName != null) {
                npc.getOrAddTrait(SkinTrait.class).setSkinName(playerName);
            }
        } catch (Exception e) {
        }

        npc.spawn(startLoc);
        npc.setProtected(true);
        
        if (replayData.type == ReplayType.BOSS_KILL) {
            startLoc.getWorld().playSound(startLoc, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        } else if (replayData.type == ReplayType.PVP) {
            startLoc.getWorld().playSound(startLoc, Sound.EVENT_RAID_HORN, 1.0f, 0.8f);
        } else {
            startLoc.getWorld().playSound(startLoc, Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD, 1.0f, 0.8f);
        }
        
        npc.data().set("nameplate-visible", false);

        if (ghostTeam != null) {
            ghostTeam.addEntry(ghostName);
            for (Player p : startLoc.getWorld().getPlayers()) {
                if (p.getLocation().distanceSquared(startLoc) < 2500) {
                    Scoreboard sb = p.getScoreboard();
                    if (sb.equals(Bukkit.getScoreboardManager().getMainScoreboard()) && sb.getEntryTeam(p.getName()) == null) {
                        ghostTeam.addEntry(p.getName());
                    }
                }
            }
        }

        new BukkitRunnable() {
            int frameIndex = 0;
            ItemStack lastEquippedItem = null;
            ItemStack[] lastEquippedArmor = null;

            @Override
            public void run() {
                if (frameIndex >= frames.size() || !npc.isSpawned()) {
                    this.cancel();
                    try {
                        cleanup();
                    } catch (Exception e) {
                        plugin.getLogger().warning("Error during replay cleanup: " + e.getMessage());
                    }
                    return;
                }

                ReplayFrame frame = frames.get(frameIndex);
                Location targetLoc = frame.getLocation().clone().add(offset);
                
                try {
                    if (targetLoc.distanceSquared(npc.getStoredLocation()) > 0.0001) {
                        npc.teleport(targetLoc, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
                    }
                    
                    ItemStack currentItem = frame.getItemInHand();
                    if (!Objects.equals(currentItem, lastEquippedItem)) {
                        if (currentItem != null) {
                            npc.getOrAddTrait(Equipment.class).set(Equipment.EquipmentSlot.HAND, currentItem);
                        } else {
                            npc.getOrAddTrait(Equipment.class).set(Equipment.EquipmentSlot.HAND, new ItemStack(Material.AIR));
                        }
                        lastEquippedItem = currentItem;
                    }

                    if (plugin.getConfig().getBoolean("armor", true) || replayData.type == ReplayType.BOSS_KILL || replayData.type == ReplayType.PVP) {
                        ItemStack[] currentArmor = frame.getArmor();
                        if (!Arrays.equals(currentArmor, lastEquippedArmor)) {
                            if (currentArmor != null && currentArmor.length == 4) {
                                Equipment equipment = npc.getOrAddTrait(Equipment.class);
                                equipment.set(Equipment.EquipmentSlot.BOOTS, currentArmor[0]);
                                equipment.set(Equipment.EquipmentSlot.LEGGINGS, currentArmor[1]);
                                equipment.set(Equipment.EquipmentSlot.CHESTPLATE, currentArmor[2]);
                                equipment.set(Equipment.EquipmentSlot.HELMET, currentArmor[3]);
                            }
                            lastEquippedArmor = currentArmor;
                        }
                    }

                    if (frameIndex % 3 == 0) {
                        World world = targetLoc.getWorld();
                        Location particleLoc = targetLoc.clone().add(0, 1, 0);
                        
                        if (replayData.type == ReplayType.BOSS_KILL) {          
                            world.spawnParticle(Particle.TOTEM_OF_UNDYING, particleLoc, 5, 0.2, 0.5, 0.2, 0.1);
                            world.spawnParticle(Particle.FIREWORK, particleLoc, 3, 0.2, 0.5, 0.2, 0.05);
                            world.spawnParticle(Particle.END_ROD, particleLoc, 1, 0.1, 0.1, 0.1, 0.01);
                        } else if (replayData.type == ReplayType.PVP) {
                            world.spawnParticle(Particle.CRIT, particleLoc, 3, 0.2, 0.5, 0.2, 0.1);
                            world.spawnParticle(Particle.SWEEP_ATTACK, particleLoc, 1, 0.1, 0.1, 0.1, 0);
                        } else {
                            world.spawnParticle(Particle.SOUL_FIRE_FLAME, particleLoc, 3, 0.1, 0.1, 0.1, 0.02);
                            world.spawnParticle(Particle.ASH, particleLoc, 9, 0.2, 0.5, 0.2, 0);
                            
                            Particle.DustOptions dustOptions = new Particle.DustOptions(Color.fromRGB(100, 0, 0), 1.0F);
                            world.spawnParticle(Particle.DUST, particleLoc, 5, 0.2, 0.5, 0.2, 0, dustOptions);
                        }
                    }

                    if (frameIndex % 40 == 0) {
                        if (replayData.type != ReplayType.BOSS_KILL && replayData.type != ReplayType.PVP) {
                            targetLoc.getWorld().playSound(targetLoc, Sound.ENTITY_VEX_AMBIENT, 0.5f, 0.5f);
                        }
                    }

                    if (frameIndex % 4 == 0) {
                        for (Player p : targetLoc.getWorld().getPlayers()) {
                            if (replayData.type == ReplayType.BOSS_KILL || replayData.type == ReplayType.PVP) continue;

                            double distanceSquared = p.getLocation().distanceSquared(targetLoc);
                            if (distanceSquared < 9) {
                                p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 30, 0, false, false, false));
                                
                                if (frameIndex % 20 == 0) {
                                    p.playSound(p.getLocation(), Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, 0.1f, 0.5f);
                                }
                            }
                        }
                    }

                    if (npc.getEntity() instanceof Player) {
                        Player npcPlayer = (Player) npc.getEntity();
                        
                        if (replayData.type == ReplayType.BOSS_KILL || replayData.type == ReplayType.PVP) {
                            if (npcPlayer.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
                                npcPlayer.removePotionEffect(PotionEffectType.INVISIBILITY);
                            }
                            
                            if (!npcPlayer.hasPotionEffect(PotionEffectType.GLOWING)) {
                                npcPlayer.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 60, 0, false, false, false));
                            }
                        } else {
                            if (!npcPlayer.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
                                npcPlayer.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 1, false, false));
                            }
                        }

                        if (frame.getAction() == PlayerAction.SWING_HAND) {
                            npcPlayer.swingMainHand();
                            if (replayData.type == ReplayType.PVP) {
                                targetLoc.getWorld().playSound(targetLoc, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.0f, 1.0f);
                            }
                        }
                        
                        npcPlayer.setSneaking(frame.isSneaking());
                    }

                } catch (Exception e) {
                    plugin.getLogger().warning("Replay stopped due to error: " + e.getMessage());
                    this.cancel();
                    try {
                        cleanup();
                    } catch (Exception ex) {
                        plugin.getLogger().warning("Error during replay cleanup (after error): " + ex.getMessage());
                    }
                    return;
                }

                frameIndex++;
            }

            private void cleanup() {
                onComplete.run();
                if (ghostTeam != null) {
                    ghostTeam.removeEntry(ghostName);
                }
                if (npc.isSpawned()) {
                    Location loc = npc.getStoredLocation();
                    
                    try {
                        if (replayData.type == ReplayType.PVP) {
                            // Epic Anime Explosion
                            safeSpawnParticle(loc.getWorld(), Particle.EXPLOSION_EMITTER, loc, 5, 0, 0, 0, 0);
                            safeSpawnParticle(loc.getWorld(), Particle.FLASH, loc, 2, 0, 0, 0, 0);
                            safeSpawnParticle(loc.getWorld(), Particle.SONIC_BOOM, loc, 1, 0, 0, 0, 0);
                            
                            loc.getWorld().playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 0.5f);
                            loc.getWorld().playSound(loc, Sound.ENTITY_WITHER_BREAK_BLOCK, 1.0f, 0.5f);
                            
                            for (org.bukkit.entity.Entity entity : loc.getWorld().getNearbyEntities(loc, 15, 15, 15)) {
                                if (entity instanceof Player && !entity.getUniqueId().equals(npc.getUniqueId()) && !CitizensAPI.getNPCRegistry().isNPC(entity)) {
                                    org.bukkit.util.Vector direction = entity.getLocation().toVector().subtract(loc.toVector());
                                    if (direction.lengthSquared() == 0) {
                                        direction = new org.bukkit.util.Vector(0, 1, 0);
                                    } else {
                                        direction.normalize();
                                    }
                                    entity.setVelocity(direction.multiply(2.5).setY(0.8));
                                }
                            }
                        } else {
                            safeSpawnParticle(loc.getWorld(), Particle.SOUL, loc.clone().add(0, 1, 0), 10, 0.5, 0.5, 0.5, 0.1);
                            safeSpawnParticle(loc.getWorld(), Particle.SCULK_SOUL, loc, 15, 0.2, 0.5, 0.2, 0.05);
                            
                            loc.getWorld().playSound(loc, Sound.ENTITY_ZOMBIE_VILLAGER_CONVERTED, 1.0f, 0.6f);

                            for (org.bukkit.entity.Entity entity : loc.getWorld().getNearbyEntities(loc, 5, 5, 5)) {
                                if (entity instanceof Player && !entity.getUniqueId().equals(npc.getUniqueId()) && !CitizensAPI.getNPCRegistry().isNPC(entity)) {
                                    org.bukkit.util.Vector direction = entity.getLocation().toVector().subtract(loc.toVector());
                                    if (direction.lengthSquared() == 0) {
                                        direction = new org.bukkit.util.Vector(0, 0.5, 0);
                                    } else {
                                        direction.normalize();
                                    }
                                    entity.setVelocity(direction.multiply(1.5).setY(0.5));
                                }
                            }
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning("Error playing cleanup effects: " + e.getMessage());
                    }

                    activeNPCs.remove(npc);
                    releaseGhostNPC(npc);
                } else {
                    activeNPCs.remove(npc);
                    releaseGhostNPC(npc);
                }
            }

            private void safeSpawnParticle(World world, Particle particle, Location loc, int count, double x, double y, double z, double speed) {
                try {
                    world.spawnParticle(particle, loc, count, x, y, z, speed);
                } catch (Exception ignored) {
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }
}
