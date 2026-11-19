package net.lag.skyblock;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.kyori.adventure.text.Component;

/**
 * The auction house. /ah sell <price> lists what you're holding; /ah browses. Buying takes a confirm click. The
 * seller is paid at once (online or not), minus the tax (a Shard sink). Listings run for auction.hours, then come
 * back to the seller under "Your listings", along with anything cancelled. Settings: config `auction:` (tax 5%,
 * max-listings 6, hours 48, blocked [SPAWNER] - cages stay on the Keeper's price curve). Wiped with the season.
 */
final class Auction implements Listener, CommandExecutor {
	private record Listing(long id, UUID seller, String name, ItemStack item, double price, long expires) { }

	private enum Mode { BROWSE, MINE, CONFIRM }

	private static final class Menu implements InventoryHolder {
		Inventory inv;
		Mode mode = Mode.BROWSE;
		int page;
		long confirm;
		final Map<Integer, Long> listings = new HashMap<>();
		final Map<Integer, Integer> returns = new HashMap<>();

		@Override
		public Inventory getInventory() {
			return inv;
		}
	}

	private static final int PREV = 45, MINE = 47, INFO = 49, BACK = 51, NEXT = 53, YES = 11, NO = 15;

	private final LagSkyblock plugin;
	private final File file;
	private final TreeMap<Long, Listing> listings = new TreeMap<>();
	private final Map<UUID, List<ItemStack>> returns = new HashMap<>();
	private long nextId = 1;

	Auction(LagSkyblock plugin) {
		this.plugin = plugin;
		this.file = new File(plugin.getDataFolder(), "auction.yml");
		load();
		Bukkit.getScheduler().runTaskTimer(plugin, this::expire, 1200L, 1200L);
	}

	private double tax() {
		return Math.max(0, Math.min(50, plugin.getConfig().getDouble("auction.tax", 5))) / 100.0;
	}

	private int max() {
		return plugin.getConfig().getInt("auction.max-listings", 6);
	}

	private Set<Material> blocked() {
		Set<Material> out = new HashSet<>();
		List<String> l = plugin.getConfig().isList("auction.blocked") ? plugin.getConfig().getStringList("auction.blocked") : List.of("SPAWNER");
		for (String s : l) {
			Material m = Material.matchMaterial(s);
			if (m != null) out.add(m);
		}
		return out;
	}

	// ---------------------------------------------------------------- storage

	private void load() {
		YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
		nextId = y.getLong("next-id", 1);
		ConfigurationSection ls = y.getConfigurationSection("listings");
		if (ls != null) {
			for (String k : ls.getKeys(false)) {
				try {
					ConfigurationSection s = ls.getConfigurationSection(k);
					ItemStack it = s.getItemStack("item");
					if (it == null) continue;
					long id = Long.parseLong(k);
					listings.put(id, new Listing(id, UUID.fromString(s.getString("seller")), s.getString("name", "?"), it, s.getDouble("price"), s.getLong("expires")));
					nextId = Math.max(nextId, id + 1);
				} catch (Exception e) {
					plugin.getLogger().warning("Skipped a broken auction listing (" + k + "): " + e);
				}
			}
		}
		ConfigurationSection rs = y.getConfigurationSection("returns");
		if (rs != null) {
			for (String u : rs.getKeys(false)) {
				List<ItemStack> items = new ArrayList<>();
				for (Object o : rs.getList(u, List.of())) if (o instanceof ItemStack it) items.add(it);
				try {
					if (!items.isEmpty()) returns.put(UUID.fromString(u), items);
				} catch (IllegalArgumentException ignored) {
				}
			}
		}
	}

	void save() {
		YamlConfiguration y = new YamlConfiguration();
		y.set("next-id", nextId);
		for (Listing l : listings.values()) {
			String b = "listings." + l.id() + ".";
			y.set(b + "seller", l.seller().toString());
			y.set(b + "name", l.name());
			y.set(b + "item", l.item());
			y.set(b + "price", l.price());
			y.set(b + "expires", l.expires());
		}
		returns.forEach((u, items) -> {
			if (!items.isEmpty()) y.set("returns." + u, items);
		});
		try {
			y.save(file);
		} catch (Exception e) {
			plugin.getLogger().warning("Couldn't save auction.yml: " + e);
		}
	}

	private void expire() {
		long now = System.currentTimeMillis();
		boolean changed = false;
		for (Listing l : new ArrayList<>(listings.values())) {
			if (l.expires() > now) continue;
			listings.remove(l.id());
			returns.computeIfAbsent(l.seller(), k -> new ArrayList<>()).add(l.item());
			changed = true;
			Player p = Bukkit.getPlayer(l.seller());
			if (p != null) p.sendMessage(Spawners.mm("<dark_gray>» <gray>Your auction for <white>" + what(l.item()) + "</white> ran out. Collect it in <white>/ah</white>."));
		}
		if (changed) save();
	}

	// ---------------------------------------------------------------- helpers

	private static String what(ItemStack it) {
		StringBuilder b = new StringBuilder();
		for (String w : it.getType().name().toLowerCase(Locale.ROOT).split("_")) b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
		return it.getAmount() + "× " + b.toString().trim();
	}

	private static String left(long ms) {
		long m = Math.max(0, ms / 60000);
		return m >= 60 ? (m / 60) + "h " + (m % 60) + "m" : m + "m";
	}

	/** 250000, 250k, 2.5m, 1b */
	private static double price(String s) {
		s = s.toLowerCase(Locale.ROOT).replace(",", "").replace("◆", "");
		double mul = 1;
		if (s.endsWith("k")) mul = 1e3;
		else if (s.endsWith("m")) mul = 1e6;
		else if (s.endsWith("b")) mul = 1e9;
		if (mul > 1) s = s.substring(0, s.length() - 1);
		try {
			return Math.floor(Double.parseDouble(s) * mul);
		} catch (NumberFormatException e) {
			return -1;
		}
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

	private static ItemStack withLore(ItemStack base, List<String> extra) {
		ItemStack it = base.clone();
		ItemMeta im = it.getItemMeta();
		if (im == null) return it;
		List<Component> l = im.lore() == null ? new ArrayList<>() : new ArrayList<>(im.lore());
		for (String s : extra) l.add(Spawners.mm(s));
		im.lore(l);
		it.setItemMeta(im);
		return it;
	}

	private void hand(Player p, ItemStack it) {
		for (ItemStack over : p.getInventory().addItem(it.clone()).values()) p.getWorld().dropItemNaturally(p.getLocation(), over);
	}

	// ---------------------------------------------------------------- menus

	private void open(Player p, Mode mode, int page) {
		Menu m = new Menu();
		m.mode = mode;
		m.page = Math.max(0, page);
		m.inv = Bukkit.createInventory(m, 54, Gui.title(mode == Mode.MINE ? "YOUR LISTINGS" : "AUCTION HOUSE", mode == Mode.MINE ? null : listings.size() + " listed", "#7A4A00"));
		draw(p, m);
		p.openInventory(m.inv);
	}

	private void draw(Player p, Menu m) {
		Gui.frame(m.inv, Material.ORANGE_STAINED_GLASS_PANE);
		m.listings.clear();
		m.returns.clear();
		long now = System.currentTimeMillis();
		List<Integer> slots = Gui.centred(1, 4, 28);
		if (m.mode == Mode.BROWSE) {
			List<Listing> all = new ArrayList<>(listings.descendingMap().values());
			all.removeIf(l -> l.expires() <= now);
			int pages = Math.max(1, (all.size() + 27) / 28);
			m.page = Math.min(m.page, pages - 1);
			for (int i = 0; i < 28 && m.page * 28 + i < all.size(); i++) {
				Listing l = all.get(m.page * 28 + i);
				boolean own = l.seller().equals(p.getUniqueId());
				m.inv.setItem(slots.get(i), withLore(l.item(), List.of(
						"",
						"<gray>Price: <gold><bold>" + LagSkyblock.shards(l.price()),
						"<gray>Seller: <white>" + l.name(),
						"<gray>Ends in: <white>" + left(l.expires() - now),
						"",
						own ? "<#9A9A9A>Yours - manage it in Your listings" : "<#7CFF6B>▶ Click to buy")));
				m.listings.put(slots.get(i), l.id());
			}
			if (all.isEmpty()) {
				m.inv.setItem(22, named(Material.GLASS_BOTTLE, "<white>Nothing for sale yet", List.of("<gray>Be the first: hold an item and", "<gray>type <white>/ah sell <price>")));
			}
			if (m.page > 0) m.inv.setItem(PREV, named(Material.ARROW, "<white>Previous page", List.of()));
			if (m.page < pages - 1) m.inv.setItem(NEXT, named(Material.ARROW, "<white>Next page", List.of()));
			int mine = 0;
			for (Listing l : listings.values()) if (l.seller().equals(p.getUniqueId())) mine++;
			int back = returns.getOrDefault(p.getUniqueId(), List.of()).size();
			m.inv.setItem(MINE, named(Material.CHEST, "<#FFB020><bold>YOUR LISTINGS", List.of(
					"<gray>Selling: <white>" + mine + "/" + max(),
					back > 0 ? "<#7CFF6B>" + back + " item(s) to collect" : "<#9A9A9A>Nothing to collect",
					"", "<#7CFF6B>▶ Click to open")));
			m.inv.setItem(INFO, named(Material.BOOK, "<#38E0FF><bold>HOW IT WORKS", List.of(
					"<gray>Hold an item and type",
					"<white>/ah sell <price> <#9A9A9A>(250k, 2.5m ...)",
					"",
					"<gray>You're paid the moment it sells,",
					"<gray>minus a <white>" + Math.round(tax() * 100) + "%</white> fee.",
					"<gray>Unsold after " + plugin.getConfig().getInt("auction.hours", 48) + "h? It comes back",
					"<gray>to you under Your listings.")));
			m.inv.setItem(BACK, named(Material.BARRIER, "<red>Close", List.of()));
		} else if (m.mode == Mode.MINE) {
			int i = 0;
			for (Listing l : listings.values()) {
				if (!l.seller().equals(p.getUniqueId()) || i >= 28) continue;
				m.inv.setItem(slots.get(i), withLore(l.item(), List.of(
						"",
						"<gray>Price: <gold><bold>" + LagSkyblock.shards(l.price()),
						"<gray>Ends in: <white>" + left(l.expires() - now),
						"",
						"<red>▶ Click to cancel and take it back")));
				m.listings.put(slots.get(i++), l.id());
			}
			List<ItemStack> back = returns.getOrDefault(p.getUniqueId(), List.of());
			for (int r = 0; r < back.size() && i < 28; r++) {
				m.inv.setItem(slots.get(i), withLore(back.get(r), List.of("", "<#9A9A9A>Didn't sell", "<#7CFF6B>▶ Click to collect")));
				m.returns.put(slots.get(i++), r);
			}
			if (i == 0) m.inv.setItem(22, named(Material.GLASS_BOTTLE, "<white>You aren't selling anything", List.of("<gray>Hold an item and type", "<white>/ah sell <price>")));
			m.inv.setItem(INFO, named(Material.ARROW, "<white>Back to the auction house", List.of()));
		}
	}

	private void confirm(Player p, Listing l) {
		Menu m = new Menu();
		m.mode = Mode.CONFIRM;
		m.confirm = l.id();
		m.inv = Bukkit.createInventory(m, 27, Gui.title("BUY THIS?", LagSkyblock.shards(l.price()), "#7A4A00"));
		Gui.frame(m.inv, Material.ORANGE_STAINED_GLASS_PANE);
		m.inv.setItem(13, withLore(l.item(), List.of("", "<gray>Price: <gold><bold>" + LagSkyblock.shards(l.price()), "<gray>Seller: <white>" + l.name())));
		m.inv.setItem(YES, named(Material.LIME_CONCRETE, "<#7CFF6B><bold>BUY", List.of("<gray>Pay <gold>" + LagSkyblock.shards(l.price()))));
		m.inv.setItem(NO, named(Material.RED_CONCRETE, "<red><bold>CANCEL", List.of()));
		p.openInventory(m.inv);
	}

	@EventHandler
	public void onClick(InventoryClickEvent e) {
		if (!(e.getInventory().getHolder() instanceof Menu m)) return;
		e.setCancelled(true);
		if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
		int s = e.getRawSlot();
		if (m.mode == Mode.CONFIRM) {
			if (s == YES) buy(p, m.confirm);
			else if (s == NO) open(p, Mode.BROWSE, 0);
			return;
		}
		if (m.mode == Mode.BROWSE) {
			if (s == PREV && m.page > 0) open(p, Mode.BROWSE, m.page - 1);
			else if (s == NEXT) open(p, Mode.BROWSE, m.page + 1);
			else if (s == MINE) open(p, Mode.MINE, 0);
			else if (s == BACK) p.closeInventory();
			else if (m.listings.containsKey(s)) {
				Listing l = listings.get(m.listings.get(s));
				if (l == null) {
					p.sendMessage(Spawners.mm("<red>That one just sold."));
					open(p, Mode.BROWSE, m.page);
				} else if (!l.seller().equals(p.getUniqueId())) {
					confirm(p, l);
				}
			}
			return;
		}
		if (s == INFO) {
			open(p, Mode.BROWSE, 0);
		} else if (m.listings.containsKey(s)) {
			Listing l = listings.get(m.listings.get(s));
			if (l != null && l.seller().equals(p.getUniqueId())) {
				listings.remove(l.id());
				save();
				hand(p, l.item());
				p.sendMessage(Spawners.mm("<dark_gray>» <gray>Cancelled: <white>" + what(l.item())));
			}
			open(p, Mode.MINE, 0);
		} else if (m.returns.containsKey(s)) {
			List<ItemStack> back = returns.get(p.getUniqueId());
			int idx = m.returns.get(s);
			if (back != null && idx < back.size()) {
				ItemStack it = back.remove(idx);
				if (back.isEmpty()) returns.remove(p.getUniqueId());
				save();
				hand(p, it);
			}
			open(p, Mode.MINE, 0);
		}
	}

	@EventHandler
	public void onDrag(InventoryDragEvent e) {
		if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
	}

	// ---------------------------------------------------------------- buying + selling

	private void buy(Player p, long id) {
		Listing l = listings.get(id);
		if (l == null || l.expires() <= System.currentTimeMillis()) {
			p.sendMessage(Spawners.mm("<red>That one is gone."));
			open(p, Mode.BROWSE, 0);
			return;
		}
		if (l.seller().equals(p.getUniqueId()) || plugin.eco() == null) return;
		if (!plugin.eco().has(p, l.price())) {
			p.sendMessage(Spawners.mm("<red>You need <white>" + LagSkyblock.shards(l.price()) + "</white> for that."));
			p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
			p.closeInventory();
			return;
		}
		listings.remove(id);
		save();
		plugin.eco().withdrawPlayer(p, l.price());
		double paid = Math.floor(l.price() * (1 - tax()));
		plugin.eco().depositPlayer(Bukkit.getOfflinePlayer(l.seller()), paid);
		hand(p, l.item());
		p.closeInventory();
		p.sendMessage(Spawners.mm("<dark_gray>» <gray>Bought <white>" + what(l.item()) + "</white> for <gold>" + LagSkyblock.shards(l.price())));
		p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);
		Player seller = Bukkit.getPlayer(l.seller());
		if (seller != null) {
			seller.sendMessage(Spawners.mm("<dark_gray>» <white>" + p.getName() + " <gray>bought your <white>" + what(l.item()) + "</white>: <gold>+" + LagSkyblock.shards(paid)));
			seller.playSound(seller.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.4f);
		}
	}

	private void sell(Player p, String priceText) {
		ItemStack hand = p.getInventory().getItemInMainHand();
		if (hand.getType().isAir()) {
			p.sendMessage(Spawners.mm("<red>Hold the item you want to sell."));
			return;
		}
		if (blocked().contains(hand.getType())) {
			p.sendMessage(Spawners.mm("<red>That can't go on the auction house."));
			return;
		}
		double price = price(priceText);
		if (price < 1 || price > 1e12) {
			p.sendMessage(Spawners.mm("<red>Give a price, like <white>/ah sell 250k"));
			return;
		}
		int mine = 0;
		for (Listing l : listings.values()) if (l.seller().equals(p.getUniqueId())) mine++;
		if (mine >= max()) {
			p.sendMessage(Spawners.mm("<red>You already have " + mine + " listings up (the most you can have)."));
			return;
		}
		ItemStack item = hand.clone();
		p.getInventory().setItemInMainHand(null);
		long id = nextId++;
		long hours = plugin.getConfig().getInt("auction.hours", 48);
		listings.put(id, new Listing(id, p.getUniqueId(), p.getName(), item, price, System.currentTimeMillis() + hours * 3_600_000L));
		save();
		p.sendMessage(Spawners.mm("<dark_gray>» <gray>Listed <white>" + what(item) + "</white> for <gold>" + LagSkyblock.shards(price) + " <#9A9A9A>(" + hours + "h)"));
		p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.4f);
	}

	@Override
	public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
		if (!(s instanceof Player p)) {
			s.sendMessage("Players only.");
			return true;
		}
		if (a.length >= 2 && a[0].equalsIgnoreCase("sell")) sell(p, a[1]);
		else if (a.length == 1 && a[0].equalsIgnoreCase("sell")) p.sendMessage(Spawners.mm("<gray>Hold the item and type <white>/ah sell <price> <#9A9A9A>(like 250k or 2.5m)"));
		else if (a.length >= 1 && (a[0].equalsIgnoreCase("mine") || a[0].equalsIgnoreCase("listings"))) open(p, Mode.MINE, 0);
		else open(p, Mode.BROWSE, 0);
		return true;
	}
}
