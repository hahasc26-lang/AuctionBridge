package net.minecraftforge.auctionbridge.events.fallback;

import net.minecraftforge.auctionbridge.database.AuctionDatabaseService;
import net.minecraftforge.auctionbridge.AuctionBridgePlugin;
import net.minecraftforge.auctionbridge.database.model.AuctionItem;
import net.minecraftforge.auctionbridge.events.AuctionBridgeEventBus;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 降级扫描器：定时从数据库拉取新增与售出状态变动记录
 * 当第三方 AuctionHouse 插件没有对外提供可监听的 Bukkit API 时，本任务作为 100% 可靠的数据兜底保障
 */
public class DatabaseWatcherTask implements Runnable {

    private static final int MAX_TRACKED_ACTIVE_ITEMS = 300;

    private final JavaPlugin plugin;
    private final AuctionDatabaseService databaseService;
    private final AuctionBridgeEventBus eventBus;
    private final int intervalSeconds;
    private final AtomicLong lastKnownId = new AtomicLong(0);
    // 追踪近期在售的物品 ID，以便在被买走 (buyer_uuid 变更) 时精准触发售出事件
    private final Set<Long> trackedActiveIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private BukkitTask task;

    public DatabaseWatcherTask(JavaPlugin plugin, AuctionDatabaseService databaseService, AuctionBridgeEventBus eventBus, int intervalSeconds) {
        this.plugin = plugin;
        this.databaseService = databaseService;
        this.eventBus = eventBus;
        this.intervalSeconds = intervalSeconds;
    }

    public void start() {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            // 1. 初始化当前最新 ID 游标
            List<AuctionItem> latest = databaseService.queryAuctions(null, null, null, null, null, "id_desc", 1, 1);
            if (!latest.isEmpty()) {
                lastKnownId.set(latest.get(0).getId());
            }

            // 2. 初始化近期在售物品集合，开启状态变动监听
            List<Long> recentActive = databaseService.queryRecentActiveItemIds(MAX_TRACKED_ACTIVE_ITEMS);
            trackedActiveIds.addAll(recentActive);

            String message = plugin instanceof AuctionBridgePlugin auctionBridgePlugin
                    ? auctionBridgePlugin.lang("database.cursorInitialized", lastKnownId.get())
                    : "Database scan cursor initialized to ID: " + lastKnownId.get() + ", tracking " + trackedActiveIds.size() + " active items.";
            plugin.getLogger().info(message);

            // 3. 启动定时异步轮询任务
            this.task = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                    plugin, this, 20L * intervalSeconds, 20L * intervalSeconds
            );
        });
    }

    @Override
    public void run() {
        try {
            // A. 检测新上架物品 (ID 增长)
            long currentCursor = lastKnownId.get();
            List<AuctionItem> newItems = databaseService.queryRecentUpdatedAfter(currentCursor, 50);

            for (AuctionItem item : newItems) {
                if (item.getId() > lastKnownId.get()) {
                    lastKnownId.set(item.getId());
                }

                if ("ACTIVE".equalsIgnoreCase(item.getStatus())) {
                    if (trackedActiveIds.size() >= MAX_TRACKED_ACTIVE_ITEMS) {
                        // 适度截断最旧的 ID，防止追踪集合无上限增长
                        trackedActiveIds.stream().min(Long::compareTo).ifPresent(trackedActiveIds::remove);
                    }
                    trackedActiveIds.add(item.getId());
                    eventBus.publish(AuctionBridgeEventBus.EventType.ITEM_LISTED, item, "Detected via DB delta scanner (ID: " + item.getId() + ")");
                } else if ("SOLD".equalsIgnoreCase(item.getStatus())) {
                    eventBus.publish(AuctionBridgeEventBus.EventType.ITEM_PURCHASED, item, "Detected via DB delta scanner (ID: " + item.getId() + ")");
                } else if ("CANCELLED".equalsIgnoreCase(item.getStatus())) {
                    eventBus.publish(AuctionBridgeEventBus.EventType.ITEM_CANCELLED, item, "Detected via DB delta scanner (ID: " + item.getId() + ")");
                } else if ("EXPIRED".equalsIgnoreCase(item.getStatus())) {
                    eventBus.publish(AuctionBridgeEventBus.EventType.ITEM_EXPIRED, item, "Detected via DB delta scanner (ID: " + item.getId() + ")");
                }
            }

            // B. 检测已在售物品的状态变动 (售出与下架物理删除监控)
            if (!trackedActiveIds.isEmpty()) {
                AuctionDatabaseService.TrackedStatusResult inspection = databaseService.inspectTrackedItems(trackedActiveIds);
                // 1. 处理售出物品
                for (AuctionItem soldItem : inspection.getSoldItems()) {
                    trackedActiveIds.remove(soldItem.getId());
                    eventBus.publish(AuctionBridgeEventBus.EventType.ITEM_PURCHASED, soldItem,
                            "Detected via DB status watcher (Sold ID: " + soldItem.getId() + ")");
                }
                // 2. 清理已被物理删除的物品（下架/过期），释放槽位避免幽灵泄漏
                for (Long missingId : inspection.getMissingIds()) {
                    trackedActiveIds.remove(missingId);
                    AuctionItem placeholder = new AuctionItem();
                    placeholder.setId(missingId);
                    placeholder.setStatus("CANCELLED");
                    eventBus.publish(AuctionBridgeEventBus.EventType.ITEM_CANCELLED, placeholder,
                            "Detected via DB status watcher (Listing removed/cancelled from database ID: " + missingId + ")");
                }
            }
        } catch (Exception e) {
            plugin.getLogger().fine("数据库增量扫描循环异常: " + e.getMessage());
        }
    }

    public void stop() {
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
        trackedActiveIds.clear();
    }
}
