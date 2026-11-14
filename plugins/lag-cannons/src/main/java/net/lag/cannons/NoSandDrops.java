package net.lag.cannons;

import org.bukkit.entity.FallingBlock;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Falling sand/gravel that breaks (lands on a slab, piston head, carpet...) doesn't drop an item. */
final class NoSandDrops implements Listener {
	private final JavaPlugin plugin;

	NoSandDrops(JavaPlugin plugin) {
		this.plugin = plugin;
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onDrop(EntityDropItemEvent e) {
		if (e.getEntity() instanceof FallingBlock && !plugin.getConfig().getBoolean("falling-blocks.drop-items", false)) e.setCancelled(true);
	}
}
