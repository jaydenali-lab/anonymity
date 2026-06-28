package dev.anonymity;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/** Op-only {@code /fragment}: gives the sender a Fragment Of Anonymity. */
final class FragmentCommand implements CommandExecutor {

    private final FragmentItem fragment;

    FragmentCommand(FragmentItem fragment) {
        this.fragment = fragment;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can receive a Fragment.");
            return true;
        }
        if (!player.hasPermission("anonymity.give")) {
            player.sendMessage("§cYou don't have permission to do that.");
            return true;
        }
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(fragment.create());
        // Drop anything that didn't fit so the player always gets it.
        leftover.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        player.sendMessage("§5You received a §cFragment Of Anonymity§5.");
        return true;
    }
}
