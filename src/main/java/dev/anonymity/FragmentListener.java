package dev.anonymity;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Right-clicking a Fragment Of Anonymity toggles anonymous mode. */
final class FragmentListener implements Listener {

    // Right-click air can fire PlayerInteractEvent twice; debounce so one click
    // is one toggle.
    private static final long DEBOUNCE_MS = 300L;

    private final AnonymityManager manager;
    private final FragmentItem fragment;
    private final Map<UUID, Long> lastUse = new HashMap<>();

    FragmentListener(AnonymityManager manager, FragmentItem fragment) {
        this.manager = manager;
        this.fragment = fragment;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return; // only the main hand, so it doesn't fire twice
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!fragment.isFragment(event.getItem())) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("anonymity.use")) {
            return;
        }
        event.setCancelled(true); // don't trigger block/item interactions

        long now = System.currentTimeMillis();
        Long previous = lastUse.put(player.getUniqueId(), now);
        if (previous != null && now - previous < DEBOUNCE_MS) {
            return; // ignore the duplicate fire from a single right-click
        }
        manager.toggle(player);
    }

    /**
     * The Fragment doesn't drop on death - it vanishes with the player, like
     * Curse of Vanishing but without the visible enchant. (On a normal death the
     * inventory is emptied into the drops, so removing it here destroys it.)
     */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(fragment::isFragment);
    }
}
