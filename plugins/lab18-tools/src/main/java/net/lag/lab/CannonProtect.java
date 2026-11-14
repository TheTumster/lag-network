package net.lag.lab;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.plugin.Plugin;

/**
 * Lab cannon protection: any block with a diamond block somewhere below it (same column) can't be blown up,
 * so cannons don't need watering to survive testing. Put a diamond block / layer under the cannon.
 */
final class CannonProtect implements Listener {
	static final Material PROTECTOR = Material.DIAMOND_BLOCK;
	/** column key -> lowest protector y in that column (Integer.MAX_VALUE = none); cleared every tick */
	private final Map<Long, Integer> cache = new HashMap<Long, Integer>();

	CannonProtect(Plugin plugin) {
		Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
			public void run() {
				cache.clear();
			}
		}, 1L, 1L);
	}

	private int lowestProtector(World w, int x, int z) {
		long key = ((long) x << 32) ^ (z & 0xFFFFFFFFL);
		Integer v = cache.get(key);
		if (v == null) {
			v = Integer.MAX_VALUE;
			for (int y = 0; y < w.getMaxHeight(); y++) {
				if (w.getBlockAt(x, y, z).getType() == PROTECTOR) {
					v = y;
					break;
				}
			}
			cache.put(key, v);
		}
		return v;
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onExplode(EntityExplodeEvent e) {
		for (Iterator<Block> it = e.blockList().iterator(); it.hasNext(); ) {
			Block b = it.next();
			if (b.getType() == PROTECTOR || lowestProtector(b.getWorld(), b.getX(), b.getZ()) < b.getY()) it.remove();
		}
	}
}
