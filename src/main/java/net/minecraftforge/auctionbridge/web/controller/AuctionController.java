package net.minecraftforge.auctionbridge.web.controller;

import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import net.minecraftforge.auctionbridge.database.AuctionDatabaseService;
import net.minecraftforge.auctionbridge.database.model.AuctionItem;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 拍卖行核心 REST 控制器
 */
public class AuctionController {

    private final AuctionDatabaseService databaseService;

    public AuctionController(AuctionDatabaseService databaseService) {
        this.databaseService = databaseService;
    }

    /**
     * GET /api/v1/auctions
     * 参数:
     * - status: ACTIVE (默认), SOLD, CANCELLED, EXPIRED, ALL
     * - search: 物品英文ID或自定义名称关键字模糊匹配
     * - minPrice / maxPrice: 价格区间
     * - sellerUuid: 卖家UUID
     * - sort: price_asc, price_desc, id_desc (默认)
     * - page: 页码 (从 1 开始)
     * - pageSize: 每页大小 (默认 20, 上限 100)
     */
    public void getAuctions(Context ctx) {
        String status = ctx.queryParam("status") != null ? ctx.queryParam("status") : "ACTIVE";
        String search = ctx.queryParam("search");
        Double minPrice = parseDoubleOrNull(ctx.queryParam("minPrice"));
        Double maxPrice = parseDoubleOrNull(ctx.queryParam("maxPrice"));
        String sellerUuid = ctx.queryParam("sellerUuid");
        String sort = ctx.queryParam("sort") != null ? ctx.queryParam("sort") : "id_desc";
        int page = parseIntOrDefault(ctx.queryParam("page"), 1);
        int pageSize = parseIntOrDefault(ctx.queryParam("pageSize"), 20);

        int safePageSize = Math.min(100, Math.max(1, pageSize));
        long totalCount = databaseService.countAuctions(status, search, minPrice, maxPrice, sellerUuid);
        long totalPages = totalCount == 0 ? 0 : (totalCount - 1) / safePageSize + 1;
        int safePage = totalPages == 0 ? 1 : (int) Math.min(Math.max(1L, page), totalPages);
        List<AuctionItem> items = databaseService.queryAuctions(status, search, minPrice, maxPrice, sellerUuid, sort, safePage, safePageSize);

        ctx.json(Map.of(
            "page", safePage,
            "pageSize", safePageSize,
            "count", items.size(),
            "totalCount", totalCount,
            "totalPages", totalPages,
            "data", items
        ));
    }

    /**
     * GET /api/v1/auctions/{id}
     */
    public void getAuctionById(Context ctx) {
        try {
            long id = Long.parseLong(ctx.pathParam("id"));
            Optional<AuctionItem> itemOpt = databaseService.findAuctionById(id);

            if (itemOpt.isPresent()) {
                ctx.json(itemOpt.get());
            } else {
                ctx.status(HttpStatus.NOT_FOUND).json(Map.of(
                    "status", 404,
                    "error", "Auction item not found with ID: " + id
                ));
            }
        } catch (NumberFormatException e) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of(
                "status", 400,
                "error", "Invalid auction ID format."
            ));
        }
    }

    /**
     * GET /api/v1/stats
     */
    public void getStats(Context ctx) {
        Map<String, Object> stats = databaseService.getOverallStats();
        ctx.json(stats);
    }

    private Double parseDoubleOrNull(String str) {
        if (str == null || str.trim().isEmpty()) return null;
        try { return Double.parseDouble(str.trim()); } catch (Exception e) { return null; }
    }

    private int parseIntOrDefault(String str, int def) {
        if (str == null || str.trim().isEmpty()) return def;
        try { return Integer.parseInt(str.trim()); } catch (Exception e) { return def; }
    }
}
