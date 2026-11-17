package net.lag.cannons;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * TNT loads the cannon. Lit TNT and falling sand keep their chunk (and the chunks around it, where the cannon's
 * redstone and dispensers are) loaded and running, so a cannon keeps working normally when its owner flies away:
 * every shot finishes and nothing is left frozen mid-flight to go off inside the cannon later.
 *
 * Guard against permanent chunk loaders (a cannon on a clock re-lights TNT forever): a chunk is held for at most
 * cannons.tnt-chunk-loading.max-seconds after it would otherwise have stopped, then it rests for the same time.
 * Chunks are let go once no TNT/sand has been in them for release-ticks.
 */
final class TntChunkLoader {
	private static final class Hold {
		final World world;
		final int x, z;
		long since;      // first tick we held it
		long lastShot;   // last tick a shot was in/near it

		Hold(World world, int x, int z, long now) {
			this.world = world;
			this.x = x;
			this.z = z;
			this.since = now;
			this.lastShot = now;
		}
	}

	private final JavaPlugin plugin;
	private final Map<String, Hold> held = new HashMap<>();
	private final Map<String, Long> resting = new HashMap<>(); // key -> tick it may be held again
	private long tick;

	TntChunkLoader(JavaPlugin plugin) {
		this.plugin = plugin;
		Bukkit.getScheduler().runTaskTimer(plugin, this::run, 1L, 2L);
	}

	private static String key(World w, int x, int z) {
		return w.getName() + ":" + x + ":" + z;
	}

	private void run() {
		tick += 2;
		if (!plugin.getConfig().getBoolean("cannons.tnt-chunk-loading.enabled", true)) {
			releaseAll();
			return;
		}
		int radius = Math.max(0, plugin.getConfig().getInt("cannons.tnt-chunk-loading.radius", 1));
		long maxTicks = 20L * plugin.getConfig().getInt("cannons.tnt-chunk-loading.max-seconds", 30);
		long releaseTicks = plugin.getConfig().getInt("cannons.tnt-chunk-loading.release-ticks", 100);

		// 1. every chunk with a live shot (plus the cannon around it) stays loaded
		Set<String> seen = new HashSet<>();
		for (World w : Bukkit.getWorlds()) {
			for (Entity e : w.getEntitiesByClasses(TNTPrimed.class, FallingBlock.class)) {
				int cx = e.getLocation().getBlockX() >> 4, cz = e.getLocation().getBlockZ() >> 4;
				for (int dx = -radius; dx <= radius; dx++) {
					for (int dz = -radius; dz <= radius; dz++) {
						String k = key(w, cx + dx, cz + dz);
						if (!seen.add(k)) continue;
						Hold h = held.get(k);
						if (h != null) {
							h.lastShot = tick;
							continue;
						}
						Long rest = resting.get(k);
						if (rest != null && rest > tick) continue;
						if (w.addPluginChunkTicket(cx + dx, cz + dz, plugin)) {
							held.put(k, new Hold(w, cx + dx, cz + dz, tick));
						} else {
							// already held by us (or forced): track it anyway so we let go later
							held.put(k, new Hold(w, cx + dx, cz + dz, tick));
						}
					}
				}
			}
		}
		// 2. let go of quiet chunks, and of chunks held too long (a cannon on a clock isn't a free chunk loader)
		for (Iterator<Map.Entry<String, Hold>> it = held.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<String, Hold> en = it.next();
			Hold h = en.getValue();
			boolean quiet = tick - h.lastShot >= releaseTicks;
			boolean tooLong = tick - h.since >= maxTicks && !playerNear(h);
			if (quiet || tooLong) {
				h.world.removePluginChunkTicket(h.x, h.z, plugin);
				it.remove();
				if (tooLong) resting.put(en.getKey(), tick + maxTicks);
			} else if (playerNear(h)) {
				h.since = tick; // someone's here anyway: the time limit only counts while nobody is
			}
		}
		resting.values().removeIf(t -> t <= tick);
	}

	/** A player close enough that the chunk would be running without us. */
	private static boolean playerNear(Hold h) {
		int sim = h.world.getSimulationDistance();
		for (var p : h.world.getPlayers()) {
			Chunk c = p.getLocation().getChunk();
			if (Math.abs(c.getX() - h.x) <= sim && Math.abs(c.getZ() - h.z) <= sim) return true;
		}
		return false;
	}

	void releaseAll() {
		for (Hold h : held.values()) h.world.removePluginChunkTicket(h.x, h.z, plugin);
		held.clear();
	}
}
