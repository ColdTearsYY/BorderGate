package dev.bordergate;

import dev.bordergate.core.GoalLedger;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;

final class EconomyBridge {
    enum Result { SUCCESS, UNAVAILABLE, INSUFFICIENT, INVALID }

    private final BorderGatePlugin plugin;

    EconomyBridge(BorderGatePlugin plugin) { this.plugin = plugin; }

    boolean available(String resource) {
        if (resource.startsWith("item:")) return Material.matchMaterial(resource.substring(5)) != null;
        if (resource.equals("vault")) return providerEnabled("vault", "Vault");
        if (resource.equals("playerpoints")) return providerEnabled("playerpoints", "PlayerPoints");
        if (resource.startsWith("playercurrency:")) return providerEnabled("playercurrency", "PlayerCurrency");
        return false;
    }

    Result charge(Player player, String resource, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) return Result.INVALID;
        if (!available(resource)) return Result.UNAVAILABLE;
        try {
            if (resource.startsWith("item:")) return chargeItems(player, resource.substring(5), amount);
            if (resource.equals("vault")) return chargeVault(player, amount);
            if (resource.equals("playerpoints")) return chargePoints(player, amount);
            if (resource.startsWith("playercurrency:")) return chargeCurrency(player, resource.substring("playercurrency:".length()), amount);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            plugin.getLogger().warning("Payment provider failed for " + resource + ": " + ex.getClass().getSimpleName());
            return Result.UNAVAILABLE;
        }
        return Result.INVALID;
    }

    private Result chargeItems(Player player, String materialName, BigDecimal amount) {
        Material material = Material.matchMaterial(materialName);
        int count;
        try { count = amount.intValueExact(); } catch (ArithmeticException ex) { return Result.INVALID; }
        if (material == null || count <= 0) return Result.INVALID;
        int found = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.getType() == material) found += stack.getAmount();
        }
        if (found < count) return Result.INSUFFICIENT;
        int left = count;
        for (int slot = 0; slot < player.getInventory().getStorageContents().length && left > 0; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack == null || stack.getType() != material) continue;
            int take = Math.min(left, stack.getAmount());
            stack.setAmount(stack.getAmount() - take);
            if (stack.getAmount() == 0) player.getInventory().setItem(slot, null);
            left -= take;
        }
        return left == 0 ? Result.SUCCESS : Result.INSUFFICIENT;
    }

    private Result chargeVault(Player player, BigDecimal amount) throws ReflectiveOperationException {
        Object economy = vaultEconomy();
        if (economy == null) return Result.UNAVAILABLE;
        double value = amount.doubleValue();
        if (!Double.isFinite(value) || value <= 0) return Result.INVALID;
        Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
        Object balance = economyClass.getMethod("getBalance", OfflinePlayer.class).invoke(economy, player);
        if (!(balance instanceof Number number) || number.doubleValue() < value) return Result.INSUFFICIENT;
        Object response = economyClass.getMethod("withdrawPlayer", OfflinePlayer.class, double.class).invoke(economy, player, value);
        return transactionSuccess(response) ? Result.SUCCESS : Result.INSUFFICIENT;
    }

    private Result chargePoints(Player player, BigDecimal amount) throws ReflectiveOperationException {
        int value;
        try { value = amount.intValueExact(); } catch (ArithmeticException ex) { return Result.INVALID; }
        if (value <= 0) return Result.INVALID;
        Class<?> pluginClass = Class.forName("org.black_ixx.playerpoints.PlayerPoints");
        Class<?> apiClass = Class.forName("org.black_ixx.playerpoints.PlayerPointsAPI");
        Object instance = pluginClass.getMethod("getInstance").invoke(null);
        Object api = pluginClass.getMethod("getAPI").invoke(instance);
        Object balance = apiClass.getMethod("look", UUID.class).invoke(api, player.getUniqueId());
        if (!(balance instanceof Number number) || number.intValue() < value) return Result.INSUFFICIENT;
        Object result = apiClass.getMethod("take", UUID.class, int.class).invoke(api, player.getUniqueId(), value);
        return Boolean.TRUE.equals(result) ? Result.SUCCESS : Result.INSUFFICIENT;
    }

    private Result chargeCurrency(Player player, String currencyType, BigDecimal amount) throws ReflectiveOperationException {
        long value;
        try { value = amount.longValueExact(); } catch (ArithmeticException ex) { return Result.INVALID; }
        if (currencyType.isBlank() || value <= 0) return Result.INVALID;
        Class<?> api = Class.forName("cn.handyplus.currency.api.PlayerCurrencyApi");
        Object balance = api.getMethod("look", UUID.class, String.class).invoke(null, player.getUniqueId(), currencyType);
        if (!(balance instanceof Number number) || number.longValue() < value) return Result.INSUFFICIENT;
        Object result = api.getMethod("take", UUID.class, String.class, Long.class, String.class, String.class)
                .invoke(null, player.getUniqueId(), currencyType, value, "BorderGate", "Community border contribution");
        return Boolean.TRUE.equals(result) ? Result.SUCCESS : Result.INSUFFICIENT;
    }

    BigDecimal vaultTotal() {
        try {
            Object economy = vaultEconomy();
            if (economy == null) return null;
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            Method balance = economyClass.getMethod("getBalance", OfflinePlayer.class);
            BigDecimal total = BigDecimal.ZERO;
            for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
                Object value = balance.invoke(economy, player);
                if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
                    total = total.add(BigDecimal.valueOf(number.doubleValue()));
                }
            }
            return total;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            plugin.getLogger().warning("Could not total Vault balances: " + ex.getClass().getSimpleName());
            return null;
        }
    }

    private Object vaultEconomy() throws ReflectiveOperationException {
        if (!providerEnabled("vault", "Vault")) return null;
        Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
        RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration(economyClass);
        return registration == null ? null : registration.getProvider();
    }

    private boolean providerEnabled(String setting, String pluginName) {
        return plugin.getConfig().getBoolean("integrations." + setting, true)
                && Bukkit.getPluginManager().isPluginEnabled(pluginName);
    }

    private boolean transactionSuccess(Object response) throws ReflectiveOperationException {
        return response != null && Boolean.TRUE.equals(response.getClass().getMethod("transactionSuccess").invoke(response));
    }
}
