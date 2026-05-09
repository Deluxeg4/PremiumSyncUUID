# PremiumSyncUUID

PremiumSyncUUID is a lightweight and highly efficient Minecraft server plugin designed to seamlessly synchronize player data between Offline-mode (Cracked) UUIDs and Online-mode (Premium) UUIDs. 

This plugin is essential for offline-mode (cracked) servers that allow premium players to join securely using their real Premium UUIDs (e.g., via FastLogin or Auto-In), ensuring they retain their inventories, stats, and advancements without any data loss!

## Features

- **Seamless Two-Way Synchronization**: 
  - **Login:** Copies existing offline data -> Premium UUID.
  - **Quit / World Save:** Syncs Premium data back -> Offline UUID.
- **Full Data Coverage**: Synchronizes `playerdata` (.dat files for inventory, enderchest, location, etc.), `advancements` (.json), and `stats` (.json).
- **Folia & PaperMC Optimized**: Automatically detects the server platform and uses the correct Asynchronous Schedulers (e.g., Regionized thread handling in Folia) to guarantee zero lag on the main server thread.
- **Smart Name Change Handling**: Automatically detects when a premium player changes their Minecraft username. It migrates their data to the new offline UUID and safely archives the old data (`.bak`) to prevent duplication or overriding.
- **Crash-Proof**: Periodically syncs data asynchronously during world saves to prevent rollback or data loss in the event of a sudden server crash.

## Compatibility

- **Java Version:** 17+
- **Minecraft API:** 1.20+
- **Supported Platforms:** Bukkit, Spigot, Paper, and Folia.

## Downloads

You can download the compiled plugin from the following sources:
- **GitHub Releases**: [Latest Release](../../releases)
- **Modrinth**: [PremiumUUIDSync](https://modrinth.com/plugin/premiuuuidsync)

## Installation

1. Download the latest `.jar` file from the releases page or Modrinth.
2. Place the `.jar` file into your server's `plugins` directory.
3. Restart your server.
4. The plugin works completely **out of the box**—no configuration is required! 

## How It Works (Behind the Scenes)

1. **Pre-Login Phase:** When a player connects, the plugin calculates their Offline UUID using their username (`OfflinePlayer:PlayerName`). If their Online UUID differs from the Offline UUID, the synchronization process begins.
2. **First Premium Login:** If the player previously played as cracked and is now logging in as premium, their old data is instantly copied to their new Premium UUID profile.
3. **Data Mirroring:** Whenever the player leaves or the server initiates a world save, the player's updated data is mirrored back to their Offline UUID file.
4. **Data Mapping:** The plugin keeps track of UUID mappings internally via `uuid_map.properties` and `uuid_name.properties` in the plugin's data folder.

## Building from Source

To build the plugin yourself, you need Java 17 and Gradle.

```bash
git clone https://github.com/PolarBearEX-8/PremiumSyncUUID.git
cd PremiumSyncUUID
./gradlew build
```
The compiled jar will be available in `build/libs/`.

## License
This project is open-source. Feel free to fork, contribute, and modify.
