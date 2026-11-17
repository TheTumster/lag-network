package net.lag.factions;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.ItemStack;

import dev.kitteh.factions.Faction;
import dev.kitteh.factions.permissible.Role;

/**
 * /f upgrades. Per-faction levels (upgrades.yml), costs in config.yml (upgrades.<id>.cost per level).
 *   spawner  spawners in your claims spawn faster
 *   farm     cane / cactus / bamboo in your claims grow faster
 *   chest    more rows in /f chest
 *   tnt      bigger TNT bank
 *   warps    more /f warps
 */
final class Upgrades implements Listener {
	enum Type {
		SPAWNER(Material.SPAWNER, "Spawner Speed"),
		FARM(Material.SUGAR_CANE, "Farm Growth"),
		CHEST(Material.CHEST, "Faction Chest"),
		TNT(Material.TNT, "TNT Bank"),
		WARPS(Material.ENDER_PEARL, "Warps");

		final Material icon;
		final String title;

		Type(Material icon, String title) {
			this.icon = icon;
			this.title = title;
		}

		String key() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	private final LagFactions plugin;
	private final File file;
	private final Map<Integer, Map<Type, Integer>> levels = new HashMap<>();

	Upgrades(LagFactions plugin) {
		this.plugin = plugin;
		this.file = new File(plugin.getDataFolder(), "upgrades.yml");
		YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
		ConfigurationSection sec = y.getConfigurationSection("factions");
		if (sec != null) {
			for (String k : sec.getKeys(false)) {
				Map<Type, Integer> m = new HashMap<>();
				for (Type t : Type.values()) m.put(t, sec.getInt(k + "." + t.key()));
				try {
					levels.put(Integer.parseInt(k), m);
				} catch (NumberFormatException ignored) { }
			}
		}
	}

	private void save() {
		YamlConfiguration y = new YamlConfiguration();
		for (var e : levels.entrySet())
			for (var t : e.getValue().entrySet()) y.set("factions." + e.getKey() + "." + t.getKey().key(), t.getValue());
		try {
			plugin.getDataFolder().mkdirs();
			y.save(file);
		} catch (IOException ex) {
			plugin.getLogger().warning("Couldn't save upgrades.yml: " + ex.getMessage());
		}
	}

	int level(Faction f, Type t) {
		if (f == null) return 0;
		return levels.getOrDefault(f.id(), Map.of()).getOrDefault(t, 0);
	}

	private List<Double> costs(Type t) {
		return plugin.getConfig().getDoubleList("upgrades." + t.key() + ".cost");
	}

	int max(Type t) {
		return costs(t).size();
	}

	/** Per-level value from upgrades.<type>.value (index = level, so value[0] is the base). */
	double value(Type t, int level) {
		List<Double> v = plugin.getConfig().getDoubleList("upgrades." + t.key() + ".value");
		if (v.isEmpty()) return 0;
		return v.get(Math.min(level, v.size() - 1));
	}

	double value(Faction f, Type t) {
		return value(t, level(f, t));
	}

	private String describe(Type t, double v) {
		return switch (t) {
			case SPAWNER -> "+" + (int) v + "% spawner speed";
			case FARM -> "+" + (int) v + "% growth";
			case CHEST -> (int) v + " rows";
			case TNT -> String.format("%,d", (long) v) + " TNT";
			case WARPS -> (int) v + " warps";
		};
	}

	// ---------------------------------------------------------------- GUI

	void open(Player p) {
		Faction f = F.of(p);
		if (f == null) { Ui.err(p, "You need a faction."); return; }
		Gui g = new Gui(4, Ui.GRAD + "<bold>UPGRADES</bold></gradient> <dark_gray>• " + Ui.esc(f.tag()));
		g.frame();
		Type[] ts = Type.values();
		int[] slots = {11, 12, 13, 14, 15};
		for (int i = 0; i < ts.length; i++) {
			Type t = ts[i];
			int lvl = level(f, t), max = max(t);
			boolean maxed = lvl >= max;
			StringBuilder bars = new StringBuilder();
			for (int k = 1; k <= max; k++) bars.append(k <= lvl ? Ui.GREEN + "■" : Ui.DIM + "■");
			String next = maxed ? Ui.GOLD + "<bold>MAXED" : Ui.LABEL + "Next: <white>" + describe(t, value(t, lvl + 1)) + Ui.LABEL + " for <white>" + Ui.money(costs(t).get(lvl));
			g.set(slots[i], Ui.item(t.icon, maxed, "<white><bold>" + t.title.toUpperCase() + "</bold> " + Ui.LABEL + "Lv " + lvl + "/" + max,
					bars.toString(), "", Ui.LABEL + "Now: <white>" + describe(t, value(t, lvl)), next, "",
					maxed ? "" : F.atLeast(p, Role.MODERATOR) ? Ui.CYAN + "Click to upgrade" : Ui.DIM + "Mods and up can buy"), c -> {
				if (maxed || !F.atLeast(c.player(), Role.MODERATOR)) return;
				buy(c.player(), t);
			});
		}
		g.set(31, Ui.item(Material.ARROW, Ui.LABEL + "Back to /f menu"), c -> plugin.menu.open(c.player()));
		g.open(p);
	}

	private void buy(Player p, Type t) {
		Faction f = F.of(p);
		int lvl = level(f, t);
		if (lvl >= max(t)) return;
		double cost = costs(t).get(lvl);
		Pay.choose(p, "<white>" + t.title + " Lv " + (lvl + 1), cost, t.icon, () -> open(p), payer -> {
			Faction ff = F.of(payer);
			if (ff == null || ff.id() != f.id()) return;
			levels.computeIfAbsent(ff.id(), k -> new HashMap<>()).put(t, level(ff, t) + 1);
			save();
			for (Player o : ff.membersOnlineAsPlayers()) {
				Ui.msg(o, Ui.GREEN + "<bold>UPGRADE</bold> " + "<white>" + t.title + " Lv " + (lvl + 1) + Ui.LABEL + ": " + describe(t, value(t, lvl + 1)));
				o.playSound(o.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.2f);
			}
			open(payer);
		});
	}

	// ---------------------------------------------------------------- effects

	/** Total spawner speed boost for a faction, percent (upgrade + outpost). */
	double spawnerBoost(Faction f) {
		return value(f, Type.SPAWNER) + (plugin.outpost != null ? plugin.outpost.boost(f) : 0);
	}

	double farmBoost(Faction f) {
		return value(f, Type.FARM) + (plugin.outpost != null ? plugin.outpost.boost(f) : 0);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onSpawn(SpawnerSpawnEvent e) {
		if (e.getSpawner() == null) return;
		Block b = e.getSpawner().getBlock();
		Faction f = F.at(b);
		if (f == null || !f.isNormal()) return;
		double boost = spawnerBoost(f);
		if (boost <= 0) return;
		Bukkit.getScheduler().runTask(plugin, () -> {
			if (!(b.getState() instanceof CreatureSpawner cs)) return;
			cs.setDelay(Math.max(1, (int) Math.round(cs.getDelay() / (1 + boost / 100.0))));
			cs.update(false, false);
		});
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onGrow(BlockGrowEvent e) {
		Material m = e.getNewState().getType();
		if (m != Material.SUGAR_CANE && m != Material.CACTUS && m != Material.BAMBOO) return;
		Faction f = F.at(e.getBlock());
		if (f == null || !f.isNormal()) return;
		double boost = farmBoost(f);
		if (boost <= 0 || Math.random() * 100 >= boost) return;
		Block grown = e.getBlock();
		Bukkit.getScheduler().runTask(plugin, () -> {
			Block up = grown.getRelative(BlockFace.UP);
			if (grown.getType() != m || !up.getType().isAir()) return;
			int height = 1;
			for (Block d = grown.getRelative(BlockFace.DOWN); d.getType() == m; d = d.getRelative(BlockFace.DOWN)) height++;
			int cap = m == Material.BAMBOO ? 16 : 3;
			if (height < cap) up.setType(m == Material.BAMBOO ? Material.BAMBOO : m, true);
		});
	}

	/** TNT bank cap + warp cap: enforced around FactionsUUID's own commands. */
	@EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
	public void onCommand(PlayerCommandPreprocessEvent e) {
		String[] a = e.getMessage().substring(1).trim().toLowerCase(Locale.ROOT).split("\\s+");
		if (a.length < 2) return;
		boolean f = a[0].equals("f") || a[0].equals("fac") || a[0].equals("faction") || a[0].equals("factions");
		Player p = e.getPlayer();
		Faction fac = F.of(p);
		if (fac == null) return;
		if ((f && a[1].equals("tnt")) || a[0].equals("tntfill") || a[0].equals("tf")) {
			Bukkit.getScheduler().runTask(plugin, () -> clampTnt(p, fac));
			return;
		}
		if (f && a[1].equals("set") && a.length > 3 && a[2].equals("warp")) {
			int cap = (int) value(fac, Type.WARPS);
			if (!fac.isWarp(a[3]) && fac.warps().size() >= cap) {
				e.setCancelled(true);
				Ui.err(p, "Your faction has " + cap + " warps. Get more in <white>/f upgrades</white>.");
			}
		}
	}

	private void clampTnt(Player p, Faction f) {
		int cap = (int) value(f, Type.TNT);
		int over = f.tntBank() - cap;
		if (over <= 0) return;
		f.tntBank(cap);
		var left = p.getInventory().addItem(new ItemStack(Material.TNT, over));
		left.values().forEach(it -> p.getWorld().dropItemNaturally(p.getLocation(), it));
		Ui.err(p, "Your TNT bank holds " + String.format("%,d", cap) + ". Gave back " + String.format("%,d", over) + " TNT. Upgrade it in <white>/f upgrades</white>.");
	}
}
