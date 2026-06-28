package dev.anonymity;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

/**
 * Plays the "tornado" transform cutscene: red cloud particles wind around the
 * player like a funnel and then explode (or, reversed, explode and then wind
 * back outward), with layered sounds. The actual transform/restore is supplied
 * as a callback and fires at the explosion frame so the change lands on the bang.
 */
final class CutsceneRunner {

    private static final double MAX_RADIUS = 3.0;
    private static final double MIN_RADIUS = 0.4;
    private static final double COLUMN_HEIGHT = 2.4;
    private static final int LEVELS = 6;
    private static final int POINTS_PER_LEVEL = 2;
    private static final double SPIN_PER_TICK = 0.55;
    private static final int SETTLE_TICKS = 8;

    private final Plugin plugin;
    private final AnonymityManager manager;

    CutsceneRunner(Plugin plugin, AnonymityManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    /**
     * @param incoming    true = converge-then-explode (going anonymous);
     *                    false = explode-then-expand (going visible)
     * @param applyChange the transform/restore, run on the explosion frame
     * @param onComplete  run when the animation finishes (or is cut short)
     */
    BukkitTask play(Player player, boolean incoming, Runnable applyChange, Runnable onComplete) {
        final int windup = Math.max(6, plugin.getConfig().getInt("cutscene.windup-ticks", 24));
        final Particle dust = manager.dustParticle();
        final Particle explosion = manager.explosionParticle();

        BukkitRunnable task = new BukkitRunnable() {
            int t = 0;
            boolean changed = false;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    if (!changed) { applyChange.run(); changed = true; }
                    finish();
                    return;
                }
                Location loc = player.getLocation();

                if (!incoming && t == 0) {
                    // Reverse: bang first, restore on the bang, then wind outward.
                    explode(loc, dust, explosion);
                    playSound(player, "minecraft:entity.generic.explode", 0.8f, 0.8f);
                    playSound(player, "minecraft:block.beacon.deactivate", 0.9f, 1.0f);
                    applyChange.run();
                    changed = true;
                }

                double progress = Math.min(1.0, (double) t / windup);
                double radius = incoming
                        ? MAX_RADIUS - (MAX_RADIUS - MIN_RADIUS) * progress   // converge
                        : MIN_RADIUS + (MAX_RADIUS - MIN_RADIUS) * progress;  // expand
                spawnFunnel(loc, dust, radius, t);

                if (t % 6 == 0) {
                    float pitch = incoming ? (float) (0.8 + progress * 0.7) : (float) (1.5 - progress * 0.7);
                    playSound(player, "minecraft:entity.ender_dragon.flap", 0.5f, pitch);
                }

                if (incoming && t >= windup && !changed) {
                    explode(loc, dust, explosion);
                    playSound(player, "minecraft:entity.generic.explode", 1.0f, 1.2f);
                    playSound(player, "minecraft:block.beacon.activate", 0.9f, 1.6f);
                    playSound(player, "minecraft:block.enchantment_table.use", 1.0f, 0.8f);
                    applyChange.run();
                    changed = true;
                }

                int endTick = incoming ? windup + SETTLE_TICKS : windup;
                if (t >= endTick) {
                    finish();
                    return;
                }
                t++;
            }

            private void finish() {
                cancel();
                onComplete.run();
            }
        };

        if (incoming) {
            playSound(player, "minecraft:block.portal.trigger", 0.6f, 1.4f);
            playSound(player, "minecraft:entity.breeze.wind_burst", 0.7f, 0.8f);
        }
        return task.runTaskTimer(plugin, 0L, 1L);
    }

    /** One frame of the spinning funnel of red dust around the player. */
    private void spawnFunnel(Location loc, Particle dust, double radius, int t) {
        if (dust == null) {
            return;
        }
        Particle.DustOptions options = manager.dustOptions();
        for (int level = 0; level < LEVELS; level++) {
            double y = (COLUMN_HEIGHT / LEVELS) * level;
            double baseAngle = t * SPIN_PER_TICK + level * 0.7;
            for (int k = 0; k < POINTS_PER_LEVEL; k++) {
                double angle = baseAngle + k * (Math.PI * 2 / POINTS_PER_LEVEL);
                double x = radius * Math.cos(angle);
                double z = radius * Math.sin(angle);
                loc.getWorld().spawnParticle(dust, loc.getX() + x, loc.getY() + y, loc.getZ() + z,
                        1, 0, 0, 0, 0, options);
            }
        }
    }

    /** The explosion frame: an outward burst of dust plus a flash. */
    private void explode(Location loc, Particle dust, Particle explosion) {
        Location center = loc.clone().add(0, 1.0, 0);
        if (dust != null) {
            loc.getWorld().spawnParticle(dust, center, 70, 0.5, 0.7, 0.5, 0.3, manager.dustOptions());
        }
        if (explosion != null) {
            loc.getWorld().spawnParticle(explosion, center, 3, 0.2, 0.2, 0.2, 0.0);
        }
    }

    private void playSound(Player player, String key, float volume, float pitch) {
        player.getWorld().playSound(player.getLocation(), key, volume, pitch);
    }
}
