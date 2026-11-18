package net.lag.skyblock;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import net.milkbowl.vault.economy.Economy;

/**
 * RelapsedMC Skyblock. One loop, one currency (Shards), one buyer (the Collector): gens and cages (spawners) make
 * loot, the Collector buys it, Shards buy more cages, heads unlock the next tier, placed cages are island value.
 */
public final class LagSkyblock extends JavaPlugin {
	static final int CONFIG_VERSION = 3;

	/** One rung of the ladder. index 0 = tier 1. value = Shards per mob's loot; loot = fixed drops per mob. */
	record Tier(int index, String mob, String name, String color, double price, int unlock, double value,
			Map<org.bukkit.Material, Integer> loot, String texture) {
		int number() {
			return index + 1;
		}

		/** What the Collector pays for one tagged loot item from this tier. */
		double itemPrice() {
			int n = 0;
			for (int a : loot.values()) n += a;
			return n == 0 ? 0 : value / n;
		}
	}

	/** Shards, formatted: "◆12,500". */
	static String shards(double d) {
		return symbol + MONEY.format(Math.floor(d));
	}

	static String symbol = "◆";
	private static final java.text.DecimalFormat MONEY = new java.text.DecimalFormat("#,##0");

	static final String HEAD_KEY = "lagskyblock:head";
	final List<Tier> tiers = new ArrayList<>();
	private Economy eco;

	/** Vault economy, looked up when first needed (the provider may enable after us). */
	Economy eco() {
		if (eco == null) {
			RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
			if (rsp != null) eco = rsp.getProvider();
		}
		return eco;
	}
	Spawners spawners;
	Generators generators;
	IslandTop top;
	Season season;
	Loot loot;
	Collector collector;
	Market market;
	Stations stations;
	Warzone warzone;

	@Override
	public void onLoad() {
		saveDefaultConfig();
		reloadConfig();
		// a new economy replaces an older config.yml once (the old one is kept next to it)
		if (getConfig().getInt("config-version", 0) < CONFIG_VERSION) {
			File cfg = new File(getDataFolder(), "config.yml");
			File old = new File(getDataFolder(), "config-old-" + getConfig().getInt("config-version", 0) + ".yml");
			if (cfg.renameTo(old)) {
				saveDefaultConfig();
				reloadConfig();
				getLogger().warning("config.yml replaced with the v" + CONFIG_VERSION + " economy (yours is in " + old.getName() + ")");
			}
		}
		symbol = getConfig().getString("shards.symbol", "◆");
		loadTiers();
		// a season was ended: wipe islands/spawners/money before anything loads them
		try {
			Season.wipeIfPending(getDataFolder(), getConfig(), getLogger());
		} catch (Exception e) {
			getLogger().severe("Season wipe failed: " + e);
		}
		// before RoseStacker / Essentials read their files: grinder rules + branding, Shards
		try {
			patchRoseStacker();
			patchEssentials();
			installIsland();
		} catch (Exception e) {
			getLogger().warning("Could not patch RoseStacker / Essentials files: " + e);
		}
	}

	@Override
	public void onEnable() {
		spawners = new Spawners(this);
		getServer().getPluginManager().registerEvents(spawners, this);
		getCommand("spawners").setExecutor(spawners);
		generators = new Generators(this);
		getServer().getPluginManager().registerEvents(generators, this);
		getCommand("gen").setExecutor(generators);
		loot = new Loot(this);
		collector = new Collector(this);
		getServer().getPluginManager().registerEvents(collector, this);
		getCommand("sell").setExecutor(collector);
		getCommand("collector").setExecutor(collector);
		market = new Market(this);
		getServer().getPluginManager().registerEvents(market, this);
		getCommand("market").setExecutor(market);
		IslandFly fly = new IslandFly(this);
		getServer().getPluginManager().registerEvents(fly, this);
		getCommand("islandfly").setExecutor(fly);
		top = new IslandTop(this);
		getServer().getPluginManager().registerEvents(top, this);
		getCommand("istop").setExecutor(top);
		season = new Season(this);
		getCommand("season").setExecutor(season);
		warzone = new Warzone(this);
		getServer().getPluginManager().registerEvents(warzone, this);
		getCommand("warzone").setExecutor(warzone);
		stations = new Stations(this);
		getServer().getPluginManager().registerEvents(stations, this);
		getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
		getCommand("lagskyblock").setExecutor((s, c, l, a) -> {
			String sub = a.length > 0 ? a[0].toLowerCase() : "reload";
			switch (sub) {
				case "season" -> season.admin(s, a);
				case "envoy" -> warzone.envoy(s);
				case "koth" -> warzone.kothAdmin(s, a);
				case "stations" -> {
					stations.respawn();
					s.sendMessage(Spawners.mm("<#7CFF6B>Spawn stations respawned"));
				}
				case "top" -> {
					top.refresh();
					s.sendMessage(Spawners.mm("<#7CFF6B>Island top recalculated <gray>(" + top.ranking().size() + " islands)"));
				}
				case "reload" -> {
					reloadConfig();
					symbol = getConfig().getString("shards.symbol", "◆");
					loadTiers();
					loot.load();
					collector.load();
					stations.respawn();
					spawners.load();
					generators.load();
					top.refresh();
					s.sendMessage(Spawners.mm("<#7CFF6B>laG Skyblock reloaded <gray>(" + tiers.size() + " tiers)"));
				}
				default -> s.sendMessage(Spawners.mm("<gray>/lsb reload | top | stations | envoy | koth [start|stop] | season [ends <date> <time> | end]"));
			}
			return true;
		});
		getLogger().info(tiers.size() + " spawner tiers loaded");
		// first run: RoseStacker only writes its files when it starts, so patch again once it has, and reload it
		Bukkit.getScheduler().runTaskLater(this, () -> {
			try {
				if (patchRoseStacker()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "rs reload");
				if (patchEssentials()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "essentials reload");
			} catch (Exception e) {
				getLogger().warning("Could not patch RoseStacker: " + e);
			}
		}, 60L);
	}

	@Override
	public void onDisable() {
		if (stations != null) stations.clear();
		if (warzone != null) warzone.clear();
		if (spawners != null) spawners.save();
		if (top != null) top.save();
		if (generators != null) generators.save();
	}

	// ---------------------------------------------------------------- tiers

	private void loadTiers() {
		tiers.clear();
		Map<String, String> textures = readTextures();
		List<Map<?, ?>> list = getConfig().getMapList("tiers");
		for (int i = 0; i < list.size(); i++) {
			Map<?, ?> m = list.get(i);
			String mob = String.valueOf(m.get("mob")).toLowerCase();
			Map<org.bukkit.Material, Integer> loot = new java.util.LinkedHashMap<>();
			if (m.get("loot") instanceof Map<?, ?> lm) {
				for (var e : lm.entrySet()) {
					org.bukkit.Material mat = org.bukkit.Material.matchMaterial(String.valueOf(e.getKey()));
					if (mat != null) loot.put(mat, (int) num(e.getValue()));
				}
			}
			tiers.add(new Tier(i, mob, String.valueOf(m.get("name")), String.valueOf(m.containsKey("color") ? m.get("color") : "#FFFFFF"),
					num(m.get("price")), (int) num(m.get("unlock")), num(m.get("value")), loot, textures.get(mob)));
		}
	}

	private static double num(Object o) {
		return o instanceof Number n ? n.doubleValue() : o == null ? 0 : Double.parseDouble(String.valueOf(o));
	}

	private File rsFile(String name) {
		return new File(getDataFolder().getParentFile(), "RoseStacker" + File.separator + name);
	}

	/** Mob head textures (Mojang texture hashes), bundled in heads.yml. */
	private Map<String, String> readTextures() {
		Map<String, String> out = new HashMap<>();
		try (var in = getResource("heads.yml")) {
			if (in == null) return out;
			YamlConfiguration y = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
			for (String k : y.getKeys(false)) out.put(k.toLowerCase(), y.getString(k));
		} catch (Exception ignored) { }
		return out;
	}

	/** The head item as an item string (our menus and the heads mobs drop). */
	String headSpec(Tier t, boolean tagged) {
		// mobs with a vanilla head use it; the rest are player heads with the mob's skin
		String base = t.texture() != null ? "player_head" : switch (t.mob()) {
			case "zombie" -> "zombie_head";
			case "skeleton" -> "skeleton_skull";
			case "creeper" -> "creeper_head";
			case "wither_skeleton" -> "wither_skeleton_skull";
			case "piglin" -> "piglin_head";
			default -> "player_head";
		};
		StringBuilder sb = new StringBuilder(base + "[");
		if (t.texture() != null) {
			String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + t.texture() + "\"}}}";
			String b64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
			sb.append("profile={properties:[{name:\"textures\",value:\"").append(b64).append("\"}]},");
		}
		sb.append("item_name={text:\"").append(t.name()).append(" Head\",color:\"").append(t.color()).append("\"},");
		sb.append("lore=[{text:\"Proof: tier ").append(t.number()).append(" mastered\",color:\"gray\",italic:false},");
		sb.append("{text:\"Trade in to unlock the next cage: /spawners\",color:\"dark_gray\",italic:false}]");
		if (tagged) sb.append(",custom_data={PublicBukkitValues:{\"").append(HEAD_KEY).append("\":\"").append(t.mob()).append("\"}}");
		return sb.append("]").toString();
	}

	/**
	 * RoseStacker: OG grinders. A stack dies all at once from the death causes in rosestacker.kill-entire-stack-on
	 * (fall, lava, fire, magma, drowning, suffocation, cramming...), with loot for every mob in it, so drop chutes and
	 * lava blades are AFK grinders. A player's sword still kills one at a time (for XP). Spawners can be picked up
	 * without silk touch. Chat prefix is ours.
	 */
	private boolean patchRoseStacker() throws Exception {
		boolean any = false;
		File cfg = rsFile("config.yml");
		if (cfg.exists()) {
			YamlConfiguration y = YamlConfiguration.loadConfiguration(cfg);
			boolean changed = false;
			Map<String, Object> want = new java.util.LinkedHashMap<>();
			want.put("global-entity-settings.kill-entire-stack-on-death", false);
			want.put("global-entity-settings.kill-entire-stack-on-death-conditions", getConfig().getStringList("rosestacker.kill-entire-stack-on"));
			want.put("global-spawner-settings.silk-touch-required", false);
			want.put("global-spawner-settings.spawn-player-activation-range", getConfig().getInt("rosestacker.player-activation-range", 24));
			want.put("global-spawner-settings.disable-mob-ai", false);
			// a fixed 360 mobs an hour per cage (2 every 400 ticks), so the economy's numbers hold
			int delay = getConfig().getInt("rosestacker.spawn-delay", 400);
			want.put("global-spawner-settings.spawn-delay-minimum", delay);
			want.put("global-spawner-settings.spawn-delay-maximum", delay);
			want.put("global-spawner-settings.spawn-count-stack-size-multiplier", getConfig().getInt("rosestacker.spawn-count", 2));
			want.put("global-spawner-settings.spawn-count-stack-size-randomized", false);
			want.put("global-spawner-settings.max-stack-size", getConfig().getInt("rosestacker.max-cage-stack", 256));
			ConfigurationSection extra = getConfig().getConfigurationSection("rosestacker.settings");
			if (extra != null) for (String k : extra.getKeys(true)) if (!extra.isConfigurationSection(k)) want.put(k, extra.get(k));
			for (var e : want.entrySet()) {
				if (!java.util.Objects.equals(String.valueOf(y.get(e.getKey())), String.valueOf(e.getValue()))) {
					y.set(e.getKey(), e.getValue());
					changed = true;
				}
			}
			if (changed) {
				y.save(cfg);
				any = true;
				getLogger().info("RoseStacker set up for grinders (whole stack dies from " + getConfig().getStringList("rosestacker.kill-entire-stack-on") + ")");
			}
		}
		File[] locales = rsFile("locale").listFiles((d, n) -> n.endsWith(".yml"));
		if (locales != null) {
			String prefix = getConfig().getString("rosestacker.prefix", "<g:#38E0FF:#B040FF>&lRELAPSED &8» &7");
			for (File f : locales) {
				YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
				if (!prefix.equals(y.getString("prefix"))) {
					y.set("prefix", prefix);
					y.save(f);
					any = true;
				}
			}
		}
		return any;
	}

	/** Our starter island becomes the only island choice, and new islands get a bigger claim. */
	private void installIsland() throws Exception {
		File dir = new File(getDataFolder().getParentFile(), "BentoBox" + File.separator + "addons" + File.separator + "BSkyBlock");
		File bps = new File(dir, "blueprints");
		if (!bps.isDirectory()) return; // BSkyBlock hasn't run yet; next start
		try (var in = getResource("blueprints/relapsed.blu")) {
			if (in != null) java.nio.file.Files.copy(in, new File(bps, "relapsed.blu").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
		String bundle = """
				{
				  "uniqueId": "relapsed",
				  "icon": "GRASS_BLOCK",
				  "displayName": "&a&lRelapsed Island",
				  "description": ["&7A big starter island with a tree,", "&7a sand beach and a starter chest."],
				  "requirePermission": false,
				  "blueprints": {"NORMAL": "relapsed", "NETHER": "nether-island", "THE_END": "end-island"},
				  "slot": 0
				}
				""";
		java.nio.file.Files.writeString(new File(bps, "relapsed.json").toPath(), bundle);
		// hide the stock islands so ours is the one everyone gets
		File[] others = bps.listFiles((d, n) -> n.endsWith(".json") && !n.equals("relapsed.json"));
		if (others != null) {
			for (File f : others) {
				String t = java.nio.file.Files.readString(f.toPath());
				String u = t.replaceAll("\"requirePermission\"\\s*:\\s*false", "\"requirePermission\": true");
				if (!u.equals(t)) java.nio.file.Files.writeString(f.toPath(), u);
			}
		}
		File cfg = new File(dir, "config.yml");
		if (cfg.exists()) {
			int range = getConfig().getInt("island.protection-range", 75);
			String t = java.nio.file.Files.readString(cfg.toPath());
			String u = t.replaceFirst("(?m)^(\\s*protection-range:)\\s*\\d+", "$1 " + range);
			// island teams: max-team-size members; coop/trusted would let extra players grind an island, so capped too
			u = u.replaceFirst("(?m)^(\\s*max-team-size:)\\s*\\d+", "$1 " + getConfig().getInt("island.max-team-size", 4));
			u = u.replaceFirst("(?m)^(\\s*max-coop-size:)\\s*\\d+", "$1 " + getConfig().getInt("island.max-coop-size", 0));
			u = u.replaceFirst("(?m)^(\\s*max-trusted-size:)\\s*\\d+", "$1 " + getConfig().getInt("island.max-trusted-size", 0));
			if (!u.equals(t)) {
				java.nio.file.Files.writeString(cfg.toPath(), u);
				getLogger().info("BSkyBlock: " + (range * 2) + "x" + (range * 2) + " claims, teams of " + getConfig().getInt("island.max-team-size", 4));
			}
		}
	}

	/** Shards: Essentials shows our symbol and new players start with shards.starting-balance. */
	private boolean patchEssentials() throws Exception {
		File f = new File(getDataFolder().getParentFile(), "Essentials" + File.separator + "config.yml");
		if (!f.exists()) return false;
		YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
		boolean changed = false;
		String sym = getConfig().getString("shards.symbol", "◆");
		double start = getConfig().getDouble("shards.starting-balance", 3500);
		if (!sym.equals(y.getString("currency-symbol"))) {
			y.set("currency-symbol", sym);
			changed = true;
		}
		if (y.getDouble("starting-balance", -1) != start) {
			y.set("starting-balance", start);
			changed = true;
		}
		if (changed) {
			y.save(f);
			getLogger().info("Essentials: Shards (" + sym + "), new players start with " + shards(start));
		}
		return changed;
	}
}
