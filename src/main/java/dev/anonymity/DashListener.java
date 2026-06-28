package dev.anonymity;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sneak (hold shift) to dash forward while anonymous. The dash is a single
 * velocity burst in the look direction, which naturally decays over about a
 * second, with a configurable cooldown.
 */
final class DashListener implements Listener {

    private final AnonymityManager manager;
    private final Map<UUID, Long> lastDash = new HashMap<>();

    DashListener(AnonymityManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) {
            return; // only on pressing shift, not releasing
        }
        Player player = event.getPlayer();
        if (!manager.isAnonymous(player) || !manager.dashEnabled()) {
            return;
        }

        long now = System.currentTimeMillis();
        Long previous = lastDash.get(player.getUniqueId());
        if (previous != null && now - previous < manager.dashCooldownMs()) {
            return;
        }
        lastDash.put(player.getUniqueId(), now);

        Vector look = player.getLocation().getDirection().normalize();
        Vector direction = look.clone().multiply(manager.dashPower());
        // Dash where you look - including downward. Only add the small upward kick
        // when you're not aiming down, so flat-ground dashes don't faceplant.
        if (look.getY() >= -0.1) {
            direction.setY(direction.getY() + manager.dashVertical());
        }
        player.setVelocity(direction);
        manager.dashEffects(player);
    }
}
