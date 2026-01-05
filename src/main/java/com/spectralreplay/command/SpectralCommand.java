package com.spectralreplay.command;

import com.spectralreplay.database.DatabaseManager;
import com.spectralreplay.manager.ReplayManager;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class SpectralCommand implements CommandExecutor {

    private final ReplayManager replayManager;
    private final DatabaseManager databaseManager;

    public SpectralCommand(ReplayManager replayManager, DatabaseManager databaseManager) {
        this.replayManager = replayManager;
        this.databaseManager = databaseManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
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
                List<DatabaseManager.ReplayData> replays = databaseManager.getNearbyReplays(player.getLocation(), radius, null);
                
                if (replays.isEmpty()) {
                    player.sendMessage(ChatColor.YELLOW + "No replays found within " + radius + " blocks.");
                    return true;
                }

                DatabaseManager.ReplayData replay = replays.get(ThreadLocalRandom.current().nextInt(replays.size()));
                replayManager.playGhostReplay(replay);
                
                player.sendMessage(ChatColor.GREEN + "Playing a random ghost replay from " + replays.size() + " found nearby.");
                return true;
            }
            
            if (args[0].equalsIgnoreCase("list")) {
                List<DatabaseManager.ReplayData> recent = databaseManager.getRecentReplays(10);
                player.sendMessage(ChatColor.GOLD + "--- Recent Replays ---");
                for (DatabaseManager.ReplayData data : recent) {
                    player.sendMessage(ChatColor.YELLOW + "ID: " + data.id + " | Type: " + data.type + " | Loc: " + 
                        String.format("%.0f, %.0f, %.0f", data.location.getX(), data.location.getY(), data.location.getZ()));
                }
                return true;
            }

            if (args[0].equalsIgnoreCase("place")) {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Usage: /spectral place <id>");
                    return true;
                }
                try {
                    int id = Integer.parseInt(args[1]);
                    DatabaseManager.ReplayData data = databaseManager.getReplayById(id);
                    if (data == null) {
                        player.sendMessage(ChatColor.RED + "Replay not found.");
                        return true;
                    }
                    
                    int placedId = databaseManager.savePlacedReplay(id, player.getLocation());
                    if (placedId != -1) {
                        replayManager.startPlacedReplayTask(new DatabaseManager.PlacedReplay(placedId, id, player.getLocation()));
                        player.sendMessage(ChatColor.GREEN + "Replay placed at your location! Placed ID: " + placedId);
                    } else {
                        player.sendMessage(ChatColor.RED + "Failed to save placed replay.");
                    }
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "Invalid ID.");
                }
                return true;
            }
            
            if (args[0].equalsIgnoreCase("list-placed")) {
                List<DatabaseManager.PlacedReplay> placed = databaseManager.getAllPlacedReplays();
                player.sendMessage(ChatColor.GOLD + "--- Placed Replays ---");
                for (DatabaseManager.PlacedReplay p : placed) {
                    player.sendMessage(ChatColor.YELLOW + "ID: " + p.id + " | ReplayID: " + p.replayId + " | Loc: " + 
                        String.format("%.0f, %.0f, %.0f", p.location.getX(), p.location.getY(), p.location.getZ()));
                }
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
                }
                return true;
            }
        }

        player.sendMessage(ChatColor.RED + "Usage: /spectral <play|list|place|list-placed|remove>");
        return true;
    }
}
