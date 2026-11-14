package net.lag.cannons;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Stops flowing water/lava from washing away redstone parts (configurable list, supports *_BUTTON style wildcards). */
final class WaterProof implements Listener {
	private final JavaPlugin plugin;

	WaterProof(JavaPlugin plugin) {
		this.plugin = plugin;
	}

	private boolean protectedBlock(Material m) {
		if (!plugin.getConfig().getBoolean("water-proof.enabled", true)) return false;
		String name = m.name();
		List<String> list = plugin.getConfig().getStringList("water-proof.blocks");
		for (String pat : new ArrayList<>(list)) {
			String p = pat.toUpperCase(Locale.ROOT);
			if (p.startsWith("*") ? name.endsWith(p.substring(1)) : p.endsWith("*") ? name.startsWith(p.substring(0, p.length() - 1)) : name.equals(p)) {
				return true;
			}
		}
		return false;
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onFlow(BlockFromToEvent e) {
		Material from = e.getBlock().getType();
		if (from != Material.WATER && from != Material.LAVA) return;
		if (protectedBlock(e.getToBlock().getType())) e.setCancelled(true);
	}
}
