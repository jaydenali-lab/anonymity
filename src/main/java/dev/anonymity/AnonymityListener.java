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
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        // Rewrite the death message first - it needs the scrambled names while
        // the players are still marked anonymous.
        if (shouldRewriteDeaths()) {
            String message = event.getDeathMessage();
            if (message != null && !message.isEmpty()) {
                if (killer != null && manager.isAnonymous(killer)) {
                    message = message.replace(killer.getName(), manager.displayNameFor(killer));
                }
                if (manager.isAnonymous(victim)) {
                    message = message.replace(victim.getName(), manager.displayNameFor(victim));
                }
                event.setDeathMessage(message);
            }
        }

        // Then handle the disguise armour in the drops and end anonymity.
        if (manager.isAnonymous(victim)) {
            manager.handleDeath(victim, event.getDrops());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        manager.forget(event.getPlayer());
    }

    private boolean shouldRewriteDeaths() {
        return manager != null && manager.deathMessagesEnabled();
    }
}
