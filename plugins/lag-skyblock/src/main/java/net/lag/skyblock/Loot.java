package net.lag.skyblock;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Spawner mob loot. Every mob from a cage drops its tier's fixed loot (config tiers.loot) instead of vanilla drops,
 * however it died, so every tier works in an AFK grinder. Each item carries an invisible spawner-loot tag: the
 * Collector pays the cage price only for tagged items (golem iron at golem prices, gen iron at ore prices).
 * Heads (proof for the next unlock) drop at head-chance % per mob. A whole RoseStacker stack killed at once gets
 * loot and head rolls for every mob in it.
 */
final class Loot implements Listener {
	static NamespacedKey TAG;
	private final LagSkyblock plugin;
	private final NamespacedKey rsSpawner = new NamespacedKey("rosestacker", "spawner_spawned");
	/** Tagged loot templates and the head, per mob (built on the main thread; cloned anywhere). */
	private final Map<String, List<ItemStack>> templates = new HashMap<>();
	private final Map<String, ItemStack> heads = new HashMap<>();
	private Method getEntityDrops, getDrops;

	Loot(LagSkyblock plugin) {
		this.plugin = plugin;
		TAG = new NamespacedKey(plugin, "loot");
		load();
		Bukkit.getPluginManager().registerEvents(this, plugin);
		hookStackDeaths();
	}

	void load() {
		templates.clear();
		heads.clear();
		for (LagSkyblock.Tier t : plugin.tiers) {
			List<ItemStack> list = new ArrayList<>();
			for (var e : t.loot().entrySet()) list.add(tagged(e.getKey(), e.getValue()));
			templates.put(t.mob(), list);
			ItemStack head = plugin.spawners == null ? null : plugin.spawners.head(t);
			if (head != null) heads.put(t.mob(), head);
		}
	}

	static ItemStack tagged(Material m, int amount) {
		ItemStack it = new ItemStack(m, Math.max(1, amount));
		ItemMeta im = it.getItemMeta();
		if (im != null) {
			im.getPersistentDataContainer().set(TAG, PersistentDataType.BYTE, (byte) 1);
			it.setItemMeta(im);
		}
		return it;
	}

	static boolean isLoot(ItemStack it) {
		return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(TAG, PersistentDataType.BYTE);
	}

	boolean fromSpawner(LivingEntity e) {
		if (e.getPersistentDataContainer().getKeys().contains(rsSpawner)) return true;
		try {
			return e.getEntitySpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER;
		} catch (Throwable t) {
			return false;
		}
	}

	private double headChance() {
		return plugin.getConfig().getDouble("head-chance", 1.0) / 100.0;
	}

	/** Loot for `kills` mobs of this kind, as stacks (thread-safe: only clones). */
	private List<ItemStack> lootFor(String mob, int kills) {
		List<ItemStack> out = new ArrayList<>();
		List<ItemStack> tpl = templates.get(mob);
		if (tpl == null || kills <= 0) return out;
		for (ItemStack t : tpl) {
			long total = (long) t.getAmount() * kills;
			while (total > 0) {
				ItemStack it = t.clone();
				int n = (int) Math.min(total, it.getMaxStackSize());
				it.setAmount(n);
				out.add(it);
				total -= n;
			}
		}
		ItemStack head = heads.get(mob);
		if (head != null) {
			double p = headChance();
			int n = 0;
			var rnd = ThreadLocalRandom.current();
			if (kills > 2000) n = (int) Math.max(0, Math.round(kills * p + rnd.nextGaussian() * Math.sqrt(kills * p * (1 - p))));
			else for (int i = 0; i < kills; i++) if (rnd.nextDouble() < p) n++;
			while (n > 0) {
				ItemStack h = head.clone();
				h.setAmount(Math.min(n, h.getMaxStackSize()));
				n -= h.getAmount();
				out.add(h);
			}
		}
		return out;
	}

	/** The grinder killed a whole stack itself: drop the tier's loot for every mob in it. False if it isn't a tier mob. */
	boolean dropStack(LivingEntity e, int kills) {
		String mob = e.getType().name().toLowerCase();
		if (!templates.containsKey(mob)) return false;
		org.bukkit.Location at = e.getLocation();
		for (ItemStack it : lootFor(mob, Math.max(1, kills))) {
			at.getWorld().dropItem(at, it, d -> d.setVelocity(new org.bukkit.util.Vector(0, 0, 0)));
		}
		return true;
	}

	/** One mob died: its drops become its tier's loot (+ a head roll). Runs before RoseStacker reads the drops. */
	@EventHandler(priority = EventPriority.LOW)
	public void onDeath(EntityDeathEvent e) {
		LivingEntity le = e.getEntity();
		if (le instanceof org.bukkit.entity.Player || !fromSpawner(le)) return;
		String mob = le.getType().name().toLowerCase();
		if (!templates.containsKey(mob)) return;
		e.getDrops().clear();
		e.getDrops().addAll(lootFor(mob, 1));
	}

	/** RoseStacker's whole-stack death: replace every mob's drops with ours, once per mob killed. By reflection. */
	private void hookStackDeaths() {
		Plugin rs = Bukkit.getPluginManager().getPlugin("RoseStacker");
		if (rs == null) {
			plugin.getLogger().warning("RoseStacker isn't installed: stacked kills won't get spawner loot");
			return;
		}
		try {
			@SuppressWarnings("unchecked")
			Class<? extends Event> type = (Class<? extends Event>) Class.forName("dev.rosewood.rosestacker.event.EntityStackMultipleDeathEvent", true, rs.getClass().getClassLoader());
			Method killCount = type.getMethod("getEntityKillCount");
			Method mainEntity = type.getMethod("getMainEntity");
			getEntityDrops = type.getMethod("getEntityDrops");
			Bukkit.getPluginManager().registerEvent(type, this, EventPriority.HIGH, (l, ev) -> {
				if (!type.isInstance(ev)) return;
				try {
					LivingEntity main = (LivingEntity) mainEntity.invoke(ev);
					int kills = ((Number) killCount.invoke(ev)).intValue();
					if (main == null || kills <= 0 || !fromSpawner(main)) return;
					String mob = main.getType().name().toLowerCase();
					if (!templates.containsKey(mob)) return;
					// Multimap<LivingEntity, EntityDrops>: clear every list, then put the whole stack's loot in the first
					Object multimap = getEntityDrops.invoke(ev);
					Collection<?> values = multimap instanceof com.google.common.collect.Multimap<?, ?> mm ? mm.values()
							: (Collection<?>) Class.forName("com.google.common.collect.Multimap", true, multimap.getClass().getClassLoader()).getMethod("values").invoke(multimap);
					List<ItemStack> first = null;
					for (Object drops : values) {
						if (getDrops == null) getDrops = drops.getClass().getMethod("getDrops");
						@SuppressWarnings("unchecked")
						List<ItemStack> list = (List<ItemStack>) getDrops.invoke(drops);
						list.clear();
						if (first == null) first = list;
					}
					List<ItemStack> all = lootFor(mob, kills);
					if (first != null) first.addAll(all);
					else Bukkit.getScheduler().runTask(plugin, () -> {
						for (ItemStack it : all) main.getWorld().dropItemNaturally(main.getLocation(), it);
					});
				} catch (Throwable ignored) { }
			}, plugin, true);
		} catch (Throwable t) {
			plugin.getLogger().warning("Couldn't hook RoseStacker stack deaths: " + t);
		}
	}
}
