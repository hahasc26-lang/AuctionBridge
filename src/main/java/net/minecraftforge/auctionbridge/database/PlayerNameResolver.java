package net.minecraftforge.auctionbridge.database;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 线程安全且防穿透的玩家名称解析器
 * 采用标准 LRU 淘汰并结合负缓存机制，避免高频离线查询引发频繁磁盘 IO 堵塞
 */
public final class PlayerNameResolver {

    private static final int MAX_CACHE_SIZE = 5_000;

    private final JavaPlugin plugin;
    private final Map<UUID, String> nameCache = Collections.synchronizedMap(
            new LinkedHashMap<>(512, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, String> eldest) {
                    return size() > MAX_CACHE_SIZE;
                }
            }
    );

    public PlayerNameResolver(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public String resolveName(String uuidText) {
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidText);
        } catch (IllegalArgumentException | NullPointerException e) {
            return fallbackName(uuidText);
        }

        String cachedName = nameCache.get(uuid);
        if (cachedName != null) {
            return cachedName;
        }

        if (Bukkit.isPrimaryThread()) {
            return fallbackName(uuidText);
        }

        try {
            Player onlinePlayer = Bukkit.getPlayer(uuid);
            if (onlinePlayer != null && onlinePlayer.getName() != null) {
                nameCache.put(uuid, onlinePlayer.getName());
                return onlinePlayer.getName();
            }

            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
            String offlineName = offlinePlayer.getName();
            if (offlineName != null && !offlineName.isEmpty()) {
                nameCache.put(uuid, offlineName);
                return offlineName;
            }
        } catch (RuntimeException e) {
            plugin.getLogger().fine("Could not resolve player name for " + uuidText + ": " + e.getMessage());
        }

        // 负缓存：对查询不到名字的离线 UUID 也记录 fallback 名称，防止反复穿透执行磁盘查找
        String fallback = fallbackName(uuidText);
        nameCache.put(uuid, fallback);
        return fallback;
    }

    private String fallbackName(String uuidText) {
        String suffix = uuidText == null ? "" : uuidText.replace("-", "");
        if (suffix.length() > 8) {
            suffix = suffix.substring(0, 8);
        }
        return "Player-" + (suffix.isEmpty() ? "Unknown" : suffix);
    }
}
