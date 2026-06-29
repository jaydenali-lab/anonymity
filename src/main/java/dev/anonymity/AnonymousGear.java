package dev.anonymity;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the trimmed netherite armour set worn while anonymous:
 * <ul>
 *   <li>Helmet  - Vex trim,    Unbreaking III, Protection III, Mending</li>
 *   <li>Chest   - Raiser trim, Unbreaking III, Protection III, Mending</li>
 *   <li>Leggings- Tide trim,   Unbreaking III, Protection III, Mending</li>
 *   <li>Boots   - Tide trim,   Feather Falling IV, Protection III, Unbreaking III, Mending</li>
 * </ul>
 * All pieces use the Redstone trim material.
 */
final class AnonymousGear {

    private AnonymousGear() {
    }

    /** @return a fresh set ordered {helmet, chestplate, leggings, boots}. */
    static ItemStack[] buildArmor() {
        return new ItemStack[] {
                piece(Material.NETHERITE_HELMET, TrimPattern.VEX, enchants(
                        Enchantment.UNBREAKING, 3, Enchantment.PROTECTION, 3, Enchantment.MENDING, 1,
                        Enchantment.BINDING_CURSE, 1)),
                piece(Material.NETHERITE_CHESTPLATE, TrimPattern.RAISER, enchants(
                        Enchantment.UNBREAKING, 3, Enchantment.PROTECTION, 3, Enchantment.MENDING, 1,
                        Enchantment.BINDING_CURSE, 1)),
                piece(Material.NETHERITE_LEGGINGS, TrimPattern.TIDE, enchants(
                        Enchantment.UNBREAKING, 3, Enchantment.PROTECTION, 3, Enchantment.MENDING, 1,
                        Enchantment.BINDING_CURSE, 1)),
                piece(Material.NETHERITE_BOOTS, TrimPattern.TIDE, enchants(
                        Enchantment.FEATHER_FALLING, 4, Enchantment.PROTECTION, 3,
                        Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1, Enchantment.BINDING_CURSE, 1)),
        };
    }

    private static ItemStack piece(Material material, TrimPattern pattern, Map<Enchantment, Integer> enchants) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        enchants.forEach((enchantment, level) -> meta.addEnchant(enchantment, level, true));
        if (meta instanceof ArmorMeta armorMeta) {
            armorMeta.setTrim(new ArmorTrim(TrimMaterial.REDSTONE, pattern));
        }
        item.setItemMeta(meta);
        return item;
    }

    /** Small varargs helper: enchant, level, enchant, level, ... preserving order. */
    private static Map<Enchantment, Integer> enchants(Object... pairs) {
        Map<Enchantment, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put((Enchantment) pairs[i], (Integer) pairs[i + 1]);
        }
        return map;
    }
}
