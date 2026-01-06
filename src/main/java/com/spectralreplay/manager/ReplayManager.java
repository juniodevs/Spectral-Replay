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
    private final Map<UUID, Deque<ReplayFrame>> recordings = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerAction> currentActions = new ConcurrentHashMap<>();
    private final Set<Integer> activeReplays = ConcurrentHashMap.newKeySet();
    private final Map<Integer, org.bukkit.scheduler.BukkitTask> placedReplayTasks = new ConcurrentHashMap<>();
    private final Map<Integer, Long> proximityCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> respawnProtections = new ConcurrentHashMap<>();
    private final Queue<NPC> npcPool = new ConcurrentLinkedQueue<>();
    private final Set<NPC> activeNPCs = ConcurrentHashMap.newKeySet();
    private long globalReplayCooldownUntil = 0;
    
    private static final int MAX_FRAMES = 200;
    private static final int GAME_TIME_NIGHT_START = 13000;
    private static final int GAME_TIME_NIGHT_END = 23000;

    public ReplayManager(SpectralReplay plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
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

    private Team getOrRegisterGhostTeam() {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = scoreboard.getTeam("spectral_ghosts");
        if (team == null) {
            try {
                team = scoreboard.registerNewTeam("spectral_ghosts");
                team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
                team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
                team.setColor(ChatColor.GRAY);
            } catch (IllegalArgumentException e) {
                team = scoreboard.getTeam("spectral_ghosts");
            }
        }
        return team;
    }

    public void addRespawnProtection(Player player) {
        long duration = plugin.getConfig().getLong("respawn-protection-duration", 10) * 1000;
        respawnProtections.put(player.getUniqueId(), System.currentTimeMillis() + duration);
    }

    public void startRecording() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    try {
                        recordFrame(player);
                    } catch (Exception e) {
                        plugin.getLogger().warning("Error recording frame for player " + player.getName() + ": " + e.getMessage());
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    public void startRandomReplayTask() {
        scheduleNextReplay(ReplayType.DEATH);
        loadPlacedReplays();
        startProximityCheckTask();
    }

    public void resetCooldowns() {
        proximityCooldowns.clear();
        globalReplayCooldownUntil = 0;
    }

    private void startProximityCheckTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    if (!plugin.getConfig().getBoolean("proximity-replay.enabled", true)) return;

                    double radius = plugin.getConfig().getDouble("proximity-replay.radius", 5.0);
                    long cooldownSeconds = plugin.getConfig().getLong("proximity-replay.cooldown", 600);
                    long cooldownMillis = cooldownSeconds * 1000;
                    
                    List<PlayerContext> candidates = new ArrayList<>();

                    for (Player player : Bukkit.getOnlinePlayers()) {
                         if (player.getGameMode() == GameMode.SPECTATOR) continue;

                         if (respawnProtections.containsKey(player.getUniqueId())) {
                             if (System.currentTimeMillis() < respawnProtections.get(player.getUniqueId())) {
                                 continue;
                             } else {
                                 respawnProtections.remove(player.getUniqueId());
                             }
                         }
                         
                         candidates.add(new PlayerContext(player.getUniqueId(), player.getLocation(), player.getWorld().getTime()));
                    }

                    if (candidates.isEmpty()) return;

                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            for (PlayerContext ctx : candidates) {
                                processProximityForPlayer(ctx, radius, cooldownMillis);
                            }
                        }
                    }.runTaskAsynchronously(plugin);
                } catch (Exception e) {
                    plugin.getLogger().warning("Error in proximity check loop: " + e.getMessage());
                }
            }
        }.runTaskTimer(plugin, 100L, 20L);
    }
    
    private void processProximityForPlayer(PlayerContext ctx, double radius, long cooldownMillis) {
        try {
            List<DatabaseManager.ReplayData> nearbyReplays = databaseManager.getNearbyReplayMeta(ctx.location, radius, null);
            int maxPlays = plugin.getConfig().getInt("max-plays-per-replay", 5);
            
            for (DatabaseManager.ReplayData replay : nearbyReplays) {
                if (replay.type != ReplayType.DEATH && replay.type != ReplayType.PVP) continue;
                
                if (replay.type == ReplayType.DEATH && (ctx.worldTime < GAME_TIME_NIGHT_START || ctx.worldTime > GAME_TIME_NIGHT_END)) continue;
                
                if (maxPlays != -1 && replay.playCount >= maxPlays) continue;
                
                if (activeReplays.contains(replay.id)) continue;

                long lastPlayed = proximityCooldowns.getOrDefault(replay.id, 0L);

                if (System.currentTimeMillis() - lastPlayed > cooldownMillis) {
                    playGhostReplay(replay);
                    proximityCooldowns.put(replay.id, System.currentTimeMillis());
                    
                    if (replay.type == ReplayType.PVP) {
                        try {
                            List<DatabaseManager.ReplayData> partners = databaseManager.getReplaysByTimestamp(replay.timestamp);
                            for (DatabaseManager.ReplayData partner : partners) {
                                proximityCooldowns.put(partner.id, System.currentTimeMillis());
                            }
                        } catch (Exception e) {
                            plugin.getLogger().warning("Failed to cooldown partner replays: " + e.getMessage());
                        }
                    }
                    
                    break; 
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error in key proximity check task: " + e.getMessage());
        }
    }

    private static class PlayerContext {
        final UUID uuid;
        final Location location;
        final long worldTime;
        
        PlayerContext(UUID uuid, Location location, long worldTime) {
            this.uuid = uuid;
            this.location = location;
            this.worldTime = worldTime;
        }
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
                        try {
                            DatabaseManager.ReplayData data = databaseManager.getReplayById(placed.replayId);
                            if (data != null) {
                                new BukkitRunnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            playGhostReplay(data, placed.location);
                                        } catch (Exception e) {
                                            plugin.getLogger().warning("Error playing placed replay: " + e.getMessage());
                                        }
                                    }
                                }.runTask(plugin);
                            }
                        } catch (Exception e) {
                            plugin.getLogger().warning("Error fetching placed replay data: " + e.getMessage());
                        }
                    }
                }.runTaskAsynchronously(plugin);
            }
        }.runTaskTimer(plugin, 100L, 600L);
        
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
        
        minDelay = plugin.getConfig().getLong("min-delay", 1200L);
        maxDelay = plugin.getConfig().getLong("max-delay", 3600L);
        
        long delay = ThreadLocalRandom.current().nextLong(minDelay, maxDelay + 1);

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    if (System.currentTimeMillis() >= globalReplayCooldownUntil) {
                        attemptReplay(type);
                    }
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
                try {
                    for (Map.Entry<Player, Location> entry : candidates.entrySet()) {
                        if (ThreadLocalRandom.current().nextDouble() > 0.3) continue;

                        Location loc = entry.getValue();
                        List<DatabaseManager.ReplayData> nearbyReplays = databaseManager.getNearbyReplayMeta(loc, 20.0, type);

                        if (nearbyReplays.isEmpty()) continue;
                        
                        int maxPlays = plugin.getConfig().getInt("max-plays-per-replay", 5);

                        List<DatabaseManager.ReplayData> availableReplays = new ArrayList<>();
                        for (DatabaseManager.ReplayData r : nearbyReplays) {
                            if (activeReplays.contains(r.id)) continue;
                            if (maxPlays != -1 && r.playCount >= maxPlays) continue;
                            availableReplays.add(r);
                        }

                        if (availableReplays.isEmpty()) continue;

                        DatabaseManager.ReplayData replay = availableReplays.get(ThreadLocalRandom.current().nextInt(availableReplays.size()));
                        
                        playGhostReplay(replay);
                        return;
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Error in attemptReplay async task: " + e.getMessage());
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    private void recordFrame(Player player) {
        try {
            UUID uuid = player.getUniqueId();
            Deque<ReplayFrame> buffer = recordings.computeIfAbsent(uuid, k -> new ArrayDeque<>(MAX_FRAMES));

            Location loc = player.getLocation();
            PlayerAction action = currentActions.getOrDefault(uuid, PlayerAction.NONE);
            boolean isSneaking = player.isSneaking();
            
            ItemStack currentItem = player.getInventory().getItemInMainHand();
            ItemStack[] currentArmor = player.getInventory().getArmorContents();
            
            ReplayFrame lastFrame = buffer.peekLast();
            
            ItemStack savedItem = null;
            if (currentItem != null && currentItem.getType() != Material.AIR) {
                if (lastFrame != null && isSimilar(lastFrame.getItemInHand(), currentItem)) {
                    savedItem = lastFrame.getItemInHand();
                } else {
                    savedItem = currentItem.clone();
                }
            }
            
            ItemStack[] savedArmor = null;
            if (currentArmor != null) {
                boolean sameArmor = false;
                if (lastFrame != null && lastFrame.getArmor() != null && lastFrame.getArmor().length == currentArmor.length) {
                    sameArmor = true;
                    for (int i = 0; i < currentArmor.length; i++) {
                        ItemStack c = currentArmor[i];
                        ItemStack l = lastFrame.getArmor()[i];
                        if ((c == null && l != null) || (c != null && l == null) || (c != null && !isSimilar(c, l))) {
                            sameArmor = false;
                            break;
                        }
                    }
                }
                
                if (sameArmor) {
                    savedArmor = lastFrame.getArmor();
                } else {
                    savedArmor = new ItemStack[currentArmor.length];
                    for (int i = 0; i < currentArmor.length; i++) {
                        savedArmor[i] = currentArmor[i] != null ? currentArmor[i].clone() : null;
                    }
                }
            }

            ReplayFrame frame = new ReplayFrame(loc, action, isSneaking, savedItem, savedArmor);

            buffer.addLast(frame);

            if (buffer.size() > MAX_FRAMES) {
                buffer.removeFirst();
            }

            currentActions.put(uuid, PlayerAction.NONE);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to record frame for " + player.getName() + ": " + e.getMessage());
        }
    }

    private boolean isSimilar(ItemStack item1, ItemStack item2) {
        if (item1 == null || item2 == null) return false;
        if (item1.getType() != item2.getType()) return false;
        if (item1.getAmount() != item2.getAmount()) return false;
        if (item1.hasItemMeta() != item2.hasItemMeta()) return false;
        return item1.isSimilar(item2);
    }

    public void setPlayerAction(Player player, PlayerAction action) {
        if (action == PlayerAction.SWING_HAND) {
            PlayerAction current = currentActions.get(player.getUniqueId());
            if (current == PlayerAction.ATTACK) {
                return;
            }
        }
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
                    int id = databaseManager.saveReplay(player.getUniqueId(), player.getLocation(), frames, type, timestamp);
                    if (id != -1) {
                        proximityCooldowns.put(id, System.currentTimeMillis());
                    }
                }
            }.runTaskAsynchronously(plugin);
        } else {
            plugin.getLogger().warning("Skipping replay save for " + player.getName() + " (Type: " + type + ") - No frames recorded.");
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
                    try {
                        List<ReplayFrame> frames = replayData.frames;
                        if (frames == null) {
                            frames = databaseManager.getReplayFrames(replayData.id);
                        }
                        
                        if (frames.isEmpty()) return;

                        DatabaseManager.ReplayData fullData = new DatabaseManager.ReplayData(
                                replayData.id, replayData.uuid, replayData.location, frames, replayData.type, replayData.timestamp, replayData.playCount
                        );

                        DatabaseManager.ReplayData partnerData = null;
                        if (fullData.type == ReplayType.PVP && origin == null) {
                             List<DatabaseManager.ReplayData> partners = databaseManager.getReplaysByTimestamp(fullData.timestamp);
                             for (DatabaseManager.ReplayData r : partners) {
                                 if (r.id != fullData.id && !r.uuid.equals(fullData.uuid)) {
                                     partnerData = r;
                                     break;
                                 }
                             }
                             if (partnerData == null) {
                                 plugin.getLogger().warning("Could not find partner replay for PVP replay ID: " + fullData.id + " (Timestamp: " + fullData.timestamp + ")");
                             }
                        }
                        
                        final DatabaseManager.ReplayData finalPartner = partnerData;

                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                try {
                                    playGhostReplayInternal(fullData, origin, finalPartner);
                                } catch (Exception e) {
                                    plugin.getLogger().warning("Error in playGhostReplayInternal: " + e.getMessage());
                                }
                            }
                        }.runTask(plugin);
                    } catch (Exception e) {
                        plugin.getLogger().warning("Error preparing ghost replay: " + e.getMessage());
                    }
                }
            }.runTaskAsynchronously(plugin);
            return;
        }
        
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    playGhostReplayInternal(replayData, origin, null);
                } catch (Exception e) {
                    plugin.getLogger().warning("Error in playGhostReplayInternal (sync): " + e.getMessage());
                }
            }
        }.runTask(plugin);
    }

    private void playGhostReplayInternal(DatabaseManager.ReplayData replayData, Location origin, DatabaseManager.ReplayData preloadedPartner) {
        if (origin == null && activeReplays.contains(replayData.id)) return;
        
        if (origin == null && System.currentTimeMillis() < globalReplayCooldownUntil) {
            return;
        }

        DatabaseManager.ReplayData partnerReplay = preloadedPartner;
        
        if (replayData.type == ReplayType.PVP && partnerReplay == null && origin == null) {
        }

        if (partnerReplay != null && origin == null && activeReplays.contains(partnerReplay.id)) {
            return;
        }

        if (origin == null) {
            int maxConcurrent = plugin.getConfig().getInt("max-concurrent-replays", 5);
            if (maxConcurrent < 2) maxConcurrent = 2;

            int needed = 1;
            if (partnerReplay != null) needed = 2;
            
            if (activeReplays.size() + needed > maxConcurrent) {
                return;
            }

            long minDelay = plugin.getConfig().getLong("min-delay", 1200L);
            long maxDelay = plugin.getConfig().getLong("max-delay", 3600L);
            long delayMillis = ThreadLocalRandom.current().nextLong(minDelay, maxDelay + 1) * 50;
            globalReplayCooldownUntil = System.currentTimeMillis() + delayMillis;

            activeReplays.add(replayData.id);
            if (partnerReplay != null) {
                activeReplays.add(partnerReplay.id);
            }
            
            new BukkitRunnable() {
                @Override
                public void run() {
                    databaseManager.incrementPlayCount(replayData.id);
                    if (partnerReplay != null) {
                        databaseManager.incrementPlayCount(partnerReplay.id);
                    }
                }
            }.runTaskAsynchronously(plugin);
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

        Location startLoc = origin != null ? origin.clone() : null;
        if (startLoc == null) {
            Location frameLoc = frames.get(0).getLocation();
            if (frameLoc == null) {
                onComplete.run();
                return;
            }
            startLoc = frameLoc;
        }

        if (!startLoc.getChunk().isLoaded()) {
            onComplete.run();
            return;
        }

        org.bukkit.util.Vector offset = origin != null 
            ? origin.toVector().subtract(new org.bukkit.util.Vector(frames.get(0).getX(), frames.get(0).getY(), frames.get(0).getZ())) 
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
        
        if (replayData.type == ReplayType.PVP) {
            startLoc.getWorld().playSound(startLoc, Sound.EVENT_RAID_HORN, 2.0f, 0.8f);
            startLoc.getWorld().playSound(startLoc, Sound.ENTITY_WITHER_SPAWN, 0.5f, 0.5f);
        } else {
            startLoc.getWorld().playSound(startLoc, Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD, 1.0f, 0.8f);
            startLoc.getWorld().playSound(startLoc, Sound.ENTITY_ENDERMAN_SCREAM, 1.0f, 0.5f);
            startLoc.getWorld().playSound(startLoc, Sound.ENTITY_GHAST_SCREAM, 0.5f, 0.1f);
        }
        
        npc.data().set("nameplate-visible", false);

        Team team = getOrRegisterGhostTeam();
        if (team != null) {
            team.addEntry(ghostName);
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
                Location frameLoc = frame.getLocation();
                if (frameLoc == null) {
                    this.cancel();
                    cleanup();
                    return;
                }
                Location targetLoc = frameLoc.clone().add(offset);
                
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

                    boolean showArmor = false;
                    if (replayData.type == ReplayType.PVP) {
                        showArmor = plugin.getConfig().getBoolean("armor-pvp", true);
                    } else {
                        showArmor = plugin.getConfig().getBoolean("armor-death", false);
                    }

                    if (showArmor) {
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
                    } else {
                        if (lastEquippedArmor != null || frameIndex == 0) {
                            Equipment equipment = npc.getOrAddTrait(Equipment.class);
                            equipment.set(Equipment.EquipmentSlot.BOOTS, new ItemStack(Material.AIR));
                            equipment.set(Equipment.EquipmentS  lot.LEGGINGS, new ItemStack(Material.AIR));
                            equipment.set(Equipment.EquipmentSlot.CHESTPLATE, new ItemStack(Material.AIR));
                            equipment.set(Equipment.EquipmentSlot.HELMET, new ItemStack(Material.AIR));
                            lastEquippedArmor = null;
                        }
                    }

                    if (frameIndex % 3 == 0) {
                        World world = targetLoc.getWorld();
                        if (world != null) {
                            Location particleLoc = targetLoc.clone().add(0, 1, 0);
                            
                            if (replayData.type == ReplayType.PVP) {                                

                                world.spawnParticle(Particle.SCULK_SOUL, particleLoc, 2, 0.3, 0.5, 0.3, 0.02);
                                
                                double maxRadius = 1.2;
                                double height = 2.2;
                                
                                double y = (frameIndex % 30) / 30.0 * height; 
                                double r = maxRadius * (1 - (y / height));
                                double time = frameIndex * 0.3;
                                
                                double x1 = r * Math.cos(time + y * 4);
                                double z1 = r * Math.sin(time + y * 4);
                                world.spawnParticle(Particle.SCULK_SOUL, targetLoc.clone().add(x1, y, z1), 1, 0, 0, 0, 0);
                                
                                double x2 = r * Math.cos(time + y * 4 + Math.PI);
                                double z2 = r * Math.sin(time + y * 4 + Math.PI);
                                world.spawnParticle(Particle.SCULK_SOUL, targetLoc.clone().add(x2, y, z2), 1, 0, 0, 0, 0);

                            } else {
                                world.spawnParticle(Particle.SCULK_SOUL, particleLoc, 3, 0.2, 0.5, 0.2, 0.02);
                            }
                        }
                    }

                    if (frameIndex % 40 == 0) {
                        if (replayData.type != ReplayType.PVP) {
                            targetLoc.getWorld().playSound(targetLoc, Sound.ENTITY_VEX_AMBIENT, 0.5f, 0.5f);
                        }
                    }

                    if (frameIndex % 4 == 0) {
                        for (Player p : targetLoc.getWorld().getPlayers()) {
                            if (replayData.type == ReplayType.PVP) continue;

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
                        
                        if (replayData.type == ReplayType.PVP) {
                            if (!npcPlayer.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
                                npcPlayer.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 1, false, false));
                            }
                            if (npcPlayer.hasPotionEffect(PotionEffectType.GLOWING)) {
                                npcPlayer.removePotionEffect(PotionEffectType.GLOWING);
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
                        } else if (frame.getAction() == PlayerAction.ATTACK) {
                            npcPlayer.swingMainHand();
                            if (replayData.type == ReplayType.PVP) {
                                targetLoc.getWorld().playSound(targetLoc, Sound.ENTITY_VEX_HURT, 1.0f, 0.6f);
                                targetLoc.getWorld().playSound(targetLoc, Sound.ENTITY_PHANTOM_HURT, 0.5f, 0.8f);
                                targetLoc.getWorld().playSound(targetLoc, Sound.ENTITY_PLAYER_HURT, 1.0f, 0.5f);
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
                Team team = getOrRegisterGhostTeam();
                if (team != null) {
                    team.removeEntry(ghostName);
                }
                if (npc.isSpawned()) {
                    Location loc = npc.getStoredLocation();
                    
                    try {
                        if (replayData.type == ReplayType.PVP) {
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
