package net.minecraftforge.auctionbridge.web.filter;

import io.javalin.config.JavalinConfig;
import net.minecraftforge.auctionbridge.config.BridgeConfig;

import java.util.List;

/**
 * 跨域资源共享 (CORS) 策略配置
 */
public class CorsFilter {

    public static void configure(JavalinConfig javalinConfig, BridgeConfig config) {
        if (!config.isCorsEnabled()) {
            return;
        }

        javalinConfig.plugins.enableCors(cors -> {
            cors.add(rule -> {
                List<String> origins = config.getCorsAllowedOrigins();
                if (origins.contains("*")) {
                    rule.anyHost();
                } else {
                    for (String origin : origins) {
                        rule.allowHost(origin);
                    }
                }

                for (String header : config.getCorsAllowedHeaders()) {
                    rule.exposeHeader(header);
                }

                rule.allowCredentials = true;
                rule.exposeHeader("X-Total-Count");
            });
        });
    }
}
