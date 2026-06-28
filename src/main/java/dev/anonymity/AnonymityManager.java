package dev.anonymity;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns all anonymous-mode state and the logic to apply and undo it: scrambled
 * name, hidden skin, enchantment aura, infinite buffs, disguise armour, the
 * sneak-dash and the tornado transform cutscene.
 */
public final class AnonymityManager {

    private final AnonymityPlugin plugin;
    private final SkinApplier skinApplier;
    private final CutsceneRunner cutscene;
    private final Particle auraParticle;       // enchantment table (idle aura)
    private final Particle dustParticle;       // red dust (tether trail + cutscene)
    private final Particle explosionParticle;  // cutscene flash

    private final Map<UUID, AnonymousState> active = new HashMap<>();
    private final Map<UUID, BukkitTask> animating = new HashMap<>();
    private BukkitTask tickTask;

    AnonymityManager(AnonymityPlugin plugin) {
        this.plugin = plugin;
        this.skinApplier = new SkinApplier(plugin);
        this.auraParticle = resolveParticle("ENCHANT", "ENCHANTMENT_TABLE");
        this.dustParticle = resolveParticle("DUST", "REDSTONE");
        this.explosionParticle = resolveParticle("EXPLOSION", "EXPLOSION_LARGE");
        this.cutscene = new CutsceneRunner(plugin, this);
    }

    /** Per-player snapshot so everything can be cleanly reverted. */
    private static final class AnonymousState {
        final String token;
        final String scrambled;
        final Object originalTextures;     // opaque authlib Property; null if none set
        final String originalDisplayName;
        final String originalListName;
        final String teamName;
        final ItemStack[] originalArmor;   // {helmet, chest, legs, boots}; entries may be null
        final ItemStack[] fakeArmor;       // disguise set, same order

        AnonymousState(String token, String scrambled, Object originalTextures,
                       String originalDisplayName, String originalListName, String teamName,
                       ItemStack[] originalArmor, ItemStack[] fakeArmor) {
            this.token = token;
            this.scrambled = scrambled;
            this.originalTextures = originalTextures;
            this.originalDisplayName = originalDisplayName;
            this.originalListName = originalListName;
            this.teamName = teamName;
            this.originalArmor = originalArmor;
            this.fakeArmor = fakeArmor;
        }
    }

    // ---------------------------------------------------------------- toggle

    public boolean isAnonymous(Player player) {
        return active.containsKey(player.getUniqueId());
    }

    /** @return {@code true} if the player is now anonymous, {@code false} if reverted. */
    public boolean toggle(Player player) {
        UUID id = player.getUniqueId();
        if (animating.containsKey(id)) {
            player.sendMessage("§5Hold still — you're mid-transformation...");
            return isAnonymous(player);
        }
        if (isAnonymous(player)) {
            startTransform(player, false, () -> doRestore(player, true, true));
            return false;
        }
        startTransform(player, true, () -> doEnable(player));
        return true;
    }

    /** Runs the change immediately, or wrapped in the cutscene if enabled. */
    private void startTransform(Player player, boolean incoming, Runnable change) {
        if (!plugin.getConfig().getBoolean("cutscene.enabled", true)) {
            change.run();
            return;
        }
        UUID id = player.getUniqueId();
        BukkitTask task = cutscene.play(player, incoming, change, () -> animating.remove(id));
        animating.put(id, task);
    }

    private void doEnable(Player player) {
        int min = plugin.getConfig().getInt("name.min-length", 7);
        int max = plugin.getConfig().getInt("name.max-length", 9);
        String token = NameObfuscator.newToken(min, max);
        String scrambled = NameObfuscator.scramble(token);

        Object originalTextures = skinApplier.captureTextures(player);
        String originalDisplay = player.getDisplayName();
        String originalList = player.getPlayerListName();
        String teamName = applyNameplate(player);

        PlayerInventory inv = player.getInventory();
        ItemStack[] originalArmor = {
                clone(inv.getHelmet()), clone(inv.getChestplate()),
                clone(inv.getLeggings()), clone(inv.getBoots())
        };
        ItemStack[] fakeArmor = null;
        if (plugin.getConfig().getBoolean("armor.enabled", true)) {
            fakeArmor = AnonymousGear.buildArmor();
            inv.setHelmet(fakeArmor[0]);
            inv.setChestplate(fakeArmor[1]);
            inv.setLeggings(fakeArmor[2]);
            inv.setBoots(fakeArmor[3]);
        }

        active.put(player.getUniqueId(), new AnonymousState(token, scrambled, originalTextures,
                originalDisplay, originalList, teamName, originalArmor, fakeArmor));

        player.setDisplayName(scrambled);
        player.setPlayerListName(scrambled);

        String value = plugin.getConfig().getString("skin.value", "");
        String signature = plugin.getConfig().getString("skin.signature", "");
        if (!value.isEmpty() && !signature.isEmpty()) {
            skinApplier.apply(player, value, signature);
        }

        applyEffects(player);
        player.sendMessage("§5You are now §k" + token + "§r§5. Nobody knows who you are.");
    }

    /**
     * Reverts everything anonymous-mode applied.
     *
     * @param armorToPlayer put the player's own armour back on them (false when
     *                      dead - the death handler manages drops instead)
     * @param notify        send the "visible again" chat message
     */
    private void doRestore(Player player, boolean armorToPlayer, boolean notify) {
        AnonymousState state = active.remove(player.getUniqueId());
        if (state == null) {
            return;
        }
        player.setDisplayName(state.originalDisplayName);
        player.setPlayerListName(state.originalListName);
        clearNameplate(player, state.teamName);
        skinApplier.restore(player, state.originalTextures);
        removeEffects(player);

        if (armorToPlayer && state.fakeArmor != null) {
            PlayerInventory inv = player.getInventory();
            inv.setHelmet(state.originalArmor[0]);
            inv.setChestplate(state.originalArmor[1]);
            inv.setLeggings(state.originalArmor[2]);
            inv.setBoots(state.originalArmor[3]);
        }
        if (notify) {
            player.sendMessage("§5You are visible again.");
        }
    }

    /** Reverts every anonymous player; used on plugin disable. */
    public void restoreAll() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        for (BukkitTask task : Map.copyOf(animating).values()) {
            task.cancel();
        }
        animating.clear();
        for (UUID id : Map.copyOf(active).keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                doRestore(player, true, false);
            } else {
                active.remove(id);
            }
        }
    }

    /** Logout: cancel any cutscene and restore the player's own armour/effects quietly. */
    void forget(Player player) {
        BukkitTask task = animating.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
        doRestore(player, true, false);
    }

    // ------------------------------------------------------------ death

    /**
     * On death: the disguise armour is removed from the drops and the player's
     * own armour is dropped in its place, then anonymous mode ends.
     */
    public void handleDeath(Player player, List<ItemStack> drops) {
        AnonymousState state = active.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        if (state.fakeArmor != null) {
            for (ItemStack fake : state.fakeArmor) {
                if (fake != null) {
                    drops.removeIf(drop -> drop != null && drop.isSimilar(fake));
                }
            }
            for (ItemStack original : state.originalArmor) {
                if (original != null && original.getType() != Material.AIR) {
                    drops.add(original.clone());
                }
            }
        }
        doRestore(player, false, false);
    }

    /**
     * If the player is anonymous, returns their scrambled name; otherwise the
     * normal display name. Used to rewrite death messages.
     */
    public String displayNameFor(Player player) {
        AnonymousState state = active.get(player.getUniqueId());
        return state != null ? state.scrambled : player.getDisplayName();
    }

    public boolean deathMessagesEnabled() {
        return plugin.getConfig().getBoolean("death-messages", true);
    }

    // ------------------------------------------------------------ effects

    private void applyEffects(Player player) {
        if (!plugin.getConfig().getBoolean("effects.enabled", true)) {
            return;
        }
        int strength = plugin.getConfig().getInt("effects.strength-amplifier", 1);
        int speed = plugin.getConfig().getInt("effects.speed-amplifier", 1);
        // ambient=false, particles=false (hidden), icon=false.
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.STRENGTH, PotionEffect.INFINITE_DURATION, strength, false, false, false));
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, speed, false, false, false));
    }

    private void removeEffects(Player player) {
        player.removePotionEffect(PotionEffectType.STRENGTH);
        player.removePotionEffect(PotionEffectType.SPEED);
    }

    // ------------------------------------------------------------ tether

    /** A red dust trail that follows a flying projectile until it lands. */
    void trailProjectile(org.bukkit.entity.Projectile projectile) {
        if (dustParticle == null) {
            return;
        }
        new BukkitRunnable() {
            int t = 0;
            @Override
            public void run() {
                if (t++ > 80 || projectile.isDead() || !projectile.isValid()) {
                    cancel();
                    return;
                }
                projectile.getWorld().spawnParticle(dustParticle, projectile.getLocation(),
                        4, 0.05, 0.05, 0.05, 0.0, dustOptions());
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** A small red dust burst, used when a tether yanks its target. */
    void dustBurst(Location location, int count) {
        if (dustParticle == null) {
            return;
        }
        location.getWorld().spawnParticle(dustParticle, location, count, 0.3, 0.4, 0.3, 0.0, dustOptions());
    }

    // ----------------------------------------------------- particle helpers

    Particle dustParticle() {
        return dustParticle;
    }

    Particle explosionParticle() {
        return explosionParticle;
    }

    /** Configured red dust colour/size for the dash trail and cutscene. */
    Particle.DustOptions dustOptions() {
        int r = clampColor(plugin.getConfig().getInt("particles.color.red", 200));
        int g = clampColor(plugin.getConfig().getInt("particles.color.green", 0));
        int b = clampColor(plugin.getConfig().getInt("particles.color.blue", 0));
        float size = (float) plugin.getConfig().getDouble("particles.size", 1.5);
        return new Particle.DustOptions(Color.fromRGB(r, g, b), size);
    }

    private static int clampColor(int v) {
        return Math.max(0, Math.min(255, v));
    }

    // -------------------------------------------------------------- nameplate

    private String applyNameplate(Player player) {
        Scoreboard scoreboard = player.getScoreboard();
        String teamName = "anon_" + Integer.toHexString(player.getUniqueId().hashCode());
        if (teamName.length() > 16) {
            teamName = teamName.substring(0, 16);
        }
        Team team = scoreboard.getTeam(teamName);
        if (team == null) {
            team = scoreboard.registerNewTeam(teamName);
        }
        team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        if (!team.hasEntry(player.getName())) {
            team.addEntry(player.getName());
        }
        return teamName;
    }

    private void clearNameplate(Player player, String teamName) {
        if (teamName == null) {
            return;
        }
        Team team = player.getScoreboard().getTeam(teamName);
        if (team != null) {
            team.removeEntry(player.getName());
            team.unregister();
        }
    }

    // --------------------------------------------------------------- aura

    void startTickTask() {
        long interval = Math.max(1L, plugin.getConfig().getLong("particles.interval-ticks", 4L));
        int count = Math.max(1, plugin.getConfig().getInt("particles.count", 10));
        double radius = plugin.getConfig().getDouble("particles.radius", 0.6);
        boolean particles = plugin.getConfig().getBoolean("particles.enabled", true) && auraParticle != null;

        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (UUID id : active.keySet()) {
                Player player = Bukkit.getPlayer(id);
                if (player == null || !player.isOnline()) {
                    continue;
                }
                applyEffects(player); // keep the infinite buffs topped up
                if (particles) {
                    World world = player.getWorld();
                    Location center = player.getLocation().add(0, 1.0, 0);
                    world.spawnParticle(auraParticle, center, count, radius, radius, radius, 0.0);
                }
            }
        }, interval, interval);
    }

    /** Resolves the first particle name that exists on this server version. */
    private Particle resolveParticle(String... names) {
        for (String name : names) {
            try {
                return Particle.valueOf(name);
            } catch (IllegalArgumentException ignored) {
                // Try the next spelling.
            }
        }
        plugin.getLogger().warning("None of these particles exist on this version: " + String.join(", ", names));
        return null;
    }

    private static ItemStack clone(ItemStack item) {
        return item == null ? null : item.clone();
    }
}
