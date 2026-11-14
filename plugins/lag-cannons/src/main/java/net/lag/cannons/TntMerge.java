package net.lag.cannons;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.Collections;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

/**
 * 1.8-cannon-server style TNT stacking. TNT that sits at exactly the same position, with the same velocity and
 * fuse, behaves identically, so we keep ONE entity and remember how many it stands for. When it explodes we fire
 * that many explosions at the same spot. Same result, a fraction of the entities (huge for 1000+ dispenser cannons).
 */
final class TntMerge implements Listener {
	private final JavaPlugin plugin;
	private final NamespacedKey countKey;
	private final Set<TNTPrimed> live = Collections.newSetFromMap(new WeakHashMap<>());
	private boolean exploding = false;

	TntMerge(JavaPlugin plugin) {
		this.plugin = plugin;
		this.countKey = new NamespacedKey(plugin, "stack");
		Bukkit.getScheduler().runTaskTimer(plugin, this::merge, 1L, 1L);
	}

	private boolean enabled() {
		return plugin.getConfig().getBoolean("tnt.merge", true);
	}

	int count(TNTPrimed t) {
		Integer c = t.getPersistentDataContainer().get(countKey, PersistentDataType.INTEGER);
		return c == null ? 1 : c;
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onSpawn(EntitySpawnEvent e) {
		if (e.getEntity() instanceof TNTPrimed t) live.add(t);
	}

	private record Key(String world, long x, long y, long z, long vx, long vy, long vz, int fuse) { }

	private static long q(double d) {
		return Math.round(d * 1_000_000d); // micro-block precision: only truly identical TNT merges
	}

	private void merge() {
		if (!enabled() || live.size() < 2) return;
		Map<Key, TNTPrimed> seen = new HashMap<>();
		List<TNTPrimed> remove = new ArrayList<>();
		for (Iterator<TNTPrimed> it = live.iterator(); it.hasNext(); ) {
			TNTPrimed t = it.next();
			if (!t.isValid()) {
				it.remove();
				continue;
			}
			Location l = t.getLocation();
			Vector v = t.getVelocity();
			Key k = new Key(l.getWorld().getName(), q(l.getX()), q(l.getY()), q(l.getZ()), q(v.getX()), q(v.getY()), q(v.getZ()), t.getFuseTicks());
			TNTPrimed keep = seen.putIfAbsent(k, t);
			if (keep != null) {
				keep.getPersistentDataContainer().set(countKey, PersistentDataType.INTEGER, count(keep) + count(t));
				remove.add(t);
			}
		}
		for (TNTPrimed t : remove) {
			live.remove(t);
			t.remove();
		}
	}

	/** A merged TNT fires the rest of its stack as extra explosions at the same spot. */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onExplode(EntityExplodeEvent e) {
		if (exploding || !(e.getEntity() instanceof TNTPrimed t)) return;
		int extra = count(t) - 1;
		if (extra <= 0) return;
		t.getPersistentDataContainer().set(countKey, PersistentDataType.INTEGER, 1);
		Location at = e.getLocation();
		float power = t.getYield();
		exploding = true;
		try {
			for (int i = 0; i < extra; i++) at.getWorld().createExplosion(t, at, power, false, true);
		} finally {
			exploding = false;
		}
	}
}
