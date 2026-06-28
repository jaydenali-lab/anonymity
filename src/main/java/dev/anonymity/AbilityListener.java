package dev.anonymity;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The Tether ability: pressing the swap-hands key (F) while anonymous fires a
 * projectile that yanks the hit player or mob back toward you. On a cooldown.
 */
final class AbilityListener implements Listener {

    private final AnonymityPlugin plugin;
    private final AnonymityManager manager;
    private final NamespacedKey tetherKey;
    private final Map<UUID, Long> cooldown = new HashMap<>();

    AbilityListener(AnonymityPlugin plugin, AnonymityManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.tetherKey = new NamespacedKey(plugin, "tether");
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (!manager.isAnonymous(player) || !plugin.getConfig().getBoolean("tether.enabled", true)) {
            return;
        }
        event.setCancelled(true); // F triggers the tether instead of swapping hands

        long now = System.currentTimeMillis();
        long cd = plugin.getConfig().getLong("tether.cooldown-ms", 2000L);
        Long previous = cooldown.get(player.getUniqueId());
        if (previous != null && now - previous < cd) {
            return;
        }
        cooldown.put(player.getUniqueId(), now);
        launchTether(player);
    }

    private void launchTether(Player player) {
        double speed = plugin.getConfig().getDouble("tether.speed", 1.8);
        Snowball ball = player.launchProjectile(Snowball.class,
                player.getLocation().getDirection().normalize().multiply(speed));
        ball.getPersistentDataContainer().set(tetherKey, PersistentDataType.BYTE, (byte) 1);
        player.getWorld().playSound(player.getLocation(), "minecraft:item.crossbow.shoot", 1.0f, 1.3f);
        manager.trailProjectile(ball);
    }

    @EventHandler
    public void onHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball ball)) {
            return;
        }
        Byte tag = ball.getPersistentDataContainer().get(tetherKey, PersistentDataType.BYTE);
        if (tag == null) {
            return;
        }
        Entity hit = event.getHitEntity();
        ProjectileSource source = ball.getShooter();
        if (!(hit instanceof LivingEntity target) || !(source instanceof Player shooter)
                || target.equals(shooter)) {
            return;
        }
        pull(target, shooter);
    }

    /** Yanks the target toward the shooter, scaled by distance with a slight arc. */
    private void pull(LivingEntity target, Player shooter) {
        Vector toShooter = shooter.getLocation().toVector().subtract(target.getLocation().toVector());
        double distance = toShooter.length();
        if (distance < 0.1) {
            return;
        }
        double base = Math.max(0.8, Math.min(distance * 0.28, 2.6));
        double strength = base * plugin.getConfig().getDouble("tether.pull-strength", 1.0);
        Vector velocity = toShooter.normalize().multiply(strength);
        velocity.setY(Math.max(velocity.getY(), 0.0) + 0.35);
        target.setVelocity(velocity);
        target.getWorld().playSound(target.getLocation(), "minecraft:entity.fishing_bobber.retrieve", 1.0f, 0.7f);
        manager.dustBurst(target.getLocation().add(0, 1.0, 0), 16);
    }
}
