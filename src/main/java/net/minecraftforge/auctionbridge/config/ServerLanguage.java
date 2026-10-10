package net.minecraftforge.auctionbridge.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class ServerLanguage {

    private final JavaPlugin plugin;
    private volatile FileConfiguration messages;

    public ServerLanguage(JavaPlugin plugin, String language) {
        this.plugin = plugin;
        load(language);
    }

    public void load(String language) {
        String resourcePath = "lang/" + language + ".yml";
        plugin.saveResource(resourcePath, false);
        File languageFile = new File(new File(plugin.getDataFolder(), "lang"), language + ".yml");
        this.messages = YamlConfiguration.loadConfiguration(languageFile);
    }

    public String get(String key, Object... values) {
        String message = messages.getString(key, key);
        for (int i = 0; i < values.length; i++) {
            message = message.replace("{" + i + "}", String.valueOf(values[i]));
        }
        return message;
    }
}
