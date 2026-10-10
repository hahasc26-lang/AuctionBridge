package net.minecraftforge.auctionbridge;

import net.minecraftforge.auctionbridge.config.BridgeConfig;
import net.minecraftforge.auctionbridge.config.ServerLanguage;
import net.minecraftforge.auctionbridge.database.AuctionDatabaseService;
import net.minecraftforge.auctionbridge.economy.VaultService;
import net.minecraftforge.auctionbridge.events.AuctionBridgeEventBus;
import net.minecraftforge.auctionbridge.events.fallback.CommandAndEconomyWatcher;
import net.minecraftforge.auctionbridge.events.fallback.DatabaseWatcherTask;
import net.minecraftforge.auctionbridge.web.HttpServerService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.logging.Level;

/**
 * AuctionBridge Paper/Spigot 核心主类
 * 负责各子系统组件的依赖注入、启动编排与平滑安全停机
 */
public final class AuctionBridgePlugin extends JavaPlugin {

    private static AuctionBridgePlugin instance;
    private BridgeConfig bridgeConfig;
    private ServerLanguage serverLanguage;
    private VaultService vaultService;
    private AuctionDatabaseService databaseService;
    private AuctionBridgeEventBus eventBus;
    private HttpServerService httpServerService;
    private DatabaseWatcherTask databaseWatcherTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        String configuredLanguage = getConfig().getString("server.language", "en_US");
        this.serverLanguage = new ServerLanguage(this,
                List.of("zh_CN", "zh_TW", "en_US").contains(configuredLanguage == null ? "" : configuredLanguage.trim())
                        ? configuredLanguage.trim() : "en_US");
        if (!checkSharedRuntime()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        instance = this;
        long startTime = System.currentTimeMillis();
        getLogger().info("====================================================");
        getLogger().info(lang("startup.initializing", getDescription().getVersion()));
        getLogger().info("====================================================");

        try {
            // 1. 加载配置
            this.bridgeConfig = new BridgeConfig(this);
            this.bridgeConfig.load();
            getLogger().info(lang("startup.config"));

            // 2. 初始化 Vault 经济桥接 (只读)
            this.vaultService = new VaultService(this);
            if (this.vaultService.hookVault()) {
                getLogger().info(lang("startup.vaultConnected", vaultService.getCurrencySymbol()));
            } else {
                getLogger().warning(lang("startup.vaultMissing"));
            }

            // 3. 初始化只读数据库连接池 (MySQL / SQLite)
            this.databaseService = new AuctionDatabaseService(this, bridgeConfig);
            this.databaseService.initialize();
            getLogger().info(lang("startup.databaseConnected", bridgeConfig.getDatabaseType()));

            // 4. 初始化事件广播总线
            this.eventBus = new AuctionBridgeEventBus(this);

            // 5. 初始化并启动轻量 HTTP & WebSocket 服务
            this.httpServerService = new HttpServerService(this, bridgeConfig, databaseService, vaultService, eventBus);
            this.httpServerService.start();
            getLogger().info(lang("startup.webStarted", bridgeConfig.getHttpPort()));

            // 6. 注册事件监听器与降级补充方案
            registerEventListeners();
            getLogger().info(lang("startup.eventsReady"));

            long elapsed = System.currentTimeMillis() - startTime;
            getLogger().info(lang("startup.ready", elapsed));
            getLogger().info(lang("startup.apiRoot", bridgeConfig.getHttpHost(), bridgeConfig.getHttpPort()));
            if (bridgeConfig.isWebSocketEnabled()) {
                getLogger().info(lang("startup.websocket", bridgeConfig.getHttpHost(), bridgeConfig.getHttpPort()));
            }
            getLogger().info("====================================================");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, lang("startup.failed"), e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private boolean checkSharedRuntime() {
        if (getServer().getPluginManager().getPlugin("HahaWebPlugin") == null
                || !getServer().getPluginManager().isPluginEnabled("HahaWebPlugin")) {
            getLogger().severe(lang("dependency.missing"));
            getLogger().severe(lang("dependency.install"));
            return false;
        }

        String[] requiredClasses = {
                "io.javalin.Javalin",
                "com.zaxxer.hikari.HikariConfig",
                "com.mysql.cj.jdbc.Driver",
                "org.sqlite.JDBC",
                "com.google.gson.Gson",
                "org.slf4j.Logger",
                "org.slf4j.LoggerFactory"
        };
        ClassLoader classLoader = getClass().getClassLoader();
        for (String className : requiredClasses) {
            try {
                Class.forName(className, false, classLoader);
            } catch (ClassNotFoundException | LinkageError e) {
                getLogger().log(Level.SEVERE,
                        lang("dependency.runtimeMissing", className), e);
                getLogger().severe(lang("dependency.install"));
                return false;
            }
        }
        return true;
    }

    private void registerEventListeners() {
        // A. 定期扫描数据库变动 (增量对比)
        if (bridgeConfig.isPeriodicScanEnabled()) {
            this.databaseWatcherTask = new DatabaseWatcherTask(this, databaseService, eventBus, bridgeConfig.getScanIntervalSeconds());
            this.databaseWatcherTask.start();
            getLogger().info(lang("startup.scanStarted", bridgeConfig.getScanIntervalSeconds()));
        }

        // B. 审计玩家命令与经济流动补充事件
        if (bridgeConfig.isVaultAuditEnabled() || bridgeConfig.isCommandAuditEnabled()) {
            CommandAndEconomyWatcher watcher = new CommandAndEconomyWatcher(this, eventBus, databaseService, bridgeConfig);
            getServer().getPluginManager().registerEvents(watcher, this);
            getLogger().info(lang("startup.auditReady"));
        }
    }

    @Override
    public void onDisable() {
        getLogger().info(lang("shutdown.starting"));

        // 停止数据库扫描任务
        if (databaseWatcherTask != null) {
            databaseWatcherTask.stop();
        }

        // 停止 HTTP & WebSocket 服务
        if (httpServerService != null) {
            try {
                httpServerService.stop();
            } catch (Exception e) {
                getLogger().log(Level.WARNING, lang("shutdown.httpError", e.getMessage()));
            }
        }

        // 关闭数据库连接池
        if (databaseService != null) {
            try {
                databaseService.shutdown();
            } catch (Exception e) {
                getLogger().log(Level.WARNING, lang("shutdown.databaseError", e.getMessage()));
            }
        }

        getLogger().info(lang("shutdown.complete"));
        instance = null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("auctionbridge.admin")) {
            sender.sendMessage("§c" + lang("command.noPermission"));
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            this.bridgeConfig.load();
            this.serverLanguage.load(bridgeConfig.getServerLanguage());

            // 动态热重载后台增量扫描器任务
            if (databaseWatcherTask != null) {
                databaseWatcherTask.stop();
                databaseWatcherTask = null;
            }
            if (bridgeConfig.isPeriodicScanEnabled()) {
                this.databaseWatcherTask = new DatabaseWatcherTask(this, databaseService, eventBus, bridgeConfig.getScanIntervalSeconds());
                this.databaseWatcherTask.start();
            }

            sender.sendMessage("§a" + lang("command.reloaded"));
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("§6" + lang("command.statusHeading"));
            sender.sendMessage("§7" + lang("command.httpPort", bridgeConfig.getHttpPort()));
            sender.sendMessage("§7" + lang("command.apiAuth", lang(bridgeConfig.isAuthRequired() ? "state.enabled" : "state.public")));
            sender.sendMessage("§7" + lang("command.websocket", lang(bridgeConfig.isWebSocketEnabled() ? "state.running" : "state.disabled")));
            sender.sendMessage("§7" + lang("command.websocketClients", httpServerService != null ? httpServerService.getActiveWebSocketCount() : 0));
            sender.sendMessage("§7" + lang("command.database", lang(databaseService != null && databaseService.isHealthy() ? "state.healthy" : "state.unhealthy")));
            return true;
        }

        sender.sendMessage("§e" + lang("command.usageHeading"));
        sender.sendMessage("§7" + lang("command.reloadUsage"));
        sender.sendMessage("§7" + lang("command.statusUsage"));
        return true;
    }

    public String lang(String key, Object... values) {
        return serverLanguage == null ? key : serverLanguage.get(key, values);
    }

    public ServerLanguage getServerLanguage() {
        return serverLanguage;
    }

    public static AuctionBridgePlugin getInstance() {
        return instance;
    }

    public BridgeConfig getBridgeConfig() {
        return bridgeConfig;
    }

    public VaultService getVaultService() {
        return vaultService;
    }

    public AuctionDatabaseService getDatabaseService() {
        return databaseService;
    }

    public AuctionBridgeEventBus getEventBus() {
        return eventBus;
    }

    public HttpServerService getHttpServerService() {
        return httpServerService;
    }
}
