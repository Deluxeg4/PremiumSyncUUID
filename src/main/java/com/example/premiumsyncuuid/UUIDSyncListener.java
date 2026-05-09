package com.example.premiumsyncuuid;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class UUIDSyncListener implements Listener {

    private final JavaPlugin plugin;
    private final File dataFolder;
    private final File mapFile;
    private final File nameFile;
    private final File migratedFolder;
    private final boolean isFoliaOrPaper;

    private final Map<UUID, UUID> onlineToOfflineMap = new ConcurrentHashMap<>();
    private final Map<UUID, String> onlineToNameMap = new ConcurrentHashMap<>();

    public UUIDSyncListener(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dataFolder = plugin.getDataFolder();
        this.dataFolder.mkdirs();
        this.mapFile = new File(dataFolder, "uuid_map.properties");
        this.nameFile = new File(dataFolder, "uuid_name.properties");
        this.migratedFolder = new File(dataFolder, "migrated");
        this.migratedFolder.mkdirs();
        this.isFoliaOrPaper = detectFoliaOrPaper();
        loadMaps();
    }

    // ========================
    // Auto-detect Paper/Folia
    // ========================
    private boolean detectFoliaOrPaper() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true; // Folia
        } catch (ClassNotFoundException ignored) {}
        try {
            plugin.getServer().getClass().getMethod("getAsyncScheduler");
            return true; // Paper
        } catch (NoSuchMethodException ignored) {}
        return false; // Bukkit/Spigot
    }

    private void runAsync(Runnable task) {
        if (isFoliaOrPaper) {
            plugin.getServer().getAsyncScheduler().runNow(plugin, t -> task.run());
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    private void runAsyncDelayed(Runnable task, long delayMs) {
        if (isFoliaOrPaper) {
            plugin.getServer().getAsyncScheduler().runDelayed(plugin, t -> task.run(), delayMs, TimeUnit.MILLISECONDS);
        } else {
            long ticks = Math.max(1, delayMs / 50);
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, ticks);
        }
    }

    // ========================
    // Load/Save maps
    // ========================
    private void loadMaps() {
        if (mapFile.exists()) {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(mapFile)) {
                props.load(fis);
                for (String key : props.stringPropertyNames()) {
                    onlineToOfflineMap.put(UUID.fromString(key), UUID.fromString(props.getProperty(key)));
                }
            } catch (IOException e) {
                plugin.getLogger().severe("[UUIDSync] Failed to load uuid_map: " + e.getMessage());
            }
        }

        if (nameFile.exists()) {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(nameFile)) {
                props.load(fis);
                for (String key : props.stringPropertyNames()) {
                    onlineToNameMap.put(UUID.fromString(key), props.getProperty(key));
                }
            } catch (IOException e) {
                plugin.getLogger().severe("[UUIDSync] Failed to load uuid_name: " + e.getMessage());
            }
        }
    }

    private void saveMaps() {
        Properties mapProps = new Properties();
        onlineToOfflineMap.forEach((k, v) -> mapProps.setProperty(k.toString(), v.toString()));
        try (FileOutputStream fos = new FileOutputStream(mapFile)) {
            mapProps.store(fos, "UUIDSync online->offline map");
        } catch (IOException e) {
            plugin.getLogger().severe("[UUIDSync] Failed to save uuid_map: " + e.getMessage());
        }

        Properties nameProps = new Properties();
        onlineToNameMap.forEach((k, v) -> nameProps.setProperty(k.toString(), v));
        try (FileOutputStream fos = new FileOutputStream(nameFile)) {
            nameProps.store(fos, "UUIDSync online->name map");
        } catch (IOException e) {
            plugin.getLogger().severe("[UUIDSync] Failed to save uuid_name: " + e.getMessage());
        }
    }

    // ========================
    // Helper
    // ========================
    private UUID getOfflineUUID(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    // ========================
    // LOGIN: Offline -> Online
    // ========================
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        UUID onlineUUID = event.getUniqueId();
        String playerName = event.getName();
        UUID offlineUUID = getOfflineUUID(playerName);

        if (onlineUUID.equals(offlineUUID)) return;

        onlineToOfflineMap.put(onlineUUID, offlineUUID);

        String oldName = onlineToNameMap.get(onlineUUID);
        boolean nameChanged = oldName != null && !oldName.equals(playerName);

        if (nameChanged) {
            UUID oldOfflineUUID = getOfflineUUID(oldName);
            copyData(onlineUUID, offlineUUID);
            archiveOldData(oldOfflineUUID);
        }

        onlineToNameMap.put(onlineUUID, playerName);
        saveMaps();

        File flagFile = new File(migratedFolder, onlineUUID + ".flag");
        if (!flagFile.exists() && !nameChanged) {
            boolean success = copyData(offlineUUID, onlineUUID);
            if (success) {
                try { flagFile.createNewFile(); } catch (IOException ignored) {}
            }
        }
    }

    // ========================
    // QUIT: Online -> Offline
    // ========================
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID onlineUUID = event.getPlayer().getUniqueId();
        UUID offlineUUID = onlineToOfflineMap.get(onlineUUID);
        if (offlineUUID == null) return;

        runAsyncDelayed(() -> copyData(onlineUUID, offlineUUID), 20L);
    }

    // ========================
    // WORLD SAVE: sync ทุกคน (กัน crash)
    // ========================
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldSave(WorldSaveEvent event) {
        if (!event.getWorld().equals(Bukkit.getWorlds().get(0))) return;

        runAsync(() -> {
            for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
                UUID onlineUUID = player.getUniqueId();
                UUID offlineUUID = onlineToOfflineMap.get(onlineUUID);
                if (offlineUUID == null) continue;
                copyData(onlineUUID, offlineUUID);
            }
            saveMaps();
        });
    }

    // ========================
    // Copy data (ใช้ได้ทั้ง 2 ทิศทาง)
    // ========================
    private boolean copyData(UUID fromUUID, UUID toUUID) {
        boolean anySuccess = false;

        for (World world : Bukkit.getWorlds()) {
            File playerDataFolder = new File(world.getWorldFolder(), "playerdata");
            File oldFile = new File(playerDataFolder, fromUUID + ".dat");
            File newFile = new File(playerDataFolder, toUUID + ".dat");

            if (oldFile.exists()) {
                try {
                    Files.copy(oldFile.toPath(), newFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    anySuccess = true;
                } catch (IOException e) {
                    plugin.getLogger().severe("[UUIDSync] Failed playerdata: " + e.getMessage());
                }
            }
        }

        if (!Bukkit.getWorlds().isEmpty()) {
            File baseFolder = Bukkit.getWorlds().get(0).getWorldFolder();

            File oldAdv = new File(baseFolder, "advancements/" + fromUUID + ".json");
            File newAdv = new File(baseFolder, "advancements/" + toUUID + ".json");
            if (oldAdv.exists()) {
                try {
                    Files.copy(oldAdv.toPath(), newAdv.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    plugin.getLogger().severe("[UUIDSync] Failed advancements: " + e.getMessage());
                }
            }

            File oldStats = new File(baseFolder, "stats/" + fromUUID + ".json");
            File newStats = new File(baseFolder, "stats/" + toUUID + ".json");
            if (oldStats.exists()) {
                try {
                    Files.copy(oldStats.toPath(), newStats.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    plugin.getLogger().severe("[UUIDSync] Failed stats: " + e.getMessage());
                }
            }
        }

        return anySuccess;
    }

    // ========================
    // Archive Offline เก่า (กัน dupe)
    // ========================
    private void archiveOldData(UUID oldOfflineUUID) {
        for (World world : Bukkit.getWorlds()) {
            File playerDataFolder = new File(world.getWorldFolder(), "playerdata");
            File oldFile = new File(playerDataFolder, oldOfflineUUID + ".dat");
            if (oldFile.exists()) {
                oldFile.renameTo(new File(playerDataFolder, oldOfflineUUID + ".dat.bak"));
            }
        }

        if (!Bukkit.getWorlds().isEmpty()) {
            File baseFolder = Bukkit.getWorlds().get(0).getWorldFolder();

            File oldAdv = new File(baseFolder, "advancements/" + oldOfflineUUID + ".json");
            if (oldAdv.exists()) oldAdv.renameTo(new File(baseFolder, "advancements/" + oldOfflineUUID + ".json.bak"));

            File oldStats = new File(baseFolder, "stats/" + oldOfflineUUID + ".json");
            if (oldStats.exists()) oldStats.renameTo(new File(baseFolder, "stats/" + oldOfflineUUID + ".json.bak"));
        }
    }
}