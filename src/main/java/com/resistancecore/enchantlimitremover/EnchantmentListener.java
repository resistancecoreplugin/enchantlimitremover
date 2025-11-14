package com.resistancecore.enchantlimitremover;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

public class EnchantmentListener implements Listener {
    private final EnchantLimitRemover plugin;

    public EnchantmentListener(EnchantLimitRemover plugin) {
        this.plugin = plugin;
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
    
    private String getConfigMessage(String key, String defaultMessage) {
        return plugin.getConfig().getString("messages." + key, defaultMessage)
                .replace("{prefix}", plugin.getConfig().getString("messages.prefix", "§6[§eELR§6] "));
    }
    
    /**
     * Checks and fixes enchantment levels on an item according to player's limits
     * @param player The player who owns/will own the item
     * @param item The item to check and fix
     * @return true if any enchantments were modified, false otherwise
     */
    private boolean checkAndFixEnchantments(Player player, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        
        boolean modified = false;
        boolean showMessages = plugin.getConfig().getBoolean("show-messages", true);
        List<String> disabledEnchants = plugin.getConfig().getStringList("disabled-enchantments");
        
        if (item.getType() == Material.ENCHANTED_BOOK) {
            // Handle enchanted books
            EnchantmentStorageMeta meta = (EnchantmentStorageMeta) item.getItemMeta();
            if (meta != null && meta.hasStoredEnchants()) {
                Map<Enchantment, Integer> storedEnchants = new HashMap<>(meta.getStoredEnchants());
                
                for (Map.Entry<Enchantment, Integer> entry : storedEnchants.entrySet()) {
                    Enchantment enchantment = entry.getKey();
                    int currentLevel = entry.getValue();
                    
                    // Check if enchantment is disabled
                    if (disabledEnchants.contains(enchantment.getKey().getKey().toLowerCase())) {
                        if (!player.hasPermission("elr.bypass.disabled")) {
                            meta.removeStoredEnchant(enchantment);
                            modified = true;
                            plugin.debugLog("Removed disabled enchantment " + enchantment.getKey().getKey() + " from enchanted book for " + player.getName());
                            if (showMessages) {
                                String message = getConfigMessage("enchant-disabled-removed", "§6Disabled enchantment §e{enchantment} §6was removed from your enchanted book!")
                                        .replace("{enchantment}", enchantment.getKey().getKey());
                                player.sendMessage(message);
                            }
                            continue;
                        }
                    }
                    
                    // Check level limits
                    int maxLevel = getMaxLevelForPlayer(player, enchantment, item.getType());
                    if (currentLevel > maxLevel) {
                        meta.removeStoredEnchant(enchantment);
                        if (maxLevel > 0) {
                            meta.addStoredEnchant(enchantment, maxLevel, true);
                        }
                        modified = true;
                        plugin.debugLog("Adjusted enchantment " + enchantment.getKey().getKey() + " from level " + currentLevel + " to " + maxLevel + " on enchanted book for " + player.getName());
                        if (showMessages) {
                            String message = getConfigMessage("enchant-level-reduced", "§6Enchantment §e{enchantment} §6level was reduced from §c{old-level} §6to §a{new-level} §6on your enchanted book!")
                                    .replace("{enchantment}", enchantment.getKey().getKey())
                                    .replace("{old-level}", String.valueOf(currentLevel))
                                    .replace("{new-level}", String.valueOf(maxLevel));
                            player.sendMessage(message);
                        }
                    }
                }
                
                if (modified) {
                    item.setItemMeta(meta);
                }
            }
        } else {
            // Handle regular items
            Map<Enchantment, Integer> enchantments = new HashMap<>(item.getEnchantments());
            
            for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
                Enchantment enchantment = entry.getKey();
                int currentLevel = entry.getValue();
                
                // Check if enchantment is disabled
                if (disabledEnchants.contains(enchantment.getKey().getKey().toLowerCase())) {
                    if (!player.hasPermission("elr.bypass.disabled")) {
                        item.removeEnchantment(enchantment);
                        modified = true;
                        plugin.debugLog("Removed disabled enchantment " + enchantment.getKey().getKey() + " from " + item.getType() + " for " + player.getName());
                        if (showMessages) {
                            String message = getConfigMessage("enchant-disabled-removed", "§6Disabled enchantment §e{enchantment} §6was removed from your {item}!")
                                    .replace("{enchantment}", enchantment.getKey().getKey())
                                    .replace("{item}", item.getType().name().toLowerCase().replace("_", " "));
                            player.sendMessage(message);
                        }
                        continue;
                    }
                }
                
                // Check level limits
                int maxLevel = getMaxLevelForPlayer(player, enchantment, item.getType());
                if (currentLevel > maxLevel) {
                    item.removeEnchantment(enchantment);
                    if (maxLevel > 0) {
                        item.addUnsafeEnchantment(enchantment, maxLevel);
                    }
                    modified = true;
                    plugin.debugLog("Adjusted enchantment " + enchantment.getKey().getKey() + " from level " + currentLevel + " to " + maxLevel + " on " + item.getType() + " for " + player.getName());
                    if (showMessages) {
                        String message = getConfigMessage("enchant-level-reduced", "§6Enchantment §e{enchantment} §6level was reduced from §c{old-level} §6to §a{new-level} §6on your {item}!")
                                .replace("{enchantment}", enchantment.getKey().getKey())
                                .replace("{old-level}", String.valueOf(currentLevel))
                                .replace("{new-level}", String.valueOf(maxLevel))
                                .replace("{item}", item.getType().name().toLowerCase().replace("_", " "));
                        player.sendMessage(message);
                    }
                }
            }
        }
        
        return modified;
    }
    
    /**
     * Checks all items in player's inventory and fixes enchantments that exceed limits
     */
    private void checkAndFixPlayerInventory(Player player) {
        if (!player.hasPermission("elr.use")) {
            return;
        }
        
        plugin.debugLog("Checking inventory for player: " + player.getName());
        
        boolean anyModified = false;
        
        // Check main inventory
        for (ItemStack item : player.getInventory().getContents()) {
            if (checkAndFixEnchantments(player, item)) {
                anyModified = true;
            }
        }
        
        // Check armor slots
        for (ItemStack item : player.getInventory().getArmorContents()) {
            if (checkAndFixEnchantments(player, item)) {
                anyModified = true;
            }
        }
        
        // Check offhand
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (checkAndFixEnchantments(player, offhand)) {
            anyModified = true;
        }
        
        if (anyModified) {
            plugin.debugLog("Fixed enchantments in inventory for player: " + player.getName());
            if (plugin.getConfig().getBoolean("show-messages", true)) {
                String message = getConfigMessage("inventory-fixed", "§6Some enchantments in your inventory were adjusted to match your limits!");
                player.sendMessage(message);
            }
        }
    }
    
    @EventHandler
    public void onEnchantItem(EnchantItemEvent event) {
        Player player = event.getEnchanter();
        
        // Check permission
        if (!player.hasPermission("elr.use")) {
            plugin.debugLog("Player " + player.getName() + " lacks elr.use permission");
            return;
        }

        // Check if book enchanting fix is enabled
        ItemStack originalItem = event.getItem();
        if (originalItem.getType() == Material.BOOK && !plugin.getConfig().getBoolean("enchanting-table.fix-book-enchanting", true)) {
            plugin.debugLog("Book enchanting fix is disabled, allowing vanilla behavior");
            return;
        }

        Map<Enchantment, Integer> enchantsToAdd = event.getEnchantsToAdd();
        List<String> disabledEnchants = plugin.getConfig().getStringList("disabled-enchantments");
        boolean showMessages = plugin.getConfig().getBoolean("show-messages", true);
        
        plugin.debugLog("Processing enchantment event for " + player.getName() + " on " + originalItem.getType());
        
        // Check if any enchantments need modification or removal
        boolean needsModification = false;
        
        for (Map.Entry<Enchantment, Integer> entry : enchantsToAdd.entrySet()) {
            Enchantment enchantment = entry.getKey();
            int level = entry.getValue();
            
            // Check disabled enchantments
            if (disabledEnchants.contains(enchantment.getKey().getKey().toLowerCase())) {
                if (!player.hasPermission("elr.bypass.disabled")) {
                    needsModification = true;
                    plugin.debugLog("Enchantment " + enchantment.getKey().getKey() + " is disabled for " + player.getName());
                    break;
                } else {
                    plugin.debugLog("Player " + player.getName() + " bypassed disabled enchantment: " + enchantment.getKey().getKey());
                }
            }
            
            // Check level limits
            int maxLevel = getMaxLevelForPlayer(player, enchantment, originalItem.getType());
            if (level > maxLevel) {
                needsModification = true;
                plugin.debugLog("Enchantment level " + level + " exceeds max " + maxLevel + " for " + player.getName());
                break;
            }
        }
        
        // If no modification needed, let vanilla handle it
        if (!needsModification) {
            plugin.debugLog("No modification needed, allowing vanilla enchanting");
            return;
        }
        
        // Cancel the event and handle manually
        event.setCancelled(true);
        plugin.debugLog("Cancelled vanilla enchanting event, handling manually");
        
        // Handle enchantment application
        ItemStack targetItem = originalItem;
        
        // Special handling for books
        if (originalItem.getType() == Material.BOOK) {
            // Convert book to enchanted book
            targetItem = new ItemStack(Material.ENCHANTED_BOOK);
            EnchantmentStorageMeta meta = (EnchantmentStorageMeta) targetItem.getItemMeta();
            
            if (meta != null) {
                // Add enchantments to the book
                for (Map.Entry<Enchantment, Integer> entry : enchantsToAdd.entrySet()) {
                    Enchantment enchantment = entry.getKey();
                    int level = entry.getValue();
                    
                    // Skip disabled enchantments
                    if (disabledEnchants.contains(enchantment.getKey().getKey().toLowerCase())) {
                        if (!player.hasPermission("elr.bypass.disabled")) {
                            if (showMessages) {
                                String message = getConfigMessage("enchant-disabled", "§c{enchantment} enchantment is disabled!")
                                        .replace("{enchantment}", enchantment.getKey().getKey());
                                player.sendMessage(message);
                            }
                            continue;
                        }
                    }
                    
                    // Apply level limits
                    int maxLevel = getMaxLevelForPlayer(player, enchantment, targetItem.getType());
                    if (level > maxLevel) {
                        level = maxLevel;
                        if (showMessages) {
                            String message = getConfigMessage("max-level-limited", "§6Enchantment level limited to {level} for {enchantment}")
                                    .replace("{level}", String.valueOf(level))
                                    .replace("{enchantment}", enchantment.getKey().getKey());
                            player.sendMessage(message);
                        }
                    }
                    
                    // Add to enchanted book
                    meta.addStoredEnchant(enchantment, level, true);
                    plugin.debugLog("Added " + enchantment.getKey().getKey() + " " + level + " to enchanted book");
                }
                
                targetItem.setItemMeta(meta);
                
                // Replace the item in inventory
                event.getInventory().setItem(0, targetItem);
            }
        } else {
            // Handle normal items (not books)
            for (Map.Entry<Enchantment, Integer> entry : enchantsToAdd.entrySet()) {
                Enchantment enchantment = entry.getKey();
                int level = entry.getValue();
                
                // Skip disabled enchantments
                if (disabledEnchants.contains(enchantment.getKey().getKey().toLowerCase())) {
                    if (!player.hasPermission("elr.bypass.disabled")) {
                        if (showMessages) {
                            String message = getConfigMessage("enchant-disabled", "§c{enchantment} enchantment is disabled!")
                                    .replace("{enchantment}", enchantment.getKey().getKey());
                            player.sendMessage(message);
                        }
                        continue;
                    }
                }
                
                // Apply level limits
                int maxLevel = getMaxLevelForPlayer(player, enchantment, targetItem.getType());
                if (level > maxLevel) {
                    level = maxLevel;
                    if (showMessages) {
                        String message = getConfigMessage("max-level-limited", "§6Enchantment level limited to {level} for {enchantment}")
                                .replace("{level}", String.valueOf(level))
                                .replace("{enchantment}", enchantment.getKey().getKey());
                        player.sendMessage(message);
                    }
                }
                
                // Add enchantment to item
                targetItem.addUnsafeEnchantment(enchantment, level);
                plugin.debugLog("Added " + enchantment.getKey().getKey() + " " + level + " to " + targetItem.getType());
            }
        }
        
        // Consume resources if enabled
        if (plugin.getConfig().getBoolean("enchanting-table.consume-resources", true)) {
            // Consume experience
            int expCost = event.getExpLevelCost();
            player.setLevel(player.getLevel() - expCost);
            plugin.debugLog("Consumed " + expCost + " experience levels");
            
            // Remove lapis from inventory
            ItemStack lapis = event.getInventory().getItem(1);
            if (lapis != null && lapis.getType() == Material.LAPIS_LAZULI) {
                int lapisUsed = Math.min(3, lapis.getAmount()); // Max 3 lapis used
                if (lapis.getAmount() <= lapisUsed) {
                    event.getInventory().setItem(1, null);
                } else {
                    lapis.setAmount(lapis.getAmount() - lapisUsed);
                }
                plugin.debugLog("Consumed " + lapisUsed + " lapis lazuli");
            }
        }
        
        // Debug logging
        plugin.debugLog("Player " + player.getName() + " enchanted item: " + 
            (originalItem.getType() == Material.BOOK ? "ENCHANTED_BOOK" : targetItem.getType()));
        
        // Success message
        if (showMessages) {
            String message = getConfigMessage("item-enchanted", "§aItem successfully enchanted!");
            player.sendMessage(message);
        }
        
        // Log activity if enabled
        if (plugin.getConfig().getBoolean("security.log-activities", true)) {
            plugin.getLogger().info("Player " + player.getName() + " enchanted " + 
                (originalItem.getType() == Material.BOOK ? "book to enchanted book" : targetItem.getType().name()) +
                " at location " + player.getLocation().toString());
        }
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        ItemStack result = event.getResult();
        if (result == null || result.getType().isAir()) {
            return;
        }
        
        // Get the player who is using the anvil
        Player player = null;
        if (event.getInventory().getViewers().size() > 0 && event.getInventory().getViewers().get(0) instanceof Player) {
            player = (Player) event.getInventory().getViewers().get(0);
        }
        
        if (player == null || !player.hasPermission("elr.use")) {
            return;
        }
        
        plugin.debugLog("Processing anvil event for " + player.getName() + " with result: " + result.getType());
        
        // Create a copy of the result to modify
        ItemStack modifiedResult = result.clone();
        boolean wasModified = checkAndFixEnchantments(player, modifiedResult);
        
        if (wasModified) {
            event.setResult(modifiedResult);
            plugin.debugLog("Modified anvil result for " + player.getName());
            
            // Log activity if enabled
            if (plugin.getConfig().getBoolean("security.log-activities", true)) {
                plugin.getLogger().info("Player " + player.getName() + " used anvil with limit enforcement at " + player.getLocation().toString());
            }
        }
    }
    
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        // Check inventory after a short delay to ensure everything is loaded
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            checkAndFixPlayerInventory(player);
        }, 20L); // 1 second delay
    }
    
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        
        Player player = (Player) event.getWhoClicked();
        
        if (!player.hasPermission("elr.use")) {
            return;
        }
        
        ItemStack currentItem = event.getCurrentItem();
        
        // Check the item being moved/clicked
        if (currentItem != null && !currentItem.getType().isAir()) {
            // Run check after the click event completes
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                checkAndFixEnchantments(player, currentItem);
            });
        }
        
        // Also check cursor item
        ItemStack cursorItem = event.getCursor();
        if (cursorItem != null && !cursorItem.getType().isAir()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                checkAndFixEnchantments(player, cursorItem);
            });
        }
    }
    
    @EventHandler
    public void onPlayerPickupItem(PlayerPickupItemEvent event) {
        Player player = event.getPlayer();
        
        if (!player.hasPermission("elr.use")) {
            return;
        }
        
        ItemStack item = event.getItem().getItemStack();
        
        // Check the item being picked up
        if (checkAndFixEnchantments(player, item)) {
            event.getItem().setItemStack(item);
            plugin.debugLog("Fixed enchantments on picked up item for " + player.getName());
        }
    }
}