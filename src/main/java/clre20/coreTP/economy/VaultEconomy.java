package clre20.coreTP.economy;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.Nullable;

/** Vault 的實作。只有在確定 Vault 已安裝時才會被載入。 */
public final class VaultEconomy implements Economy {

    private final net.milkbowl.vault.economy.Economy vault;

    private VaultEconomy(net.milkbowl.vault.economy.Economy vault) {
        this.vault = vault;
    }

    public static @Nullable Economy find() {
        RegisteredServiceProvider<net.milkbowl.vault.economy.Economy> rsp =
                Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
        return rsp == null ? null : new VaultEconomy(rsp.getProvider());
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return vault.has(player, amount);
    }

    @Override
    public double balance(OfflinePlayer player) {
        return vault.getBalance(player);
    }

    @Override
    public boolean withdraw(OfflinePlayer player, double amount) {
        return vault.withdrawPlayer(player, amount).transactionSuccess();
    }

    @Override
    public void deposit(OfflinePlayer player, double amount) {
        vault.depositPlayer(player, amount);
    }

    @Override
    public String format(double amount) {
        return vault.format(amount);
    }
}
