package net.minecraftforge.auctionbridge.web.controller;

import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import net.minecraftforge.auctionbridge.database.AuctionDatabaseService;
import net.minecraftforge.auctionbridge.database.model.AuctionItem;
import net.minecraftforge.auctionbridge.economy.VaultService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 玩家相关只读 API 控制器
 */
public class PlayerController {

    private final AuctionDatabaseService databaseService;
    private final VaultService vaultService;

    public PlayerController(AuctionDatabaseService databaseService, VaultService vaultService) {
        this.databaseService = databaseService;
        this.vaultService = vaultService;
    }

    /**
     * GET /api/v1/players/{uuid}/balance
     * 读取玩家在游戏内的当前 Vault 余额 (只读，不修改)
     */
    public void getPlayerBalance(Context ctx) {
        String uuidStr = ctx.pathParam("uuid");
        try {
            UUID uuid = UUID.fromString(uuidStr);
            if (!vaultService.isAvailable()) {
                ctx.status(HttpStatus.SERVICE_UNAVAILABLE).json(Map.of(
                    "status", 503,
                    "error", "Vault economy is not available on this server."
                ));
                return;
            }

            Optional<Double> balanceOpt = vaultService.getPlayerBalance(uuid);
            if (balanceOpt.isPresent()) {
                ctx.json(Map.of(
                    "uuid", uuid.toString(),
                    "balance", balanceOpt.get(),
                    "currency", vaultService.getCurrencySymbol()
                ));
            } else {
                ctx.status(HttpStatus.NOT_FOUND).json(Map.of(
                    "status", 404,
                    "error", "Player account not found in economy system."
                ));
            }
        } catch (IllegalArgumentException e) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of(
                "status", 400,
                "error", "Invalid UUID string format: " + uuidStr
            ));
        }
    }

    /**
     * GET /api/v1/players/{uuid}/listings
     * 查询指定玩家的在售商品
     */
    public void getPlayerListings(Context ctx) {
        String uuidStr = ctx.pathParam("uuid");
        List<AuctionItem> items = databaseService.queryAuctions("ACTIVE", null, null, null, uuidStr, "id_desc", 1, 50);
        ctx.json(Map.of(
            "playerUuid", uuidStr,
            "count", items.size(),
            "listings", items
        ));
    }
}
