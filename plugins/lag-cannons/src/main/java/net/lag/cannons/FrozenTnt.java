package net.lag.cannons;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Stops cannons blowing themselves up after their owner leaves. When a player flies away, the cannon's chunks first
 * stop ticking and later unload; any lit TNT / falling sand in mid-flight freezes there and finishes its fuse inside
 * the cannon when someone comes back. Lit TNT and falling blocks are removed as soon as their chunk stops ticking,
 * are never saved with an unloading chunk, and any that were saved before are removed when the chunk loads.
 */
final class FrozenTnt implements Listener {
	private final JavaPlugin plugin;

	FrozenTnt(JavaPlugin plugin) {
		this.plugin = plugin;
		Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, 20L, 5L);
	}

	private boolean on() {
		return plugin.getConfig().getBoolean("cannons.remove-frozen-tnt", true);
	}

	private static boolean shot(Entity e) {
		return e instanceof TNTPrimed || e instanceof FallingBlock;
	}

	/** Lit TNT / sand sitting in a chunk that isn't running entities any more. */
	private void sweep() {
		if (!on()) return;
		for (World w : Bukkit.getWorlds()) {
			for (Entity e : w.getEntitiesByClasses(TNTPrimed.class, FallingBlock.class)) {
				int cx = e.getLocation().getBlockX() >> 4, cz = e.getLocation().getBlockZ() >> 4;
				if (!w.isChunkLoaded(cx, cz)) {
					e.remove();
					continue;
				}
				Chunk c = w.getChunkAt(cx, cz);
				if (c.getLoadLevel() != Chunk.LoadLevel.ENTITY_TICKING) e.remove();
			}
		}
	}

	@EventHandler
	public void onUnload(EntitiesUnloadEvent e) {
		if (!on()) return;
		for (Entity en : e.getEntities()) if (shot(en)) en.remove();
	}

	@EventHandler
	public void onLoad(EntitiesLoadEvent e) {
		if (!on()) return;
		for (Entity en : e.getEntities()) if (shot(en)) en.remove();
	}
}
