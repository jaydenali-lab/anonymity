package dev.anonymity;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Entry point. Wires together the {@link AnonymityManager}, the {@code /anonymous}
 * command, the particle aura task and the death-message / cleanup listeners.
 */
public final class AnonymityPlugin extends JavaPlugin {

    private AnonymityManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.manager = new AnonymityManager(this);

        FragmentItem fragment = new FragmentItem(this);
        getCommand("fragment").setExecutor(new FragmentCommand(fragment));

        getServer().getPluginManager().registerEvents(new AnonymityListener(manager), this);
        getServer().getPluginManager().registerEvents(new DashListener(manager), this);
        getServer().getPluginManager().registerEvents(new FragmentListener(manager, fragment), this);

        manager.startTickTask();

        getLogger().info("Anonymity enabled.");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            // Restore everyone so the server doesn't leak scrambled names / skins
            // if the plugin is reloaded.
            manager.restoreAll();
        }
        getLogger().info("Anonymity disabled.");
    }

    public AnonymityManager getManager() {
        return manager;
    }
}
