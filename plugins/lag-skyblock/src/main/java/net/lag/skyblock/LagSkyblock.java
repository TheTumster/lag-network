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

/** RelapsedMC Skyblock: the spawner tier ladder and mob heads (heads drop from spawner mobs; RoseStacker stacks them). */
public final class LagSkyblock extends JavaPlugin {
	/** One rung of the ladder. index 0 = tier 1. */
	record Tier(int index, String mob, String name, String color, double price, double headValue, int unlock, String texture) {
		int number() {
			return index + 1;
		}
	}

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
	Pit pit;

	@Override
	public void onLoad() {
		saveDefaultConfig();
		reloadConfig();
		loadTiers();
		// a season was ended: wipe islands/spawners/money before anything loads them
		try {
			Season.wipeIfPending(getDataFolder(), getConfig(), getLogger());
		} catch (Exception e) {
			getLogger().severe("Season wipe failed: " + e);
		}
		// before RoseStacker / EconomyShopGUI read their files: grinder rules + branding, old spawner shop off
		try {
			patchRoseStacker();
			patchShop();
			installIsland();
		} catch (Exception e) {
			getLogger().warning("Could not patch RoseStacker / EconomyShopGUI files: " + e);
		}
	}

	@Override
	public void onEnable() {
		spawners = new Spawners(this);
		getServer().getPluginManager().registerEvents(spawners, this);
		getCommand("spawners").setExecutor(spawners);
		getCommand("sellheads").setExecutor(spawners);
		generators = new Generators(this);
		getServer().getPluginManager().registerEvents(generators, this);
		getCommand("gen").setExecutor(generators);
		new MobHeads(this);
		IslandFly fly = new IslandFly(this);
		getServer().getPluginManager().registerEvents(fly, this);
		getCommand("islandfly").setExecutor(fly);
		top = new IslandTop(this);
		getServer().getPluginManager().registerEvents(top, this);
		getCommand("istop").setExecutor(top);
		season = new Season(this);
		getCommand("season").setExecutor(season);
		pit = new Pit(this);
		getServer().getPluginManager().registerEvents(pit, this);
		getCommand("pit").setExecutor(pit);
		getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
		getCommand("lagskyblock").setExecutor((s, c, l, a) -> {
			String sub = a.length > 0 ? a[0].toLowerCase() : "reload";
			switch (sub) {
				case "season" -> season.admin(s, a);
				case "pit" -> {
					if (a.length > 1 && a[1].equalsIgnoreCase("build")) {
						int n = pit.build();
						pit.holograms();
						s.sendMessage(Spawners.mm("<#7CFF6B>The Pit rebuilt <gray>(" + n + " blocks)"));
					}
					else s.sendMessage(Spawners.mm("<gray>/lsb pit build <dark_gray>- place the arena again (center/radius: pit in config.yml)"));
				}
				case "top" -> {
					top.refresh();
					s.sendMessage(Spawners.mm("<#7CFF6B>Island top recalculated <gray>(" + top.ranking().size() + " islands)"));
				}
				case "reload" -> {
					reloadConfig();
					loadTiers();
					spawners.load();
					generators.load();
					top.refresh();
					s.sendMessage(Spawners.mm("<#7CFF6B>laG Skyblock reloaded <gray>(" + tiers.size() + " tiers)"));
				}
				default -> s.sendMessage(Spawners.mm("<gray>/lsb reload | top | pit build | season [ends <date> <time> | end]"));
			}
			return true;
		});
		getLogger().info(tiers.size() + " spawner tiers loaded");
		// first run: RoseStacker only writes its files when it starts, so patch again once it has, and reload it
		Bukkit.getScheduler().runTaskLater(this, () -> {
			try {
				if (patchRoseStacker()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "rs reload");
			} catch (Exception e) {
				getLogger().warning("Could not patch RoseStacker: " + e);
			}
		}, 60L);
	}

	@Override
	public void onDisable() {
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
			tiers.add(new Tier(i, mob, String.valueOf(m.get("name")), String.valueOf(m.containsKey("color") ? m.get("color") : "#FFFFFF"),
					num(m.get("price")), num(m.get("head-value")), (int) num(m.get("unlock")), textures.get(mob)));
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
		sb.append("lore=[{text:\"Tier ").append(t.number()).append(" head\",color:\"gray\",italic:false},");
		sb.append("{text:\"Sell or trade in: /spawners\",color:\"dark_gray\",italic:false}]");
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

	private void patchShop() throws Exception {
		File sec = new File(getDataFolder().getParentFile(), "EconomyShopGUI" + File.separator + "sections" + File.separator + "Spawners.yml");
		if (sec.exists()) {
			YamlConfiguration y = YamlConfiguration.loadConfiguration(sec);
			if (y.getBoolean("enable", true)) {
				y.set("enable", false);
				y.save(sec);
				getLogger().info("Turned off EconomyShopGUI's spawner section (spawners are sold in /spawners)");
			}
		}
	}
}
