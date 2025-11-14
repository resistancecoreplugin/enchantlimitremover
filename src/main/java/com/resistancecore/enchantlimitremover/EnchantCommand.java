package com.resistancecore.enchantlimitremover;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

public class EnchantCommand implements CommandExecutor {
    private final EnchantLimitRemover plugin;
    private final Map<String, Long> cooldowns = new HashMap<>();

    public EnchantCommand(EnchantLimitRemover plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be used by players!");
            return true;
        }

        Player player = (Player) sender;

        if (!player.hasPermission("elr.admin")) {
            player.sendMessage(getConfigMessage("no-permission", "§cYou don't have permission to use this command!"));
            return true;
        }

        if (args.length < 1) {
            sendHelpMessage(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "add":
                if (args.length < 3) {
                    player.sendMessage("§cUsage: /elrenchant add <enchantment> <level>");
                    return true;
                }
                handleAddEnchant(player, args[1], args[2]);
                break;

            case "remove":
                if (args.length < 2) {
                    player.sendMessage("§cUsage: /elrenchant remove <enchantment>");
                    return true;
                }
                handleRemoveEnchant(player, args[1]);
                break;

            case "list":
                handleListEnchants(player);
                break;

            case "reload":
                handleReload(player);
                break;

            case "info":
                handleInfo(player);
                break;

            case "clear":
                handleClearEnchants(player);
                break;

            default:
                sendHelpMessage(player);
                break;
        }

        return true;
    }

    private void sendHelpMessage(Player player) {
        player.sendMessage("§6═══════ EnchantLimitRemover Commands ═══════");
        player.sendMessage("§e/elrenchant add <enchant> <level> §7- Add enchantment to item");
        player.sendMessage("§e/elrenchant remove <enchant> §7- Remove enchantment from item");
        player.sendMessage("§e/elrenchant clear §7- Remove all enchantments from item");
        player.sendMessage("§e/elrenchant list §7- List all available enchantments");
        player.sendMessage("§e/elrenchant info §7- Show item enchantment info");
        player.sendMessage("§e/elrenchant reload §7- Reload plugin configuration");
        player.sendMessage("§6═══════════════════════════════════════════");
    }
    
    private String getConfigMessage(String key, String defaultMessage) {
        return plugin.getConfig().getString("messages." + key, defaultMessage)
                .replace("{prefix}", plugin.getConfig().getString("messages.prefix", "§6[§eELR§6] "));
    }

    private boolean checkCooldown(Player player) {
        if (!plugin.getConfig().getBoolean("cooldowns.enabled", false)) {
            return true;
        }
        
        // Check cooldown bypass permission
        if (player.hasPermission("elr.bypass.cooldown")) {
            return true;
        }

        String playerName = player.getName();
        long cooldownDuration = plugin.getConfig().getLong("cooldowns.duration", 60) * 1000; // Convert to milliseconds
        
        if (cooldowns.containsKey(playerName)) {
            long timeLeft = cooldowns.get(playerName) + cooldownDuration - System.currentTimeMillis();
            if (timeLeft > 0) {
                String message = getConfigMessage("cooldown-active", "§cYou must wait {time} seconds before using this command again!")
                        .replace("{time}", String.valueOf(timeLeft / 1000));
                player.sendMessage(message);
                return false;
            }
        }
        
        cooldowns.put(playerName, System.currentTimeMillis());
        return true;
    }

    /**
     * Gets the maximum enchantment level for a player considering all limits
     */
    private int getMaxLevelForPlayer(Player player, Enchantment enchantment, Material itemType) {
        // Start with base config level
        int maxLevel = plugin.getConfig().getInt("max-enchant-level", 10);
        
        plugin.debugLog("Base max level from config: " + maxLevel);
        
        // Apply permission-based level (if higher)
        if (plugin.getConfig().getBoolean("use-permission-levels", true)) {
            int permissionLevel = plugin.getCachedPermissionLevel(player);
            if (permissionLevel > 0) {
                maxLevel = Math.max(maxLevel, permissionLevel);
                plugin.debugLog("Applied permission level " + permissionLevel + ", new max: " + maxLevel);
            }
        }
        
        // Apply item-specific limits (if lower)
        if (plugin.getConfig().getBoolean("item-specific-limits.enabled", false) && enchantment != null && itemType != null) {
            String itemKey = itemType.name();
            String enchantKey = enchantment.getKey().getKey();
            String configPath = "item-specific-limits.items." + itemKey + "." + enchantKey;
            
            if (plugin.getConfig().contains(configPath)) {
                int itemSpecificLimit = plugin.getConfig().getInt(configPath, maxLevel);
                maxLevel = Math.min(maxLevel, itemSpecificLimit);
                plugin.debugLog("Applied item-specific limit for " + itemKey + "." + enchantKey + ": " + itemSpecificLimit + ", new max: " + maxLevel);
            }
        }
        
        // Apply absolute maximum limit (security)
        int absoluteMax = plugin.getConfig().getInt("security.absolute-max-level", 1000);
        maxLevel = Math.min(maxLevel, absoluteMax);
        
        plugin.debugLog("Final max level for " + player.getName() + ": " + maxLevel);
        
        return maxLevel;
    }

    private void handleAddEnchant(Player player, String enchantName, String levelStr) {
        if (!checkCooldown(player)) {
            return;
        }

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) {
            player.sendMessage(getConfigMessage("no-item", "§cYou must hold an item in your main hand!"));
            return;
        }

        try {
            Enchantment enchantment = Enchantment.getByKey(NamespacedKey.minecraft(enchantName.toLowerCase()));
            if (enchantment == null) {
                player.sendMessage(getConfigMessage("invalid-enchant", "§cInvalid enchantment name! Use §e/elrenchant list §cto see available enchantments."));
                return;
            }

            int level = Integer.parseInt(levelStr);
            if (level <= 0) {
                player.sendMessage(getConfigMessage("invalid-level", "§cEnchantment level must be greater than 0!"));
                return;
            }

            // Check if enchantment is disabled
            List<String> disabledEnchants = plugin.getConfig().getStringList("disabled-enchantments");
            if (disabledEnchants.contains(enchantment.getKey().getKey().toLowerCase())) {
                // Check bypass permission
                if (!player.hasPermission("elr.bypass.disabled")) {
                    player.sendMessage(getConfigMessage("enchant-disabled", "§cThis enchantment is disabled!"));
                    return;
                }
                plugin.debugLog("Player " + player.getName() + " bypassed disabled enchantment: " + enchantment.getKey().getKey());
            }

            // Get max level for this player, enchantment, and item
            int maxLevel = getMaxLevelForPlayer(player, enchantment, item.getType());
            if (level > maxLevel) {
                String message = getConfigMessage("level-too-high", "§cMaximum enchantment level for you is {max-level}!")
                        .replace("{max-level}", String.valueOf(maxLevel));
                player.sendMessage(message);
                return;
            }

            // Handle enchanted books differently
            if (item.getType() == Material.ENCHANTED_BOOK) {
                EnchantmentStorageMeta meta = (EnchantmentStorageMeta) item.getItemMeta();
                if (meta != null) {
                    meta.addStoredEnchant(enchantment, level, true);
                    item.setItemMeta(meta);
                }
            } else {
                // Handle regular items
                item.addUnsafeEnchantment(enchantment, level);
            }
            
            if (plugin.getConfig().getBoolean("show-messages", true)) {
                String message = getConfigMessage("enchant-added", "§aSuccessfully added §e{enchantment} {level} §ato item!")
                        .replace("{enchantment}", enchantment.getKey().getKey())
                        .replace("{level}", String.valueOf(level));
                player.sendMessage(message);
            }
            
            plugin.debugLog("Player " + player.getName() + " added " + enchantment.getKey().getKey() + " " + level + " to " + item.getType());
            
        } catch (NumberFormatException e) {
            player.sendMessage(getConfigMessage("invalid-level", "§cInvalid level number! Please enter a valid integer."));
        }
    }

    private void handleRemoveEnchant(Player player, String enchantName) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) {
            player.sendMessage(getConfigMessage("no-item", "§cYou must hold an item in your main hand!"));
            return;
        }

        Enchantment enchantment = Enchantment.getByKey(NamespacedKey.minecraft(enchantName.toLowerCase()));
        if (enchantment == null) {
            player.sendMessage(getConfigMessage("invalid-enchant", "§cInvalid enchantment name! Use §e/elrenchant list §cto see available enchantments."));
            return;
        }

        // Handle enchanted books differently
        if (item.getType() == Material.ENCHANTED_BOOK) {
            EnchantmentStorageMeta meta = (EnchantmentStorageMeta) item.getItemMeta();
            if (meta != null) {
                if (meta.hasStoredEnchant(enchantment)) {
                    meta.removeStoredEnchant(enchantment);
                    item.setItemMeta(meta);
                    String message = getConfigMessage("enchant-removed", "§aSuccessfully removed §e{enchantment} §afrom enchanted book!")
                            .replace("{enchantment}", enchantment.getKey().getKey());
                    player.sendMessage(message);
                } else {
                    player.sendMessage("§cThis enchanted book doesn't have " + enchantment.getKey().getKey() + "!");
                }
            }
        } else {
            // Handle regular items
            if (item.getEnchantments().containsKey(enchantment)) {
                item.removeEnchantment(enchantment);
                String message = getConfigMessage("enchant-removed", "§aSuccessfully removed §e{enchantment} §afrom item!")
                        .replace("{enchantment}", enchantment.getKey().getKey());
                player.sendMessage(message);
            } else {
                player.sendMessage("§cThis item doesn't have " + enchantment.getKey().getKey() + "!");
            }
        }
        
        plugin.debugLog("Player " + player.getName() + " removed " + enchantment.getKey().getKey() + " from " + item.getType());
    }

    private void handleClearEnchants(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) {
            player.sendMessage(getConfigMessage("no-item", "§cYou must hold an item in your main hand!"));
            return;
        }

        if (item.getType() == Material.ENCHANTED_BOOK) {
            EnchantmentStorageMeta meta = (EnchantmentStorageMeta) item.getItemMeta();
            if (meta != null) {
                Map<Enchantment, Integer> storedEnchants = meta.getStoredEnchants();
                for (Enchantment enchant : storedEnchants.keySet()) {
                    meta.removeStoredEnchant(enchant);
                }
                item.setItemMeta(meta);
                player.sendMessage(getConfigMessage("enchants-cleared", "§aAll enchantments removed from enchanted book!"));
            }
        } else {
            Map<Enchantment, Integer> enchants = item.getEnchantments();
            for (Enchantment enchant : enchants.keySet()) {
                item.removeEnchantment(enchant);
            }
            player.sendMessage(getConfigMessage("enchants-cleared", "§aAll enchantments removed from item!"));
        }
        
        plugin.debugLog("Player " + player.getName() + " cleared all enchantments from " + item.getType());
    }

    private void handleListEnchants(Player player) {
        player.sendMessage("§6═══════ Available Enchantments ═══════");
        player.sendMessage("§7Format: §eenchantment_name §7(Vanilla Max: §aX§7, Your Max: §bY§7)");
        player.sendMessage("");
        
        List<String> disabledEnchants = plugin.getConfig().getStringList("disabled-enchantments");
        int playerMaxLevel = getMaxLevelForPlayer(player, null, null);
        
        for (Enchantment enchant : Enchantment.values()) {
            String enchantName = enchant.getKey().getKey();
            int vanillaMax = enchant.getMaxLevel();
            
            // Check if enchantment is disabled
            if (disabledEnchants.contains(enchantName.toLowerCase())) {
                if (player.hasPermission("elr.bypass.disabled")) {
                    player.sendMessage("§e- " + enchantName + " §7(Vanilla: §a" + vanillaMax + "§7, Your Max: §b" + playerMaxLevel + "§7) §c[DISABLED - BYPASSED]");
                } else {
                    player.sendMessage("§c- " + enchantName + " §7(§cDISABLED§7)");
                }
            } else {
                player.sendMessage("§e- " + enchantName + " §7(Vanilla: §a" + vanillaMax + "§7, Your Max: §b" + playerMaxLevel + "§7)");
            }
        }
        player.sendMessage("§6══════════════════════════════════════");
    }

    private void handleInfo(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) {
            player.sendMessage(getConfigMessage("no-item", "§cYou must hold an item in your main hand!"));
            return;
        }

        player.sendMessage("§6═══════ Item Enchantment Info ═══════");
        player.sendMessage("§eItem: §f" + item.getType().name());
        
        if (item.getType() == Material.ENCHANTED_BOOK) {
            EnchantmentStorageMeta meta = (EnchantmentStorageMeta) item.getItemMeta();
            if (meta != null && meta.hasStoredEnchants()) {
                player.sendMessage("§eStored Enchantments:");
                for (Map.Entry<Enchantment, Integer> entry : meta.getStoredEnchants().entrySet()) {
                    int maxForThis = getMaxLevelForPlayer(player, entry.getKey(), item.getType());
                    player.sendMessage("§a- " + entry.getKey().getKey().getKey() + " " + entry.getValue() + " §7(Max: §b" + maxForThis + "§7)");
                }
            } else {
                player.sendMessage("§7No stored enchantments");
            }
        } else {
            if (item.getEnchantments().isEmpty()) {
                player.sendMessage("§7No enchantments");
            } else {
                player.sendMessage("§eEnchantments:");
                for (Map.Entry<Enchantment, Integer> entry : item.getEnchantments().entrySet()) {
                    int maxForThis = getMaxLevelForPlayer(player, entry.getKey(), item.getType());
                    player.sendMessage("§a- " + entry.getKey().getKey().getKey() + " " + entry.getValue() + " §7(Max: §b" + maxForThis + "§7)");
                }
            }
        }
        
        player.sendMessage("§eYour base max level: §a" + getMaxLevelForPlayer(player, null, null));
        player.sendMessage("§ePermission level: §a" + plugin.getCachedPermissionLevel(player));
        player.sendMessage("§eAbsolute max level: §a" + plugin.getConfig().getInt("security.absolute-max-level", 1000));
        player.sendMessage("§6═══════════════════════════════════════");
    }

    private void handleReload(Player player) {
        try {
            plugin.reloadConfig();
            // Clear cooldowns on reload
            cooldowns.clear();
            String message = getConfigMessage("config-reloaded", "§aPlugin configuration reloaded successfully!");
            player.sendMessage(message);
            
            plugin.debugLog("Configuration reloaded by " + player.getName());
        } catch (Exception e) {
            player.sendMessage("§cError reloading configuration: " + e.getMessage());
            plugin.getLogger().severe("Error reloading config: " + e.getMessage());
        }
    }
}