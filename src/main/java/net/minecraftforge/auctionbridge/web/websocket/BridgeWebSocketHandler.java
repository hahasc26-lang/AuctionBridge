package net.minecraftforge.auctionbridge.web.websocket;

import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsErrorContext;
import io.javalin.websocket.WsMessageContext;
import net.minecraftforge.auctionbridge.config.BridgeConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * WebSocket 链接事件生命周期处理
 */
public class BridgeWebSocketHandler {

    private final WebSocketBroadcastService broadcastService;
    private final BridgeConfig config;
    private final boolean requireApiKey;

    public BridgeWebSocketHandler(WebSocketBroadcastService broadcastService, BridgeConfig config,
                                  boolean requireApiKey) {
        this.broadcastService = broadcastService;
        this.config = config;
        this.requireApiKey = requireApiKey;
    }

    public void onConnect(WsConnectContext ctx) {
        // 如果开启了 API Key，校验 Query 参数中携带的 apiKey 或 Token
        if (requireApiKey && config.isAuthRequired()) {
            String apiKey = ctx.queryParam("apiKey");
            String expectedApiKey = config.getApiKey();
            if (apiKey == null || expectedApiKey == null || !isConstantTimeEquals(apiKey, expectedApiKey)) {
                ctx.session.close(4401, "Unauthorized: Invalid or missing apiKey parameter");
                return;
            }
        }

        broadcastService.addSession(ctx);
        // 发送握手欢迎与初始元信息
        ctx.send("{\"type\":\"CONNECTED\",\"message\":\"Welcome to AuctionBridge WebSocket Stream\",\"time\":" + System.currentTimeMillis() + "}");
    }

    private boolean isConstantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(aBytes, bBytes);
    }

    public void onMessage(WsMessageContext ctx) {
        String msg = ctx.message().trim();
        // 客户端心跳 PONG 回复
        if ("PING".equalsIgnoreCase(msg)) {
            ctx.send("{\"type\":\"PONG\",\"time\":" + System.currentTimeMillis() + "}");
        }
    }

    public void onClose(WsCloseContext ctx) {
        broadcastService.removeSession(ctx);
    }

    public void onError(WsErrorContext ctx) {
        broadcastService.removeSession(ctx);
    }
}
