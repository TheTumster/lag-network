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

/** RelapsedMC Skyblock: the spawner tier ladder and mob heads (heads come from SmartSpawner loot). */
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

	@Override
	public void onLoad() {
		saveDefaultConfig();
		reloadConfig();
		loadTiers();
		// before SmartSpawner / EconomyShopGUI read their files: heads in spawner loot, head selling off there, old spawner shop off
		try {
			patchSmartSpawner();
			patchShop();
		} catch (Exception e) {
			getLogger().warning("Could not patch SmartSpawner / EconomyShopGUI files: " + e);
		}
	}

	@Override
	public void onEnable() {
		spawners = new Spawners(this);
		getServer().getPluginManager().registerEvents(spawners, this);
		getCommand("spawners").setExecutor(spawners);
		getCommand("sellheads").setExecutor(spawners);
		getCommand("lagskyblock").setExecutor((s, c, l, a) -> {
			reloadConfig();
			loadTiers();
			spawners.load();
			s.sendMessage(Spawners.mm("<#7CFF6B>laG Skyblock reloaded <gray>(" + tiers.size() + " tiers)"));
			return true;
		});
		getLogger().info(tiers.size() + " spawner tiers loaded");
		// first run: SmartSpawner only writes its files when it starts, so patch again once it has, and reload it
		Bukkit.getScheduler().runTaskLater(this, () -> {
			try {
				loadTiers();
				if (patchSmartSpawner()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ss reload");
			} catch (Exception e) {
				getLogger().warning("Could not patch SmartSpawner: " + e);
			}
		}, 60L);
	}

	@Override
	public void onDisable() {
		if (spawners != null) spawners.save();
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

	private File ssFile(String name) {
		return new File(getDataFolder().getParentFile(), "SmartSpawner" + File.separator + name);
	}

	/** Mob head textures from SmartSpawner's own spawner_mobs.yml (mob_head.hash_texture). */
	private Map<String, String> readTextures() {
		Map<String, String> out = new HashMap<>();
		File f = ssFile("spawner_mobs.yml");
		if (!f.exists()) return out;
		YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
		for (String k : y.getKeys(false)) {
			String hash = y.getString(k + ".mob_head.hash_texture");
			if (hash != null && !hash.isEmpty() && !"null".equals(hash)) out.put(k.toLowerCase(), hash);
		}
		return out;
	}

	/** The head item as an item string (same string goes into SmartSpawner's loot table and our menus). */
	String headSpec(Tier t, boolean tagged) {
		StringBuilder sb = new StringBuilder("player_head[");
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

	private boolean patchSmartSpawner() throws Exception {
		boolean any = false;
		File mobs = ssFile("spawner_mobs.yml");
		if (mobs.exists()) {
			YamlConfiguration y = YamlConfiguration.loadConfiguration(mobs);
			double chance = getConfig().getDouble("head-chance", 4.0);
			boolean changed = false;
			for (Tier t : tiers) {
				if (!y.isConfigurationSection(t.mob())) continue;
				ConfigurationSection loot = y.getConfigurationSection(t.mob() + ".loot");
				if (loot == null) loot = y.createSection(t.mob() + ".loot");
				String key = null;
				int max = 0;
				for (String k : loot.getKeys(false)) {
					String item = loot.getString(k + ".item", "");
					if (item.contains(HEAD_KEY)) key = k;
					try {
						max = Math.max(max, Integer.parseInt(k));
					} catch (NumberFormatException ignored) { }
				}
				if (key == null) key = String.valueOf(max + 1);
				String spec = headSpec(t, true);
				if (!spec.equals(loot.getString(key + ".item")) || loot.getDouble(key + ".chance") != chance) {
					loot.set(key + ".item", spec);
					loot.set(key + ".amount", "1-1");
					loot.set(key + ".chance", chance);
					changed = true;
				}
			}
			if (changed) {
				y.save(mobs);
				any = true;
				getLogger().info("Added tier heads to SmartSpawner loot (" + chance + "% per mob)");
			}
		}
		// heads are sold through /spawners at tier prices, never by SmartSpawner's sell button
		File sell = ssFile("sell_integration.yml");
		if (sell.exists()) {
			YamlConfiguration y = YamlConfiguration.loadConfiguration(sell);
			if (y.getDouble("custom_prices.prices.PLAYER_HEAD", -1) != 0.0) {
				y.set("custom_prices.prices.PLAYER_HEAD", 0.0);
				y.save(sell);
				any = true;
			}
		}
		return any;
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
