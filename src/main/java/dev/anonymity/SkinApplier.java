package dev.anonymity;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * Swaps a player's skin by mutating the {@code textures} property on the
 * live {@link GameProfile} backing their entity, then re-sending the player
 * to every other online player so their client re-reads the new texture.
 *
 * <p>This deliberately avoids version-specific NMS packet classes: the only
 * reflection needed is to reach the {@link GameProfile} (its location/name
 * changes between Minecraft versions), and the re-send is done with the stable
 * Bukkit {@link Player#hidePlayer}/{@link Player#showPlayer} API.</p>
 */
final class SkinApplier {

    private static final String TEXTURES = "textures";

    private final Plugin plugin;

    SkinApplier(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Returns the existing signed textures property, or {@code null} if none. */
    Property captureTextures(Player player) {
        GameProfile profile = profileOf(player);
        if (profile == null) {
            return null;
        }
        for (Property property : profile.getProperties().get(TEXTURES)) {
            return property;
        }
        return null;
    }

    /** Applies a signed (value + signature) texture and refreshes viewers. */
    void apply(Player player, String value, String signature) {
        setTextures(player, new Property(TEXTURES, value, signature));
    }

    /**
     * Restores a previously captured texture, or clears it entirely if the
     * player had none originally.
     */
    void restore(Player player, Property original) {
        setTextures(player, original);
    }

    private void setTextures(Player player, Property texture) {
        GameProfile profile = profileOf(player);
        if (profile == null) {
            plugin.getLogger().warning("Could not resolve GameProfile for "
                    + player.getName() + "; skin will not change.");
            return;
        }

        PropertyMap properties = profile.getProperties();
        properties.removeAll(TEXTURES);
        if (texture != null) {
            properties.put(TEXTURES, texture);
        }

        refresh(player);
    }

    /**
     * Forces every other client to despawn and re-spawn the player, which
     * rebuilds the player-info entry from the (now updated) GameProfile.
     */
    private void refresh(Player player) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(player) || !viewer.canSee(player)) {
                continue;
            }
            viewer.hidePlayer(plugin, player);
        }
        // Show again a tick later so the client processes the removal first.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (viewer.equals(player)) {
                    continue;
                }
                viewer.showPlayer(plugin, player);
            }
        }, 2L);
    }

    /** Resolves the live {@link GameProfile} of an online player via reflection. */
    private GameProfile profileOf(Player player) {
        // Try CraftPlayer#getProfile(), then the NMS handle's profile accessor.
        // Method names differ across versions, so we match on the return type.
        GameProfile profile = findGameProfile(player);
        if (profile != null) {
            return profile;
        }
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            return findGameProfile(handle);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private GameProfile findGameProfile(Object holder) {
        if (holder == null) {
            return null;
        }
        for (Method method : holder.getClass().getMethods()) {
            if (method.getParameterCount() == 0
                    && GameProfile.class.isAssignableFrom(method.getReturnType())) {
                try {
                    Object result = method.invoke(holder);
                    if (result instanceof GameProfile gameProfile) {
                        return gameProfile;
                    }
                } catch (ReflectiveOperationException ignored) {
                    // Try the next candidate.
                }
            }
        }
        return null;
    }
}
