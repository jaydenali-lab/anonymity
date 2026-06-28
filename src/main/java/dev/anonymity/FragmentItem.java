package dev.anonymity;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * The "Fragment Of Anonymity" - a red-named amethyst shard. Right-clicking it
 * toggles anonymous mode. Items are tagged with a persistent-data key so they're
 * recognised reliably even if renamed.
 */
final class FragmentItem {

    static final String DISPLAY_NAME = "§r§cFragment Of Anonymity";

    /** Pack hook: items with this custom-model-data render the red texture. */
    static final int MODEL_DATA = 1;

    private final NamespacedKey key;

    FragmentItem(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "fragment");
    }

    /** A fresh Fragment Of Anonymity item. */
    ItemStack create() {
        ItemStack item = new ItemStack(Material.AMETHYST_SHARD);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(DISPLAY_NAME);
        meta.setLore(List.of("§7Right-click to slip into", "§7or out of anonymity."));
        meta.setEnchantmentGlintOverride(true); // glow without a real enchant
        // Custom model data the resource pack keys off to show the red texture.
        // Without the pack the item just renders as a normal amethyst shard.
        meta.setCustomModelData(MODEL_DATA);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /** Whether the given item is a Fragment Of Anonymity. */
    boolean isFragment(ItemStack item) {
        if (item == null || item.getType() != Material.AMETHYST_SHARD || !item.hasItemMeta()) {
            return false;
        }
        Byte flag = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.BYTE);
        return flag != null && flag == (byte) 1;
    }
}
