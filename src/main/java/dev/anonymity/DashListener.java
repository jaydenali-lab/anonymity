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

        Vector direction = player.getLocation().getDirection().normalize().multiply(manager.dashPower());
        direction.setY(Math.max(direction.getY() * 0.5, 0.0) + manager.dashVertical());
        player.setVelocity(direction);
        manager.dashPuff(player);
    }
}
