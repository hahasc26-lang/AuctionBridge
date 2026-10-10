package net.minecraftforge.auctionbridge.database.model;

import java.util.List;
import java.util.Map;

/**
 * 拍卖行在售/历史物品模型 (只读 DTO)
 */
public class AuctionItem {

    private long id;
    private String sellerUuid;
    private String sellerName;
    private String buyerUuid;
    private String buyerName;
    private double price;
    private String itemType;
    private String material;
    private String displayName;
    private String itemName;
    private int amount;
    private List<String> lore;
    private Integer customModelData;
    private Map<String, Integer> enchantments;
    private String rawNbt;
    private String status; // ACTIVE, SOLD, CANCELLED, EXPIRED
    private long createdAt;
    private long expiresAt;
    private long soldAt;

    public AuctionItem() {}

    public AuctionItem(long id, String sellerUuid, String sellerName, double price, String itemType, String displayName, int amount, String status, long createdAt, long expiresAt) {
        this.id = id;
        this.sellerUuid = sellerUuid;
        this.sellerName = sellerName;
        this.price = price;
        this.itemType = itemType;
        this.material = itemType;
        this.displayName = displayName;
        this.itemName = displayName;
        this.amount = amount;
        this.status = status;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    // Getters and Setters
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public String getSellerUuid() { return sellerUuid; }
    public void setSellerUuid(String sellerUuid) { this.sellerUuid = sellerUuid; }
    public String getSellerName() { return sellerName; }
    public void setSellerName(String sellerName) { this.sellerName = sellerName; }
    public String getBuyerUuid() { return buyerUuid; }
    public void setBuyerUuid(String buyerUuid) { this.buyerUuid = buyerUuid; }
    public String getBuyerName() { return buyerName; }
    public void setBuyerName(String buyerName) { this.buyerName = buyerName; }
    public double getPrice() { return price; }
    public void setPrice(double price) { this.price = price; }
    public String getItemType() { return itemType; }
    public void setItemType(String itemType) { this.itemType = itemType; this.material = itemType; }
    public String getMaterial() { return material; }
    public void setMaterial(String material) { this.material = material; this.itemType = material; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; this.itemName = displayName; }
    public String getItemName() { return itemName; }
    public void setItemName(String itemName) { this.itemName = itemName; this.displayName = itemName; }
    public int getAmount() { return amount; }
    public void setAmount(int amount) { this.amount = amount; }
    public List<String> getLore() { return lore; }
    public void setLore(List<String> lore) { this.lore = lore; }
    public Integer getCustomModelData() { return customModelData; }
    public void setCustomModelData(Integer customModelData) { this.customModelData = customModelData; }
    public Map<String, Integer> getEnchantments() { return enchantments; }
    public void setEnchantments(Map<String, Integer> enchantments) { this.enchantments = enchantments; }
    public String getRawNbt() { return rawNbt; }
    public void setRawNbt(String rawNbt) { this.rawNbt = rawNbt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(long expiresAt) { this.expiresAt = expiresAt; }
    public long getSoldAt() { return soldAt; }
    public void setSoldAt(long soldAt) { this.soldAt = soldAt; }
}
