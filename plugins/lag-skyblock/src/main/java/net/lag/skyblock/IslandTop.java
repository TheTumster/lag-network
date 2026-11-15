package net.lag.skyblock;

import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;

import net.kyori.adventure.text.Component;

/**
 * Island top, by spawner value only: every placed SmartSpawner on an island is worth (stack size x that mob's tier
 * price from config "tiers"). Recalculated every island-top.refresh-seconds. /istop (and /is top) opens the GUI.
 */
final class IslandTop implements Listener, CommandExecutor {
	/** One island on the board. byMob = mob -> spawner count (stacks counted). */
	record Entry(String id, UUID owner, String name, Set<UUID> members, double value, int spawners, Map<String, Integer> byMob) {
		String display() {
			if (name != null) return name;
			OfflinePlayer o = owner == null ? null : Bukkit.getOfflinePlayer(owner);
			return (o == null || o.getName() == null ? "Unknown" : o.getName()) + "'s Island";
		}
	}

	private static final DecimalFormat MONEY = new DecimalFormat("#,##0");
	// podium: #1 top centre, #2/#3 either side below, #4-#10 across the next row
	private static final int[] SLOTS = {13, 21, 23, 28, 29, 30, 31, 32, 33, 34};
	private static final int INFO = 45, YOURS = 49, CLOSE = 53;

	private final LagSkyblock plugin;
	private volatile List<Entry> ranking = List.of();
	private long updated;
	private boolean warned;

	private static final class Menu implements InventoryHolder {
		Inventory inv;

		@Override
		public Inventory getInventory() {
			return inv;
		}
	}

	IslandTop(LagSkyblock plugin) {
		this.plugin = plugin;
		long every = Math.max(10, plugin.getConfig().getLong("island-top.refresh-seconds", 60)) * 20L;
		Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 200L, every);
	}

	List<Entry> ranking() {
		return ranking;
	}

	static String money(double d) {
		return "$" + MONEY.format(d);
	}

	/** Spawner value per mob (tier price), plus island-top.extra-values for mobs outside the ladder. */
	private Map<String, Double> values() {
		Map<String, Double> v = new HashMap<>();
		var extra = plugin.getConfig().getConfigurationSection("island-top.extra-values");
		if (extra != null) for (String k : extra.getKeys(false)) v.put(k.toLowerCase(), extra.getDouble(k));
		for (LagSkyblock.Tier t : plugin.tiers) v.put(t.mob(), t.price());
		return v;
	}

	// ---------------------------------------------------------------- SmartSpawner (reflection, no compile dep)

	private List<?> allSpawners() {
		Plugin ss = Bukkit.getPluginManager().getPlugin("SmartSpawner");
		if (ss == null || !ss.isEnabled()) return null;
		try {
			Object api = ss.getClass().getMethod("getAPI").invoke(ss);
			Class<?> apiType = Class.forName("github.nighter.smartspawner.api.SmartSpawnerAPI", true, ss.getClass().getClassLoader());
			return (List<?>) apiType.getMethod("getAllSpawners").invoke(api);
		} catch (Throwable t) {
			if (!warned) {
				warned = true;
				plugin.getLogger().warning("Island top: couldn't read SmartSpawner's spawners (" + t + ")");
			}
			return null;
		}
	}

	private static Object get(Object o, Map<String, Method> cache, String name) throws Exception {
		Method m = cache.get(name);
		if (m == null) {
			m = o.getClass().getMethod(name);
			cache.put(name, m);
		}
		return m.invoke(o);
	}

	/** Recalculate the board now (main thread). */
	void refresh() {
		List<?> all = allSpawners();
		if (all == null) return;
		Map<String, Double> values = values();
		Map<String, Object> islands = new HashMap<>();
		Map<String, double[]> totals = new HashMap<>();      // id -> {value, spawners}
		Map<String, Map<String, Integer>> mobs = new HashMap<>();
		Map<String, Method> cache = new HashMap<>();
		for (Object dto : all) {
			try {
				if (Boolean.TRUE.equals(get(dto, cache, "isItemSpawner"))) continue;
				Location l = (Location) get(dto, cache, "getLocation");
				Object type = get(dto, cache, "getEntityType");
				int stack = ((Number) get(dto, cache, "getStackSize")).intValue();
				if (l == null || type == null || l.getWorld() == null) continue;
				Object island = Islands.at(l);
				String id = Islands.id(island);
				if (id == null) continue;
				String mob = String.valueOf(type).toLowerCase();
				islands.putIfAbsent(id, island);
				double[] t = totals.computeIfAbsent(id, k -> new double[2]);
				t[0] += stack * values.getOrDefault(mob, 0.0);
				t[1] += stack;
				mobs.computeIfAbsent(id, k -> new HashMap<>()).merge(mob, stack, Integer::sum);
			} catch (Throwable ignored) { }
		}
		List<Entry> out = new ArrayList<>();
		totals.forEach((id, t) -> {
			Object is = islands.get(id);
			if (t[0] <= 0 || Islands.owner(is) == null) return;
			Map<String, Integer> sorted = new LinkedHashMap<>();
			mobs.get(id).entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
					.forEach(e -> sorted.put(e.getKey(), e.getValue()));
			out.add(new Entry(id, Islands.owner(is), Islands.name(is), Set.copyOf(Islands.members(is)), t[0], (int) t[1], sorted));
		});
		out.sort(Comparator.comparingDouble(Entry::value).reversed());
		boolean first = updated == 0;
		ranking = List.copyOf(out);
		updated = System.currentTimeMillis();
		if (first) plugin.getLogger().info("Island top: " + all.size() + " spawners read, " + out.size() + " islands ranked");
	}

	/** This player's island on the board (rank from 1), or null if their island has no spawners yet. */
	int rankOf(UUID player) {
		List<Entry> r = ranking;
		for (int i = 0; i < r.size(); i++) if (r.get(i).members().contains(player)) return i + 1;
		return 0;
	}

	// ---------------------------------------------------------------- GUI

	void open(Player p) {
		Menu m = new Menu();
		int season = plugin.season == null ? 1 : plugin.season.number();
		m.inv = Bukkit.createInventory(m, 54, Spawners.mm("<gradient:#FFD84D:#FF8A3D><bold>ISLAND TOP</bold></gradient> <dark_gray>· Season " + season));
		draw(p, m.inv);
		p.openInventory(m.inv);
		p.playSound(p.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 0.4f, 1.4f);
	}

	private static ItemStack pane(Material mat) {
		ItemStack it = new ItemStack(mat);
		ItemMeta im = it.getItemMeta();
		im.setHideTooltip(true);
		it.setItemMeta(im);
		return it;
	}

	private static ItemStack named(Material mat, String name, List<String> lore) {
		ItemStack it = new ItemStack(mat);
		ItemMeta im = it.getItemMeta();
		im.displayName(Spawners.mm(name));
		List<Component> l = new ArrayList<>();
		for (String s : lore) l.add(Spawners.mm(s));
		im.lore(l);
		it.setItemMeta(im);
		return it;
	}

	private static String place(int rank) {
		return switch (rank) {
			case 1 -> "<gradient:#FFD84D:#FF8A3D><bold>#1</bold></gradient>";
			case 2 -> "<#D9E2EC><bold>#2</bold>";
			case 3 -> "<#E0915A><bold>#3</bold>";
			default -> "<gray><bold>#" + rank + "</bold>";
		};
	}

	private String mobName(String mob) {
		for (LagSkyblock.Tier t : plugin.tiers) if (t.mob().equals(mob)) return "<color:" + t.color() + ">" + t.name();
		String s = mob.replace('_', ' ');
		return "<white>" + Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private List<String> members(Entry e) {
		List<String> names = new ArrayList<>();
		for (UUID u : e.members()) {
			String n = Bukkit.getOfflinePlayer(u).getName();
			if (n != null) names.add((u.equals(e.owner()) ? "<white>" : "<gray>") + n);
		}
		return names;
	}

	private ItemStack islandItem(Entry e, int rank) {
		ItemStack it = new ItemStack(Material.PLAYER_HEAD);
		SkullMeta sm = (SkullMeta) it.getItemMeta();
		if (e.owner() != null) sm.setOwningPlayer(Bukkit.getOfflinePlayer(e.owner()));
		sm.displayName(Spawners.mm(place(rank) + " <white><bold>" + esc(e.display())));
		List<String> lore = new ArrayList<>();
		lore.add("<gray>Value: <gold><bold>" + money(e.value()));
		lore.add("<gray>Spawners: <white>" + MONEY.format(e.spawners()));
		lore.add("");
		int shown = 0;
		for (var m : e.byMob().entrySet()) {
			if (shown++ == 4) break;
			lore.add("<dark_gray>▸ " + mobName(m.getKey()) + " <dark_gray>× <white>" + MONEY.format(m.getValue()));
		}
		lore.add("");
		lore.add("<gray>Team: " + String.join("<dark_gray>, ", members(e)));
		List<Component> l = new ArrayList<>();
		for (String s : lore) l.add(Spawners.mm(s));
		sm.lore(l);
		if (rank == 1) sm.setEnchantmentGlintOverride(true);
		it.setItemMeta(sm);
		return it;
	}

	private static String esc(String s) {
		return s.replace("<", "\\<");
	}

	private void draw(Player p, Inventory inv) {
		for (int i = 0; i < 54; i++) inv.setItem(i, pane(Material.BLACK_STAINED_GLASS_PANE));
		for (int i = 0; i < 9; i++) inv.setItem(i, pane(i % 2 == 0 ? Material.ORANGE_STAINED_GLASS_PANE : Material.YELLOW_STAINED_GLASS_PANE));
		List<Entry> r = ranking;
		for (int i = 0; i < SLOTS.length; i++) {
			if (i < r.size()) inv.setItem(SLOTS[i], islandItem(r.get(i), i + 1));
			else inv.setItem(SLOTS[i], named(Material.GRAY_STAINED_GLASS_PANE, place(i + 1) + " <dark_gray>Unclaimed", List.of(
					"<dark_gray>Place spawners on your island", "<dark_gray>to take this spot.")));
		}
		long ago = updated == 0 ? -1 : (System.currentTimeMillis() - updated) / 1000;
		inv.setItem(INFO, named(Material.BOOK, "<#38E0FF><bold>HOW IT WORKS", List.of(
				"<gray>Island value = your <white>placed spawners</white>.",
				"<gray>Each spawner is worth its <white>/spawners</white> price,",
				"<gray>so stacks and higher tiers climb fastest.",
				"",
				"<gray>Top islands at the end of the season",
				"<gray>win <gold>store credit</gold> and a <gradient:#FFD84D:#FF8A3D><bold>CHAMPION</bold></gradient> title.",
				"",
				"<dark_gray>Updated " + (ago < 0 ? "soon" : ago + "s ago"))));
		int rank = rankOf(p.getUniqueId());
		if (rank > 0) {
			Entry e = r.get(rank - 1);
			String gap = rank == 1 ? "<gradient:#FFD84D:#FF8A3D>You're in first.</gradient>"
					: "<gray>Next place: <white>" + money(r.get(rank - 2).value() - e.value()) + " <gray>behind";
			inv.setItem(YOURS, named(Material.GRASS_BLOCK, "<#7CFF6B><bold>YOUR ISLAND</bold> <dark_gray>· " + place(rank), List.of(
					"<gray>Value: <gold><bold>" + money(e.value()),
					"<gray>Spawners: <white>" + MONEY.format(e.spawners()),
					"",
					gap)));
		} else {
			inv.setItem(YOURS, named(Material.GRASS_BLOCK, "<#7CFF6B><bold>YOUR ISLAND", List.of(
					"<gray>Not ranked yet.",
					"<gray>Buy spawners in <white>/spawners</white> and place",
					"<gray>them on your island to get on the board.")));
		}
		inv.setItem(CLOSE, named(Material.BARRIER, "<red>Close", List.of()));
	}

	@EventHandler
	public void onClick(InventoryClickEvent e) {
		if (!(e.getInventory().getHolder() instanceof Menu)) return;
		e.setCancelled(true);
		if (e.getRawSlot() == CLOSE && e.getWhoClicked() instanceof Player p) p.closeInventory();
	}

	@EventHandler
	public void onDrag(InventoryDragEvent e) {
		if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
	}

	/** /is top (BentoBox's Level addon ranks by blocks) opens our spawner board instead. */
	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onCmd(PlayerCommandPreprocessEvent e) {
		String m = e.getMessage().toLowerCase().trim().replaceAll("\\s+", " ");
		if (m.equals("/is top") || m.equals("/island top") || m.equals("/bsb top") || m.equals("/is leaderboard")) {
			e.setCancelled(true);
			open(e.getPlayer());
		}
	}

	@Override
	public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
		if (s instanceof Player p) {
			open(p);
			return true;
		}
		List<Entry> r = ranking;
		s.sendMessage("Island top (" + r.size() + " ranked):");
		for (int i = 0; i < Math.min(10, r.size()); i++) s.sendMessage("#" + (i + 1) + " " + r.get(i).display() + " - " + money(r.get(i).value()));
		return true;
	}
}
