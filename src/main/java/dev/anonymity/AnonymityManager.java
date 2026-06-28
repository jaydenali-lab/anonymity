package dev.anonymity;

import com.mojang.authlib.properties.Property;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Owns all anonymous-mode state and the logic to apply and undo it:
 * scrambled name (chat / tab / nameplate), hidden skin, the enchantment aura
 * and the data the death listener needs.
 */
public final class AnonymityManager {

    private final AnonymityPlugin plugin;
    private final SkinApplier skinApplier;
    private final Particle auraParticle;

    private final Map<UUID, AnonymousState> active = new HashMap<>();
    private BukkitTask particleTask;

    AnonymityManager(AnonymityPlugin plugin) {
        this.plugin = plugin;
        this.skinApplier = new SkinApplier(plugin);
        this.auraParticle = resolveEnchantParticle();
    }

    /** Per-player snapshot so everything can be cleanly reverted. */
    private static final class AnonymousState {
        final String token;
        final String scrambled;
        final Property originalTextures; // null if the player had no skin set
        final String originalDisplayName;
        final String originalListName;
        final String teamName;

        AnonymousState(String token, String scrambled, Property originalTextures,
                       String originalDisplayName, String originalListName, String teamName) {
            this.token = token;
            this.scrambled = scrambled;
            this.originalTextures = originalTextures;
            this.originalDisplayName = originalDisplayName;
            this.originalListName = originalListName;
            this.teamName = teamName;
        }
    }

    // ---------------------------------------------------------------- toggle

    public boolean isAnonymous(Player player) {
        return active.containsKey(player.getUniqueId());
    }

    /** @return {@code true} if the player is now anonymous, {@code false} if reverted. */
    public boolean toggle(Player player) {
        if (isAnonymous(player)) {
            disable(player);
            return false;
        }
        enable(player);
        return true;
    }

    private void enable(Player player) {
        int min = plugin.getConfig().getInt("name.min-length", 7);
        int max = plugin.getConfig().getInt("name.max-length", 9);
        String token = NameObfuscator.newToken(min, max);
        String scrambled = NameObfuscator.scramble(token);

        Property originalTextures = skinApplier.captureTextures(player);
        String originalDisplay = player.getDisplayName();
        String originalList = player.getPlayerListName();

        String teamName = applyNameplate(player, scrambled);

        active.put(player.getUniqueId(), new AnonymousState(
                token, scrambled, originalTextures, originalDisplay, originalList, teamName));

        // Scramble the name everywhere Bukkit lets us: chat and tab list.
        player.setDisplayName(scrambled);
        player.setPlayerListName(scrambled);

        // Hidden skin.
        String value = plugin.getConfig().getString("skin.value", "");
        String signature = plugin.getConfig().getString("skin.signature", "");
        if (!value.isEmpty() && !signature.isEmpty()) {
            skinApplier.apply(player, value, signature);
        }

        player.sendMessage("§5You are now §k" + token + "§r§5. Nobody knows who you are.");
    }

    private void disable(Player player) {
        AnonymousState state = active.remove(player.getUniqueId());
        if (state == null) {
            return;
        }

        player.setDisplayName(state.originalDisplayName);
        player.setPlayerListName(state.originalListName);

        clearNameplate(player, state.teamName);

        // Restore the original skin (or clear ours if they had none).
        skinApplier.restore(player, state.originalTextures);

        player.sendMessage("§5You are visible again.");
    }

    /** Reverts every anonymous player; used on plugin disable. */
    public void restoreAll() {
        if (particleTask != null) {
            particleTask.cancel();
            particleTask = null;
        }
        for (UUID id : Map.copyOf(active).keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                disable(player);
            } else {
                active.remove(id);
            }
        }
    }

    /** Drops state for a player who logged off (no entity to restore). */
    void forget(Player player) {
        AnonymousState state = active.remove(player.getUniqueId());
        if (state != null) {
            clearNameplate(player, state.teamName);
        }
    }

    // ------------------------------------------------------------ death names

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

    // -------------------------------------------------------------- nameplate

    /**
     * Prefixes the over-head name with the scrambled token via a scoreboard
     * team. (Fully replacing the nameplate text isn't possible through the
     * Bukkit API without a packet library, so we prepend the magic token.)
     *
     * @return the created team name, for later cleanup.
     */
    private String applyNameplate(Player player, String scrambled) {
        Scoreboard scoreboard = player.getScoreboard();
        String teamName = "anon_" + Integer.toHexString(player.getUniqueId().hashCode());
        if (teamName.length() > 16) {
            teamName = teamName.substring(0, 16);
        }

        Team team = scoreboard.getTeam(teamName);
        if (team == null) {
            team = scoreboard.registerNewTeam(teamName);
        }
        team.setPrefix(scrambled + " ");
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

    void startParticleTask() {
        if (!plugin.getConfig().getBoolean("particles.enabled", true) || auraParticle == null) {
            return;
        }
        long interval = Math.max(1L, plugin.getConfig().getLong("particles.interval-ticks", 4L));
        int count = Math.max(1, plugin.getConfig().getInt("particles.count", 10));
        double radius = plugin.getConfig().getDouble("particles.radius", 0.6);

        particleTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (UUID id : active.keySet()) {
                Player player = Bukkit.getPlayer(id);
                if (player == null || !player.isOnline()) {
                    continue;
                }
                World world = player.getWorld();
                Location center = player.getLocation().add(0, 1.0, 0);
                world.spawnParticle(auraParticle, center, count, radius, radius, radius, 0.0);
            }
        }, interval, interval);
    }

    /** Enchantment-table particles: {@code ENCHANT} on 1.20.5+, else {@code ENCHANTMENT_TABLE}. */
    private Particle resolveEnchantParticle() {
        for (String name : new String[] {"ENCHANT", "ENCHANTMENT_TABLE"}) {
            try {
                return Particle.valueOf(name);
            } catch (IllegalArgumentException ignored) {
                // Try the other spelling.
            }
        }
        plugin.getLogger().warning("No enchantment particle found on this server version; aura disabled.");
        return null;
    }
}
