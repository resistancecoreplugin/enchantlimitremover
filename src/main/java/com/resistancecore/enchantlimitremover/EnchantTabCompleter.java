package com.resistancecore.enchantlimitremover;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class EnchantTabCompleter implements TabCompleter {
    private final EnchantLimitRemover plugin;

    public EnchantTabCompleter(EnchantLimitRemover plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("elrenchant")) {
            return new ArrayList<>();
        }

        // Check permission
        if (!sender.hasPermission("elr.admin")) {
            return new ArrayList<>();
        }

        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            // First argument - subcommands
            List<String> subCommands = new ArrayList<>();
            subCommands.add("add");
            subCommands.add("remove");
            subCommands.add("clear");
            subCommands.add("list");
            subCommands.add("info");
            subCommands.add("reload");

            return subCommands.stream()
                .filter(cmd -> cmd.toLowerCase().startsWith(args[0].toLowerCase()))
                .collect(Collectors.toList());
        }

        if (args.length == 2) {
            // Second argument
            if (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("remove")) {
                // Get available enchantments
                List<String> disabledEnchants = plugin.getConfig().getStringList("disabled-enchantments");
                
                for (Enchantment enchant : Enchantment.values()) {
                    String enchantName = enchant.getKey().getKey().toLowerCase();
                    
                    // For 'add' command, handle disabled enchantments based on permission
                    if (args[0].equalsIgnoreCase("add") && disabledEnchants.contains(enchantName)) {
                        // Only suggest if player has bypass permission
                        if (sender instanceof Player) {
                            Player player = (Player) sender;
                            if (!player.hasPermission("elr.bypass.disabled")) {
                                continue;
                            }
                        } else {
                            continue; // Console doesn't have bypass permission
                        }
                    }
                    
                    completions.add(enchantName);
                }

                return completions.stream()
                    .filter(enchant -> enchant.toLowerCase().startsWith(args[1].toLowerCase()))
                    .sorted()
                    .collect(Collectors.toList());
            }
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("add")) {
            // Third argument for 'add' command - level suggestions
            if (!(sender instanceof Player)) {
                // For console, provide basic suggestions
                return getBasicLevelSuggestions(args[2]);
            }
            
            Player player = (Player) sender;
            ItemStack item = player.getInventory().getItemInMainHand();
            
            // Get the enchantment
            Enchantment enchantment = null;
            try {
                enchantment = Enchantment.getByKey(NamespacedKey.minecraft(args[1].toLowerCase()));
            } catch (Exception e) {
                // Invalid enchantment, provide basic suggestions
                return getBasicLevelSuggestions(args[2]);
            }
            
            if (enchantment == null) {
                return getBasicLevelSuggestions(args[2]);
            }
            
            // Get max level for this player, enchantment, and item
            Material itemType = (item != null && !item.getType().isAir()) ? item.getType() : Material.DIAMOND_SWORD;
            int maxLevel = getMaxLevelForPlayer(player, enchantment, itemType);
            int vanillaMax = enchantment.getMaxLevel();
            
            plugin.debugLog("Tab completion - Max level for " + player.getName() + ": " + maxLevel);
            
            List<String> levels = new ArrayList<>();
            
            // Add vanilla max level
            levels.add(String.valueOf(vanillaMax));
            
            // Add some common levels up to max
            int[] commonLevels = {1, 2, 3, 4, 5, 10, 15, 20, 25, 30, 50, 100, 255, 500, 1000};
            
            for (int level : commonLevels) {
                if (level <= maxLevel && level != vanillaMax) {
                    levels.add(String.valueOf(level));
                }
            }
            
            // Add max level if it's not already included
            if (maxLevel != vanillaMax && !levels.contains(String.valueOf(maxLevel))) {
                levels.add(String.valueOf(maxLevel));
            }
            
            // Filter and sort
            return levels.stream()
                .distinct()
                .filter(level -> level.startsWith(args[2]))
                .sorted((a, b) -> Integer.compare(Integer.parseInt(a), Integer.parseInt(b)))
                .collect(Collectors.toList());
        }

        return completions;
    }
    
    /**
     * Gets basic level suggestions when detailed calculation isn't possible
     */
    private List<String> getBasicLevelSuggestions(String input) {
        List<String> levels = new ArrayList<>();
        int baseMax = plugin.getConfig().getInt("max-enchant-level", 10);
        int absoluteMax = plugin.getConfig().getInt("security.absolute-max-level", 1000);
        
        // Add some common levels
        int[] commonLevels = {1, 2, 3, 4, 5, baseMax, 10, 20, 50, 100};
        
        for (int level : commonLevels) {
            if (level <= absoluteMax) {
                levels.add(String.valueOf(level));
            }
        }
        
        // Add absolute max if not already included
        if (absoluteMax != baseMax && absoluteMax != 10 && absoluteMax != 20 && 
            absoluteMax != 50 && absoluteMax != 100) {
            levels.add(String.valueOf(absoluteMax));
        }
        
        return levels.stream()
            .distinct()
            .filter(level -> level.startsWith(input))
            .sorted((a, b) -> Integer.compare(Integer.parseInt(a), Integer.parseInt(b)))
            .collect(Collectors.toList());
    }
    
    /**
     * Gets the maximum enchantment level for a player considering all limits
     * (Duplicated from command class for tab completion)
     */
    private int getMaxLevelForPlayer(Player player, Enchantment enchantment, Material itemType) {
        // Start with base config level
        int maxLevel = plugin.getConfig().getInt("max-enchant-level", 10);
        
        // Apply permission-based level (if higher)
        if (plugin.getConfig().getBoolean("use-permission-levels", true)) {
            int permissionLevel = plugin.getCachedPermissionLevel(player);
            if (permissionLevel > 0) {
                maxLevel = Math.max(maxLevel, permissionLevel);
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
            }
        }
        
        // Apply absolute maximum limit (security)
        int absoluteMax = plugin.getConfig().getInt("security.absolute-max-level", 1000);
        maxLevel = Math.min(maxLevel, absoluteMax);
        
        return maxLevel;
    }
}