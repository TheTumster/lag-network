package net.lag.cannons;

import java.util.EnumSet;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.FallingBlock;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Falling sand/gravel that breaks (lands on a slab, piston head, carpet...) doesn't drop an item.
 * Seaweed doesn't break sand: sand that lands in kelp/seagrass settles there and stacks (only slabs break sand).
 */
final class NoSandDrops implements Listener {
	private static final Set<Material> SEAWEED = EnumSet.of(Material.KELP, Material.KELP_PLANT, Material.SEAGRASS, Material.TALL_SEAGRASS);
	private final JavaPlugin plugin;

	NoSandDrops(JavaPlugin plugin) {
		this.plugin = plugin;
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void onDrop(EntityDropItemEvent e) {
		if (!(e.getEntity() instanceof FallingBlock fb)) return;
		if (plugin.getConfig().getBoolean("falling-blocks.stack-on-seaweed", true)) {
			Block b = fb.getLocation().getBlock();
			if (SEAWEED.contains(b.getType())) {
				// tall seagrass is two blocks: clear the other half too
				if (b.getType() == Material.TALL_SEAGRASS) {
					for (Block o : new Block[] {b.getRelative(0, 1, 0), b.getRelative(0, -1, 0)}) {
						if (o.getType() == Material.TALL_SEAGRASS) o.setType(Material.WATER, false);
					}
				}
				b.setBlockData(fb.getBlockData(), false);
				e.setCancelled(true);
				return;
			}
		}
		if (!plugin.getConfig().getBoolean("falling-blocks.drop-items", false)) e.setCancelled(true);
	}
}
