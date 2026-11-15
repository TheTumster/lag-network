package net.lag.core;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * The RelapsedMC hub spawn: places the generated floating island (resources/hub-spawn.txt.gz, made by
 * tools/hub/genspawn.py) on first start, sets spawn on it, spawns the floating titles, and turns the
 * nether portals into server portals (walk in = join that server). Offsets in config are relative to the
 * spawn floor centre ("hub.origin").
 */
final class HubBuild implements Listener {
	static final int BUILD_VERSION = 2;
	private final LagCore plugin;
	private final NamespacedKey holoKey;
	private final Map<UUID, Long> portalCooldown = new HashMap<>();
	private final Map<TextDisplay, String> live = new HashMap<>();
	private final Map<TextDisplay, Float> liveScale = new HashMap<>();
	private final Map<org.bukkit.entity.ItemDisplay, float[]> spinners = new HashMap<>(); // {scale, spin speed}
	private long tick;

	HubBuild(LagCore plugin) {
		this.plugin = plugin;
		this.holoKey = new NamespacedKey(plugin, "holo");
	}

	/** Called on enable (one tick later, worlds loaded). */
	void start() {
		World w = Bukkit.getWorlds().get(0);
		if (plugin.getConfig().getInt("hub.build-version", 0) < BUILD_VERSION && plugin.getConfig().getBoolean("hub.auto-build", true)) {
			build(w, 0, 100, 0);
		}
		atmosphere(w);
		holograms();
		Bukkit.getScheduler().runTaskTimer(plugin, this::animate, 2L, 2L);
	}

	Location origin() {
		String s = plugin.getConfig().getString("hub.origin", "");
		if (s == null || s.isEmpty()) return null;
		String[] p = s.split(",");
		World w = Bukkit.getWorld(p[0]);
		return w == null ? null : new Location(w, Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
	}

	/** Place the island with its floor centre at (ox, oy, oz) and put spawn on it. Returns blocks placed. */
	int build(World w, int ox, int oy, int oz) {
		long t0 = System.currentTimeMillis();
		int n = 0;
		try (InputStream in = plugin.getResource("hub-spawn.txt.gz")) {
			if (in == null) {
				plugin.getLogger().warning("hub-spawn.txt.gz missing from the plugin jar");
				return 0;
			}
			BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8));
			String[] names = r.readLine().split("\\|");
			BlockData[] palette = new BlockData[names.length];
			for (int i = 0; i < names.length; i++) {
				try {
					palette[i] = Bukkit.createBlockData(names[i]);
				} catch (IllegalArgumentException e) {
					plugin.getLogger().warning("Unknown block " + names[i] + ", using stone");
					palette[i] = Bukkit.createBlockData("minecraft:stone");
				}
			}
			String line;
			while ((line = r.readLine()) != null) {
				if (line.isEmpty()) continue;
				String[] p = line.split(" ");
				w.getBlockAt(ox + Integer.parseInt(p[0]), oy + Integer.parseInt(p[1]), oz + Integer.parseInt(p[2]))
						.setBlockData(palette[Integer.parseInt(p[3])], false);
				n++;
			}
		} catch (Exception e) {
			plugin.getLogger().severe("Hub build failed: " + e);
			return n;
		}
		var c = plugin.getConfig();
		c.set("hub.origin", w.getName() + "," + ox + "," + oy + "," + oz);
		// stand on the purple glass over the beacon, facing the Factions portal (north)
		c.set("hub.spawn", w.getName() + "," + (ox + 0.5) + "," + (oy + 1) + "," + (oz + 0.5) + ",180.0,0.0");
		c.set("hub.void-y", oy - 60);
		c.set("hub.build-version", BUILD_VERSION);
		plugin.saveConfig();
		w.setSpawnLocation(ox, oy + 1, oz);
		plugin.getLogger().info("Built the hub spawn: " + n + " blocks in " + (System.currentTimeMillis() - t0) + " ms");
		return n;
	}

	void atmosphere(World w) {
		if (!plugin.getConfig().getBoolean("hub.night", true)) return;
		// game rule names changed in 1.21.11 (doDaylightCycle -> advance_time ...), so match either spelling
		rule(w, false, "doDaylightCycle", "advance_time", "do_daylight_cycle");
		rule(w, false, "doWeatherCycle", "advance_weather", "do_weather_cycle");
		rule(w, false, "doMobSpawning", "spawn_mobs", "do_mob_spawning");
		rule(w, false, "doFireTick", "fire_spread", "do_fire_tick");
		rule(w, 0, "randomTickSpeed", "random_tick_speed"); // nothing grows, decays or spreads on the build
		w.setStorm(false);
		w.setThundering(false);
		w.setTime(plugin.getConfig().getLong("hub.time", 18000L));
	}

	@SuppressWarnings({"unchecked", "deprecation"})
	private <T> void rule(World w, T value, String... names) {
		for (GameRule<?> g : GameRule.values()) {
			for (String n : names) {
				if (g.getName().equalsIgnoreCase(n) || g.getName().equalsIgnoreCase("minecraft:" + n)) {
					if (g.getType().isInstance(value)) w.setGameRule((GameRule<T>) g, value);
					return;
				}
			}
		}
		plugin.getLogger().fine("No game rule named " + String.join("/", names));
	}

	// ---------------------------------------------------------------- holograms

	/** Respawn the floating text/items if any of them got unloaded or removed. */
	void ensure() {
		boolean bad = live.isEmpty() && !plugin.getConfig().getMapList("hub.holograms").isEmpty();
		for (TextDisplay d : live.keySet()) bad |= !d.isValid();
		for (var d : spinners.keySet()) bad |= !d.isValid();
		if (bad) holograms();
	}

	void holograms() {
		Location o = origin();
		if (o == null) return;
		// keep the spawn area loaded even with nobody in the hub, so the displays aren't unloaded with it
		for (int cx = (o.getBlockX() >> 4) - 2; cx <= (o.getBlockX() >> 4) + 2; cx++) {
			for (int cz = (o.getBlockZ() >> 4) - 2; cz <= (o.getBlockZ() >> 4) + 2; cz++) {
				o.getWorld().getChunkAt(cx, cz).addPluginChunkTicket(plugin);
			}
		}
		for (Entity e : o.getWorld().getEntities()) {
			if (e.getPersistentDataContainer().has(holoKey, PersistentDataType.BYTE)) e.remove();
		}
		live.clear();
		liveScale.clear();
		spinners.clear();
		List<Map<?, ?>> list = plugin.getConfig().getMapList("hub.holograms");
		for (Map<?, ?> m : list) {
			List<?> off = (List<?>) m.get("offset");
			Location at = o.clone().add(num(off.get(0)), num(off.get(1)), num(off.get(2)));
			String text = String.valueOf(m.get("text"));
			float scale = m.containsKey("scale") ? (float) num(m.get("scale")) : 1.5f;
			at.getChunk().load();
			TextDisplay d = at.getWorld().spawn(at, TextDisplay.class, t -> {
				t.getPersistentDataContainer().set(holoKey, PersistentDataType.BYTE, (byte) 1);
				t.setPersistent(false);
				t.setBillboard(Display.Billboard.CENTER);
				t.setShadowed(true);
				t.setSeeThrough(false);
				t.setDefaultBackground(false);
				t.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
				t.setLineWidth(400);
				t.setViewRange(4f);
				t.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
			});
			live.put(d, text);
			liveScale.put(d, scale);
		}
		for (Map<?, ?> m : plugin.getConfig().getMapList("hub.displays")) {
			List<?> off = (List<?>) m.get("offset");
			Location at = o.clone().add(num(off.get(0)), num(off.get(1)), num(off.get(2)));
			org.bukkit.Material mat = org.bukkit.Material.matchMaterial(String.valueOf(m.get("item")));
			if (mat == null) continue;
			float scale = m.containsKey("scale") ? (float) num(m.get("scale")) : 2f;
			float spin = m.containsKey("spin") ? (float) num(m.get("spin")) : 1f;
			String glow = m.containsKey("glow") ? String.valueOf(m.get("glow")) : null;
			at.getChunk().load();
			org.bukkit.entity.ItemDisplay d = at.getWorld().spawn(at, org.bukkit.entity.ItemDisplay.class, t -> {
				t.getPersistentDataContainer().set(holoKey, PersistentDataType.BYTE, (byte) 1);
				t.setPersistent(false);
				t.setItemStack(new org.bukkit.inventory.ItemStack(mat));
				t.setItemDisplayTransform(org.bukkit.entity.ItemDisplay.ItemDisplayTransform.FIXED);
				t.setBillboard(Display.Billboard.FIXED);
				t.setViewRange(4f);
				t.setBrightness(new Display.Brightness(15, 15));
				t.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
				if (glow != null) {
					t.setGlowing(true);
					t.setGlowColorOverride(org.bukkit.Color.fromRGB(Integer.parseInt(glow.replace("#", ""), 16)));
				}
			});
			spinners.put(d, new float[] {scale, spin});
		}
		refresh();
	}

	/** Every 2 ticks: gradient sweep on text; every 40: bob text and spin items (client interpolates smoothly). */
	private void animate() {
		tick += 2;
		refresh();
		if (tick % 40 != 0) return;
		boolean up = (tick / 40) % 2 == 0;
		for (var en : liveScale.entrySet()) {
			TextDisplay d = en.getKey();
			if (!d.isValid()) continue;
			float sc = en.getValue();
			d.setInterpolationDelay(0);
			d.setInterpolationDuration(40);
			d.setTransformation(new Transformation(new Vector3f(0, up ? 0.12f : -0.12f, 0), new AxisAngle4f(),
					new Vector3f(sc, sc, sc), new AxisAngle4f()));
		}
		for (var en : spinners.entrySet()) {
			org.bukkit.entity.ItemDisplay d = en.getKey();
			if (!d.isValid()) continue;
			float sc = en.getValue()[0], speed = en.getValue()[1];
			float angle = (float) ((tick / 40) * (Math.PI / 2) * speed % (Math.PI * 2));
			d.setInterpolationDelay(0);
			d.setInterpolationDuration(40);
			d.setTransformation(new Transformation(new Vector3f(0, up ? 0.15f : -0.15f, 0), new AxisAngle4f(angle, 0, 1, 0),
					new Vector3f(sc, sc, sc), new AxisAngle4f()));
		}
	}

	private static double num(Object o) {
		return o instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(o));
	}

	/** Fill in {server} player counts. */
	void refresh() {
		live.entrySet().removeIf(en -> !en.getKey().isValid());
		ConfigurationSection realms = plugin.getConfig().getConfigurationSection("realms");
		for (var en : live.entrySet()) {
			String t = en.getValue();
			if (realms != null) {
				for (String k : realms.getKeys(false)) {
					String server = realms.getString(k + ".server", k);
					t = t.replace("{" + k + "}", String.valueOf(plugin.count(server)));
				}
			}
			t = t.replace("{online}", String.valueOf(plugin.count("ALL"))).replace("{network}", plugin.networkName())
					.replace("{logo}", Pack.glyph(Pack.LOGO_HOLO))
					.replace("{phase}", String.format(java.util.Locale.ROOT, "%.2f", ((tick * 0.02) % 2.0) - 1.0));
			en.getKey().text(LagCore.mm(t));
		}
	}

	// ---------------------------------------------------------------- portals

	@EventHandler
	public void onPortal(PlayerPortalEvent e) {
		e.setCancelled(true); // never go to the nether from the hub
	}

	@EventHandler
	public void onEnter(EntityPortalEnterEvent e) {
		if (!(e.getEntity() instanceof Player p)) return;
		long now = System.currentTimeMillis();
		Long last = portalCooldown.get(p.getUniqueId());
		if (last != null && now - last < 3000) return;
		Location o = origin();
		ConfigurationSection portals = plugin.getConfig().getConfigurationSection("hub.portals");
		if (o == null || portals == null) return;
		String best = null;
		double bestD = 12 * 12;
		for (String realm : portals.getKeys(false)) {
			List<?> off = portals.getList(realm);
			if (off == null || off.size() < 3) continue;
			Location at = o.clone().add(num(off.get(0)), num(off.get(1)), num(off.get(2)));
			if (!at.getWorld().equals(p.getWorld())) continue;
			double d = at.distanceSquared(p.getLocation());
			if (d < bestD) {
				bestD = d;
				best = realm;
			}
		}
		if (best == null) return;
		portalCooldown.put(p.getUniqueId(), now);
		String server = plugin.getConfig().getString("realms." + best + ".server", best);
		p.playSound(p.getLocation(), Sound.BLOCK_PORTAL_TRAVEL, 0.25f, 1.6f);
		p.sendActionBar(LagCore.mm("<gradient:#38E0FF:#B040FF>Sending you to <bold>" + best.toUpperCase() + "</bold>...</gradient>"));
		plugin.send(p, server);
		// bounce them back out so they don't stand in the portal if the server is offline
		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			if (p.isOnline() && p.getWorld().equals(o.getWorld())) {
				Location s = plugin.spawn();
				if (s.distanceSquared(p.getLocation()) > 25) p.teleport(s);
			}
		}, 40L);
	}
}
