package net.minecraftforge.auctionbridge.web.filter;

import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import net.minecraftforge.auctionbridge.config.BridgeConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * API Key 鉴权拦截器
 * 检查 'X-API-Key' 请求头或 '?apiKey=...' 查询参数
 */
public class ApiKeyAuthFilter {

    private final BridgeConfig config;

    public ApiKeyAuthFilter(BridgeConfig config) {
        this.config = config;
    }

    public void handle(Context ctx) {
        // 放行 OPTIONS 预检请求与健康检查公开接口
        if ("OPTIONS".equalsIgnoreCase(ctx.method().name())) {
            return;
        }
        String path = ctx.path();
        if (path.endsWith("/health") || path.endsWith("/info")
                || path.startsWith("/api/dashboard/")) {
            return;
        }

        // 若配置文件未设置 api-key 则默认不开启鉴权
        if (!config.isAuthRequired()) {
            return;
        }

        String providedKey = ctx.header("X-API-Key");
        if (providedKey == null || providedKey.isEmpty()) {
            providedKey = ctx.queryParam("apiKey");
        }

        // 防时序攻击恒定时间校验
        if (providedKey == null || !isConstantTimeEquals(providedKey, config.getApiKey())) {
            ctx.status(HttpStatus.UNAUTHORIZED)
               .json(Map.of(
                   "status", 401,
                   "error", "Unauthorized",
                   "message", "Missing or invalid API Key. Provide it via 'X-API-Key' header or 'apiKey' query parameter."
               ));
            return;
        }
    }

    /**
     * 恒定时间字符串比较，防止时序侧信道攻击
     */
    private boolean isConstantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(aBytes, bBytes);
    }
}
