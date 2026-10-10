package net.minecraftforge.auctionbridge.events.fallback;

import net.minecraftforge.auctionbridge.config.BridgeConfig;
import net.minecraftforge.auctionbridge.database.AuctionDatabaseService;
import net.minecraftforge.auctionbridge.events.AuctionBridgeEventBus;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 命令与经济流审计降级补偿器
 * 当玩家输入 /ah sell <价格> 时，主动触发延迟校验，从数据库提取刚上架的条目并立即推送
 */
public class CommandAndEconomyWatcher implements Listener {

    private final JavaPlugin plugin;
    private final AuctionBridgeEventBus eventBus;
    private final AuctionDatabaseService databaseService;
    private final BridgeConfig config;

    public CommandAndEconomyWatcher(JavaPlugin plugin, AuctionBridgeEventBus eventBus, AuctionDatabaseService databaseService, BridgeConfig config) {
        this.plugin = plugin;
        this.eventBus = eventBus;
        this.databaseService = databaseService;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!config.isCommandAuditEnabled()) return;

        String cmd = event.getMessage().toLowerCase();
        // 匹配常用拍卖上架命令
        if (cmd.startsWith("/ah sell") || cmd.startsWith("/auction sell") || cmd.startsWith("/ah list")) {
            final String playerName = event.getPlayer().getName();
            final String playerUuid = event.getPlayer().getUniqueId().toString();
            final long commandTime = System.currentTimeMillis();

            // 延迟 1.25 秒异步读取数据库（等待第三方插件完成写入）
            plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                try {
                    var latest = databaseService.queryAuctions("ACTIVE", null, null, null, playerUuid, "id_desc", 1, 1);
                    if (!latest.isEmpty()) {
                        var item = latest.get(0);
                        // 校验该物品是否确实为本次命令产生的新物品，防止命令失败时误播历史旧物品
                        long createdAt = item.getCreatedAt();
                        if (createdAt > 0 && createdAt < (commandTime - 5000L)) {
                            // 该在售物品创建时间早于命令触发时刻，说明本次命令未成功上架新物品
                            return;
                        }
                        eventBus.publish(AuctionBridgeEventBus.EventType.ITEM_LISTED, item, "Triggered by player command audit: " + playerName);
                    }
                } catch (Exception ignored) {}
            }, 25L);
        }
    }
}
