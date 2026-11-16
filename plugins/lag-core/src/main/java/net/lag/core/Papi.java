package net.lag.core;

import java.lang.reflect.Method;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/** PlaceholderAPI via reflection (optional dependency). Legacy § colours are stripped so the result is MiniMessage-safe. */
final class Papi {
	private static Method set;
	private static boolean tried;

	private Papi() { }

	static String apply(OfflinePlayer p, String text) {
		if (!tried) {
			tried = true;
			try {
				if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI"))
					set = Class.forName("me.clip.placeholderapi.PlaceholderAPI").getMethod("setPlaceholders", OfflinePlayer.class, String.class);
			} catch (Throwable ignored) {
				set = null;
			}
		}
		if (set == null) return text;
		try {
			return String.valueOf(set.invoke(null, p, text)).replaceAll("§[0-9a-fk-orA-FK-ORxX]", "");
		} catch (Throwable t) {
			return text;
		}
	}

	static boolean present() {
		apply(null, "");
		return set != null;
	}
}
