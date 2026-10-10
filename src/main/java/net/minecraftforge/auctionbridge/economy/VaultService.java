package net.minecraftforge.auctionbridge.economy;

import net.milkbowl.vault.economy.Economy;
import net.minecraftforge.auctionbridge.AuctionBridgePlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Vault 经济只读服务接口
 * 保证对经济层只读，从源头上杜绝刷钱漏洞
 */
public class VaultService {

    private final JavaPlugin plugin;
    private Economy economy = null;

    public VaultService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean hookVault() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        this.economy = rsp.getProvider();
        return this.economy != null;
    }

    public boolean isAvailable() {
        return economy != null;
    }

    /**
     * 安全读取玩家当前余额 (只读操作)
     */
    public Optional<Double> getPlayerBalance(UUID playerUuid) {
        if (economy == null || playerUuid == null) {
            return Optional.empty();
        }
        try {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerUuid);
            if (economy.hasAccount(offlinePlayer)) {
                return Optional.of(economy.getBalance(offlinePlayer));
            }
        } catch (Exception e) {
            String message = plugin instanceof AuctionBridgePlugin auctionBridgePlugin
                    ? auctionBridgePlugin.lang("economy.balanceError", playerUuid, e.getMessage())
                    : "Failed to read player balance (" + playerUuid + "): " + e.getMessage();
            plugin.getLogger().warning(message);
        }
        return Optional.empty();
    }

    public String getCurrencySymbol() {
        if (economy == null) return "$";
        try {
            String name = economy.currencyNameSingular();
            if (name == null || name.trim().isEmpty()) {
                return "$";
            }
            return currencySymbolFor(name);
        } catch (Exception e) {
            return "$";
        }
    }

    private String currencySymbolFor(String currencyName) {
        String normalized = currencyName.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "dollar", "dollars", "usd", "us dollar", "us dollars" -> "$";
            case "euro", "euros", "eur" -> "€";
            case "pound", "pounds", "gbp", "pound sterling" -> "£";
            case "yen", "jpy", "yuan", "cny" -> "¥";
            case "rupee", "rupees", "inr" -> "₹";
            case "won", "krw" -> "₩";
            case "ruble", "rubles", "rub" -> "₽";
            case "real", "reais", "brl" -> "R$";
            default -> currencyName.trim();
        };
    }
}
