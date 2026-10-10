package net.minecraftforge.auctionbridge.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.minecraftforge.auctionbridge.config.BridgeConfig;
import net.minecraftforge.auctionbridge.AuctionBridgePlugin;
import net.minecraftforge.auctionbridge.database.model.AuctionItem;
import org.bukkit.plugin.java.JavaPlugin;
import org.sqlite.SQLiteConfig;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;

/**
 * 只读拍卖行数据库服务
 * 严格只读！禁止执行 INSERT, UPDATE, DELETE，避免破坏 AuctionHouse 插件内部状态与经济事务
 */
public class AuctionDatabaseService {

    private final JavaPlugin plugin;
    private final BridgeConfig config;
    private final PlayerNameResolver playerNameResolver;
    private HikariDataSource dataSource;

    // 轻量级短时 COUNT(*) 缓存，减轻分页与高频刷新对数据库造成的全表扫描压力
    private static final long COUNT_CACHE_TTL_MS = 3000L;
    private final Map<String, CachedCount> countCache = new java.util.concurrent.ConcurrentHashMap<>();

    private static final class CachedCount {
        final long count;
        final long timestamp;
        CachedCount(long count, long timestamp) {
            this.count = count;
            this.timestamp = timestamp;
        }
    }

    public AuctionDatabaseService(JavaPlugin plugin, BridgeConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.playerNameResolver = new PlayerNameResolver(plugin);
    }

    public void initialize() throws SQLException {
        if (dataSource != null && !dataSource.isClosed()) {
            return;
        }

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPoolName("AuctionBridge-ReadOnlyPool");
        // 关键安全设定：强制 JDBC 连接级别只读，绝不污染或死锁原插件数据库
        hikariConfig.setReadOnly(true);

        String databaseType = config.getDatabaseType();
        if ("SQLITE".equalsIgnoreCase(databaseType)) {
            File dbFile = resolveSqliteFile(config.getSqliteFilePath());
            SQLiteConfig sqliteConfig = new SQLiteConfig();
            sqliteConfig.setReadOnly(true);
            hikariConfig.setDriverClassName("org.sqlite.JDBC");
            hikariConfig.setJdbcUrl("jdbc:sqlite:" + dbFile.toPath().toAbsolutePath().normalize()
                    .toString().replace('\\', '/'));
            hikariConfig.setDataSourceProperties(sqliteConfig.toProperties());
            hikariConfig.setMaximumPoolSize(3);
        } else if ("MYSQL".equalsIgnoreCase(databaseType) || "MARIADB".equalsIgnoreCase(databaseType)) {
            // MySQL / MariaDB
            hikariConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");
            String jdbcUrl = String.format(
                    "jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&autoReconnect=true&readOnly=true",
                    config.getMysqlHost(), config.getMysqlPort(), config.getMysqlDatabase()
            );
            hikariConfig.setJdbcUrl(jdbcUrl);
            hikariConfig.setUsername(config.getMysqlUsername());
            hikariConfig.setPassword(config.getMysqlPassword());
            hikariConfig.setMaximumPoolSize(config.getMysqlMaxPoolSize());
            hikariConfig.setMinimumIdle(config.getMysqlMinIdle());
            hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
            hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
            hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        } else {
            throw new IllegalArgumentException("Unsupported database type: " + databaseType + ". Supported: MYSQL, MARIADB, SQLITE");
        }

        this.dataSource = new HikariDataSource(hikariConfig);

        // 验证连接
        try (Connection conn = dataSource.getConnection()) {
            if (!conn.isValid(3)) {
                throw new SQLException("无法建立与拍卖行数据库的有效只读连接");
            }
        }
    }

    private File resolveSqliteFile(String configuredPath) {
        File dbFile = new File(configuredPath);
        if (dbFile.isAbsolute()) {
            return dbFile;
        }

        // Accept both plugins-relative paths and legacy server-root paths such as plugins/AuctionHouse/...
        if (configuredPath.regionMatches(true, 0, "plugins/", 0, 8)
                || configuredPath.regionMatches(true, 0, "plugins\\", 0, 8)) {
            File serverRoot = plugin.getDataFolder().getParentFile().getParentFile();
            return new File(serverRoot, configuredPath);
        }
        return new File(plugin.getDataFolder().getParentFile(), configuredPath);
    }

    public boolean isHealthy() {
        if (dataSource == null || dataSource.isClosed()) return false;
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 多条件分页查询数据库中保留的拍卖记录 (只读查询)
     */
    public List<AuctionItem> queryAuctions(String status, String search, Double minPrice, Double maxPrice, String sellerUuid, String sort, int page, int pageSize) {
        List<AuctionItem> items = executeQueryAuctions(status, search, minPrice, maxPrice, sellerUuid, sort, page, pageSize);
        // 若指定了 search 但 SQL 检索返回为空，极大概率为底层采用 GZIP 二进制 BLOB 导致 SQL LIKE 失效
        // 此时自动回退到内存反序列化匹配
        if (items.isEmpty() && search != null && !search.trim().isEmpty()) {
            List<AuctionItem> candidates = executeQueryAuctions(status, null, minPrice, maxPrice, sellerUuid, sort, 1, 300);
            String searchLower = search.trim().toLowerCase(Locale.ROOT);
            List<AuctionItem> matched = new ArrayList<>();
            for (AuctionItem candidate : candidates) {
                if (matchesSearch(candidate, searchLower)) {
                    matched.add(candidate);
                }
            }
            int safePage = Math.max(1, page);
            int safeSize = Math.min(100, Math.max(1, pageSize));
            int fromIndex = (safePage - 1) * safeSize;
            if (fromIndex < matched.size()) {
                int toIndex = Math.min(fromIndex + safeSize, matched.size());
                return matched.subList(fromIndex, toIndex);
            }
            return Collections.emptyList();
        }
        return items;
    }

    private List<AuctionItem> executeQueryAuctions(String status, String search, Double minPrice, Double maxPrice, String sellerUuid, String sort, int page, int pageSize) {
        List<AuctionItem> items = new ArrayList<>();
        if (dataSource == null || dataSource.isClosed()) {
            throw new DatabaseQueryException("Auction database is not initialized.", null);
        }

        String tableName = sanitizeTableName(config.getTableName());
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(tableName)
                .append(buildAuctionFilters(status, search, minPrice, maxPrice, sellerUuid, params));

        // 排序
        if ("price_asc".equalsIgnoreCase(sort)) {
            sql.append(" ORDER BY price ASC");
        } else if ("price_desc".equalsIgnoreCase(sort)) {
            sql.append(" ORDER BY price DESC");
        } else {
            sql.append(" ORDER BY id DESC");
        }

        // 分页 (安全限制: 每页最多 100 条)
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, pageSize));
        long offset = ((long) safePage - 1) * safeSize;
        sql.append(" LIMIT ? OFFSET ?");
        params.add(safeSize);
        params.add(offset);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(mapResultSetToAuctionItem(rs));
                }
            }
        } catch (SQLException e) {
            throw queryFailure("database.query.readAuctions", e);
        }
        return items;
    }

    private boolean matchesSearch(AuctionItem item, String searchLower) {
        if (item == null || searchLower.isEmpty()) return true;
        if (item.getItemName() != null && item.getItemName().toLowerCase(Locale.ROOT).contains(searchLower)) {
            return true;
        }
        if (item.getMaterial() != null && item.getMaterial().toLowerCase(Locale.ROOT).contains(searchLower)) {
            return true;
        }
        if (item.getDisplayName() != null && item.getDisplayName().toLowerCase(Locale.ROOT).contains(searchLower)) {
            return true;
        }
        if (item.getLore() != null) {
            for (String line : item.getLore()) {
                if (line != null && line.toLowerCase(Locale.ROOT).contains(searchLower)) {
                    return true;
                }
            }
        }
        return false;
    }

    public long countAuctions(String status, String search, Double minPrice, Double maxPrice, String sellerUuid) {
        if (dataSource == null || dataSource.isClosed()) {
            throw new DatabaseQueryException("Auction database is not initialized.", null);
        }

        String cacheKey = String.format("%s|%s|%s|%s|%s", status, search, minPrice, maxPrice, sellerUuid);
        long now = System.currentTimeMillis();
        CachedCount cached = countCache.get(cacheKey);
        if (cached != null && (now - cached.timestamp < COUNT_CACHE_TTL_MS)) {
            return cached.count;
        }

        List<Object> params = new ArrayList<>();
        String sql = "SELECT COUNT(*) AS total_count FROM " + sanitizeTableName(config.getTableName())
                + buildAuctionFilters(status, search, minPrice, maxPrice, sellerUuid, params);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                long total = rs.next() ? rs.getLong("total_count") : 0L;
                // 若 search 不为空且计数为 0，可能因 GZIP 二进制 BLOB 导致 LIKE 失效，使用反序列化回退计数
                if (total == 0L && search != null && !search.trim().isEmpty()) {
                    List<AuctionItem> candidates = executeQueryAuctions(status, null, minPrice, maxPrice, sellerUuid, "id_desc", 1, 300);
                    String searchLower = search.trim().toLowerCase(Locale.ROOT);
                    long matchedCount = 0L;
                    for (AuctionItem candidate : candidates) {
                        if (matchesSearch(candidate, searchLower)) {
                            matchedCount++;
                        }
                    }
                    total = matchedCount;
                }
                if (countCache.size() > 200) {
                    countCache.clear();
                }
                countCache.put(cacheKey, new CachedCount(total, now));
                return total;
            }
        } catch (SQLException e) {
            throw queryFailure("database.query.countAuctions", e);
        }
    }

    private String buildAuctionFilters(String status, String search, Double minPrice, Double maxPrice,
                                       String sellerUuid, List<Object> params) {
        StringBuilder filters = new StringBuilder(" WHERE 1=1");
        if (status != null && !status.isEmpty() && !"ALL".equalsIgnoreCase(status)) {
            if ("ACTIVE".equalsIgnoreCase(status) || "AVAILABLE".equalsIgnoreCase(status)) {
                filters.append(" AND (buyer_uuid IS NULL OR buyer_uuid = '')");
            } else if ("SOLD".equalsIgnoreCase(status) || "COMPLETED".equalsIgnoreCase(status)) {
                filters.append(" AND (buyer_uuid IS NOT NULL AND buyer_uuid != '')");
            } else {
                filters.append(" AND 1 = 0");
            }
        }
        if (search != null && !search.trim().isEmpty()) {
            filters.append(" AND item LIKE ?");
            String term = "%" + search.trim() + "%";
            params.add(term);
        }
        if (minPrice != null && minPrice >= 0) {
            filters.append(" AND price >= ?");
            params.add(minPrice);
        }
        if (maxPrice != null && maxPrice >= 0) {
            filters.append(" AND price <= ?");
            params.add(maxPrice);
        }
        if (sellerUuid != null && !sellerUuid.trim().isEmpty()) {
            filters.append(" AND seller_uuid = ?");
            params.add(sellerUuid.trim());
        }
        return filters.toString();
    }

    /**
     * 根据 ID 精确获取单个拍卖品
     */
    public Optional<AuctionItem> findAuctionById(long id) {
        if (dataSource == null || dataSource.isClosed()) {
            throw new DatabaseQueryException("Auction database is not initialized.", null);
        }

        String sql = "SELECT * FROM " + sanitizeTableName(config.getTableName()) + " WHERE id = ? LIMIT 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToAuctionItem(rs));
                }
            }
        } catch (SQLException e) {
            throw queryFailure("database.query.auctionById", e);
        }
        return Optional.empty();
    }

    /**
     * 降级扫描器增量获取最近发生更新的记录
     */
    public List<AuctionItem> queryRecentUpdatedAfter(long minId, int limit) {
        List<AuctionItem> items = new ArrayList<>();
        if (dataSource == null || dataSource.isClosed()) {
            throw new DatabaseQueryException("Auction database is not initialized.", null);
        }

        String sql = "SELECT * FROM " + sanitizeTableName(config.getTableName()) + " WHERE id > ? ORDER BY id ASC LIMIT ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, minId);
            ps.setInt(2, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(mapResultSetToAuctionItem(rs));
                }
            }
        } catch (SQLException e) {
            throw queryFailure("database.query.incrementalScan", e);
        }
        return items;
    }

    /**
     * 降级扫描器获取当前近期在售状态的商品 ID，用于后续监听售出状态变更
     */
    public List<Long> queryRecentActiveItemIds(int limit) {
        List<Long> ids = new ArrayList<>();
        if (dataSource == null || dataSource.isClosed()) {
            return ids;
        }

        String sql = "SELECT id FROM " + sanitizeTableName(config.getTableName()) +
                " WHERE (buyer_uuid IS NULL OR buyer_uuid = '') ORDER BY id DESC LIMIT ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong("id"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().fine("Failed to query recent active item IDs: " + e.getMessage());
        }
        return ids;
    }

    /**
     * 批量查询追踪商品在数据库中的现存状态结果
     */
    public static class TrackedStatusResult {
        private final List<AuctionItem> soldItems = new ArrayList<>();
        private final Set<Long> activeIds = new HashSet<>();
        private final Set<Long> missingIds = new HashSet<>();

        public List<AuctionItem> getSoldItems() { return soldItems; }
        public Set<Long> getActiveIds() { return activeIds; }
        public Set<Long> getMissingIds() { return missingIds; }
    }

    /**
     * 批量检查被追踪商品在数据库中的现存状态（识别已售出、仍在售以及已被物理删除/取消的物品）
     */
    public TrackedStatusResult inspectTrackedItems(Collection<Long> ids) {
        TrackedStatusResult result = new TrackedStatusResult();
        if (ids == null || ids.isEmpty() || dataSource == null || dataSource.isClosed()) {
            return result;
        }

        String tableName = sanitizeTableName(config.getTableName());
        StringBuilder inClause = new StringBuilder();
        int size = ids.size();
        for (int i = 0; i < size; i++) {
            if (i > 0) inClause.append(",");
            inClause.append("?");
        }

        String sql = "SELECT * FROM " + tableName + " WHERE id IN (" + inClause + ")";
        Set<Long> foundIds = new HashSet<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int index = 1;
            for (Long id : ids) {
                ps.setLong(index++, id);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    foundIds.add(id);
                    String buyerUuid = getStringOrFallback(rs, "buyer_uuid", "buyer");
                    if (buyerUuid != null && !buyerUuid.trim().isEmpty()) {
                        result.soldItems.add(mapResultSetToAuctionItem(rs));
                    } else {
                        result.activeIds.add(id);
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().fine("Failed to inspect tracked item IDs: " + e.getMessage());
        }

        for (Long id : ids) {
            if (!foundIds.contains(id)) {
                result.missingIds.add(id);
            }
        }
        return result;
    }

    /**
     * 批量查询追踪的商品中是否已发生购买 (buyer_uuid 非空)
     */
    public List<AuctionItem> querySoldItemsFromIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty() || dataSource == null || dataSource.isClosed()) {
            return Collections.emptyList();
        }

        List<AuctionItem> items = new ArrayList<>();
        String tableName = sanitizeTableName(config.getTableName());
        StringBuilder inClause = new StringBuilder();
        int size = ids.size();
        for (int i = 0; i < size; i++) {
            if (i > 0) inClause.append(",");
            inClause.append("?");
        }

        String sql = "SELECT * FROM " + tableName +
                " WHERE id IN (" + inClause + ") AND (buyer_uuid IS NOT NULL AND buyer_uuid != '')";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int index = 1;
            for (Long id : ids) {
                ps.setLong(index++, id);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(mapResultSetToAuctionItem(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().fine("Failed to query sold items from tracked IDs: " + e.getMessage());
        }
        return items;
    }

    /**
     * 获取全服拍卖统计指标
     */
    public Map<String, Object> getOverallStats() {
        Map<String, Object> stats = new HashMap<>();
        if (dataSource == null || dataSource.isClosed()) {
            throw new DatabaseQueryException("Auction database is not initialized.", null);
        }

        String sql = "SELECT " +
                "COUNT(*) AS total_count, " +
                "COALESCE(SUM(CASE WHEN buyer_uuid IS NULL OR buyer_uuid = '' THEN 1 ELSE 0 END), 0) AS active_count, " +
                "COALESCE(SUM(CASE WHEN buyer_uuid IS NOT NULL AND buyer_uuid != '' THEN 1 ELSE 0 END), 0) AS sold_count, " +
                "0 AS cancelled_count, " +
                "0 AS expired_count, " +
                "COALESCE(SUM(CASE WHEN buyer_uuid IS NOT NULL AND buyer_uuid != '' THEN price ELSE 0 END), 0) AS total_volume " +
                "FROM " + sanitizeTableName(config.getTableName());

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                stats.put("totalCount", rs.getLong("total_count"));
                stats.put("activeCount", rs.getLong("active_count"));
                stats.put("soldCount", rs.getLong("sold_count"));
                stats.put("cancelledCount", rs.getLong("cancelled_count"));
                stats.put("expiredCount", rs.getLong("expired_count"));
                stats.put("totalVolume", rs.getDouble("total_volume"));
            }
        } catch (SQLException e) {
            throw queryFailure("database.query.statistics", e);
        }
        return stats;
    }

    private DatabaseQueryException queryFailure(String operation, SQLException exception) {
        String message = plugin instanceof AuctionBridgePlugin auctionBridgePlugin
                ? auctionBridgePlugin.lang("database.queryFailure", auctionBridgePlugin.lang(operation))
                : operation + " failed. Check the database connection, table name, and schema.";
        plugin.getLogger().log(Level.WARNING, message, exception);
        return new DatabaseQueryException(
                "Auction database query failed. Check the configured database, table name, and schema.",
                exception
        );
    }

    /**
     * 宽容映射 ResultSet (适配主流 AuctionHouse 字段命名差)
     */
    private AuctionItem mapResultSetToAuctionItem(ResultSet rs) throws SQLException {
        AuctionItem item = new AuctionItem();
        item.setId(rs.getLong("id"));
        item.setSellerUuid(getStringOrFallback(rs, "seller_uuid", "seller", "owner"));
        String sellerName = getStringOrFallback(rs, "seller_name", "owner_name");
        item.setSellerName(sellerName.isEmpty()
                ? playerNameResolver.resolveName(item.getSellerUuid())
                : sellerName);
        item.setPrice(getDoubleOrFallback(rs, "price", "cost", "buy_price"));
        Optional<ItemSerializer.ItemDetails> serializedItem = ItemSerializer.deserialize(getBytesOrFallback(rs, "item"));
        if (serializedItem.isPresent()) {
            ItemSerializer.ItemDetails details = serializedItem.get();
            item.setMaterial(details.getMaterial());
            item.setItemName(details.getDisplayName());
            item.setAmount(details.getAmount());
            item.setLore(details.getLore());
            item.setCustomModelData(details.getCustomModelData());
        } else {
            item.setMaterial(getStringOrFallback(rs, "item_type", "type", "material"));
            item.setItemName(getStringOrFallback(rs, "display_name", "name", "custom_name"));
            item.setAmount(getIntOrFallback(rs, "amount", "count", "quantity"));
        }
        item.setBuyerUuid(getStringOrFallback(rs, "buyer_uuid", "buyer"));
        String status = getStringOrFallback(rs, "status", "state");
        item.setStatus(status.isEmpty()
                ? (item.getBuyerUuid().isEmpty() ? "ACTIVE" : "SOLD")
                : status);
        item.setCreatedAt(getLongOrFallback(rs, "created_at", "timestamp", "start_time"));
        item.setExpiresAt(getLongOrFallback(rs, "expires_at", "expire_time", "end_time"));
        String buyerName = getStringOrFallback(rs, "buyer_name");
        item.setBuyerName(buyerName.isEmpty() && !item.getBuyerUuid().isEmpty()
                ? playerNameResolver.resolveName(item.getBuyerUuid())
                : buyerName);
        item.setSoldAt(getLongOrFallback(rs, "sold_at", "purchase_time", "sold_time"));
        return item;
    }

    private String getStringOrFallback(ResultSet rs, String... columnNames) {
        for (String col : columnNames) {
            try {
                String val = rs.getString(col);
                if (val != null) return val;
            } catch (SQLException ignored) {}
        }
        return "";
    }

    private byte[] getBytesOrFallback(ResultSet rs, String... columnNames) {
        for (String col : columnNames) {
            try {
                byte[] value = rs.getBytes(col);
                if (value != null) return value;
            } catch (SQLException ignored) {}
        }
        return null;
    }

    private double getDoubleOrFallback(ResultSet rs, String... columnNames) {
        for (String col : columnNames) {
            try {
                return rs.getDouble(col);
            } catch (SQLException ignored) {}
        }
        return 0.0;
    }

    private int getIntOrFallback(ResultSet rs, String... columnNames) {
        for (String col : columnNames) {
            try {
                return rs.getInt(col);
            } catch (SQLException ignored) {}
        }
        return 1;
    }

    private long getLongOrFallback(ResultSet rs, String... columnNames) {
        for (String col : columnNames) {
            try {
                return rs.getLong(col);
            } catch (SQLException ignored) {}
        }
        return 0L;
    }

    private String sanitizeTableName(String tableName) {
        String trimmed = tableName == null ? "auctions" : tableName.trim();
        if (trimmed.isEmpty()) {
            return "auctions";
        }
        if (trimmed.contains(";") || trimmed.contains("--") || trimmed.contains("/*") || trimmed.contains("*/")) {
            throw new IllegalArgumentException("Invalid database table name: " + tableName);
        }
        return trimmed;
    }

    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
