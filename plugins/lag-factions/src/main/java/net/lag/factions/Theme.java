package net.lag.factions;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Recolours every FactionsUUID message to the RELAPSEDMC palette (runs in onLoad, before FactionsUUID reads its
 * files): yellow/gold text becomes gray/cyan, green/red become the brand green/red. Both message files are covered:
 * config/lang.yml (legacy & codes) and config/translations.conf (MiniMessage). Safe to run every start.
 */
final class Theme {
	private Theme() { }

	private static final Map<String, String> LEGACY = new LinkedHashMap<>();
	private static final Map<String, String> MINI = new LinkedHashMap<>();
	static {
		LEGACY.put("&e", "&7");   // yellow body text -> gray
		LEGACY.put("&6", "&b");   // gold highlights -> aqua (closest legacy to brand cyan)
		LEGACY.put("&2", "&d");   // dark green headers -> light purple
		MINI.put("<yellow>", "<gray>");
		MINI.put("</yellow>", "</gray>");
		MINI.put("<gold>", "<#38E0FF>");
		MINI.put("</gold>", "</#38E0FF>");
		MINI.put("<dark_green>", "<#B040FF>");
		MINI.put("</dark_green>", "</#B040FF>");
		MINI.put("<green>", "<#7CFF6B>");
		MINI.put("</green>", "</#7CFF6B>");
		MINI.put("<red>", "<#FF4D6D>");
		MINI.put("</red>", "</#FF4D6D>");
		MINI.put("<light_purple>", "<#B040FF>");
		MINI.put("</light_purple>", "</#B040FF>");
	}

	static void apply(File pluginsDir, Logger log) {
		File base = new File(pluginsDir, "FactionsUUID/config");
		int n = remap(new File(base, "lang.yml"), LEGACY, log) + remap(new File(base, "translations.conf"), MINI, log);
		if (n > 0) log.info("Recoloured " + n + " FactionsUUID message colours to the RELAPSEDMC palette");
	}

	private static int remap(File f, Map<String, String> map, Logger log) {
		if (!f.isFile()) return 0;
		try {
			String t = Files.readString(f.toPath(), StandardCharsets.UTF_8);
			String o = t;
			int n = 0;
			for (var e : map.entrySet()) {
				int before = o.length();
				String next = o.replace(e.getKey(), e.getValue());
				if (!next.equals(o)) n += Math.max(1, Math.abs(next.length() - before) + 1);
				o = next;
				// legacy codes can also be written with §
				if (e.getKey().startsWith("&")) o = o.replace("§" + e.getKey().substring(1), "§" + e.getValue().substring(1));
			}
			if (!o.equals(t)) Files.writeString(f.toPath(), o, StandardCharsets.UTF_8);
			return o.equals(t) ? 0 : n;
		} catch (Exception ex) {
			log.warning("Couldn't recolour " + f.getName() + ": " + ex.getMessage());
			return 0;
		}
	}
}
