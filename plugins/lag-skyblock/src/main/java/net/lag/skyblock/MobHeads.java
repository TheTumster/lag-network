package net.lag.skyblock;

import java.lang.reflect.Method;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Tier heads drop from spawner mobs: head-chance % per mob killed, however it died. A grinder that kills a whole
 * RoseStacker stack at once rolls once for every mob in the stack, and the heads drop where the stack died (into
 * the player's hoppers). Heads are what unlock the next spawner tier in /spawners.
 */
final class MobHeads implements Listener {
	private final LagSkyblock plugin;
	private final NamespacedKey rsSpawner = new NamespacedKey("rosestacker", "spawner_spawned");
	private Method killCount, mainEntity;

	MobHeads(LagSkyblock plugin) {
		this.plugin = plugin;
		Bukkit.getPluginManager().registerEvents(this, plugin);
		hookStackDeaths();
	}

	private double chance() {
		return plugin.getConfig().getDouble("head-chance", 4.0) / 100.0;
	}

	private boolean fromSpawner(LivingEntity e) {
		if (e.getPersistentDataContainer().has(rsSpawner, PersistentDataType.INTEGER)) return true;
		try {
			return e.getEntitySpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER;
		} catch (Throwable t) {
			return false;
		}
	}

	private LagSkyblock.Tier tier(LivingEntity e) {
		String mob = e.getType().name().toLowerCase();
		for (LagSkyblock.Tier t : plugin.tiers) if (t.mob().equals(mob)) return t;
		return null;
	}

	/** Rolls `kills` times and drops the heads at `at` (main thread). */
	private void drop(LagSkyblock.Tier t, Location at, int kills) {
		if (kills <= 0 || at.getWorld() == null) return;
		double p = chance();
		int heads = 0;
		var rnd = ThreadLocalRandom.current();
		if (kills > 2000) heads = (int) Math.round(kills * p + rnd.nextGaussian() * Math.sqrt(kills * p * (1 - p)));
		else for (int i = 0; i < kills; i++) if (rnd.nextDouble() < p) heads++;
		if (heads <= 0) return;
		ItemStack head = plugin.spawners.head(t);
		if (head == null) return;
		while (heads > 0) {
			ItemStack it = head.clone();
			it.setAmount(Math.min(heads, it.getMaxStackSize()));
			heads -= it.getAmount();
			at.getWorld().dropItemNaturally(at, it);
		}
	}

	/** One mob died (a player kill, or the top mob of a stack that a grinder wiped). */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onDeath(EntityDeathEvent e) {
		LivingEntity le = e.getEntity();
		if (!fromSpawner(le)) return;
		LagSkyblock.Tier t = tier(le);
		if (t != null) drop(t, le.getLocation(), 1);
	}

	/** RoseStacker's "whole stack died" event: the rest of the stack (minus the mob above). Registered by reflection. */
	private void hookStackDeaths() {
		Plugin rs = Bukkit.getPluginManager().getPlugin("RoseStacker");
		if (rs == null) {
			plugin.getLogger().warning("RoseStacker isn't installed: stacked kills won't drop extra heads");
			return;
		}
		try {
			@SuppressWarnings("unchecked")
			Class<? extends Event> type = (Class<? extends Event>) Class.forName("dev.rosewood.rosestacker.event.EntityStackMultipleDeathEvent", true, rs.getClass().getClassLoader());
			killCount = type.getMethod("getEntityKillCount");
			mainEntity = type.getMethod("getMainEntity");
			Bukkit.getPluginManager().registerEvent(type, this, EventPriority.MONITOR, (l, ev) -> {
				if (!type.isInstance(ev)) return;
				try {
					LivingEntity main = (LivingEntity) mainEntity.invoke(ev);
					int extra = ((Number) killCount.invoke(ev)).intValue() - 1;
					if (main == null || extra <= 0) return;
					LagSkyblock.Tier t = tier(main);
					if (t == null || !fromSpawner(main)) return;
					Location at = main.getLocation();
					// RoseStacker may work out stack loot off the main thread
					if (Bukkit.isPrimaryThread()) drop(t, at, extra);
					else Bukkit.getScheduler().runTask(plugin, () -> drop(t, at, extra));
				} catch (Throwable ignored) { }
			}, plugin, true);
		} catch (Throwable t) {
			plugin.getLogger().warning("Couldn't hook RoseStacker stack deaths: " + t);
		}
	}
}
