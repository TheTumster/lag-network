package net.lag.skyblock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.SlimeSplitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * One grinder design for every cage, Chicken to Iron Golem.
 * <ul>
 * <li>Cage mobs don't think (RoseStacker disable-mob-ai): no walking, climbing, teleporting or splitting.</li>
 * <li>A drop of grinder.fall-height blocks (6) or touching lava, fire or magma kills any cage mob, whole stack,
 * whatever vanilla says about chickens, golems and blazes.</li>
 * <li>Cage loot and heads don't burn, so a lava grinder needs no tricks to save the drops.</li>
 * </ul>
 * Also builds the example grinder at the Cage Sanctum (spawn): a glass drop shaft with labels.
 */
final class Grinder implements Listener {
	private final LagSkyblock plugin;
	private final NamespacedKey demoTag, headKey;
	private Map<UUID, Double> peaks = new HashMap<>();
	private final List<Entity> demo = new ArrayList<>();

	Grinder(LagSkyblock plugin) {
		this.plugin = plugin;
		this.demoTag = new NamespacedKey(plugin, "grinder_demo");
		this.headKey = NamespacedKey.fromString(LagSkyblock.HEAD_KEY);
		Bukkit.getScheduler().runTaskTimer(plugin, this::scan, 100L, 10L);
		Bukkit.getScheduler().runTaskTimer(plugin, this::demo, 120L, 200L);
	}

	// ---------------------------------------------------------------- the kill rule

	private static boolean hot(Material m) {
		return m == Material.LAVA || m == Material.FIRE || m == Material.SOUL_FIRE;
	}

	private void scan() {
		if (plugin.loot == null || !plugin.getConfig().getBoolean("grinder.enabled", true)) return;
		double need = need();
		Map<UUID, Double> next = new HashMap<>();
		for (World w : Bukkit.getWorlds()) {
			for (LivingEntity e : w.getLivingEntities()) {
				if (e instanceof Player || e.isDead() || !plugin.loot.fromSpawner(e)) continue;
				try {
					Double p = step(e, need);
					if (p != null) next.put(e.getUniqueId(), p);
				} catch (Throwable t) {
					if (!warned) {
						warned = true;
						plugin.getLogger().warning("Grinder check failed on a " + e.getType() + ": " + t);
					}
				}
			}
		}
		peaks = next;
	}

	private boolean warned;

	/** Mobs spawn up to a block below the cage, so a "6 block" drop is counted from 4.5. */
	private double need() {
		return plugin.getConfig().getDouble("grinder.fall-height", 4.5);
	}

	/** One cage mob, one check: returns its highest point since it last stood on ground, or null if it was killed. */
	private Double step(LivingEntity e, double need) {
		// "no AI" must not mean "no gravity": keep physics on, keep the brain off
		if (e instanceof org.bukkit.entity.Mob m) {
			if (!m.hasAI()) m.setAI(true);
			if (m.isAware()) m.setAware(false);
		}
		{
			{
				Location l = e.getLocation();
				Material feet = l.getBlock().getType();
				Material under = l.clone().subtract(0, 0.3, 0).getBlock().getType();
				if (hot(feet) || hot(e.getEyeLocation().getBlock().getType()) || under == Material.MAGMA_BLOCK) {
					kill(e);
					return null;
				}
				double y = l.getY();
				double peak = Math.max(peaks.getOrDefault(e.getUniqueId(), y), y);
				if (e.isOnGround()) {
					if (peak - y >= need) {
						kill(e);
						return null;
					}
					peak = y;
				}
				return peak;
			}
		}
	}

	/** /lsb grinder: what the grinder rules see around this player (to debug a grinder that isn't killing). */
	void debug(Player p) {
		int all = 0, near = 0;
		p.sendMessage(Spawners.mm("<#38E0FF><bold>GRINDER CHECK</bold> <gray>drop needed: <white>" + need() + " <gray>enabled: <white>" + plugin.getConfig().getBoolean("grinder.enabled", true)));
		for (LivingEntity e : p.getWorld().getLivingEntities()) {
			if (e instanceof Player) continue;
			boolean cage = plugin.loot != null && plugin.loot.fromSpawner(e);
			if (cage) all++;
			if (e.getLocation().distanceSquared(p.getLocation()) > 20 * 20 || near >= 8) continue;
			near++;
			Double peak = peaks.get(e.getUniqueId());
			p.sendMessage(Spawners.mm("<gray>" + e.getType().name().toLowerCase() + " <white>cage=" + cage + " ground=" + e.isOnGround()
					+ " y=" + String.format("%.1f", e.getLocation().getY()) + " peak=" + (peak == null ? "-" : String.format("%.1f", peak))
					+ " stack=" + stackSize(e) + " ai=" + (e instanceof org.bukkit.entity.Mob m ? m.hasAI() + "/" + m.isAware() : "-") + " reason=" + e.getEntitySpawnReason()));
		}
		p.sendMessage(Spawners.mm("<gray>Cage mobs in this world: <white>" + all + " <gray>· shown: <white>" + near + " <gray>within 20 blocks"));
	}

	/** Dies of "cramming": nothing is immune to it, and RoseStacker takes the whole stack with it (our config). */
	private void kill(LivingEntity e) {
		// the whole stack goes at once and we drop the loot ourselves: no dependence on how RoseStacker reads the death
		try {
			int size = stackSize(e);
			if (plugin.loot.dropStack(e, size)) {
				Location l = e.getLocation().add(0, 0.4, 0);
				l.getWorld().spawnParticle(org.bukkit.Particle.POOF, l, 8, 0.25, 0.25, 0.25, 0.02);
				l.getWorld().playSound(l, org.bukkit.Sound.ENTITY_CHICKEN_EGG, 0.25f, 0.6f);
				e.remove();
				return;
			}
		} catch (Throwable t) {
			if (!warned) {
				warned = true;
				plugin.getLogger().warning("Whole-stack kill failed on a " + e.getType() + ": " + t);
			}
		}
		try {
			e.setInvulnerable(false);
			e.setNoDamageTicks(0);
			e.damage(1_000_000, DamageSource.builder(DamageType.CRAMMING).build());
		} catch (Throwable t) {
			// fall through to the plain kill
		}
		if (!e.isDead() && e.getHealth() > 0) e.setHealth(0);
	}

	private Object rsApi;
	private java.lang.reflect.Method rsStacked, rsSize;

	/** How many mobs this entity stands for (RoseStacker stacks them into one entity). */
	int stackSize(LivingEntity e) {
		try {
			if (rsApi == null) {
				var rs = Bukkit.getPluginManager().getPlugin("RoseStacker");
				if (rs == null) return 1;
				Class<?> api = Class.forName("dev.rosewood.rosestacker.api.RoseStackerAPI", true, rs.getClass().getClassLoader());
				rsStacked = api.getMethod("getStackedEntity", LivingEntity.class);
				rsApi = api.getMethod("getInstance").invoke(null);
			}
			Object se = rsStacked.invoke(rsApi, e);
			if (se == null) return 1;
			if (rsSize == null) rsSize = se.getClass().getMethod("getStackSize");
			return Math.max(1, ((Number) rsSize.invoke(se)).intValue());
		} catch (Throwable t) {
			return 1;
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onSplit(SlimeSplitEvent e) {
		if (plugin.loot != null && plugin.loot.fromSpawner(e.getEntity())) e.setCancelled(true);
	}

	@EventHandler(ignoreCancelled = true)
	public void onTeleport(EntityTeleportEvent e) {
		if (e.getEntity() instanceof LivingEntity le && !(le instanceof Player) && plugin.loot != null && plugin.loot.fromSpawner(le)) e.setCancelled(true);
	}

	// ---------------------------------------------------------------- loot doesn't burn

	private boolean ours(Entity e) {
		if (!(e instanceof Item item)) return false;
		ItemStack it = item.getItemStack();
		if (Loot.isLoot(it)) return true;
		return headKey != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().getKeys().contains(headKey);
	}

	@EventHandler(ignoreCancelled = true)
	public void onItemBurn(EntityDamageEvent e) {
		switch (e.getCause()) {
			case LAVA, FIRE, FIRE_TICK, HOT_FLOOR, CAMPFIRE -> {
				if (ours(e.getEntity())) e.setCancelled(true);
			}
			default -> { }
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onItemCombust(EntityCombustEvent e) {
		if (ours(e.getEntity())) e.setCancelled(true);
	}

	// ---------------------------------------------------------------- the example grinder at the Cage Sanctum

	private void set(World w, int x, int y, int z, String data) {
		Block b = w.getBlockAt(x, y, z);
		var bd = Bukkit.createBlockData(data);
		if (!b.getBlockData().matches(bd)) b.setBlockData(bd, false);
	}

	private void demo() {
		List<?> at = plugin.getConfig().getList("grinder.example");
		if (at != null && at.isEmpty()) return; // example: [] turns it off
		List<?> o = plugin.getConfig().getList("spawn.origin");
		int ox = 0, oy = 100, oz = 0;
		if (o != null && o.size() >= 3) {
			ox = ((Number) o.get(0)).intValue();
			oy = ((Number) o.get(1)).intValue();
			oz = ((Number) o.get(2)).intValue();
		}
		int cx = ox + 58, by = oy, cz = oz + 6;
		if (at != null && at.size() >= 3) {
			cx = ox + ((Number) at.get(0)).intValue();
			by = oy + ((Number) at.get(1)).intValue();
			cz = oz + ((Number) at.get(2)).intValue();
		}
		World w = Bukkit.getWorlds().get(0);
		if (!w.isChunkLoaded(cx >> 4, cz >> 4) || !w.getBlockAt(cx, by, cz).getType().isSolid()) return; // spawn not built (yet)
		try {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (dx == 0 && dz == 0) continue;
					if (!(dx == 0 && dz == -1)) set(w, cx + dx, by + 1, cz + dz, "minecraft:stone_bricks");
					for (int y = 2; y <= 7; y++) set(w, cx + dx, by + y, cz + dz, "minecraft:glass");
					set(w, cx + dx, by + 8, cz + dz, "minecraft:chiseled_stone_bricks");
				}
			}
			set(w, cx, by + 1, cz, "minecraft:hopper[facing=north]");
			set(w, cx, by + 1, cz - 1, "minecraft:chest[facing=north]");
		} catch (Throwable t) {
			plugin.getLogger().warning("Example grinder blocks failed: " + t);
			return;
		}
		demo.removeIf(e -> !e.isValid());
		if (demo.size() >= 5) return;
		for (Entity e : demo) e.remove();
		demo.clear();
		Location base = new Location(w, cx, by, cz);
		for (Entity e : w.getNearbyEntities(base.clone().add(0.5, 5, 0.5), 4, 8, 4)) {
			if (e.getPersistentDataContainer().has(demoTag, PersistentDataType.BYTE)) e.remove();
		}
		demo.add(w.spawn(base.clone().add(0, 8, 0), BlockDisplay.class, d -> {
			d.setBlock(Material.SPAWNER.createBlockData());
			d.setPersistent(false);
			d.getPersistentDataContainer().set(demoTag, PersistentDataType.BYTE, (byte) 1);
		}));
		label(base.clone().add(0.5, 10.4, 0.5), "<gradient:#38E0FF:#B040FF><bold>HOW TO BUILD A GRINDER</bold></gradient>\n<gray>One design works for <white>every cage");
		label(base.clone().add(0.5, 8.4, -1.4), "<#38E0FF><bold>1</bold> <white>Cage up high\n<gray>open air under it");
		label(base.clone().add(0.5, 5.0, -1.4), "<#38E0FF><bold>2</bold> <white>Mobs drop 6+ blocks\n<gray>(or into lava) and the\n<gray>whole stack dies");
		label(base.clone().add(0.5, 2.3, -1.9), "<#38E0FF><bold>3</bold> <white>Hopper into a chest\n<gray>then <white>/sell");
	}

	private void label(Location at, String text) {
		demo.add(at.getWorld().spawn(at, TextDisplay.class, t -> {
			t.text(Spawners.mm(text));
			t.setBillboard(Display.Billboard.CENTER);
			t.setShadowed(true);
			t.setDefaultBackground(false);
			t.setBackgroundColor(org.bukkit.Color.fromARGB(120, 0, 0, 0));
			t.setPersistent(false);
			t.getPersistentDataContainer().set(demoTag, PersistentDataType.BYTE, (byte) 1);
		}));
	}

	void shutdown() {
		for (Entity e : demo) if (e.isValid()) e.remove();
		demo.clear();
	}
}
