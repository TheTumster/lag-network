package net.lag.core;

import java.lang.reflect.Method;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/** Vault balance without a compile dependency (laGCore also runs where there's no economy, like the hub). */
final class Money {
	private static Object eco;
	private static Method getBalance;
	private static long lastTry;

	private Money() { }

	/** The player's balance, or null when this server has no economy. */
	static Double balance(Player p) {
		try {
			if (eco == null) {
				if (System.currentTimeMillis() - lastTry < 30_000) return null;
				lastTry = System.currentTimeMillis();
				Class<?> c = Class.forName("net.milkbowl.vault.economy.Economy");
				var reg = Bukkit.getServicesManager().getRegistration(c);
				if (reg == null) return null;
				eco = reg.getProvider();
				getBalance = c.getMethod("getBalance", OfflinePlayer.class);
			}
			return ((Number) getBalance.invoke(eco, p)).doubleValue();
		} catch (Throwable t) {
			eco = null;
			return null;
		}
	}

	/** 1,234 / 12.3K / 4.56M */
	static String fmt(double v) {
		double a = Math.abs(v);
		if (a >= 1_000_000_000) return String.format(java.util.Locale.ROOT, "%.2fB", v / 1e9);
		if (a >= 1_000_000) return String.format(java.util.Locale.ROOT, "%.2fM", v / 1e6);
		if (a >= 100_000) return String.format(java.util.Locale.ROOT, "%.1fK", v / 1e3);
		return String.format(java.util.Locale.ROOT, "%,d", (long) Math.floor(v));
	}
}
