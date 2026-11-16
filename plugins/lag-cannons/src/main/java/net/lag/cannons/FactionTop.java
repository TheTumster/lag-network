package net.lag.cannons;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Live "Top Factions" text for the spawn podium hologram ({ftop} in laGCore). Until the F-top value plugin lands,
 * factions rank by land claimed, then members. Recomputed every 10s; laGCore reads it through a Supplier<String>
 * service so neither plugin depends on the other.
 */
final class FactionTop implements Supplier<String> {
	private static final String[] PLACE = {"<#FFD24D>#1", "<#D8D8D8>#2", "<#E08A5A>#3", "<gray>#4", "<gray>#5"};
	private volatile String text = "<dark_gray>No factions yet <dark_gray>• <white>/f create";

	FactionTop(JavaPlugin plugin) {
		Bukkit.getServicesManager().register(Supplier.class, this, plugin, ServicePriority.Normal);
		Bukkit.getScheduler().runTaskTimer(plugin, this::recompute, 40L, 200L);
	}

	private void recompute() {
		List<Object> all = new ArrayList<>(Fuuid.all());
		if (all.isEmpty()) {
			text = "<dark_gray>No factions yet <dark_gray>• <white>/f create";
			return;
		}
		all.sort(Comparator.comparingInt((Object f) -> Fuuid.claims(f)).thenComparingInt(Fuuid::size).reversed());
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < Math.min(5, all.size()); i++) {
			Object f = all.get(i);
			if (i > 0) sb.append('\n');
			sb.append(PLACE[i]).append(" <white>").append(MiniMessage.miniMessage().escapeTags(Fuuid.tag(f)))
					.append(" <dark_gray>• <gray>").append(Fuuid.claims(f)).append(" land <dark_gray>• <gray>")
					.append(Fuuid.online(f)).append("<dark_gray>/</dark_gray>").append(Fuuid.size(f));
		}
		sb.append("\n<dark_gray>by land claimed");
		text = sb.toString();
	}

	@Override
	public String get() {
		return text;
	}
}
