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
import java.util.concurrent.ThreadLocalRandom;

public class ReplayManager {

    private final SpectralReplay plugin;
    private final DatabaseManager databaseManager;
    private Team ghostTeam;

    private final Map<UUID, Deque<ReplayFrame>> recordings = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerAction> currentActions = new ConcurrentHashMap<>();
    private final Set<Integer> activeReplays = ConcurrentHashMap.newKeySet();
    
    private static final int MAX_FRAMES = 200;

    public ReplayManager(SpectralReplay plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        setupGhostTeam();
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
        Collections.shuffle(players);

        for (Player player : players) {
            long time = player.getWorld().getTime();
            if (time < 13000 || time > 23000) continue;

            if (ThreadLocalRandom.current().nextDouble() > 0.3) continue;

            List<DatabaseManager.ReplayData> nearbyReplays = databaseManager.getNearbyReplays(player.getLocation(), 20.0, type);
            if (nearbyReplays.isEmpty()) continue;

            List<DatabaseManager.ReplayData> availableReplays = new ArrayList<>();
            for (DatabaseManager.ReplayData r : nearbyReplays) {
                if (!activeReplays.contains(r.id)) {
                    availableReplays.add(r);
                }
            }

            if (availableReplays.isEmpty()) continue;

            DatabaseManager.ReplayData replay = availableReplays.get(ThreadLocalRandom.current().nextInt(availableReplays.size()));
            
            playGhostReplay(replay);
            return;
        }
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

    public void saveDeathReplay(Player player, ReplayType type) {
        List<ReplayFrame> frames = getSnapshot(player);
        if (!frames.isEmpty()) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    databaseManager.saveReplay(player.getUniqueId(), player.getLocation(), frames, type);
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
        if (activeReplays.contains(replayData.id)) return;
        activeReplays.add(replayData.id);

        List<ReplayFrame> frames = replayData.frames;
        if (frames.isEmpty()) {
            activeReplays.remove(replayData.id);
            return;
        }

        Location startLoc = frames.get(0).getLocation();
        if (!startLoc.getChunk().isLoaded()) {
            activeReplays.remove(replayData.id);
            return;
        }

        String ghostName = "Ghost-" + UUID.randomUUID().toString().substring(0, 8);
        
        net.citizensnpcs.api.npc.NPCRegistry registry = CitizensAPI.getNPCRegistry();
        if (registry == null) {
            plugin.getLogger().severe("Citizens NPC Registry is null! Is Citizens enabled correctly?");
            activeReplays.remove(replayData.id);
            return;
        }

        NPC npc = registry.createNPC(EntityType.PLAYER, ghostName);
        
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
                    cleanup();
                    this.cancel();
                    return;
                }

                ReplayFrame frame = frames.get(frameIndex);
                
                try {
                    if (frame.getLocation().distanceSquared(npc.getStoredLocation()) > 0.0001) {
                        npc.teleport(frame.getLocation(), org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
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

                    if (plugin.getConfig().getBoolean("armor", true) || replayData.type == ReplayType.BOSS_KILL) {
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
                        World world = frame.getLocation().getWorld();
                        Location particleLoc = frame.getLocation().add(0, 1, 0);
                        
                        if (replayData.type == ReplayType.BOSS_KILL) {          
                            world.spawnParticle(Particle.TOTEM_OF_UNDYING, particleLoc, 5, 0.2, 0.5, 0.2, 0.1);
                            world.spawnParticle(Particle.FIREWORK, particleLoc, 3, 0.2, 0.5, 0.2, 0.05);
                            world.spawnParticle(Particle.END_ROD, particleLoc, 1, 0.1, 0.1, 0.1, 0.01);
                        } else {
                            world.spawnParticle(Particle.SOUL_FIRE_FLAME, particleLoc, 3, 0.1, 0.1, 0.1, 0.02);
                            world.spawnParticle(Particle.ASH, particleLoc, 9, 0.2, 0.5, 0.2, 0);
                            
                            Particle.DustOptions dustOptions = new Particle.DustOptions(Color.fromRGB(100, 0, 0), 1.0F);
                            world.spawnParticle(Particle.DUST, particleLoc, 5, 0.2, 0.5, 0.2, 0, dustOptions);
                        }
                    }

                    if (frameIndex % 40 == 0) {
                        if (replayData.type != ReplayType.BOSS_KILL) {
                            frame.getLocation().getWorld().playSound(frame.getLocation(), Sound.ENTITY_VEX_AMBIENT, 0.5f, 0.5f);
                        }
                    }

                    if (frameIndex % 10 == 0) {
                        for (Player p : frame.getLocation().getWorld().getPlayers()) {
                            double distanceSquared = p.getLocation().distanceSquared(frame.getLocation());
                            if (distanceSquared < 36) {
                                if (replayData.type == ReplayType.BOSS_KILL) {
                                } else {
                                    p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, false, false, false));
                                    
                                    if (frameIndex % 20 == 0) {
                                        p.playSound(p.getLocation(), Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, 0.1f, 0.5f);
                                    }
                                }
                            }
                        }
                    }

                    if (npc.getEntity() instanceof Player) {
                        Player npcPlayer = (Player) npc.getEntity();
                        
                        if (replayData.type == ReplayType.BOSS_KILL) {
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
                        }
                        
                        npcPlayer.setSneaking(frame.isSneaking());
                    }

                } catch (Exception e) {
                    plugin.getLogger().warning("Replay stopped due to error: " + e.getMessage());
                    cleanup();
                    this.cancel();
                    return;
                }

                frameIndex++;
            }

            private void cleanup() {
                activeReplays.remove(replayData.id);
                if (ghostTeam != null) {
                    ghostTeam.removeEntry(ghostName);
                }
                if (npc.isSpawned()) {
                    Location loc = npc.getStoredLocation();
                    loc.getWorld().spawnParticle(Particle.SOUL, loc.add(0, 1, 0), 10, 0.5, 0.5, 0.5, 0.1);
                    loc.getWorld().spawnParticle(Particle.SCULK_SOUL, loc, 15, 0.2, 0.5, 0.2, 0.05);
                    loc.getWorld().playSound(loc, Sound.ENTITY_ZOMBIE_VILLAGER_CONVERTED, 1.0f, 0.6f);

                    for (org.bukkit.entity.Entity entity : loc.getWorld().getNearbyEntities(loc, 5, 5, 5)) {
                        if (entity instanceof Player) {
                            org.bukkit.util.Vector direction = entity.getLocation().toVector().subtract(loc.toVector());
                            if (direction.lengthSquared() == 0) {
                                direction = new org.bukkit.util.Vector(0, 0.5, 0);
                            } else {
                                direction.normalize();
                            }
                            entity.setVelocity(direction.multiply(1.5).setY(0.5));
                        }
                    }

                    npc.destroy();
                }
                CitizensAPI.getNPCRegistry().deregister(npc);
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }
}
