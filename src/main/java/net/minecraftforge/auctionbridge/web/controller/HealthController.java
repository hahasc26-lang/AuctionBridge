package net.minecraftforge.auctionbridge.web.controller;

import io.javalin.http.Context;
import net.minecraftforge.auctionbridge.config.BridgeConfig;
import net.minecraftforge.auctionbridge.database.AuctionDatabaseService;
import net.minecraftforge.auctionbridge.economy.VaultService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.management.ManagementFactory;
import java.util.Map;

/**
 * 服务端状态与健康检查探针
 */
public class HealthController {

    private final JavaPlugin plugin;
    private final BridgeConfig config;
    private final AuctionDatabaseService databaseService;
    private final VaultService vaultService;

    public HealthController(JavaPlugin plugin, BridgeConfig config, AuctionDatabaseService databaseService, VaultService vaultService) {
        this.plugin = plugin;
        this.config = config;
        this.databaseService = databaseService;
        this.vaultService = vaultService;
    }

    public void getHealth(Context ctx) {
        boolean dbOk = databaseService.isHealthy();
        boolean vaultOk = vaultService.isAvailable();

        ctx.json(Map.of(
            "status", dbOk ? "UP" : "DEGRADED",
            "serverTime", System.currentTimeMillis(),
            "uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime(),
            "database", dbOk ? "CONNECTED" : "DISCONNECTED",
            "vaultEconomy", vaultOk ? "AVAILABLE" : "DISABLED",
            "onlinePlayers", Bukkit.getOnlinePlayers().size()
        ));
    }

    public void getInfo(Context ctx) {
        ctx.json(Map.of(
            "pluginName", plugin.getName(),
            "version", plugin.getDescription().getVersion(),
            "serverPlatform", Bukkit.getName() + " " + Bukkit.getVersion(),
            "javaVersion", System.getProperty("java.version"),
            "databaseType", config.getDatabaseType(),
            "currencySymbol", vaultService.getCurrencySymbol(),
            "webSocketPush", config.isWebSocketEnabled(),
            "ssePush", config.isSseEnabled()
        ));
    }
}
