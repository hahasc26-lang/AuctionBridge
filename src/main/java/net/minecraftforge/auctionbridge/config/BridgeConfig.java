package net.minecraftforge.auctionbridge.config;

import net.minecraftforge.auctionbridge.AuctionBridgePlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * 桥接配置管理封装
 */
public class BridgeConfig {

    private final JavaPlugin plugin;

    // HTTP & Auth
    private String httpHost;
    private int httpPort;
    private String apiKey;
    private String webLanguage;
    private String serverLanguage;
    private boolean corsEnabled;
    private List<String> corsAllowedOrigins;
    private List<String> corsAllowedHeaders;

    // Push
    private boolean webSocketEnabled;
    private boolean sseEnabled;
    private int heartbeatIntervalSeconds;

    // Database
    private String databaseType;
    private String mysqlHost;
    private int mysqlPort;
    private String mysqlDatabase;
    private String mysqlUsername;
    private String mysqlPassword;
    private int mysqlMaxPoolSize;
    private int mysqlMinIdle;
    private String sqliteFilePath;
    private String tableName;

    // Fallback
    private boolean periodicScanEnabled;
    private int scanIntervalSeconds;
    private int scanBatchSize;
    private boolean vaultAuditEnabled;
    private boolean commandAuditEnabled;

    public BridgeConfig(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        FileConfiguration config = plugin.getConfig();

        // Server
        this.httpHost = config.getString("server.host", "127.0.0.1");
        this.httpPort = Math.max(1, config.getInt("server.port", 8085));
        this.apiKey = config.getString("server.api-key", "");
        if (this.apiKey == null || this.apiKey.trim().isEmpty()
                || "sk_live_auction_bridge_secret_9981".equals(this.apiKey)) {
            byte[] keyBytes = new byte[32];
            new SecureRandom().nextBytes(keyBytes);
            this.apiKey = Base64.getUrlEncoder().withoutPadding().encodeToString(keyBytes);
            config.set("server.api-key", this.apiKey);
            plugin.saveConfig();
            plugin.getLogger().info(localized("config.apiKeyGenerated", "已生成新的 API Key 并保存到 config.yml。"));
        }
        String configuredLanguage = config.getString("web.language", "en_US");
        if (configuredLanguage == null || !List.of("zh_CN", "zh_TW", "en_US")
                .contains(configuredLanguage.trim())) {
            plugin.getLogger().warning(localized("config.unsupportedWebLanguage",
                    "不支持的 web.language: " + configuredLanguage + "，将使用 en_US。", configuredLanguage));
            this.webLanguage = "en_US";
        } else {
            this.webLanguage = configuredLanguage.trim();
        }
        String configuredServerLanguage = config.getString("server.language", "en_US");
        if (configuredServerLanguage == null || !List.of("zh_CN", "zh_TW", "en_US")
                .contains(configuredServerLanguage.trim())) {
            plugin.getLogger().warning(localized("config.unsupportedServerLanguage",
                    "不支持的 server.language: " + configuredServerLanguage + "，将使用 en_US。", configuredServerLanguage));
            this.serverLanguage = "en_US";
        } else {
            this.serverLanguage = configuredServerLanguage.trim();
        }
        this.corsEnabled = config.getBoolean("server.cors.enabled", true);
        this.corsAllowedOrigins = config.getStringList("server.cors.allowed-origins");
        if (this.corsAllowedOrigins == null || this.corsAllowedOrigins.isEmpty()) {
            this.corsAllowedOrigins = Collections.singletonList("*");
        }
        this.corsAllowedHeaders = config.getStringList("server.cors.allowed-headers");
        if (this.corsAllowedHeaders == null || this.corsAllowedHeaders.isEmpty()) {
            this.corsAllowedHeaders = List.of("Content-Type", "Authorization", "X-API-Key");
        }

        // Push
        this.webSocketEnabled = config.getBoolean("push.websocket-enabled", true);
        this.sseEnabled = config.getBoolean("push.sse-enabled", true);
        this.heartbeatIntervalSeconds = Math.max(1, config.getInt("push.heartbeat-interval-seconds", 25));

        // Database
        this.databaseType = config.getString("database.type", "SQLITE");
        if (this.databaseType == null || this.databaseType.trim().isEmpty()) {
            this.databaseType = "SQLITE";
        }
        this.databaseType = this.databaseType.toUpperCase();
        this.mysqlHost = config.getString("database.mysql.host", "127.0.0.1");
        this.mysqlPort = Math.max(1, config.getInt("database.mysql.port", 3306));
        this.mysqlDatabase = config.getString("database.mysql.database", "minecraft");
        this.mysqlUsername = config.getString("database.mysql.username", "root");
        this.mysqlPassword = config.getString("database.mysql.password", "");
        this.mysqlMaxPoolSize = Math.max(1, config.getInt("database.mysql.pool.maximum-pool-size", 10));
        this.mysqlMinIdle = Math.max(0, config.getInt("database.mysql.pool.minimum-idle", 2));
        this.sqliteFilePath = config.getString("database.sqlite.file-path", "AuctionHouse/auctions.db");
        if (this.sqliteFilePath == null || this.sqliteFilePath.trim().isEmpty()) {
            this.sqliteFilePath = "AuctionHouse/auctions.db";
        }
        this.tableName = config.getString("database.table-name", "listings");
        if (this.tableName == null || this.tableName.trim().isEmpty()) {
            this.tableName = "listings";
        }

        // Fallback
        this.periodicScanEnabled = config.getBoolean("fallback.periodic-scan.enabled", true);
        this.scanIntervalSeconds = Math.max(1, config.getInt("fallback.periodic-scan.interval-seconds", 3));
        this.scanBatchSize = Math.max(1, config.getInt("fallback.periodic-scan.batch-size", 50));
        this.vaultAuditEnabled = config.getBoolean("fallback.vault-audit.enabled", true);
        this.commandAuditEnabled = config.getBoolean("fallback.command-audit.enabled", true);
    }

    public boolean isAuthRequired() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    private String localized(String key, String fallback, Object... values) {
        if (plugin instanceof AuctionBridgePlugin auctionBridgePlugin
                && auctionBridgePlugin.getServerLanguage() != null) {
            return auctionBridgePlugin.lang(key, values);
        }
        return fallback;
    }

    // Getters
    public String getHttpHost() { return httpHost; }
    public int getHttpPort() { return httpPort; }
    public String getApiKey() { return apiKey; }
    public String getWebLanguage() { return webLanguage; }
    public String getServerLanguage() { return serverLanguage; }
    public boolean isCorsEnabled() { return corsEnabled; }
    public List<String> getCorsAllowedOrigins() { return corsAllowedOrigins; }
    public List<String> getCorsAllowedHeaders() { return corsAllowedHeaders; }
    public boolean isWebSocketEnabled() { return webSocketEnabled; }
    public boolean isSseEnabled() { return sseEnabled; }
    public int getHeartbeatIntervalSeconds() { return heartbeatIntervalSeconds; }
    public String getDatabaseType() { return databaseType; }
    public String getMysqlHost() { return mysqlHost; }
    public int getMysqlPort() { return mysqlPort; }
    public String getMysqlDatabase() { return mysqlDatabase; }
    public String getMysqlUsername() { return mysqlUsername; }
    public String getMysqlPassword() { return mysqlPassword; }
    public int getMysqlMaxPoolSize() { return mysqlMaxPoolSize; }
    public int getMysqlMinIdle() { return mysqlMinIdle; }
    public String getSqliteFilePath() { return sqliteFilePath; }
    public String getTableName() { return tableName; }
    public boolean isPeriodicScanEnabled() { return periodicScanEnabled; }
    public int getScanIntervalSeconds() { return scanIntervalSeconds; }
    public int getScanBatchSize() { return scanBatchSize; }
    public boolean isVaultAuditEnabled() { return vaultAuditEnabled; }
    public boolean isCommandAuditEnabled() { return commandAuditEnabled; }
}
