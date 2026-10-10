package net.minecraftforge.auctionbridge.database;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.io.BukkitObjectInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;

/**
 * Decodes AuctionHouse's serialized ItemStack and extracts web-facing item details.
 */
public final class ItemSerializer {

    private static final Logger LOGGER = Logger.getLogger(ItemSerializer.class.getName());
    private static final Gson GSON = new Gson();
    private static final int MAX_HEADER_LENGTH = 1024;
    private static final int MAX_PAPER_PREFIX_LENGTH = 64;
    private static final int MAX_DECOMPRESSED_SIZE = 8 * 1024 * 1024;
    private static final int MAX_FAILURE_WARNINGS = 3;
    private static final AtomicInteger failureWarnings = new AtomicInteger();

    // 静态缓存 Paper 的 ItemStack.deserializeBytes 反射方法，避免每次反序列化都反射查找
    private static final Method DESERIALIZE_BYTES_METHOD;
    static {
        Method method = null;
        try {
            method = ItemStack.class.getMethod("deserializeBytes", byte[].class);
        } catch (ReflectiveOperationException ignored) {
            // Paper 特有方法不可用 (例如旧版 Spigot)
        }
        DESERIALIZE_BYTES_METHOD = method;
    }

    // 高性能线程安全 LRU 缓存，避免对相同物品 BLOB 重复反序列化与解压
    private static final int CACHE_MAX_ENTRIES = 4096;
    private static final Map<ByteArrayKey, ItemDetails> ITEM_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<ByteArrayKey, ItemDetails> eldest) {
                    return size() > CACHE_MAX_ENTRIES;
                }
            }
    );

    private ItemSerializer() {}

    public static Optional<ItemDetails> deserialize(byte[] serializedItem) {
        if (serializedItem == null || serializedItem.length == 0) {
            return Optional.empty();
        }

        ByteArrayKey cacheKey = new ByteArrayKey(serializedItem);
        ItemDetails cached = ITEM_CACHE.get(cacheKey);
        if (cached != null) {
            return Optional.of(cached);
        }

        try {
            Optional<ItemDetails> details = decodePayload(serializedItem, 0);
            if (details.isPresent()) {
                ITEM_CACHE.put(cacheKey, details.get());
                return details;
            }
            logUnsupportedFormat(serializedItem);
            return Optional.empty();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not decode AuctionHouse item BLOB (length "
                    + serializedItem.length + ", header " + hexHeader(serializedItem) + ").", e);
            return Optional.empty();
        }
    }

    private static Optional<ItemDetails> decodePayload(byte[] data, int depth) throws Exception {
        if (depth > 2) {
            return Optional.empty();
        }

        Optional<ItemDetails> direct = readItemStack(data);
        if (direct.isPresent()) {
            return direct;
        }

        int gzipOffset = findMagic(data, 0x1f, 0x8b);
        if (gzipOffset >= 0) {
            try {
                Optional<ItemDetails> uncompressed = decodePayload(decompress(data, gzipOffset), depth + 1);
                if (uncompressed.isPresent()) {
                    return uncompressed;
                }
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "AuctionHouse item data contains an invalid GZIP stream.", e);
            }
        }

        String encodedText = new String(data, StandardCharsets.UTF_8).trim();
        if (encodedText.startsWith("{")) {
            try {
                Type itemMapType = new TypeToken<Map<String, Object>>() {}.getType();
                Map<String, Object> serializedMap = GSON.fromJson(encodedText, itemMapType);
                Optional<ItemStack> mapped = deserializeMap(serializedMap);
                if (mapped.isPresent()) {
                    return Optional.of(extractDetails(mapped.get()));
                }
            } catch (RuntimeException e) {
                LOGGER.log(Level.FINE, "AuctionHouse item data is not a serialized JSON ItemStack map.", e);
            }
        }

        if (encodedText.startsWith("item:")
                || encodedText.startsWith("==:")
                || encodedText.startsWith("type:")) {
            try {
                YamlConfiguration serializedYaml = new YamlConfiguration();
                serializedYaml.loadFromString(encodedText);
                ItemStack nestedItem = serializedYaml.getItemStack("item");
                if (nestedItem != null && !nestedItem.getType().isAir()) {
                    return Optional.of(extractDetails(nestedItem));
                }
                Optional<ItemStack> mapped = deserializeMap(serializedYaml.getValues(false));
                if (mapped.isPresent()) {
                    return Optional.of(extractDetails(mapped.get()));
                }
            } catch (InvalidConfigurationException | RuntimeException e) {
                LOGGER.log(Level.FINE, "AuctionHouse item data is not a serialized YAML ItemStack.", e);
            }
        }

        String base64 = encodedText.replaceAll("\\s", "");
        if (base64.length() >= 8 && base64.matches("[A-Za-z0-9+/=_-]+")) {
            try {
                byte[] decoded = Base64.getDecoder().decode(base64);
                if (!Arrays.equals(decoded, data)) {
                    Optional<ItemDetails> base64Item = decodePayload(decoded, depth + 1);
                    if (base64Item.isPresent()) {
                        return base64Item;
                    }
                }
            } catch (IllegalArgumentException ignored) {
                try {
                    byte[] decoded = Base64.getUrlDecoder().decode(base64);
                    if (!Arrays.equals(decoded, data)) {
                        return decodePayload(decoded, depth + 1);
                    }
                } catch (IllegalArgumentException invalidBase64) {
                    LOGGER.log(Level.FINE, "AuctionHouse item data is not valid Base64.", invalidBase64);
                }
            }
        }
        return Optional.empty();
    }

    private static byte[] decompress(byte[] data, int offset) throws IOException {
        try (InputStream gzip = new GZIPInputStream(
                new ByteArrayInputStream(data, offset, data.length - offset));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int count;
            while ((count = gzip.read(buffer)) != -1) {
                total += count;
                if (total > MAX_DECOMPRESSED_SIZE) {
                    throw new IOException("Compressed item data exceeds the maximum supported size.");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static Optional<ItemDetails> readItemStack(byte[] data) throws Exception {
        Optional<ItemStack> objectStreamItem = deserializeObjectStream(data);
        if (objectStreamItem.isPresent()) {
            return Optional.of(extractDetails(objectStreamItem.get()));
        }

        Optional<ItemStack> serializedBytesItem = deserializeItemBytes(data);
        if (serializedBytesItem.isPresent()) {
            return Optional.of(extractDetails(serializedBytesItem.get()));
        }
        return Optional.empty();
    }

    private static Optional<ItemStack> deserializeObjectStream(byte[] data) {
        int streamOffset = findObjectStreamHeader(data);
        if (streamOffset < 0) {
            return Optional.empty();
        }
        try (BukkitObjectInputStream input = new BukkitObjectInputStream(
                new ByteArrayInputStream(data, streamOffset, data.length - streamOffset))) {
            Object decoded = input.readObject();
            Optional<ItemStack> item = asItemStack(decoded, 0);
            if (item.isPresent()) {
                return item;
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Bukkit object stream did not contain an ItemStack.", e);
        }
        return Optional.empty();
    }

    private static Optional<ItemStack> asItemStack(Object value, int depth) {
        if (depth > 4) {
            return Optional.empty();
        }
        if (value instanceof ItemStack itemStack && !itemStack.getType().isAir()) {
            return Optional.of(itemStack);
        }
        if (value instanceof Map<?, ?> serializedMap) {
            Optional<ItemStack> direct = deserializeMap(serializedMap);
            if (direct.isPresent()) {
                return direct;
            }
            for (String key : new String[]{"item", "itemstack", "stack", "serialized", "data", "value"}) {
                Object nested = serializedMap.get(key);
                Optional<ItemStack> decoded = asItemStack(nested, depth + 1);
                if (decoded.isPresent()) {
                    return decoded;
                }
            }
            for (Object nested : serializedMap.values()) {
                if (nested instanceof Map<?, ?> || nested instanceof byte[] || nested instanceof ItemStack) {
                    Optional<ItemStack> decoded = asItemStack(nested, depth + 1);
                    if (decoded.isPresent()) {
                        return decoded;
                    }
                }
            }
        }
        if (value instanceof byte[] nestedBytes) {
            Optional<ItemStack> objectStreamItem = deserializeObjectStream(nestedBytes);
            if (objectStreamItem.isPresent()) {
                return objectStreamItem;
            }
            return deserializeItemBytes(nestedBytes);
        }
        return Optional.empty();
    }

    private static Optional<ItemStack> deserializeMap(Map<?, ?> source) {
        if (source == null || source.isEmpty()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> itemData = new java.util.HashMap<>();
            source.forEach((key, value) -> {
                if (key instanceof String stringKey) {
                    itemData.put(stringKey, value);
                }
            });
            ItemStack itemStack = ItemStack.deserialize(itemData);
            return itemStack != null && !itemStack.getType().isAir()
                    ? Optional.of(itemStack)
                    : Optional.empty();
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "AuctionHouse serialized map is not a valid ItemStack.", e);
            return Optional.empty();
        }
    }

    private static Optional<ItemStack> deserializeItemBytes(byte[] data) {
        if (DESERIALIZE_BYTES_METHOD == null) {
            return Optional.empty();
        }
        try {
            // 优先尝试 0 偏移 (标准 Paper 序列化绝大多数为 0 偏移)
            try {
                Object direct = DESERIALIZE_BYTES_METHOD.invoke(null, (Object) data);
                if (direct instanceof ItemStack itemStack && !itemStack.getType().isAir()) {
                    return Optional.of(itemStack);
                }
            } catch (InvocationTargetException | IllegalArgumentException ignored) {
                // 存在自定义头部，继续探测
            }

            // 仅在候选字节首位符合 NBT Tag_Compound (0x0A) 时尝试反射，避免无节制抛出异常引发性能雪崩
            int maxOffset = Math.min(data.length - 1, MAX_PAPER_PREFIX_LENGTH);
            for (int offset = 1; offset <= maxOffset; offset++) {
                if ((data[offset] & 0xFF) != 0x0A) {
                    continue;
                }
                byte[] candidate = Arrays.copyOfRange(data, offset, data.length);
                try {
                    Object result = DESERIALIZE_BYTES_METHOD.invoke(null, (Object) candidate);
                    if (result instanceof ItemStack itemStack && !itemStack.getType().isAir()) {
                        return Optional.of(itemStack);
                    }
                } catch (InvocationTargetException | IllegalArgumentException ignored) {
                    // 继续尝试后续符合特征的偏移
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.log(Level.FINE, "Paper ItemStack byte deserialization failed.", e);
        }
        return Optional.empty();
    }

    @SuppressWarnings("deprecation")
    private static ItemDetails extractDetails(ItemStack itemStack) {
        Material material = itemStack.getType();
        ItemMeta meta = itemStack.getItemMeta();
        String displayName = meta != null && meta.hasDisplayName()
                ? meta.getDisplayName()
                : humanizeMaterial(material.name());
        List<String> lore = meta != null && meta.hasLore() && meta.getLore() != null
                ? new ArrayList<>(meta.getLore())
                : List.of();
        Integer customModelData = meta != null && meta.hasCustomModelData()
                ? meta.getCustomModelData()
                : null;

        return new ItemDetails(
                material.name().toLowerCase(Locale.ROOT),
                displayName,
                itemStack.getAmount(),
                lore,
                customModelData
        );
    }

    private static int findObjectStreamHeader(byte[] data) {
        int lastOffset = Math.min(data.length - 4, MAX_HEADER_LENGTH);
        for (int offset = 0; offset <= lastOffset; offset++) {
            if ((data[offset] & 0xff) == 0xac
                    && (data[offset + 1] & 0xff) == 0xed
                    && (data[offset + 2] & 0xff) == 0x00
                    && (data[offset + 3] & 0xff) == 0x05) {
                return offset;
            }
        }
        return -1;
    }

    private static int findMagic(byte[] data, int first, int second) {
        int lastOffset = Math.min(data.length - 2, MAX_HEADER_LENGTH);
        for (int offset = 0; offset <= lastOffset; offset++) {
            if ((data[offset] & 0xff) == first && (data[offset + 1] & 0xff) == second) {
                return offset;
            }
        }
        return -1;
    }

    private static void logUnsupportedFormat(byte[] data) {
        int warningNumber = failureWarnings.incrementAndGet();
        if (warningNumber <= MAX_FAILURE_WARNINGS) {
            LOGGER.warning("Unsupported AuctionHouse item BLOB (length " + data.length
                    + ", header " + hexHeader(data) + "). Item details will use fallback values.");
        }
    }

    private static String hexHeader(byte[] data) {
        StringBuilder header = new StringBuilder();
        int length = Math.min(data.length, 12);
        for (int i = 0; i < length; i++) {
            if (i > 0) {
                header.append(' ');
            }
            header.append(String.format(Locale.ROOT, "%02x", data[i] & 0xff));
        }
        return header.toString();
    }

    private static String humanizeMaterial(String materialName) {
        String[] words = materialName.toLowerCase(Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                if (result.length() > 0) {
                    result.append(' ');
                }
                result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return result.toString();
    }

    public static final class ItemDetails {
        private final String material;
        private final String displayName;
        private final int amount;
        private final List<String> lore;
        private final Integer customModelData;

        private ItemDetails(String material, String displayName, int amount, List<String> lore,
                            Integer customModelData) {
            this.material = material;
            this.displayName = displayName;
            this.amount = amount;
            this.lore = lore;
            this.customModelData = customModelData;
        }

        public String getMaterial() {
            return material;
        }

        public String getDisplayName() {
            return displayName;
        }

        public int getAmount() {
            return amount;
        }

        public List<String> getLore() {
            return lore;
        }

        public Integer getCustomModelData() {
            return customModelData;
        }
    }

    private static final class ByteArrayKey {
        private final byte[] bytes;
        private final int hashCode;

        private ByteArrayKey(byte[] bytes) {
            this.bytes = bytes;
            this.hashCode = Arrays.hashCode(bytes);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ByteArrayKey other)) return false;
            return Arrays.equals(this.bytes, other.bytes);
        }

        @Override
        public int hashCode() {
            return this.hashCode;
        }
    }
}
