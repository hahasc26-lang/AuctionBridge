package net.minecraftforge.auctionbridge.events;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraftforge.auctionbridge.database.model.AuctionItem;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 统一事件分发总线
 * 将各种来源 (原生事件 / 降级扫描 / 命令推测) 的拍卖动作标准化并广播
 */
public class AuctionBridgeEventBus {

    private final JavaPlugin plugin;
    private final Gson gson = new Gson();
    private final CopyOnWriteArrayList<Consumer<String>> subscribers = new CopyOnWriteArrayList<>();

    public enum EventType {
        ITEM_LISTED("ITEM_LISTED"),
        ITEM_PURCHASED("ITEM_PURCHASED"),
        ITEM_CANCELLED("ITEM_CANCELLED"),
        ITEM_EXPIRED("ITEM_EXPIRED");

        private final String code;
        EventType(String code) { this.code = code; }
        public String getCode() { return code; }
    }

    public AuctionBridgeEventBus(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void registerSubscriber(Consumer<String> subscriber) {
        subscribers.add(subscriber);
    }

    public void unregisterSubscriber(Consumer<String> subscriber) {
        subscribers.remove(subscriber);
    }

    /**
     * 广播拍卖事件给所有已连接的客户端 (WebSocket & SSE)
     */
    public void publish(EventType type, AuctionItem item, String extraDetails) {
        JsonObject json = new JsonObject();
        json.addProperty("event", type.getCode());
        json.addProperty("timestamp", System.currentTimeMillis());
        json.add("item", gson.toJsonTree(item));
        if (extraDetails != null) {
            json.addProperty("details", extraDetails);
        }

        String payload = gson.toJson(json);

        // 异步分发给订阅者，绝不阻塞主线程
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            for (Consumer<String> sub : subscribers) {
                try {
                    sub.accept(payload);
                } catch (Exception e) {
                    plugin.getLogger().fine("广播事件至客户端时出现通道异常: " + e.getMessage());
                }
            }
        });
    }
}
