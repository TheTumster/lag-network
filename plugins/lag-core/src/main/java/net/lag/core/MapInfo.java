package net.lag.core;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Hologram/scoreboard placeholders for the current map (config "map:"):
 *   {map}    Relapse #1
 *   {reset}  "Launches in 3d 4h" / "Resets in 12d 4h" / the status text when no dates are set
 *   {ftop}   live top-factions list, provided by laGCannons (a Supplier<String> service), "" if absent
 */
final class MapInfo {
	private MapInfo() { }

	static String fill(LagCore plugin, String t) {
		if (t.contains("{map}")) t = t.replace("{map}", plugin.getConfig().getString("map.name", "Relapse #1"));
		if (t.contains("{reset}")) t = t.replace("{reset}", countdown(plugin));
		if (t.contains("{ftop}")) t = t.replace("{ftop}", ftop());
		return t;
	}

	static String countdown(LagCore plugin) {
		var c = plugin.getConfig();
		ZoneId zone;
		try {
			zone = ZoneId.of(c.getString("map.timezone", "America/New_York"));
		} catch (Exception e) {
			zone = ZoneId.of("America/New_York");
		}
		ZonedDateTime now = ZonedDateTime.now(zone);
		ZonedDateTime start = parse(c.getString("map.starts", ""), zone), end = parse(c.getString("map.ends", ""), zone);
		if (start != null && now.isBefore(start)) return "<gray>Launches in <white>" + left(now, start);
		if (end != null && now.isBefore(end)) return "<gray>Resets in <white>" + left(now, end);
		if (end != null) return "<#FF4D6D>Map over <dark_gray>• <gray>reset incoming";
		return c.getString("map.status", "<gray>Launching <white>December");
	}

	private static ZonedDateTime parse(String s, ZoneId zone) {
		if (s == null || s.isBlank()) return null;
		try {
			return LocalDateTime.parse(s.trim().replace(' ', 'T')).atZone(zone); // 2026-12-12T18:00
		} catch (Exception e) {
			return null;
		}
	}

	private static String left(ZonedDateTime from, ZonedDateTime to) {
		Duration d = Duration.between(from, to);
		long days = d.toDays(), hours = d.toHours() % 24, mins = d.toMinutes() % 60;
		if (days > 0) return days + "d " + hours + "h";
		if (hours > 0) return hours + "h " + mins + "m";
		return Math.max(1, mins) + "m";
	}

	/** laGCannons registers a Supplier<String> service with the current top-factions text. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	static String ftop() {
		for (RegisteredServiceProvider<Supplier> r : Bukkit.getServicesManager().getRegistrations(Supplier.class)) {
			if (r.getPlugin().getName().equals("laGCannons")) {
				Object v = r.getProvider().get();
				return v == null ? "" : String.valueOf(v);
			}
		}
		return "<dark_gray>No factions yet";
	}

	/** Shield status line from laGFactions (a Function<Player,String> service), "" if absent. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	static String shield(org.bukkit.entity.Player p) {
		for (RegisteredServiceProvider<java.util.function.Function> r : Bukkit.getServicesManager().getRegistrations(java.util.function.Function.class)) {
			if (r.getPlugin().getName().equals("laGFactions")) {
				Object v = r.getProvider().apply(p);
				return v == null ? "" : String.valueOf(v);
			}
		}
		return "";
	}
}
