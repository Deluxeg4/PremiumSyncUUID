package com.example.premiumsyncuuid;

import org.bukkit.plugin.java.JavaPlugin;

public class PremiumSyncUUID extends JavaPlugin {

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(new UUIDSyncListener(this), this);
        getLogger().info("PremiumSyncUUID enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("PremiumSyncUUID disabled!");
    }
}
