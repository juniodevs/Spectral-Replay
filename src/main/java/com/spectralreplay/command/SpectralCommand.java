package com.spectralreplay.command;

import com.spectralreplay.database.DatabaseManager;
import com.spectralreplay.manager.ReplayManager;
import com.spectralreplay.SpectralReplay;
import org.bukkit.Location;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class SpectralCommand implements CommandExecutor {

    private final SpectralReplay plugin;
    private final ReplayManager replayManager;
    private final DatabaseManager databaseManager;

    public SpectralCommand(SpectralReplay plugin, ReplayManager replayManager, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.replayManager = replayManager;
        this.databaseManager = databaseManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Only players can use this command.");
                return true;
            }

            Player player = (Player) sender;
            if (!player.isOp() && !player.hasPermission("spectralreplay.admin")) {
                player.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
                return true;
            }

            if (args.length > 0) {
                if (args[0].equalsIgnoreCase("play")) {
                    double radius = 20.0;
                    Location loc = player.getLocation();
                    
                    new org.bukkit.scheduler.BukkitRunnable() {
                        @Override
                        public void run() {
                            try {
                                List<DatabaseManager.ReplayData> replays = databaseManager.getNearbyReplays(loc, radius, null);
                                
                                new org.bukkit.scheduler.BukkitRunnable() {
                                    @Override
                                    public void run() {
                                        if (replays.isEmpty()) {
                                            player.sendMessage(ChatColor.YELLOW + "No replays found within " + radius + " blocks.");
                                            return;
                                        }

                                        DatabaseManager.ReplayData replay = replays.get(ThreadLocalRandom.current().nextInt(replays.size()));
                                        replayManager.playGhostReplay(replay);
                                        
                                        player.sendMessage(ChatColor.GREEN + "Playing a random ghost replay from " + replays.size() + " found nearby.");
                                    }
                                }.runTask(plugin);
                            } catch (Exception e) {
                                player.sendMessage(ChatColor.RED + "Error fetching replays: " + e.getMessage());
                                plugin.getLogger().warning("Error in /spectral play: " + e.getMessage());
                            }
                        }
                    }.runTaskAsynchronously(plugin);
                    return true;
                }
                
                if (args[0].equalsIgnoreCase("list")) {
                    new org.bukkit.scheduler.BukkitRunnable() {
                        @Override
                        public void run() {
                            try {
                                List<DatabaseManager.ReplayData> recent = databaseManager.getRecentReplays(10);
                                player.sendMessage(ChatColor.GOLD + "--- Recent Replays ---");
                                for (DatabaseManager.ReplayData data : recent) {
                                    String playerName = org.bukkit.Bukkit.getOfflinePlayer(data.uuid).getName();
                                    if (playerName == null) playerName = "Unknown";
                                    
                                    player.sendMessage(ChatColor.YELLOW + "ID: " + data.id + " | Player: " + playerName + " | Type: " + data.type + " | Loc: " + 
                                        String.format("%.0f, %.0f, %.0f", data.location.getX(), data.location.getY(), data.location.getZ()));
                                }
                            } catch (Exception e) {
                                player.sendMessage(ChatColor.RED + "Error listing replays: " + e.getMessage());
                                plugin.getLogger().warning("Error in /spectral list: " + e.getMessage());
                            }
                        }
                    }.runTaskAsynchronously(plugin);
                    return true;
                }

                if (args[0].equalsIgnoreCase("place")) {
                    if (args.length < 2) {
                        player.sendMessage(ChatColor.RED + "Usage: /spectral place <id>");
                        return true;
                    }
                    try {
                        int id = Integer.parseInt(args[1]);
                        Location loc = player.getLocation();
                        
                        new org.bukkit.scheduler.BukkitRunnable() {
                            @Override
                            public void run() {
                                try {
                                    DatabaseManager.ReplayData data = databaseManager.getReplayById(id);
                                    if (data == null) {
                                        player.sendMessage(ChatColor.RED + "Replay not found.");
                                        return;
                                    }
                                    
                                    int placedId = databaseManager.savePlacedReplay(id, loc);
                                    
                                    new org.bukkit.scheduler.BukkitRunnable() {
                                        @Override
                                        public void run() {
                                            if (placedId != -1) {
                                                replayManager.startPlacedReplayTask(new DatabaseManager.PlacedReplay(placedId, id, loc));
                                                player.sendMessage(ChatColor.GREEN + "Replay placed at your location! Placed ID: " + placedId);
                                            } else {
                                                player.sendMessage(ChatColor.RED + "Failed to save placed replay.");
                                            }
                                        }
                                    }.runTask(plugin);
                                } catch (Exception e) {
                                    player.sendMessage(ChatColor.RED + "Error placing replay: " + e.getMessage());
                                    plugin.getLogger().warning("Error in /spectral place: " + e.getMessage());
                                }
                            }
                        }.runTaskAsynchronously(plugin);
                    } catch (NumberFormatException e) {
                        player.sendMessage(ChatColor.RED + "Invalid ID format.");
                        return true;
                    }
                    return true;
                }
            
            if (args[0].equalsIgnoreCase("list-placed")) {
                new org.bukkit.scheduler.BukkitRunnable() {
                    @Override
                    public void run() {
                        try {
                            List<DatabaseManager.PlacedReplay> placed = databaseManager.getAllPlacedReplays();
                            player.sendMessage(ChatColor.GOLD + "--- Placed Replays ---");
                            for (DatabaseManager.PlacedReplay p : placed) {
                                player.sendMessage(ChatColor.YELLOW + "ID: " + p.id + " | ReplayID: " + p.replayId + " | Loc: " + 
                                    String.format("%.0f, %.0f, %.0f", p.location.getX(), p.location.getY(), p.location.getZ()));
                            }
                        } catch (Exception e) {
                            player.sendMessage(ChatColor.RED + "Error listing placed replays: " + e.getMessage());
                            plugin.getLogger().warning("Error in /spectral list-placed: " + e.getMessage());
                        }
                    }
                }.runTaskAsynchronously(plugin);
                return true;
            }

            if (args[0].equalsIgnoreCase("remove")) {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Usage: /spectral remove <placed_id>");
                    return true;
                }
                try {
                    int id = Integer.parseInt(args[1]);
                    if (replayManager.removePlacedReplay(id)) {
                        player.sendMessage(ChatColor.GREEN + "Placed replay removed.");
                    } else {
                        player.sendMessage(ChatColor.RED + "Placed replay not found or could not be removed.");
                    }
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "Invalid ID.");
                } catch (Exception e) {
                    player.sendMessage(ChatColor.RED + "Error removing replay: " + e.getMessage());
                    plugin.getLogger().warning("Error in /spectral remove: " + e.getMessage());
                }
                return true;
            }

            if (args[0].equalsIgnoreCase("delete")) {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Usage: /spectral delete <replay_id>");
                    return true;
                }
                try {
                    int id = Integer.parseInt(args[1]);
                    if (databaseManager.deleteReplay(id)) {
                        player.sendMessage(ChatColor.GREEN + "Replay deleted from database.");
                    } else {
                        player.sendMessage(ChatColor.RED + "Replay not found or could not be deleted.");
                    }
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "Invalid ID.");
                } catch (Exception e) {
                    player.sendMessage(ChatColor.RED + "Error deleting replay: " + e.getMessage());
                    plugin.getLogger().warning("Error in /spectral delete: " + e.getMessage());
                }
                return true;
            }

            if (args[0].equalsIgnoreCase("reset-cooldowns")) {
                replayManager.resetCooldowns();
                player.sendMessage(ChatColor.GREEN + "All replay cooldowns have been reset.");
                return true;
            }
        }

        player.sendMessage(ChatColor.RED + "Invalid usage. Try:");
        player.sendMessage(ChatColor.WHITE + "/spectral play " + ChatColor.GRAY + "- Play a random nearby replay");
        player.sendMessage(ChatColor.WHITE + "/spectral list " + ChatColor.GRAY + "- List recent replays");
        player.sendMessage(ChatColor.WHITE + "/spectral place <id> " + ChatColor.GRAY + "- Place a replay permanently");
        player.sendMessage(ChatColor.WHITE + "/spectral list-placed " + ChatColor.GRAY + "- List all placed replays");
        player.sendMessage(ChatColor.WHITE + "/spectral remove <id> " + ChatColor.GRAY + "- Remove a placed replay");
        player.sendMessage(ChatColor.WHITE + "/spectral delete <id> " + ChatColor.GRAY + "- Delete a replay from database");
        player.sendMessage(ChatColor.WHITE + "/spectral reset-cooldowns " + ChatColor.GRAY + "- Reset all replay cooldowns");
        return true;
        
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "An internal error occurred while executing the command.");
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Error executing command", e);
        }
        return false;
    }
}
