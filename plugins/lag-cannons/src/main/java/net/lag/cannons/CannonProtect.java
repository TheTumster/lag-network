package net.lag.cannons;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Cannon-testing protection: in the worlds listed under cannon-protect.worlds, any block with a diamond block
 * somewhere below it (same column) can't be blown up. Off everywhere by default: on a real factions world this
 * would let people make unraidable walls.
 */
final class CannonProtect implements Listener {
	private final JavaPlugin plugin;
	private final Map<Long, Integer> cache = new HashMap<>();

	CannonProtect(JavaPlugin plugin) {
		this.plugin = plugin;
		Bukkit.getScheduler().runTaskTimer(plugin, cache::clear, 1L, 1L);
	}

	private boolean active(World w) {
		List<String> worlds = plugin.getConfig().getStringList("cannon-protect.worlds");
		return worlds.contains(w.getName()) || worlds.contains("*");
	}

	private int lowestProtector(World w, int x, int z) {
		long key = ((long) x << 32) ^ (z & 0xFFFFFFFFL);
		return cache.computeIfAbsent(key, k -> {
			for (int y = w.getMinHeight(); y < w.getMaxHeight(); y++)
				if (w.getBlockAt(x, y, z).getType() == Material.DIAMOND_BLOCK) return y;
			return Integer.MAX_VALUE;
		});
	}

	private void filter(World w, List<Block> blocks) {
		if (!active(w)) return;
		blocks.removeIf(b -> b.getType() == Material.DIAMOND_BLOCK || lowestProtector(w, b.getX(), b.getZ()) < b.getY());
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onEntity(EntityExplodeEvent e) {
		filter(e.getLocation().getWorld(), e.blockList());
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onBlock(BlockExplodeEvent e) {
		filter(e.getBlock().getWorld(), e.blockList());
	}
}
