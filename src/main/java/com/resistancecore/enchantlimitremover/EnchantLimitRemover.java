package com.resistancecore.enchantlimitremover;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.NamespacedKey;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

public class EnchantLimitRemover extends JavaPlugin {
    
    private Map<String, Integer> permissionLevelCache = new HashMap<>();
    
    @Override
    public void onEnable() {
        // Save default config if it doesn't exist
        saveDefaultConfig();
        
        // Validate and fix config
        validateConfig();
        
        // Register events and commands with plugin instance
        getServer().getPluginManager().registerEvents(new EnchantmentListener(this), this);
        
        // Register command executor and tab completer
        if (getCommand("elrenchant") != null) {
            getCommand("elrenchant").setExecutor(new EnchantCommand(this));
            getCommand("elrenchant").setTabCompleter(new EnchantTabCompleter(this));
        } else {
            getLogger().severe("Command 'elrenchant' not found in plugin.yml!");
        }

        // Log successful enable
        getLogger().info("==================================================");
        getLogger().info("EnchantmentLimitRemover v" + getDescription().getVersion() + " has been enabled!");
        getLogger().info("Author: " + getDescription().getAuthors());
        getLogger().info("Base max enchant level: " + getConfig().getInt("max-enchant-level", 10));
        getLogger().info("Absolute max level: " + getConfig().getInt("security.absolute-max-level", 1000));
        getLogger().info("Permission levels enabled: " + getConfig().getBoolean("use-permission-levels", true));
        getLogger().info("Item-specific limits enabled: " + getConfig().getBoolean("item-specific-limits.enabled", false));
        getLogger().info("Debug mode: " + getConfig().getBoolean("debug", false));
        
        if (getConfig().getBoolean("debug", false)) {
            getLogger().info("Debug information:");
            getLogger().info("- Disabled enchantments: " + getConfig().getStringList("disabled-enchantments"));
            getLogger().info("- Show messages: " + getConfig().getBoolean("show-messages", true));
            getLogger().info("- Economy enabled: " + getConfig().getBoolean("economy.enabled", false));
            getLogger().info("- Cooldowns enabled: " + getConfig().getBoolean("cooldowns.enabled", false));
            getLogger().info("- Book enchanting fix: " + getConfig().getBoolean("enchanting-table.fix-book-enchanting", true));
        }
        
        getLogger().info("==================================================");
        
        // Check for updates if enabled
        if (getConfig().getBoolean("check-for-updates", true)) {
            getLogger().info("Update checker is enabled. Checking for updates...");
        }
    }

    @Override
    public void onDisable() {
        // Clear cache
        permissionLevelCache.clear();
        
        getLogger().info("==================================================");
        getLogger().info("EnchantmentLimitRemover has been disabled!");
        getLogger().info("Thank you for using our plugin!");
        getLogger().info("==================================================");
    }
    
    /**
     * Validates and fixes configuration values
     */
    private void validateConfig() {
        FileConfiguration config = getConfig();
        boolean configChanged = false;
        
        // Validate max-enchant-level
        if (config.getInt("max-enchant-level", -1) == -1 || config.getInt("max-enchant-level") < 1) {
            getLogger().warning("Invalid max-enchant-level in config! Setting to default value: 10");
            config.set("max-enchant-level", 10);
            configChanged = true;
        }
        
        // Validate absolute max level
        int absoluteMax = config.getInt("security.absolute-max-level", 1000);
        if (absoluteMax < 1 || absoluteMax > 32767) {
            getLogger().warning("Invalid absolute-max-level! Setting to default: 1000");
            config.set("security.absolute-max-level", 1000);
            configChanged = true;
        }
        
        // Validate base max vs absolute max
        int baseMax = config.getInt("max-enchant-level", 10);
        if (baseMax > absoluteMax) {
            getLogger().warning("Base max-enchant-level (" + baseMax + ") is higher than absolute max (" + absoluteMax + ")! Adjusting base to " + absoluteMax);
            config.set("max-enchant-level", absoluteMax);
            configChanged = true;
        }
        
        // Validate language setting
        String language = config.getString("language", "en");
        if (!language.equals("en") && !language.equals("id")) {
            getLogger().warning("Invalid language setting! Supported languages: en, id. Setting to default: en");
            config.set("language", "en");
            configChanged = true;
        }
        
        // Validate cooldown duration
        if (config.getBoolean("cooldowns.enabled", false)) {
            if (config.getInt("cooldowns.duration", -1) == -1 || config.getInt("cooldowns.duration") < 0) {
                getLogger().warning("Invalid cooldown duration! Setting to default: 60 seconds");
                config.set("cooldowns.duration", 60);
                configChanged = true;
            }
        }
        
        // Validate economy cost
        if (config.getBoolean("economy.enabled", false)) {
            if (config.getDouble("economy.cost-per-level", -1) == -1 || config.getDouble("economy.cost-per-level") < 0) {
                getLogger().warning("Invalid economy cost-per-level! Setting to default: 100");
                config.set("economy.cost-per-level", 100.0);
                configChanged = true;
            }
        }
        
        // Validate disabled enchantments list
        if (config.getStringList("disabled-enchantments") == null) {
            config.set("disabled-enchantments", new String[0]);
            configChanged = true;
        }
        
        // Validate disabled enchantments exist
        List<String> disabledEnchants = config.getStringList("disabled-enchantments");
        List<String> validDisabledEnchants = new java.util.ArrayList<>();
        for (String enchantName : disabledEnchants) {
            Enchantment enchant = Enchantment.getByKey(NamespacedKey.minecraft(enchantName.toLowerCase()));
            if (enchant != null) {
                validDisabledEnchants.add(enchantName.toLowerCase());
            } else {
                getLogger().warning("Invalid enchantment name in disabled list: " + enchantName);
            }
        }
        if (validDisabledEnchants.size() != disabledEnchants.size()) {
            config.set("disabled-enchantments", validDisabledEnchants);
            configChanged = true;
        }
        
        // Validate item-specific limits
        if (config.getBoolean("item-specific-limits.enabled", false)) {
            if (config.getConfigurationSection("item-specific-limits.items") != null) {
                for (String itemKey : config.getConfigurationSection("item-specific-limits.items").getKeys(false)) {
                    try {
                        org.bukkit.Material.valueOf(itemKey.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        getLogger().warning("Invalid material in item-specific-limits: " + itemKey);
                        config.set("item-specific-limits.items." + itemKey, null);
                        configChanged = true;
                    }
                }
            }
        }
        
        // Save config if changes were made
        if (configChanged) {
            saveConfig();
            getLogger().info("Configuration has been validated and updated!");
        }
    }
    
    /**
     * Reloads the plugin configuration
     */
    @Override
    public void reloadConfig() {
        super.reloadConfig();
        // Clear permission cache on reload
        permissionLevelCache.clear();
        validateConfig();
        getLogger().info("Configuration reloaded successfully!");
    }
    
    /**
     * Gets the plugin version
     */
    public String getPluginVersion() {
        return getDescription().getVersion();
    }
    
    /**
     * Checks if debug mode is enabled
     */
    public boolean isDebugEnabled() {
        return getConfig().getBoolean("debug", false);
    }
    
    /**
     * Logs debug message if debug mode is enabled
     */
    public void debugLog(String message) {
        if (isDebugEnabled()) {
            getLogger().info("[DEBUG] " + message);
        }
    }
    
    /**
     * Clears permission level cache for a player
     */
    public void clearPermissionCache(String playerName) {
        permissionLevelCache.remove(playerName);
    }
    
    /**
     * Gets cached or calculates permission level for player
     */
    public int getCachedPermissionLevel(org.bukkit.entity.Player player) {
        String playerName = player.getName();
        
        if (permissionLevelCache.containsKey(playerName)) {
            return permissionLevelCache.get(playerName);
        }
        
        int level = calculatePermissionLevel(player);
        permissionLevelCache.put(playerName, level);
        
        debugLog("Calculated permission level for " + playerName + ": " + level);
        
        return level;
    }
    
    /**
     * Calculates the maximum permission level for a player
     */
    private int calculatePermissionLevel(org.bukkit.entity.Player player) {
        if (!getConfig().getBoolean("use-permission-levels", true)) {
            return 0; // No permission levels
        }
        
        // Check common permission levels first (optimization)
        int[] commonLevels = {1000, 500, 255, 100, 50, 20, 10, 5, 1};
        
        for (int level : commonLevels) {
            if (player.hasPermission("elr.level." + level)) {
                debugLog("Player " + player.getName() + " has permission elr.level." + level);
                return level;
            }
        }
        
        return 0; // No permission level found
    }
}