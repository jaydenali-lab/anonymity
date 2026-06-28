package dev.anonymity;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
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

    // Per-key cooldowns and ability state.
    private final Map<UUID, Long> cooldownSneak = new java.util.HashMap<>();
    private final Map<UUID, Long> cooldownSwap = new java.util.HashMap<>();
    private final Map<UUID, Long> empoweredUntil = new java.util.HashMap<>();
    private final Map<UUID, Long> stunCharge = new java.util.HashMap<>();
    private final Map<UUID, Long> noFallUntil = new java.util.HashMap<>();
    private final Map<UUID, Mark> marks = new java.util.HashMap<>();

    AbilityListener(AnonymityPlugin plugin, AnonymityManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        buildRegistry();
        startHud();
    }

    // --------------------------------------------------- ability cooldown HUD

    private void startHud() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            String swapId = plugin.getConfig().getString("abilities.swap-hand", "tether");
            String sneakId = plugin.getConfig().getString("abilities.sneak", "stun");
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!manager.isAnonymous(player)) {
                    continue;
                }
                String text = hudEntry(swapId, cooldownSwap, player) + " §8|§r " + hudEntry(sneakId, cooldownSneak, player);
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(text));
            }
        }, 20L, 10L);
    }

    private String hudEntry(String id, Map<UUID, Long> cooldowns, Player player) {
        long now = System.currentTimeMillis();
        String status;
        Long armed = stunCharge.get(player.getUniqueId());
        if (id.equals("stun") && armed != null && now < armed) {
            // Stun is armed and waiting for a hit.
            status = "§eACTIVE";
        } else {
            Long last = cooldowns.get(player.getUniqueId());
            long remaining = last == null ? 0 : Math.max(0, cooldownMs(id) - (now - last));
            status = remaining <= 0 ? "§aREADY" : "§c" + (int) Math.ceil(remaining / 1000.0) + "s";
        }
        return abilityColor(id) + abilityIcon(id) + " " + abilityLabel(id) + " " + status;
    }

    private String abilityIcon(String id) {
        return switch (id) {
            case "tether" -> "➤";   // ➤
            case "stun" -> "⛓";     // ⛓
            default -> "✦";         // ✦
        };
    }

    private String abilityLabel(String id) {
        return switch (id) {
            case "tether" -> "Tether";
            case "stun" -> "Bind";
            default -> {
                String[] parts = id.split("_");
                StringBuilder sb = new StringBuilder();
                for (String p : parts) {
                    if (!p.isEmpty()) {
                        sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
                    }
                }
                yield sb.toString().trim();
            }
        };
    }

    private String abilityColor(String id) {
        return switch (id) {
            case "tether" -> "§b";
            case "stun" -> "§d";
            default -> "§f";
        };
    }

    private void buildRegistry() {
        // Combat
        register("tether", "combat", this::tether);
        register("empowered_strike", "combat", this::empoweredStrike);
        register("chain_lightning", "combat", this::chainLightning);
        register("venom_burst", "combat", this::venomBurst);
        register("ground_slam", "combat", this::groundSlam);
        register("stun", "combat", this::armStun);
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
        long cd = cooldownMs(ability.id());
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

    /** Cooldown for an ability in milliseconds (per-ability config, else a default). */
    private long cooldownMs(String id) {
        if (plugin.getConfig().contains("abilities.cooldowns." + id)) {
            return Math.max(0L, plugin.getConfig().getLong("abilities.cooldowns." + id) * 1000L);
        }
        return switch (id) {
            case "tether" -> 30000L;
            case "stun" -> 20000L;
            default -> plugin.getConfig().getLong("abilities.cooldown-ms", 2500L);
        };
    }

    // ----------------------------------------------------------- combat

    /** A straight, instant laser: hits the first entity in your line of sight and yanks it back. */
    private void tether(Player player) {
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        double maxRange = plugin.getConfig().getDouble("tether.range", 40.0);

        RayTraceResult blockHit = player.getWorld().rayTraceBlocks(eye, dir, maxRange);
        double maxDist = blockHit != null ? eye.toVector().distance(blockHit.getHitPosition()) : maxRange;
        RayTraceResult entityHit = player.getWorld().rayTraceEntities(eye, dir, maxDist, 0.65,
                e -> e instanceof LivingEntity && !e.equals(player));

        LivingEntity target = entityHit != null && entityHit.getHitEntity() instanceof LivingEntity le ? le : null;
        Location end;
        if (target != null) {
            end = target.getLocation().add(0, target.getHeight() * 0.5, 0);
        } else if (blockHit != null) {
            end = blockHit.getHitPosition().toLocation(player.getWorld());
        } else {
            end = eye.clone().add(dir.clone().multiply(maxRange));
        }

        drawBeam(eye, end);
        sound(player, "minecraft:entity.guardian.attack", 1.0f, 1.5f);
        sound(player, "minecraft:item.crossbow.shoot", 0.6f, 1.6f);
        if (target != null) {
            pullToShooter(target, player);
        }
    }

    /** Red dust beam in a straight line from a to b. */
    private void drawBeam(Location from, Location to) {
        Particle dust = manager.dustParticle();
        if (dust == null) {
            return;
        }
        Particle.DustOptions options = manager.dustOptions();
        Vector full = to.toVector().subtract(from.toVector());
        double length = full.length();
        if (length < 0.01) {
            return;
        }
        Vector step = full.normalize().multiply(0.3);
        Location point = from.clone();
        for (double d = 0; d < length; d += 0.3) {
            from.getWorld().spawnParticle(dust, point, 1, 0, 0, 0, 0, options);
            point.add(step);
        }
    }

    /**
     * Reels a target all the way to the shooter: each tick it re-aims at the
     * shooter and applies velocity with a slight lift, so the target skims over
     * the ground (ground friction can't stall the pull) until it arrives.
     */
    private void pullToShooter(LivingEntity target, Player shooter) {
        target.getWorld().playSound(target.getLocation(), "minecraft:entity.fishing_bobber.retrieve", 1.0f, 0.6f);
        final double strength = plugin.getConfig().getDouble("tether.pull-strength", 1.1);
        new BukkitRunnable() {
            int t = 0;
            @Override
            public void run() {
                if (t++ > 25 || target.isDead() || !target.isValid() || !shooter.isOnline()) {
                    cancel();
                    return;
                }
                Vector to = shooter.getLocation().toVector().subtract(target.getLocation().toVector());
                double dist = to.length();
                if (dist < 1.4) {
                    cancel(); // arrived
                    return;
                }
                double speed = Math.min(0.9, 0.3 + dist * 0.22) * strength;
                Vector velocity = to.normalize().multiply(speed);
                velocity.setY(0.22); // gentle lift so they glide over the floor
                target.setVelocity(velocity);
                manager.dustBurst(target.getLocation().add(0, 1, 0), 4);
            }
        }.runTaskTimer(plugin, 0L, 1L);
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

    /** Arms the player's next melee hit to root the target in place. (No chat, just sfx.) */
    private void armStun(Player player) {
        stunCharge.put(player.getUniqueId(), System.currentTimeMillis() + 6000L);
        sound(player, "minecraft:block.chain.place", 1.0f, 1.4f);
        sound(player, "minecraft:block.enchantment_table.use", 0.7f, 1.6f);
        manager.dustBurst(player.getLocation().add(0, 1, 0), 20);
    }

    /** Roots a victim where they stand for 1.5s inside a cage of chain particles. */
    private void applyStun(LivingEntity victim) {
        final int ticks = 30; // 1.5 seconds
        final Location lock = victim.getLocation().clone();
        // No slowness effect at all - the per-tick position lock holds them, so
        // nothing (icon or particles) shows on the victim.
        final boolean mob = victim instanceof Mob;
        if (mob) {
            ((Mob) victim).setAI(false);
        }
        victim.getWorld().playSound(lock, "minecraft:block.chain.place", 1.2f, 0.7f);
        victim.getWorld().playSound(lock, "minecraft:entity.leash_knot.place", 1.0f, 0.8f);

        new BukkitRunnable() {
            int t = 0;
            @Override
            public void run() {
                if (t++ >= ticks || victim.isDead() || !victim.isValid()) {
                    if (mob && victim.isValid()) {
                        ((Mob) victim).setAI(true);
                    }
                    cancel();
                    return;
                }
                // Hold them at the locked spot but let them look around.
                Location current = victim.getLocation();
                lock.setYaw(current.getYaw());
                lock.setPitch(current.getPitch());
                if (current.distanceSquared(lock) > 0.0025) {
                    victim.teleport(lock);
                }
                victim.setVelocity(new Vector(0, 0, 0));
                chainCage(lock);
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** A cage of red chain-like particles around a point. */
    private void chainCage(Location center) {
        Particle dust = manager.dustParticle();
        if (dust == null) {
            return;
        }
        Particle.DustOptions options = manager.dustOptions();
        double radius = 0.55;
        for (int i = 0; i < 4; i++) {
            double angle = i * Math.PI / 2 + (center.getYaw() == 0 ? 0 : 0);
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            for (double y = 0.1; y <= 2.0; y += 0.4) {
                center.getWorld().spawnParticle(dust, center.getX() + x, center.getY() + y, center.getZ() + z,
                        1, 0, 0, 0, 0, options);
            }
        }
        center.getWorld().spawnParticle(Particle.CRIT, center.clone().add(0, 1.0, 0), 4, 0.4, 0.6, 0.4, 0.0);
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

        Long stunArmed = stunCharge.get(attacker.getUniqueId());
        if (stunArmed != null && now < stunArmed) {
            stunCharge.remove(attacker.getUniqueId());
            applyStun(victim);
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
