package dev.anonymity;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;

/**
 * Swaps a player's skin by mutating the {@code textures} property on the live
 * {@code GameProfile} backing their entity, then re-sending the player to every
 * other online player so their client re-reads the new texture.
 *
 * <p>Everything here is done by reflection against {@code com.mojang.authlib}
 * <em>on purpose</em>: that library is reshaped between Minecraft versions. In
 * authlib 7.x (MC 1.21.9+) {@code GameProfile} became a {@code record}, its
 * {@code getProperties()} accessor was renamed to {@code properties()}, and
 * {@code Property} became a record too. Hard-linking against any one version
 * throws {@link NoSuchMethodError} on the others, so we never reference those
 * types at compile time and adapt to whatever the server actually ships.</p>
 */
final class SkinApplier {

    private static final String TEXTURES = "textures";
    private static final String PROPERTY_CLASS = "com.mojang.authlib.properties.Property";
    private static final String GAME_PROFILE_CLASS = "com.mojang.authlib.GameProfile";
    private static final String MULTIMAP_CLASS = "com.google.common.collect.Multimap";
    private static final String LINKED_MULTIMAP_CLASS = "com.google.common.collect.LinkedHashMultimap";
    // Accessor names across authlib versions: 6.x and older / 7.x record style.
    private static final String[] PROPERTIES_ACCESSORS = {"getProperties", "properties"};

    private final Plugin plugin;

    SkinApplier(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Returns the existing signed textures property (opaque), or {@code null}. */
    Object captureTextures(Player player) {
        Object map = propertyMapOf(player);
        if (map == null) {
            return null;
        }
        try {
            Collection<?> existing = (Collection<?>) map.getClass()
                    .getMethod("get", Object.class).invoke(map, TEXTURES);
            for (Object property : existing) {
                return property; // keep the whole Property object to restore later
            }
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().warning("Could not read current skin: " + e.getMessage());
        }
        return null;
    }

    /** Applies a signed (value + signature) texture and refreshes viewers. */
    void apply(Player player, String value, String signature) {
        Object property = buildProperty(value, signature);
        if (property != null) {
            setTextures(player, property);
        }
    }

    /** Restores a previously captured texture, or clears ours if there was none. */
    void restore(Player player, Object originalProperty) {
        setTextures(player, originalProperty);
    }

    /** Applies a raw captured texture property (used to copy another player's skin). */
    void applyRaw(Player player, Object property) {
        setTextures(player, property);
    }

    private void setTextures(Player player, Object propertyOrNull) {
        Object map = propertyMapOf(player);
        if (map == null) {
            plugin.getLogger().warning("Could not resolve GameProfile for "
                    + player.getName() + "; skin will not change.");
            return;
        }
        boolean changed = tryMutate(map, propertyOrNull)
                || (makeDelegateMutable(map) && tryMutate(map, propertyOrNull));
        if (!changed) {
            plugin.getLogger().warning("Skin swap is unsupported for " + player.getName()
                    + " (the profile texture map is immutable, typical of an offline-mode "
                    + "server). The scrambled name, particle aura and death messages still apply.");
            return;
        }
        refresh(player);
    }

    /** Direct multimap mutation; returns {@code false} if the map is immutable. */
    private boolean tryMutate(Object map, Object propertyOrNull) {
        try {
            map.getClass().getMethod("removeAll", Object.class).invoke(map, TEXTURES);
            if (propertyOrNull != null) {
                map.getClass().getMethod("put", Object.class, Object.class)
                        .invoke(map, TEXTURES, propertyOrNull);
            }
            return true;
        } catch (ReflectiveOperationException e) {
            // InvocationTargetException(UnsupportedOperationException) -> immutable map.
            return false;
        }
    }

    /**
     * Replaces a {@code PropertyMap}'s internal delegate with a mutable copy so a
     * later mutation succeeds. Refuses to touch the shared {@code PropertyMap.EMPTY}
     * singleton (mutating it would be global and, since the record still references
     * EMPTY, wouldn't take effect anyway).
     */
    private boolean makeDelegateMutable(Object map) {
        try {
            Class<?> mapClass = map.getClass();
            try {
                Object empty = mapClass.getField("EMPTY").get(null);
                if (map == empty) {
                    return false;
                }
            } catch (NoSuchFieldException ignored) {
                // Older authlib without an EMPTY singleton; safe to continue.
            }

            Field delegate = null;
            for (Field field : mapClass.getDeclaredFields()) {
                if (field.getType().getName().equals(MULTIMAP_CLASS)) {
                    delegate = field;
                    break;
                }
            }
            if (delegate == null) {
                return false;
            }
            delegate.setAccessible(true);

            Class<?> multimapClass = Class.forName(MULTIMAP_CLASS);
            Object mutable = Class.forName(LINKED_MULTIMAP_CLASS).getMethod("create").invoke(null);
            Object current = delegate.get(map);
            if (current != null) {
                mutable.getClass().getMethod("putAll", multimapClass).invoke(mutable, current);
            }
            delegate.set(map, mutable);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
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
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (viewer.equals(player)) {
                    continue;
                }
                viewer.showPlayer(plugin, player);
            }
        }, 2L);
    }

    /** Constructs a {@code com.mojang.authlib.properties.Property} reflectively. */
    private Object buildProperty(String value, String signature) {
        try {
            Class<?> propertyClass = Class.forName(PROPERTY_CLASS);
            Constructor<?> ctor = propertyClass.getConstructor(String.class, String.class, String.class);
            return ctor.newInstance(TEXTURES, value, signature);
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().warning("Could not build skin property: " + e.getMessage());
            return null;
        }
    }

    /** The live {@code PropertyMap} (a Guava Multimap) of the player's profile. */
    private Object propertyMapOf(Player player) {
        Object profile = profileOf(player);
        if (profile == null) {
            return null;
        }
        for (String accessor : PROPERTIES_ACCESSORS) {
            try {
                return profile.getClass().getMethod(accessor).invoke(profile);
            } catch (NoSuchMethodException ignored) {
                // Try the next naming convention.
            } catch (ReflectiveOperationException e) {
                plugin.getLogger().warning("Could not read profile properties: " + e.getMessage());
                return null;
            }
        }
        plugin.getLogger().warning("No properties accessor found on GameProfile.");
        return null;
    }

    /** Resolves the live GameProfile of an online player via reflection. */
    private Object profileOf(Player player) {
        // Prefer the NMS handle's profile: that is the exact instance the server
        // serialises into the player-info packet, so mutating it is what other
        // clients actually see. CraftPlayer#getProfile() can return a copy.
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            Object fromHandle = findGameProfile(handle);
            if (fromHandle != null) {
                return fromHandle;
            }
        } catch (ReflectiveOperationException ignored) {
            // Fall back to the CraftPlayer below.
        }
        return findGameProfile(player);
    }

    /** Finds a no-arg method returning a GameProfile and invokes it. */
    private Object findGameProfile(Object holder) {
        if (holder == null) {
            return null;
        }
        for (Method method : holder.getClass().getMethods()) {
            if (method.getParameterCount() == 0
                    && method.getReturnType().getName().equals(GAME_PROFILE_CLASS)) {
                try {
                    Object result = method.invoke(holder);
                    if (result != null) {
                        return result;
                    }
                } catch (ReflectiveOperationException ignored) {
                    // Try the next candidate.
                }
            }
        }
        return null;
    }
}
