package net.minecraftforge.auctionbridge.web.websocket;

import io.javalin.websocket.WsContext;
import net.minecraftforge.auctionbridge.config.BridgeConfig;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 线程安全的 WebSocket 广播与会话管理器
 */
public class WebSocketBroadcastService {

    private final JavaPlugin plugin;
    private final BridgeConfig config;
    private final Set<WsContext> sessions = ConcurrentHashMap.newKeySet();
    private BukkitTask heartbeatTask;

    public WebSocketBroadcastService(JavaPlugin plugin, BridgeConfig config) {
        this.plugin = plugin;
        this.config = config;
        startHeartbeat();
    }

    public void addSession(WsContext ctx) {
        sessions.add(ctx);
        plugin.getLogger().fine("新的 WebSocket 客户端已连接. 当前总连接数: " + sessions.size());
    }

    public void removeSession(WsContext ctx) {
        sessions.remove(ctx);
        plugin.getLogger().fine("WebSocket 客户端已断开. 当前总连接数: " + sessions.size());
    }

    public int getActiveSessionCount() {
        return sessions.size();
    }

    /**
     * 线程安全地发送 WebSocket 消息，防止 Jetty 多线程并发写导致连接崩溃
     */
    private boolean safeSend(WsContext ctx, String message) {
        if (ctx == null || ctx.session == null) {
            return false;
        }
        try {
            synchronized (ctx.session) {
                if (ctx.session.isOpen()) {
                    ctx.send(message);
                    return true;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().fine("WebSocket 发送异常: " + e.getMessage());
        }
        return false;
    }

    /**
     * 广播 JSON 事件字符串给所有在线的 WebSocket 客户端
     */
    public void broadcast(String message) {
        for (WsContext ctx : sessions) {
            if (!safeSend(ctx, message)) {
                sessions.remove(ctx);
            }
        }
    }

    private void startHeartbeat() {
        int interval = config.getHeartbeatIntervalSeconds();
        this.heartbeatTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
            plugin,
            () -> {
                String pingMessage = "{\"type\":\"PING\",\"time\":" + System.currentTimeMillis() + "}";
                for (WsContext ctx : sessions) {
                    if (!safeSend(ctx, pingMessage)) {
                        sessions.remove(ctx);
                    }
                }
            },
            20L * interval,
            20L * interval
        );
    }

    public void shutdown() {
        if (heartbeatTask != null && !heartbeatTask.isCancelled()) {
            heartbeatTask.cancel();
        }
        for (WsContext ctx : sessions) {
            try {
                ctx.session.close(1001, "Server shutting down");
            } catch (Exception ignored) {}
        }
        sessions.clear();
    }
}
