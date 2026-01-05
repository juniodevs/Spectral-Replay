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

        if (args.length > 0 && args[0].equalsIgnoreCase("play")) {
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

        player.sendMessage(ChatColor.RED + "Usage: /spectral play");
        return true;
    }
}
