package com.example.premiumsyncuuid;

import org.bukkit.plugin.java.JavaPlugin;

public class PremiumSyncUUID extends JavaPlugin {
    private UUIDSyncListener listener;

    @Override
    public void onEnable() {
        listener = new UUIDSyncListener(this);
        getServer().getPluginManager().registerEvents(listener, this);
        getLogger().info("PremiumSyncUUID enabled!");
    }

    @Override
    public void onDisable() {
        if (listener != null) {
            listener.saveMaps();
        }
        getLogger().info("PremiumSyncUUID disabled!");
    }
}
