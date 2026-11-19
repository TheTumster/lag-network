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
 * spawn floor centre (base + ".origin").
 */
final class HubBuild implements Listener {
	static final int BUILD_VERSION = 14; // themes: hub.theme picks hub-spawn.txt.gz or hub-spawn-christmas.txt.gz. v14: grand gates, gem crystal + halo, no sky sign, minimal holograms (lore moved to the Codex)
	static final int SPAWN_VERSION = 1;
	/** Bump when hub-spawn-christmas.txt.gz changes, so hubs already on the Christmas theme rebuild. */
	static final int CHRISTMAS_REV = 4; // 4: sunken rink, garden beds, outer gates turned in, garland lights, snow cleanup
	private final LagCore plugin;
	private final NamespacedKey holoKey;
	private final Map<UUID, Long> portalCooldown = new HashMap<>();
	private final Map<TextDisplay, String> live = new HashMap<>();
	private final Map<TextDisplay, Float> liveScale = new HashMap<>();
	private final Map<org.bukkit.entity.ItemDisplay, float[]> spinners = new HashMap<>(); // {scale, spin speed}
	private long tick;

	/** Config section ("hub" or "spawns.<role>") and the island file in the jar. */
	final String base;
	private final String file;
	private final boolean hub;

	HubBuild(LagCore plugin) {
		this(plugin, "hub", "hub-spawn.txt.gz");
	}

	HubBuild(LagCore plugin, String base, String file) {
		this.base = base;
		this.file = file;
		this.hub = "hub".equals(base);
		this.plugin = plugin;
		this.holoKey = new NamespacedKey(plugin, "holo");
	}

	/** Called on enable (one tick later, worlds loaded). */
	void start() {
		World w = Bukkit.getWorlds().get(0);
		// hub theme: "christmas" swaps in the dressed-up build; changing it rebuilds once on the next start
		boolean themeChanged = hub && !themeStamp().equals(plugin.getConfig().getString(base + ".build-theme", "default"));
		if ((plugin.getConfig().getInt(base + ".build-version", 0) < version() || themeChanged) && plugin.optBool(base + ".auto-build", true)) {
			String ys = String.valueOf(plugin.opt(base + ".y", 100));
			Location old = origin();
			// a rebuild keeps the old floor height (on terrain, the highest block at 0,0 is the old build itself)
			int y = hub ? 100 : old != null ? old.getBlockY() : "ground".equalsIgnoreCase(ys) ? w.getHighestBlockYAt(0, 0) + 1 : Integer.parseInt(ys);
			build(w, 0, y, 0);
		}
		atmosphere(w);
		holograms();
		Bukkit.getScheduler().runTaskTimer(plugin, this::animate, 2L, 2L);
	}

	/** The hub's seasonal theme ("default" or "christmas"), from config hub.theme. */
	String theme() {
		if (!hub) return "default";
		String t = String.valueOf(plugin.opt(base + ".theme", "christmas")).toLowerCase(java.util.Locale.ROOT);
		return t.equals("christmas") ? t : "default";
	}

	private String themeStamp() {
		return "christmas".equals(theme()) ? "christmas:" + CHRISTMAS_REV : "default";
	}

	/** The build file in the jar for this spawn (the hub's depends on its theme). */
	private String resource() {
		return hub && "christmas".equals(theme()) ? "hub-spawn-christmas.txt.gz" : file;
	}

	private int version() {
		if (hub) return BUILD_VERSION;
		if ("spawns.factions".equals(base)) return 2; // factions v2: "the Fault" (tools/hub/genspawn_factions.py)
		return "spawns.skyblock".equals(base) ? 3 : SPAWN_VERSION; // skyblock v3: the Shattered World (tools/hub/genspawn_skyblock.py)
	}

	/** Spawn point on the island (null until built). */
	Location spawnPoint() {
		String s = plugin.getConfig().getString(base + ".spawn");
		if (s == null || s.isEmpty()) return null;
		String[] p = s.split(",");
		World w = Bukkit.getWorld(p[0]);
		return w == null ? null : new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
				Float.parseFloat(p[4]), Float.parseFloat(p[5]));
	}

	/** Within r blocks of the spawn island (same world). */
	boolean near(Location l, double r) {
		Location o = origin();
		return o != null && l.getWorld().equals(o.getWorld()) && o.distanceSquared(l) <= r * r;
	}

	Location origin() {
		String s = plugin.getConfig().getString(base + ".origin", "");
		if (s == null || s.isEmpty()) return null;
		String[] p = s.split(",");
		World w = Bukkit.getWorld(p[0]);
		return w == null ? null : new Location(w, Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
	}

	/** Place the island with its floor centre at (ox, oy, oz) and put spawn on it. Returns blocks placed. */
	int build(World w, int ox, int oy, int oz) {
		long t0 = System.currentTimeMillis();
		int n = 0;
		// a newer layout replaces the old one: clear the old build's area first
		if (!hub && plugin.getConfig().getInt(base + ".build-version", 0) > 0) {
			BlockData air = Bukkit.createBlockData("minecraft:air");
			int cleared = 0;
			// on real terrain only clear above the floor (never dig a pit into the world); void spawns clear it all
			boolean ground = "ground".equalsIgnoreCase(String.valueOf(plugin.opt(base + ".y", 100)));
			// clear: [radius, depth, height] around the old build (bigger spawns set their own)
			List<?> cl = plugin.getConfig().getList(base + ".clear");
			int cr = cl != null && cl.size() >= 3 ? (int) num(cl.get(0)) : 48, cd = cl != null && cl.size() >= 3 ? (int) num(cl.get(1)) : 48,
					cu = cl != null && cl.size() >= 3 ? (int) num(cl.get(2)) : 32;
			for (int x = ox - cr; x <= ox + cr; x++) {
				for (int z = oz - cr; z <= oz + cr; z++) {
					for (int y = ground ? oy + 1 : oy - cd; y <= oy + cu; y++) {
						var b = w.getBlockAt(x, y, z);
						if (!b.getType().isAir()) {
							b.setBlockData(air, false);
							cleared++;
						}
					}
				}
			}
			plugin.getLogger().info("Cleared the old " + base + " build (" + cleared + " blocks)");
			// the new layout ships its own holograms / portal / protection settings: take them from the jar
			var defs = plugin.getConfig().getDefaults();
			if (defs != null) {
				for (String k : new String[] {"holograms", "displays", "portals", "protect-radius", "catch-falls", "pvp-below", "clear", "stand-y"}) {
					Object dv = defs.get(base + "." + k);
					if (dv instanceof ConfigurationSection cs) plugin.getConfig().createSection(base + "." + k, cs.getValues(false));
					else if (dv != null) plugin.getConfig().set(base + "." + k, dv);
				}
			}
		}
		if (hub && plugin.getConfig().getInt(base + ".build-version", 0) > 0) {
			BlockData air = Bukkit.createBlockData("minecraft:air");
			int cleared = 0;
			for (int x = ox - 120; x <= ox + 120; x++) {
				for (int z = oz - 150; z <= oz + 110; z++) {
					for (int y = oy - 60; y <= oy + 120; y++) { // +120: the sky sign (top at +104)
						var b = w.getBlockAt(x, y, z);
						if (!b.getType().isAir()) {
							b.setBlockData(air, false);
							cleared++;
						}
					}
				}
			}
			plugin.getLogger().info("Cleared the old hub spawn (" + cleared + " blocks)");
		}
		String file = resource();
		try (InputStream in = plugin.getResource(file)) {
			if (in == null) {
				plugin.getLogger().warning(file + " missing from the plugin jar");
				return 0;
			}
			BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8));
			String[] names = r.readLine().split("\\|");
			BlockData[] palette = new BlockData[names.length];
			for (int i = 0; i < names.length; i++) {
				try {
					palette[i] = Bukkit.createBlockData(names[i]);
				} catch (IllegalArgumentException e) {
					// try without block states, then give up with air (never leave stone blobs in the build)
					try {
						palette[i] = Bukkit.createBlockData(names[i].split("\\[")[0]);
						plugin.getLogger().warning("Block states not accepted for " + names[i] + ", placed plain");
					} catch (IllegalArgumentException e2) {
						plugin.getLogger().warning("Unknown block " + names[i] + ", skipped");
						palette[i] = Bukkit.createBlockData("minecraft:air");
					}
				}
			}
			String line;
			while ((line = r.readLine()) != null) {
				if (line.isEmpty()) continue;
				String[] p = line.split(" ");
				int by = oy + Integer.parseInt(p[1]);
				if (by < w.getMinHeight() || by >= w.getMaxHeight()) continue;
				w.getBlockAt(ox + Integer.parseInt(p[0]), by, oz + Integer.parseInt(p[2]))
						.setBlockData(palette[Integer.parseInt(p[3])], false);
				n++;
			}
		} catch (Exception e) {
			plugin.getLogger().severe("Hub build failed: " + e);
			return n;
		}
		var c = plugin.getConfig();
		c.set(base + ".origin", w.getName() + "," + ox + "," + oy + "," + oz);
		// stand on the purple glass over the beacon, facing the Factions portal (north)
		double standY = num(plugin.opt(base + ".stand-y", 1)); // a spawn whose centre is a raised dais stands higher
		c.set(base + ".spawn", w.getName() + "," + (ox + 0.5) + "," + (oy + standY) + "," + (oz + 0.5) + ",180.0,0.0");
		c.set(base + ".void-y", oy - 60);
		c.set(base + ".build-version", version());
		if (hub) c.set(base + ".build-theme", themeStamp());
		plugin.saveConfig();
		w.setSpawnLocation(ox, oy + (int) standY, oz);
		plugin.getLogger().info("Built the " + base + " spawn: " + n + " blocks in " + (System.currentTimeMillis() - t0) + " ms");
		return n;
	}

	void atmosphere(World w) {
		if (!hub || !plugin.getConfig().getBoolean(base + ".night", true)) return;
		// game rule names changed in 1.21.11 (doDaylightCycle -> advance_time ...), so match either spelling
		rule(w, false, "doDaylightCycle", "advance_time", "do_daylight_cycle");
		rule(w, false, "doWeatherCycle", "advance_weather", "do_weather_cycle");
		rule(w, false, "doMobSpawning", "spawn_mobs", "do_mob_spawning");
		rule(w, false, "doFireTick", "fire_spread", "do_fire_tick");
		rule(w, 0, "randomTickSpeed", "random_tick_speed"); // nothing grows, decays or spreads on the build
		w.setStorm(false);
		w.setThundering(false);
		w.setTime(plugin.getConfig().getLong(base + ".time", 18000L));
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
		boolean bad = live.isEmpty() && !plugin.getConfig().getMapList(base + ".holograms").isEmpty();
		for (TextDisplay d : live.keySet()) bad |= !d.isValid();
		for (var d : spinners.keySet()) bad |= !d.isValid();
		for (Entity n : npcs) bad |= !n.isValid();
		if (bad) holograms();
	}

	void holograms() {
		Location o = origin();
		if (o == null) return;
		// keep the spawn area loaded even with nobody in the hub, so the displays aren't unloaded with it
		for (int cx = (o.getBlockX() >> 4) - 6; cx <= (o.getBlockX() >> 4) + 6; cx++) {
			for (int cz = (o.getBlockZ() >> 4) - 8; cz <= (o.getBlockZ() >> 4) + 5; cz++) {
				o.getWorld().getChunkAt(cx, cz).addPluginChunkTicket(plugin);
			}
		}
		for (Entity e : o.getWorld().getEntities()) {
			if (e.getPersistentDataContainer().has(holoKey, PersistentDataType.BYTE)) e.remove();
		}
		live.clear();
		liveScale.clear();
		spinners.clear();
		List<Map<?, ?>> list = new java.util.ArrayList<>(plugin.getConfig().getMapList(base + ".holograms"));
		if (christmas()) {
			// the logo + "walk through a gate" line move (they stood in front of the middle gap); the theme adds its own
			List<?> hide = plugin.getConfig().getList(base + ".christmas.hide", List.of("{logo}", "walk through a gate"));
			list.removeIf(m -> hide.stream().anyMatch(h -> {
				String hs = String.valueOf(h), t = String.valueOf(m.get("text"));
				return hs.startsWith("=") ? t.equals(hs.substring(1)) : t.contains(hs);
			}));
			list.addAll(plugin.getConfig().getMapList(base + ".christmas.holograms"));
		}
		for (Map<?, ?> m : list) {
			List<?> off = (List<?>) m.get("offset");
			Location at = o.clone().add(num(off.get(0)), num(off.get(1)), num(off.get(2)));
			String text = String.valueOf(m.get("text"));
			float scale = m.containsKey("scale") ? (float) num(m.get("scale")) : 1.5f;
			// optional: yaw (fixed boards face this way; 180 = faces south toward spawn), billboard, background, width
			if (m.containsKey("yaw")) at.setYaw((float) num(m.get("yaw")));
			text(at, text, scale, m);
		}
		spawnNpcs(o);
		// parkour start (leaderboard) + finish
		if (plugin.parkour != null) {
			Location ps = plugin.parkour.startHologram(), pf = plugin.parkour.finishHologram(), pb = plugin.parkour.boardHologram();
			if (pb != null) text(pb, "<gradient:#38E0FF:#B040FF:#38E0FF:{phase}><bold>PARKOUR</bold></gradient>\n<gray>Follow the bridge to the <gold>gold plate</gold>\n{parkour_top}", 1.3f);
			if (ps != null) text(ps, "<gold><bold>START</bold>\n<gray>Step on the plate", 1.2f);
			if (pf != null) text(pf, "<gradient:#FFD24D:#FF4DD2:#FFD24D:{phase}><bold>FINISH</bold></gradient>", 2f);
		}
		for (Map<?, ?> m : plugin.getConfig().getMapList(base + ".displays")) {
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
		if ("spawns.factions".equals(base) && tick % 4 == 0) faultFx();
		if (hub && tick % 4 == 0 && "christmas".equals(theme()) && plugin.optBool(base + ".snow", true)) snowFx();
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

	/** Christmas hub: snow drifting down round every player near the spawn (sent to that player only). */
	private void snowFx() {
		Location o = origin();
		if (o == null) return;
		java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
		for (org.bukkit.entity.Player p : o.getWorld().getPlayers()) {
			if (!near(p.getLocation(), 160)) continue;
			Location l = p.getLocation();
			for (int i = 0; i < 26; i++) {
				Location at = l.clone().add(r.nextDouble(-22, 22), r.nextDouble(4, 20), r.nextDouble(-22, 22));
				// count 0: the offsets are a direction, extra is the speed. Slow fall with a little wind.
				p.spawnParticle(org.bukkit.Particle.SNOWFLAKE, at, 0, 0.15 + r.nextDouble(-0.05, 0.05), -1, 0.08, 0.05);
			}
		}
	}

	/** Factions spawn: the fault line breathes purple, ash drifts over the corrupted half. Only near players. */
	private void faultFx() {
		Location o = origin();
		if (o == null || o.getWorld().getPlayers().stream().noneMatch(p -> near(p.getLocation(), 60))) return;
		var r = java.util.concurrent.ThreadLocalRandom.current();
		for (int i = 0; i < 5; i++) {
			o.getWorld().spawnParticle(org.bukkit.Particle.REVERSE_PORTAL,
					o.clone().add(0.5 + r.nextDouble(-1.2, 1.2), 1.1, r.nextDouble(-36, 36)), 2, 0.1, 0.4, 0.1, 0.02);
		}
		for (int i = 0; i < 6; i++) {
			o.getWorld().spawnParticle(org.bukkit.Particle.ASH, o.clone().add(r.nextDouble(3, 34), r.nextDouble(1, 9), r.nextDouble(-34, 34)), 3, 1, 1, 1, 0);
		}
		if (tick % 20 == 0) {
			o.getWorld().spawnParticle(org.bukkit.Particle.SOUL, o.clone().add(r.nextDouble(4, 30), 1.2, r.nextDouble(-30, 30)), 1, 0.2, 0.3, 0.2, 0.01);
		}
	}

	private void text(Location at, String text, float scale) {
		text(at, text, scale, java.util.Map.of());
	}

	/** Hologram options: billboard center|fixed|vertical|horizontal, background "#AARRGGBB" / "#RRGGBB" (a panel behind
	 *  the text for contrast), width (line width in px), bob false (fixed boards shouldn't float), see-through. */
	private void text(Location at, String text, float scale, Map<?, ?> opt) {
		at.getChunk().load();
		Display.Billboard bb = Display.Billboard.CENTER;
		if (opt.containsKey("billboard")) {
			try {
				bb = Display.Billboard.valueOf(String.valueOf(opt.get("billboard")).toUpperCase(java.util.Locale.ROOT));
			} catch (IllegalArgumentException ignored) { }
		}
		final Display.Billboard billboard = bb;
		org.bukkit.Color bg = org.bukkit.Color.fromARGB(0, 0, 0, 0);
		if (opt.containsKey("background")) {
			String h = String.valueOf(opt.get("background")).replace("#", "");
			long v = Long.parseLong(h, 16);
			bg = h.length() <= 6 ? org.bukkit.Color.fromARGB(0xB0, (int) (v >> 16) & 255, (int) (v >> 8) & 255, (int) v & 255)
					: org.bukkit.Color.fromARGB((int) (v >> 24) & 255, (int) (v >> 16) & 255, (int) (v >> 8) & 255, (int) v & 255);
		}
		final org.bukkit.Color background = bg;
		final int width = opt.containsKey("width") ? (int) num(opt.get("width")) : 400;
		final boolean seeThrough = Boolean.TRUE.equals(opt.get("see-through"));
		final float viewRange = opt.containsKey("view-range") ? (float) num(opt.get("view-range")) : 4f;
		TextDisplay d = at.getWorld().spawn(at, TextDisplay.class, t -> {
			t.getPersistentDataContainer().set(holoKey, PersistentDataType.BYTE, (byte) 1);
			t.setPersistent(false);
			t.setBillboard(billboard);
			t.setShadowed(true);
			t.setSeeThrough(seeThrough);
			t.setDefaultBackground(false);
			t.setBackgroundColor(background);
			t.setLineWidth(width);
			t.setAlignment(TextDisplay.TextAlignment.CENTER);
			if (background.getAlpha() > 0 || Boolean.TRUE.equals(opt.get("glow"))) t.setBrightness(new Display.Brightness(15, 15)); // panels stay readable at night
			t.setViewRange(viewRange);
			t.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
		});
		live.put(d, text);
		if (!Boolean.FALSE.equals(opt.get("bob"))) liveScale.put(d, scale);
		else d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
	}

	private static double num(Object o) {
		return o instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(o));
	}

	/** Fill in {server} player counts. */
	void refresh() {
		live.entrySet().removeIf(en -> !en.getKey().isValid());
		ConfigurationSection realms = plugin.section("realms");
		for (var en : live.entrySet()) {
			String t = en.getValue();
			if (realms != null) {
				for (String k : realms.getKeys(false)) {
					String server = realms.getString(k + ".server", k);
					t = t.replace("{" + k + "}", String.valueOf(plugin.count(server)));
				}
			}
			if (t.contains("{parkour_top}") && plugin.parkour != null) t = t.replace("{parkour_top}", plugin.parkour.top());
			if (t.indexOf('{') >= 0) t = MapInfo.fill(plugin, t);
			t = t.replace("{online}", String.valueOf(plugin.count("ALL"))).replace("{network}", plugin.networkName())
					.replace("{logo}", Pack.glyph(Pack.LOGO_HOLO))
					.replace("{phase}", String.format(java.util.Locale.ROOT, "%.2f", ((tick * 0.02) % 2.0) - 1.0));
			en.getKey().text(LagCore.mm(t));
		}
	}

	// ---------------------------------------------------------------- portals

	@EventHandler
	public void onPortal(PlayerPortalEvent e) {
		if (hub || near(e.getFrom(), 80)) e.setCancelled(true); // the spawn's portals are server portals, not nether ones
	}

	@EventHandler
	public void onEnter(EntityPortalEnterEvent e) {
		if (e.getEntity() instanceof Player p && (hub || near(p.getLocation(), 80))) tryPortal(p);
	}

	/** Backup for the portal event: standing in a portal block sends you too. */
	@EventHandler(ignoreCancelled = true)
	public void onMovePortal(org.bukkit.event.player.PlayerMoveEvent e) {
		if (e.getFrom().getBlockX() == e.getTo().getBlockX() && e.getFrom().getBlockY() == e.getTo().getBlockY()
				&& e.getFrom().getBlockZ() == e.getTo().getBlockZ()) return;
		var feet = e.getTo().getBlock().getType();
		var head = e.getTo().clone().add(0, 1, 0).getBlock().getType();
		if ((feet == org.bukkit.Material.NETHER_PORTAL || head == org.bukkit.Material.NETHER_PORTAL) && (hub || near(e.getTo(), 80))) tryPortal(e.getPlayer());
	}

	void tryPortal(Player p) {
		long now = System.currentTimeMillis();
		Long last = portalCooldown.get(p.getUniqueId());
		if (last != null && now - last < 3000) return;
		Location o = origin();
		ConfigurationSection portals = plugin.section(base + ".portals");
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
		if (!sendToRealm(p, best)) return;
		// bounce them back out so they don't stand in the portal if the server is offline
		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			if (p.isOnline() && p.getWorld().equals(o.getWorld())) {
				Location s = spawnPoint() != null ? spawnPoint() : plugin.spawn();
				if (s.distanceSquared(p.getLocation()) > 25) p.teleport(s);
			}
		}, 40L);
	}

	/** Send a player to a realm by its key (skyblock, factions ...). False if it's coming soon (they're told why). */
	boolean sendToRealm(Player p, String key) {
		var realmSec = plugin.section("realms");
		var realm = realmSec == null ? null : realmSec.getConfigurationSection(key);
		if (realm != null && realm.getBoolean("coming-soon", false)) {
			p.sendActionBar(LagCore.mm("<gradient:#B040FF:#38E0FF><bold>" + key.toUpperCase() + "</bold></gradient> <gray>is coming soon"));
			p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
			Location o = origin();
			if (o != null && p.getWorld().equals(o.getWorld()) && near(p.getLocation(), 60) && p.getLocation().getBlock().getType() == org.bukkit.Material.NETHER_PORTAL)
				Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) p.teleport(spawnPoint() != null ? spawnPoint() : plugin.spawn()); }, 20L);
			return false;
		}
		String server = realm != null ? realm.getString("server", key) : key;
		p.playSound(p.getLocation(), Sound.BLOCK_PORTAL_TRAVEL, 0.25f, 1.6f);
		p.sendActionBar(LagCore.mm("<gradient:#38E0FF:#B040FF>Sending you to <bold>" + key.toUpperCase() + "</bold>...</gradient>"));
		plugin.send(p, server);
		return true;
	}

	// ---------------------------------------------------------------- Christmas: NPCs (hub.christmas.npcs)

	private final NamespacedKey npcKey = new NamespacedKey("lagcore", "npc");
	private final java.util.List<Entity> npcs = new java.util.ArrayList<>();
	private final Map<UUID, Long> npcCooldown = new HashMap<>();

	boolean christmas() {
		return hub && "christmas".equals(theme());
	}

	/** Santa (a mannequin wearing the pack's santa skin) by spawn, a snow golem at every gate. Click = play. */
	private void spawnNpcs(Location o) {
		npcs.clear();
		if (!christmas()) return;
		for (Map<?, ?> m : plugin.getConfig().getMapList(base + ".christmas.npcs")) {
			List<?> off = (List<?>) m.get("offset");
			Location at = o.clone().add(num(off.get(0)), num(off.get(1)), num(off.get(2)));
			at.setYaw(m.containsKey("yaw") ? (float) num(m.get("yaw")) : 0f);
			String type = String.valueOf(m.get("type"));
			String action = m.containsKey("realm") ? String.valueOf(m.get("realm")) : "menu";
			at.getChunk().load();
			try {
				Entity e;
				if ("santa".equalsIgnoreCase(type)) {
					String skin = m.containsKey("skin") ? String.valueOf(m.get("skin")) : "relapsed:entity/santa";
					e = at.getWorld().spawn(at, org.bukkit.entity.Mannequin.class, n -> {
						n.setProfile(io.papermc.paper.datacomponent.item.ResolvableProfile.resolvableProfile()
								.skinPatch(sp -> sp.body(net.kyori.adventure.key.Key.key(skin)).model(org.bukkit.profile.PlayerTextures.SkinModel.CLASSIC))
								.build());
						n.setImmovable(true);
						n.setDescription(LagCore.mm("<#F4C542>Click to play"));
						n.customName(LagCore.mm("<#FF4D4D><bold>Santa</bold>"));
						n.setCustomNameVisible(true);
						npcBase(n, action);
					});
				} else {
					e = at.getWorld().spawn(at, org.bukkit.entity.Snowman.class, n -> {
						n.setDerp(false);
						npcBase(n, action);
					});
				}
				npcs.add(e);
			} catch (Throwable t) {
				plugin.getLogger().warning("Couldn't spawn the " + type + " NPC: " + t);
			}
		}
	}

	private void npcBase(org.bukkit.entity.LivingEntity n, String action) {
		n.getPersistentDataContainer().set(holoKey, PersistentDataType.BYTE, (byte) 1);
		n.getPersistentDataContainer().set(npcKey, PersistentDataType.STRING, action);
		n.setPersistent(false);
		n.setAI(false);
		n.setGravity(false);
		n.setInvulnerable(true);
		n.setSilent(true);
		n.setCollidable(false);
		n.setRemoveWhenFarAway(false);
	}

	private void npcClick(Player p, Entity e) {
		String action = e.getPersistentDataContainer().get(npcKey, PersistentDataType.STRING);
		if (action == null) return;
		long now = System.currentTimeMillis();
		Long last = npcCooldown.get(p.getUniqueId());
		if (last != null && now - last < 1500) return;
		npcCooldown.put(p.getUniqueId(), now);
		if ("menu".equals(action)) {
			p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.4f);
			Selector.open(plugin, p);
		} else {
			sendToRealm(p, action);
		}
	}

	@EventHandler
	public void onNpcInteract(org.bukkit.event.player.PlayerInteractEntityEvent e) {
		if (!e.getRightClicked().getPersistentDataContainer().has(npcKey, PersistentDataType.STRING)) return;
		e.setCancelled(true);
		if (e.getHand() == org.bukkit.inventory.EquipmentSlot.HAND) npcClick(e.getPlayer(), e.getRightClicked());
	}

	@EventHandler
	public void onNpcHit(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
		if (!e.getEntity().getPersistentDataContainer().has(npcKey, PersistentDataType.STRING)) return;
		e.setCancelled(true);
		if (e.getDamager() instanceof Player p) npcClick(p, e.getEntity());
	}

	/** Santa says hello when you arrive in the Christmas hub. */
	@EventHandler
	public void onJoinGreet(org.bukkit.event.player.PlayerJoinEvent e) {
		if (!christmas() || !plugin.optBool(base + ".christmas.greet", true)) return;
		Player p = e.getPlayer();
		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			if (!p.isOnline()) return;
			String msg = String.valueOf(plugin.opt(base + ".christmas.greeting",
					"<#FF4D4D><bold>Santa</bold> <dark_gray>» <white>Ho ho ho, <#F4C542>{player}<white>! Merry Christmas from <gradient:#38E0FF:#B040FF><bold>RelapsedMC</bold></gradient><white>. Follow a runner to a gate, or click me to pick a realm."));
			p.sendMessage(LagCore.mm(msg.replace("{player}", p.getName())));
			p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 1.2f);
		}, 50L);
	}
}
