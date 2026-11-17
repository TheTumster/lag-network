package net.lag.factions;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.ServicePriority;

import dev.kitteh.factions.FLocation;
import dev.kitteh.factions.Faction;
import dev.kitteh.factions.Factions;

/**
 * F-top: a faction's value = the worth of every spawner inside its claims (spawner prices from shop.spawners, or
 * ftop.spawner-values). Chunks are valued when loaded (every 2 min and when they unload) and remembered in ftop.yml,
 * so unloaded bases still count. /f top opens the leaderboard; the spawn podium hologram shows the top 5.
 */
final class FTop implements Listener, Supplier<String> {
	private final LagFactions plugin;
	private final File file;
	/** "world:cx:cz" -> value of spawners in that chunk */
	private final Map<String, Double> chunkValue = new HashMap<>();
	private volatile List<Object[]> ranking = List.of(); // {Faction, value}
	private volatile String hologram = "<dark_gray>No factions yet <dark_gray>• <white>/f create";

	FTop(LagFactions plugin) {
		this.plugin = plugin;
		this.file = new File(plugin.getDataFolder(), "ftop.yml");
		YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
		var sec = y.getConfigurationSection("chunks");
		if (sec != null) for (String k : sec.getKeys(false)) chunkValue.put(k.replace('|', ':'), sec.getDouble(k));
		Bukkit.getServicesManager().register(Supplier.class, this, plugin, ServicePriority.High);
		Bukkit.getScheduler().runTaskTimer(plugin, this::recompute, 100L, 2400L);
	}

	private double spawnerValue(EntityType t) {
		var c = plugin.getConfig();
		String k = t.name().toLowerCase(Locale.ROOT);
		if (c.contains("ftop.spawner-values." + k)) return c.getDouble("ftop.spawner-values." + k);
		return c.getDouble("shop.spawners." + k, c.getDouble("ftop.default-spawner-value", 10000));
	}

	private double value(Chunk ch) {
		double v = 0;
		for (BlockState s : ch.getTileEntities(b -> b.getType() == Material.SPAWNER, false))
			if (s instanceof CreatureSpawner cs && cs.getSpawnedType() != null) v += spawnerValue(cs.getSpawnedType());
		return v;
	}

	@EventHandler
	public void onUnload(ChunkUnloadEvent e) {
		Faction f = F.at(e.getWorld().getName(), e.getChunk().getX(), e.getChunk().getZ());
		if (f != null && f.isNormal()) chunkValue.put(key(e.getWorld().getName(), e.getChunk().getX(), e.getChunk().getZ()), value(e.getChunk()));
	}

	private static String key(String w, int x, int z) {
		return w + ":" + x + ":" + z;
	}

	double total(Faction f) {
		double v = 0;
		for (FLocation l : f.claims()) {
			World w = Bukkit.getWorld(l.worldName());
			String k = key(l.worldName(), l.x(), l.z());
			if (w != null && w.isChunkLoaded(l.x(), l.z())) {
				double cv = value(w.getChunkAt(l.x(), l.z()));
				chunkValue.put(k, cv);
				v += cv;
			} else v += chunkValue.getOrDefault(k, 0.0);
		}
		return v;
	}

	void recompute() {
		List<Object[]> r = new ArrayList<>();
		for (Faction f : Factions.factions().all()) if (f.isNormal()) r.add(new Object[] {f, total(f)});
		r.sort(Comparator.comparingDouble((Object[] o) -> (double) o[1]).reversed()
				.thenComparing(o -> -((Faction) o[0]).claimCount()));
		ranking = r;
		StringBuilder sb = new StringBuilder();
		String[] place = {"<#FFD24D>#1", "<#D8D8D8>#2", "<#E08A5A>#3", "<gray>#4", "<gray>#5"};
		for (int i = 0; i < Math.min(5, r.size()); i++) {
			Faction f = (Faction) r.get(i)[0];
			if (i > 0) sb.append('\n');
			sb.append(place[i]).append(" <white>").append(Ui.esc(f.tag())).append(" <dark_gray>• <#7CFF6B>").append(Ui.money((double) r.get(i)[1]));
		}
		hologram = r.isEmpty() ? "<dark_gray>No factions yet <dark_gray>• <white>/f create" : sb.toString();
		save();
	}

	private void save() {
		YamlConfiguration y = new YamlConfiguration();
		for (var e : chunkValue.entrySet()) if (e.getValue() > 0) y.set("chunks." + e.getKey().replace(':', '|'), e.getValue());
		try {
			plugin.getDataFolder().mkdirs();
			y.save(file);
		} catch (IOException ignored) { }
	}

	@Override
	public String get() {
		return hologram;
	}

	void open(Player p) {
		Gui g = new Gui(6, Ui.GRAD + "<bold>TOP FACTIONS</bold></gradient> <dark_gray>• spawner value");
		g.frame();
		List<Object[]> r = ranking;
		int[] slots = {13, 21, 23, 29, 30, 31, 32, 33, 39, 41};
		Material[] icons = {Material.GOLD_BLOCK, Material.IRON_BLOCK, Material.COPPER_BLOCK};
		for (int i = 0; i < Math.min(slots.length, r.size()); i++) {
			Faction f = (Faction) r.get(i)[0];
			double v = (double) r.get(i)[1];
			Material icon = i < 3 ? icons[i] : Material.WHITE_BANNER;
			String col = i == 0 ? Ui.GOLD : i == 1 ? "<#D8D8D8>" : i == 2 ? "<#E08A5A>" : "<white>";
			g.set(slots[i], Ui.item(icon, i == 0, col + "<bold>#" + (i + 1) + " " + Ui.esc(f.tag()),
					Ui.LABEL + "Value <#7CFF6B>" + Ui.money(v),
					Ui.LABEL + "Members <white>" + f.members().size() + Ui.LABEL + " • Land <white>" + f.claimCount(),
					Ui.LABEL + "Shield " + plugin.shields.status(f), "", Ui.CYAN + "Click for /f who"), c -> {
				c.player().closeInventory();
				plugin.who.show(c.player(), f);
			});
		}
		if (r.isEmpty()) g.set(22, Ui.item(Material.BARRIER, Ui.LABEL + "No factions yet", Ui.LABEL + "Be the first: <white>/f create"));
		g.set(4, Ui.item(Material.NETHER_STAR, true, "<white><bold>F-TOP", Ui.LABEL + "Value of the spawners in each", Ui.LABEL + "faction's claims. Top factions", Ui.LABEL + "win store credit at map end.",
				"", Ui.DIM + "Updates every 2 minutes"));
		g.set(49, Ui.item(Material.ARROW, Ui.LABEL + "Back to /f menu"), c -> plugin.menu.open(c.player()));
		g.open(p);
	}
}
