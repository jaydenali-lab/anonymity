package dev.anonymity;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Rewrites death messages so an anonymous killer (or victim) appears as the
 * scrambled "unintelligible letters" name, and cleans up state on logout.
 */
final class AnonymityListener implements Listener {

    private final AnonymityManager manager;

    AnonymityListener(AnonymityManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (!shouldRewriteDeaths()) {
            return;
        }
        String message = event.getDeathMessage();
        if (message == null || message.isEmpty()) {
            return;
        }

        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        // Replace any anonymous participant's real username with their scrambled
        // name. Vanilla phrasing (and the weapon, if any) is preserved.
        if (killer != null && manager.isAnonymous(killer)) {
            message = message.replace(killer.getName(), manager.displayNameFor(killer));
        }
        if (manager.isAnonymous(victim)) {
            message = message.replace(victim.getName(), manager.displayNameFor(victim));
        }

        event.setDeathMessage(message);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        manager.forget(event.getPlayer());
    }

    private boolean shouldRewriteDeaths() {
        return manager != null && manager.deathMessagesEnabled();
    }
}
