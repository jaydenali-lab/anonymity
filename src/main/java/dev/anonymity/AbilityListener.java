package dev.anonymity;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The anonymous ability system: a registry of 15 abilities (5 combat, 5
 * mobility, 5 trickster). The server binds one ability to the Sneak key and one
 * to the swap-hands (F) key in config; this listener fires the bound ability on
 * the matching trigger (with a cooldown) and runs the abilities' side effects
 * (tether pull, empowered/marked hits, fall-damage immunity).
 */
final class AbilityListener implements Listener {

    private record Ability(String id, String category, Consumer<Player> action) {}
    private record Mark(UUID marker, long until) {}

    private final AnonymityPlugin plugin;
    private final AnonymityManager manager;
    private final Map<String, Ability> registry = new LinkedHashMap<>();
    private final NamespacedKey tetherKey;

    // Per-key cooldowns and ability state.
    private final Map<UUID, Long> cooldownSneak = new java.util.HashMap<>();
    private final Map<UUID, Long> cooldownSwap = new java.util.HashMap<>();
    private final Map<UUID, Long> empoweredUntil = new java.util.HashMap<>();
    private final Map<UUID, Long> noFallUntil = new java.util.HashMap<>();
    private final Map<UUID, Mark> marks = new java.util.HashMap<>();

    AbilityListener(AnonymityPlugin plugin, AnonymityManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.tetherKey = new NamespacedKey(plugin, "tether");
        buildRegistry();
    }

    private void buildRegistry() {
        // Combat
        register("tether", "combat", this::tether);
        register("empowered_strike", "combat", this::empoweredStrike);
        register("chain_lightning", "combat", this::chainLightning);
        register("venom_burst", "combat", this::venomBurst);
        register("ground_slam", "combat", this::groundSlam);
        // Mobility
        register("super_leap", "mobility", this::superLeap);
        register("shadow_blink", "mobility", this::shadowBlink);
        register("grappling_hook", "mobility", this::grapplingHook);
        register("speed_surge", "mobility", this::speedSurge);
        register("disengage", "mobility", this::disengage);
        // Trickster
        register("vanish", "trickster", this::vanish);
        register("smoke_bomb", "trickster", this::smokeBomb);
        register("decoy_clone", "trickster", this::decoyClone);
        register("mimic", "trickster", this::mimic);
        register("mark_for_death", "trickster", this::markForDeath);
    }

    private void register(String id, String category, Consumer<Player> action) {
        registry.put(id, new Ability(id, category, action));
    }

    // ----------------------------------------------------------- triggers

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) {
            return;
        }
        trigger(event.getPlayer(), plugin.getConfig().getString("abilities.sneak", "ground_slam"), cooldownSneak);
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (manager.isAnonymous(event.getPlayer())) {
            event.setCancelled(true); // F is an ability key while anonymous
        }
        trigger(event.getPlayer(), plugin.getConfig().getString("abilities.swap-hand", "tether"), cooldownSwap);
    }

    private void trigger(Player player, String abilityId, Map<UUID, Long> cooldowns) {
        if (!manager.isAnonymous(player)) {
            return;
        }
        Ability ability = registry.get(abilityId == null ? "" : abilityId.toLowerCase());
        if (ability == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long cd = plugin.getConfig().getLong("abilities.cooldown-ms", 2500L);
        Long previous = cooldowns.get(player.getUniqueId());
        if (previous != null && now - previous < cd) {
            return;
        }
        cooldowns.put(player.getUniqueId(), now);
        try {
            ability.action().accept(player);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Ability '" + ability.id() + "' failed: " + e);
        }
    }

    // ----------------------------------------------------------- combat

    private void tether(Player player) {
        double speed = plugin.getConfig().getDouble("tether.speed", 1.8);
        Snowball ball = player.launchProjectile(Snowball.class,
                player.getLocation().getDirection().normalize().multiply(speed));
        ball.getPersistentDataContainer().set(tetherKey, PersistentDataType.BYTE, (byte) 1);
        sound(player, "minecraft:item.crossbow.shoot", 1.0f, 1.3f);
        manager.trailProjectile(ball);
    }

    private void empoweredStrike(Player player) {
        empoweredUntil.put(player.getUniqueId(), System.currentTimeMillis() + 6000L);
        sound(player, "minecraft:item.totem.use", 0.6f, 1.4f);
        sound(player, "minecraft:block.anvil.land", 0.4f, 1.6f);
        manager.dustBurst(player.getLocation().add(0, 1, 0), 25);
    }

    private void chainLightning(Player player) {
        LivingEntity target = targetEntity(player, 30);
        if (target == null) {
            sound(player, "minecraft:block.note_block.bit", 0.6f, 0.6f);
            return;
        }
        strike(target, player, 7.0);
        int arcs = 0;
        for (LivingEntity near : nearby(target, 5.0)) {
            if (near.equals(player)) {
                continue;
            }
            strike(near, player, 4.0);
            if (++arcs >= 2) {
                break;
            }
        }
    }

    private void venomBurst(Player player) {
        sound(player, "minecraft:entity.witch.throw", 1.0f, 0.8f);
        manager.dustBurst(player.getLocation().add(0, 1, 0), 30);
        for (LivingEntity enemy : nearby(player, 5.0)) {
            enemy.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 100, 1));
            enemy.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 80, 0));
        }
    }

    private void groundSlam(Player player) {
        sound(player, "minecraft:entity.generic.explode", 0.9f, 1.2f);
        sound(player, "minecraft:entity.ravager.attack", 0.7f, 0.8f);
        manager.dustBurst(player.getLocation(), 40);
        for (LivingEntity enemy : nearby(player, 5.0)) {
            Vector up = enemy.getLocation().toVector().subtract(player.getLocation().toVector());
            up.setY(0);
            if (up.lengthSquared() > 0.01) {
                up.normalize().multiply(0.4);
            }
            up.setY(0.9);
            enemy.setVelocity(up);
            enemy.damage(4.0, player);
        }
    }

    // ----------------------------------------------------------- mobility

    private void superLeap(Player player) {
        Vector v = player.getLocation().getDirection().normalize().multiply(0.6);
        v.setY(1.1);
        player.setVelocity(v);
        grantNoFall(player);
        sound(player, "minecraft:entity.ender_dragon.flap", 1.0f, 1.2f);
        manager.dustBurst(player.getLocation(), 18);
    }

    private void shadowBlink(Player player) {
        Vector dir = player.getLocation().getDirection().normalize();
        RayTraceResult ray = player.rayTraceBlocks(8.0);
        Location dest;
        if (ray != null && ray.getHitPosition() != null) {
            dest = ray.getHitPosition().subtract(dir.clone().multiply(1.0)).toLocation(player.getWorld());
        } else {
            dest = player.getLocation().add(dir.multiply(8));
        }
        dest.setYaw(player.getLocation().getYaw());
        dest.setPitch(player.getLocation().getPitch());
        manager.dustBurst(player.getLocation().add(0, 1, 0), 25);
        sound(player, "minecraft:entity.enderman.teleport", 1.0f, 1.0f);
        player.teleport(dest);
        grantNoFall(player);
        manager.dustBurst(dest.clone().add(0, 1, 0), 25);
    }

    private void grapplingHook(Player player) {
        RayTraceResult ray = player.rayTraceBlocks(30.0);
        if (ray == null || ray.getHitPosition() == null) {
            sound(player, "minecraft:block.note_block.bit", 0.6f, 0.6f);
            return;
        }
        Vector to = ray.getHitPosition().subtract(player.getEyeLocation().toVector());
        double dist = to.length();
        Vector v = to.normalize().multiply(Math.min(dist * 0.3, 2.6));
        v.setY(v.getY() + 0.3);
        player.setVelocity(v);
        grantNoFall(player);
        sound(player, "minecraft:item.crossbow.loading_end", 1.0f, 1.2f);
    }

    private void speedSurge(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 120, 2, false, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 120, 2, false, false, false));
        sound(player, "minecraft:entity.breeze.wind_burst", 1.0f, 1.4f);
        manager.dustBurst(player.getLocation(), 15);
    }

    private void disengage(Player player) {
        Vector back = player.getLocation().getDirection().normalize().multiply(-1.1);
        back.setY(0.55);
        player.setVelocity(back);
        grantNoFall(player);
        sound(player, "minecraft:entity.player.attack.sweep", 1.0f, 1.5f);
        manager.dustBurst(player.getLocation(), 18);
    }

    // ----------------------------------------------------------- trickster

    private void vanish(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 100, 0, false, false, false));
        sound(player, "minecraft:entity.illusioner.mirror_move", 1.0f, 1.0f);
        manager.dustBurst(player.getLocation().add(0, 1, 0), 35);
    }

    private void smokeBomb(Player player) {
        sound(player, "minecraft:entity.tnt.primed", 0.8f, 1.4f);
        player.getWorld().spawnParticle(org.bukkit.Particle.LARGE_SMOKE,
                player.getLocation().add(0, 1, 0), 60, 1.5, 1.0, 1.5, 0.02);
        for (LivingEntity enemy : nearby(player, 6.0)) {
            enemy.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 80, 0));
            enemy.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 1));
        }
        player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 60, 0, false, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, 1, false, false, false));
    }

    private void decoyClone(Player player) {
        ArmorStand clone = player.getWorld().spawn(player.getLocation(), ArmorStand.class, stand -> {
            stand.setArms(true);
            stand.setBasePlate(false);
            stand.setGravity(false);
            stand.setInvulnerable(true);
            EntityEquipment src = player.getEquipment();
            EntityEquipment dst = stand.getEquipment();
            if (src != null && dst != null) {
                dst.setHelmet(src.getHelmet());
                dst.setChestplate(src.getChestplate());
                dst.setLeggings(src.getLeggings());
                dst.setBoots(src.getBoots());
                dst.setItemInMainHand(src.getItemInMainHand());
            }
        });
        plugin.getServer().getScheduler().runTaskLater(plugin, clone::remove, 120L);
        player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 60, 0, false, false, false));
        sound(player, "minecraft:entity.illusioner.cast_spell", 1.0f, 1.0f);
        manager.dustBurst(player.getLocation().add(0, 1, 0), 30);
    }

    private void mimic(Player player) {
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Entity e : player.getNearbyEntities(16, 16, 16)) {
            if (e instanceof Player other && !other.equals(player)) {
                double d = other.getLocation().distanceSquared(player.getLocation());
                if (d < best) {
                    best = d;
                    nearest = other;
                }
            }
        }
        if (nearest == null) {
            sound(player, "minecraft:block.note_block.bit", 0.6f, 0.6f);
            return;
        }
        manager.mimic(player, nearest);
        sound(player, "minecraft:entity.illusioner.cast_spell", 1.0f, 1.2f);
        manager.dustBurst(player.getLocation().add(0, 1, 0), 30);
        player.sendMessage("§5You now appear as §f" + nearest.getName() + "§5.");
    }

    private void markForDeath(Player player) {
        LivingEntity target = targetEntity(player, 30);
        if (target == null) {
            sound(player, "minecraft:block.note_block.bit", 0.6f, 0.6f);
            return;
        }
        marks.put(target.getUniqueId(), new Mark(player.getUniqueId(), System.currentTimeMillis() + 8000L));
        target.setGlowing(true);
        UUID id = target.getUniqueId();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Mark m = marks.get(id);
            if (m == null || System.currentTimeMillis() >= m.until()) {
                marks.remove(id);
                if (target.isValid()) {
                    target.setGlowing(false);
                }
            }
        }, 160L);
        sound(player, "minecraft:entity.wither.shoot", 0.8f, 1.4f);
        manager.dustBurst(target.getLocation().add(0, 1, 0), 20);
    }

    // ----------------------------------------------------- side effects

    @EventHandler
    public void onTetherHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball ball)) {
            return;
        }
        if (ball.getPersistentDataContainer().get(tetherKey, PersistentDataType.BYTE) == null) {
            return;
        }
        ProjectileSource source = ball.getShooter();
        if (!(event.getHitEntity() instanceof LivingEntity target) || !(source instanceof Player shooter)
                || target.equals(shooter)) {
            return;
        }
        Vector toShooter = shooter.getLocation().toVector().subtract(target.getLocation().toVector());
        double distance = toShooter.length();
        if (distance < 0.1) {
            return;
        }
        double base = Math.max(0.8, Math.min(distance * 0.28, 2.6));
        Vector velocity = toShooter.normalize().multiply(base * plugin.getConfig().getDouble("tether.pull-strength", 1.0));
        velocity.setY(Math.max(velocity.getY(), 0.0) + 0.35);
        target.setVelocity(velocity);
        target.getWorld().playSound(target.getLocation(), "minecraft:entity.fishing_bobber.retrieve", 1.0f, 0.7f);
        manager.dustBurst(target.getLocation().add(0, 1, 0), 16);
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker) || !(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        long now = System.currentTimeMillis();

        Long charged = empoweredUntil.get(attacker.getUniqueId());
        if (charged != null && now < charged) {
            empoweredUntil.remove(attacker.getUniqueId());
            event.setDamage(event.getDamage() + 8.0);
            Vector kb = victim.getLocation().toVector().subtract(attacker.getLocation().toVector());
            if (kb.lengthSquared() > 0.01) {
                kb.normalize().multiply(1.4);
            }
            kb.setY(0.45);
            victim.setVelocity(kb);
            attacker.getWorld().playSound(victim.getLocation(), "minecraft:item.mace.smash_ground", 1.0f, 1.0f);
            manager.dustBurst(victim.getLocation().add(0, 1, 0), 25);
        }

        Mark mark = marks.get(victim.getUniqueId());
        if (mark != null && now < mark.until() && mark.marker().equals(attacker.getUniqueId())) {
            event.setDamage(event.getDamage() + 4.0);
            manager.dustBurst(victim.getLocation().add(0, 1, 0), 10);
        }
    }

    @EventHandler
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL || !(event.getEntity() instanceof Player player)) {
            return;
        }
        Long until = noFallUntil.get(player.getUniqueId());
        if (until != null && System.currentTimeMillis() < until) {
            event.setCancelled(true);
        }
    }

    // ----------------------------------------------------------- helpers

    private void strike(LivingEntity target, Player source, double damage) {
        target.getWorld().strikeLightningEffect(target.getLocation());
        target.damage(damage, source);
    }

    private LivingEntity targetEntity(Player player, int range) {
        Entity e = player.getTargetEntity(range);
        return e instanceof LivingEntity le && !le.equals(player) ? le : null;
    }

    private List<LivingEntity> nearby(LivingEntity center, double radius) {
        List<LivingEntity> out = new ArrayList<>();
        for (Entity e : center.getNearbyEntities(radius, radius, radius)) {
            if (e instanceof LivingEntity le && !le.equals(center)) {
                out.add(le);
            }
        }
        return out;
    }

    private void grantNoFall(Player player) {
        noFallUntil.put(player.getUniqueId(), System.currentTimeMillis() + 6000L);
    }

    private void sound(Player player, String key, float volume, float pitch) {
        player.getWorld().playSound(player.getLocation(), key, volume, pitch);
    }
}
