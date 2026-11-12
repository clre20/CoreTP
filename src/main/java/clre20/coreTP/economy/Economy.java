package clre20.coreTP.economy;

import org.bukkit.OfflinePlayer;

/** 經濟系統的最小介面；沒有 Vault 時不會載入任何 Vault 類別。 */
public interface Economy {

    boolean has(OfflinePlayer player, double amount);

    double balance(OfflinePlayer player);

    boolean withdraw(OfflinePlayer player, double amount);

    void deposit(OfflinePlayer player, double amount);

    String format(double amount);
}
