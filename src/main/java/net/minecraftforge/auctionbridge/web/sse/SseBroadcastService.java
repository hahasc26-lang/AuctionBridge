package net.minecraftforge.auctionbridge.web.sse;

import io.javalin.http.sse.SseClient;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-Sent Events (SSE) 备用长连接推送机制
 */
public class SseBroadcastService {

    private final Set<SseClient> clients = ConcurrentHashMap.newKeySet();

    public SseBroadcastService(JavaPlugin plugin) {
    }

    public void handleSseConnection(SseClient client) {
        clients.add(client);
        client.onClose(() -> clients.remove(client));
        client.sendEvent("CONNECTED", "{\"message\":\"AuctionBridge SSE Stream Established\"}");
    }

    public void broadcast(String jsonPayload) {
        for (SseClient client : clients) {
            try {
                client.sendEvent("auction_update", jsonPayload);
            } catch (Exception e) {
                clients.remove(client);
            }
        }
    }
}
