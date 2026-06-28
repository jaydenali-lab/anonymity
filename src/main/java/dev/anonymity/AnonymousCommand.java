package dev.anonymity;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Handles {@code /anonymous} (alias {@code /anon}): toggles anonymous mode. */
final class AnonymousCommand implements CommandExecutor {

    private final AnonymityManager manager;

    AnonymousCommand(AnonymityManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can become anonymous.");
            return true;
        }
        if (!player.hasPermission("anonymity.use")) {
            player.sendMessage("§cYou don't have permission to do that.");
            return true;
        }
        manager.toggle(player);
        return true;
    }
}
