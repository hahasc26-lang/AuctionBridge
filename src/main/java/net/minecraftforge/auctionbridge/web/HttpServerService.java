package net.minecraftforge.auctionbridge.web;

import com.google.gson.Gson;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.http.HttpStatus;
import io.javalin.json.JsonMapper;
import io.javalin.util.JavalinLogger;
import net.minecraftforge.auctionbridge.config.BridgeConfig;
import net.minecraftforge.auctionbridge.AuctionBridgePlugin;
import net.minecraftforge.auctionbridge.database.AuctionDatabaseService;
import net.minecraftforge.auctionbridge.database.DatabaseQueryException;
import net.minecraftforge.auctionbridge.economy.VaultService;
import net.minecraftforge.auctionbridge.events.AuctionBridgeEventBus;
import net.minecraftforge.auctionbridge.web.controller.AuctionController;
import net.minecraftforge.auctionbridge.web.controller.HealthController;
import net.minecraftforge.auctionbridge.web.controller.PlayerController;
import net.minecraftforge.auctionbridge.web.filter.ApiKeyAuthFilter;
import net.minecraftforge.auctionbridge.web.filter.CorsFilter;
import net.minecraftforge.auctionbridge.web.sse.SseBroadcastService;
import net.minecraftforge.auctionbridge.web.websocket.BridgeWebSocketHandler;
import net.minecraftforge.auctionbridge.web.websocket.WebSocketBroadcastService;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.Map;

/**
 * 嵌入式 HTTP 服务与 WebSocket/SSE 路由生命周期管理器
 */
public class HttpServerService {

    private final JavaPlugin plugin;
    private final BridgeConfig config;
    private final AuctionDatabaseService databaseService;
    private final VaultService vaultService;
    private final AuctionBridgeEventBus eventBus;

    private Javalin app;
    private WebSocketBroadcastService wsBroadcastService;
    private SseBroadcastService sseBroadcastService;

    public HttpServerService(JavaPlugin plugin, BridgeConfig config, AuctionDatabaseService databaseService,
                             VaultService vaultService, AuctionBridgeEventBus eventBus) {
        this.plugin = plugin;
        this.config = config;
        this.databaseService = databaseService;
        this.vaultService = vaultService;
        this.eventBus = eventBus;
    }

    public void start() {
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            // Javalin discovers SLF4J providers using the context class loader.
            Thread.currentThread().setContextClassLoader(Javalin.class.getClassLoader());
            JavalinLogger.startupInfo = false;

            File dashboardDirectory = new File(plugin.getDataFolder(), "web");
            File dashboardIndex = new File(dashboardDirectory, "index.html");
            plugin.saveResource("web/index.html", false);
            saveWebEntries();
            refreshBundledDashboardIfOutdated(dashboardIndex);

            this.app = Javalin.create(javalinConfig -> {
                javalinConfig.showJavalinBanner = false;
                Gson gson = new Gson();
                javalinConfig.jsonMapper(new JsonMapper() {
                    @Override
                    public String toJsonString(Object value, Type type) {
                        return gson.toJson(value, type);
                    }

                    @Override
                    public <T> T fromJsonString(String json, Type targetType) {
                        return gson.fromJson(json, targetType);
                    }
                });
                // 配置跨域
                CorsFilter.configure(javalinConfig, config);

                javalinConfig.staticFiles.add(staticFileConfig -> {
                    staticFileConfig.hostedPath = "/";
                    staticFileConfig.directory = dashboardDirectory.getAbsolutePath();
                    staticFileConfig.location = Location.EXTERNAL;
                });
            });

            this.app.get("/", ctx -> {
                String savedLanguage = ctx.cookie("auctionbridge_language");
                String language = isSupportedWebLanguage(savedLanguage)
                        ? savedLanguage
                        : config.getWebLanguage();
                if (!isSupportedWebLanguage(language)) {
                    language = "en_US";
                }
                ctx.redirect("/index_" + language + ".html");
            });

            // 1. 注册安全过滤器 (API Key 鉴权)
            ApiKeyAuthFilter authFilter = new ApiKeyAuthFilter(config);
            this.app.before("/api/*", authFilter::handle);
            this.app.exception(DatabaseQueryException.class, (exception, ctx) ->
                    ctx.status(HttpStatus.SERVICE_UNAVAILABLE).json(Map.of(
                            "status", 503,
                            "error", exception.getMessage()
                    )));

            // 2. 注册 REST 控制器
            HealthController healthController = new HealthController(plugin, config, databaseService, vaultService);
            AuctionController auctionController = new AuctionController(databaseService);
            PlayerController playerController = new PlayerController(databaseService, vaultService);

            // Health & Metadata
            this.app.get("/api/v1/health", healthController::getHealth);
            this.app.get("/api/v1/info", healthController::getInfo);

            // Auctions REST API
            this.app.get("/api/v1/auctions", auctionController::getAuctions);
            this.app.get("/api/v1/auctions/{id}", auctionController::getAuctionById);
            this.app.get("/api/v1/stats", auctionController::getStats);

            // Public read-only routes used by the player-facing dashboard.
            this.app.get("/api/dashboard/v1/auctions", auctionController::getAuctions);
            this.app.get("/api/dashboard/v1/stats", auctionController::getStats);
            this.app.get("/api/dashboard/v1/info", healthController::getInfo);

            // Player REST API
            this.app.get("/api/v1/players/{uuid}/balance", playerController::getPlayerBalance);
            this.app.get("/api/v1/players/{uuid}/listings", playerController::getPlayerListings);

            // 3. 注册 WebSocket 实时推送
            if (config.isWebSocketEnabled()) {
                this.wsBroadcastService = new WebSocketBroadcastService(plugin, config);
                this.eventBus.registerSubscriber(wsBroadcastService::broadcast);
                this.app.ws("/ws/auctions", ws -> {
                    BridgeWebSocketHandler handler = new BridgeWebSocketHandler(wsBroadcastService, config, true);
                    ws.onConnect(handler::onConnect);
                    ws.onMessage(handler::onMessage);
                    ws.onClose(handler::onClose);
                    ws.onError(handler::onError);
                });
                this.app.ws("/ws/dashboard/auctions", ws -> {
                    BridgeWebSocketHandler handler = new BridgeWebSocketHandler(wsBroadcastService, config, false);
                    ws.onConnect(handler::onConnect);
                    ws.onMessage(handler::onMessage);
                    ws.onClose(handler::onClose);
                    ws.onError(handler::onError);
                });
            }

            // 4. 注册 SSE 推送 (Server-Sent Events)
            if (config.isSseEnabled()) {
                this.sseBroadcastService = new SseBroadcastService(plugin);
                this.eventBus.registerSubscriber(sseBroadcastService::broadcast);
                this.app.sse("/api/v1/stream", sseBroadcastService::handleSseConnection);
            }

            // 5. 绑定端口启动
            this.app.start(config.getHttpHost(), config.getHttpPort());
        } catch (Exception e) {
            String message = plugin instanceof AuctionBridgePlugin auctionBridgePlugin
                    ? auctionBridgePlugin.lang("web.startFailed", config.getHttpHost(), config.getHttpPort())
                    : "HTTP service failed to start on " + config.getHttpHost() + ":" + config.getHttpPort();
            plugin.getLogger().log(Level.SEVERE, message, e);
            throw new RuntimeException("HttpServerService failed to bind", e);
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    private void saveWebEntries() {
        for (String language : new String[]{"zh_CN", "zh_TW", "en_US"}) {
            plugin.saveResource("web/index_" + language + ".html", false);
        }
    }

    private void refreshBundledDashboardIfOutdated(File dashboardIndex) throws IOException {
        if (!dashboardIndex.isFile()) {
            return;
        }

        String currentDashboard = Files.readString(dashboardIndex.toPath());
        boolean containsDemoData = currentDashboard.contains("const mockAuctions = [")
                && currentDashboard.contains("function loadMockData()");
        boolean loadsTranslationsFromLang = currentDashboard.contains("fetch(`/LANG/");
        boolean usesLegacyTemplateInterpolation = currentDashboard.contains(
                "message.replaceAll(`{${name}}`, String(value))"
        );
        boolean containsFilteredMavenCoordinates = currentDashboard.contains(
                "net.minecraftforge.auctionbridge:AuctionBridge:jar:"
        );
        boolean currentDashboardRevision = currentDashboard.contains(
                "const DASHBOARD_REVISION = \"2026-10-06-language-cookie\";"
        );
        if (!containsDemoData && !loadsTranslationsFromLang && !usesLegacyTemplateInterpolation
                && !containsFilteredMavenCoordinates && currentDashboardRevision) {
            return;
        }

        try (var bundledDashboard = plugin.getResource("web/index.html")) {
            if (bundledDashboard == null) {
                throw new IOException("Bundled dashboard resource is missing.");
            }
            Files.copy(bundledDashboard, dashboardIndex.toPath(), StandardCopyOption.REPLACE_EXISTING);
            String message = plugin instanceof AuctionBridgePlugin auctionBridgePlugin
                    ? auctionBridgePlugin.lang("web.oldDashboardRefreshed")
                    : "Refreshed the outdated dashboard.";
            plugin.getLogger().info(message);
        }
    }

    private boolean isSupportedWebLanguage(String language) {
        return "zh_CN".equals(language) || "zh_TW".equals(language) || "en_US".equals(language);
    }

    public int getActiveWebSocketCount() {
        return wsBroadcastService != null ? wsBroadcastService.getActiveSessionCount() : 0;
    }

    public void stop() {
        if (wsBroadcastService != null) {
            wsBroadcastService.shutdown();
        }
        if (app != null) {
            app.stop();
            app = null;
        }
    }
}
