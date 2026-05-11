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
    private final boolean isModernScheduler;

    private final Map<UUID, UUID> onlineToOfflineMap = new ConcurrentHashMap<>();
    private final Map<UUID, String> onlineToNameMap = new ConcurrentHashMap<>();

    public UUIDSyncListener(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dataFolder = plugin.getDataFolder();
        if (!this.dataFolder.exists()) {
            this.dataFolder.mkdirs();
        }
        this.mapFile = new File(dataFolder, "uuid_map.properties");
        this.nameFile = new File(dataFolder, "uuid_name.properties");
        this.migratedFolder = new File(dataFolder, "migrated");
        if (!this.migratedFolder.exists()) {
            this.migratedFolder.mkdirs();
        }
        this.isModernScheduler = detectModernScheduler();
        loadMaps();
    }

    private boolean detectModernScheduler() {
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
        if (isModernScheduler) {
            plugin.getServer().getAsyncScheduler().runNow(plugin, t -> task.run());
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    private void runAsyncDelayed(Runnable task, long delayMs) {
        if (isModernScheduler) {
            plugin.getServer().getAsyncScheduler().runDelayed(plugin, t -> task.run(), delayMs, TimeUnit.MILLISECONDS);
        } else {
            long ticks = Math.max(1, delayMs / 50);
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, ticks);
        }
    }

    private void loadMaps() {
        loadMap(mapFile, (k, v) -> onlineToOfflineMap.put(UUID.fromString(k), UUID.fromString(v)));
        loadMap(nameFile, (k, v) -> onlineToNameMap.put(UUID.fromString(k), v));
    }

    private void loadMap(File file, java.util.function.BiConsumer<String, String> consumer) {
        if (file.exists()) {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(file)) {
                props.load(fis);
                props.forEach((k, v) -> consumer.accept((String) k, (String) v));
            } catch (IOException | IllegalArgumentException e) {
                plugin.getLogger().severe("[UUIDSync] Failed to load " + file.getName() + ": " + e.getMessage());
            }
        }
    }

    public void saveMaps() {
        saveMap(mapFile, onlineToOfflineMap, "UUIDSync online->offline map");
        saveMap(nameFile, onlineToNameMap, "UUIDSync online->name map");
    }

    private <K, V> void saveMap(File file, Map<K, V> map, String comments) {
        Properties props = new Properties();
        map.forEach((k, v) -> props.setProperty(k.toString(), v.toString()));
        try (FileOutputStream fos = new FileOutputStream(file)) {
            props.store(fos, comments);
        } catch (IOException e) {
            plugin.getLogger().severe("[UUIDSync] Failed to save " + file.getName() + ": " + e.getMessage());
        }
    }

    private UUID getOfflineUUID(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

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
            syncPlayerData(onlineUUID, offlineUUID);
            archiveOldData(oldOfflineUUID);
        }

        onlineToNameMap.put(onlineUUID, playerName);
        saveMaps();

        File flagFile = new File(migratedFolder, onlineUUID + ".flag");
        if (!flagFile.exists() && !nameChanged) {
            if (syncPlayerData(offlineUUID, onlineUUID)) {
                try {
                    flagFile.createNewFile();
                } catch (IOException e) {
                    plugin.getLogger().warning("[UUIDSync] Could not create flag file: " + e.getMessage());
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID onlineUUID = event.getPlayer().getUniqueId();
        UUID offlineUUID = onlineToOfflineMap.get(onlineUUID);
        if (offlineUUID != null) {
            runAsyncDelayed(() -> syncPlayerData(onlineUUID, offlineUUID), 1000L); // 1 second delay
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldSave(WorldSaveEvent event) {
        // Only trigger on the main world to avoid multiple triggers
        if (Bukkit.getWorlds().isEmpty() || !event.getWorld().equals(Bukkit.getWorlds().get(0))) return;

        runAsync(() -> {
            for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
                UUID onlineUUID = player.getUniqueId();
                UUID offlineUUID = onlineToOfflineMap.get(onlineUUID);
                if (offlineUUID != null) {
                    syncPlayerData(onlineUUID, offlineUUID);
                }
            }
            saveMaps();
        });
    }

    private boolean syncPlayerData(UUID fromUUID, UUID toUUID) {
        boolean success = false;
        for (World world : Bukkit.getWorlds()) {
            File worldDir = world.getWorldFolder();
            if (copyFile(new File(worldDir, "playerdata/" + fromUUID + ".dat"), new File(worldDir, "playerdata/" + toUUID + ".dat"))) {
                success = true;
            }
        }

        if (!Bukkit.getWorlds().isEmpty()) {
            File mainWorldDir = Bukkit.getWorlds().get(0).getWorldFolder();
            copyFile(new File(mainWorldDir, "advancements/" + fromUUID + ".json"), new File(mainWorldDir, "advancements/" + toUUID + ".json"));
            copyFile(new File(mainWorldDir, "stats/" + fromUUID + ".json"), new File(mainWorldDir, "stats/" + toUUID + ".json"));
        }
        return success;
    }

    private void archiveOldData(UUID uuid) {
        for (World world : Bukkit.getWorlds()) {
            renameToBackup(new File(world.getWorldFolder(), "playerdata/" + uuid + ".dat"));
        }
        if (!Bukkit.getWorlds().isEmpty()) {
            File mainWorldDir = Bukkit.getWorlds().get(0).getWorldFolder();
            renameToBackup(new File(mainWorldDir, "advancements/" + uuid + ".json"));
            renameToBackup(new File(mainWorldDir, "stats/" + uuid + ".json"));
        }
    }

    private boolean copyFile(File source, File target) {
        if (!source.exists()) return false;
        try {
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("[UUIDSync] Failed to copy " + source.getName() + ": " + e.getMessage());
            return false;
        }
    }

    private void renameToBackup(File file) {
        if (file.exists()) {
            File backup = new File(file.getPath() + ".bak");
            if (!file.renameTo(backup)) {
                plugin.getLogger().warning("[UUIDSync] Failed to backup " + file.getName());
            }
        }
    }
}