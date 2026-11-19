package net.lag.skyblock;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import net.kyori.adventure.text.Component;

/**
 * Crates at the Crate Vaults (spawn, south-east island). Keys are virtual (keys.yml), so they can't be duped, lost or
 * traded: /keys shows yours. Right-click a crate to open it, left-click to see every reward and its odds. Keys come
 * from voting (NuVotifier, if installed), envoys, KOTH, other crates, and the store: every source runs the same
 * console command, `crate give <player> <crate> [amount]`, and every key opens the same crate with the same odds.
 * Crates, rewards and where they stand: crates.yml.
 */
final class Crates implements Listener, CommandExecutor {
	private record Reward(double weight, String label, Material icon, int amount, int[] shards, Material item, int cages, String key,
			List<String> commands) { }

	private record Crate(String id, String name, String color, int[] at, List<Reward> rewards, double total) { }

	private static final class Menu implements InventoryHolder {
		Inventory inv;

		@Override
		public Inventory getInventory() {
			return inv;
		}
	}

	private final LagSkyblock plugin;
	private final File keyFile;
	private final Map<String, Crate> crates = new LinkedHashMap<>();
	private final Map<UUID, Map<String, Integer>> keys = new HashMap<>();
	private final Map<String, TextDisplay> labels = new HashMap<>();
	private final Map<UUID, Long> cooldown = new HashMap<>();
	private final NamespacedKey tag;

	Crates(LagSkyblock plugin) {
		this.plugin = plugin;
		this.tag = new NamespacedKey(plugin, "crate_label");
		this.keyFile = new File(plugin.getDataFolder(), "keys.yml");
		load();
		Bukkit.getScheduler().runTaskTimer(plugin, this::labels, 100L, 200L);
		hookVotes();
	}

	// ---------------------------------------------------------------- config + storage

	void load() {
		File f = new File(plugin.getDataFolder(), "crates.yml");
		if (!f.exists()) plugin.saveResource("crates.yml", false);
		YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
		crates.clear();
		ConfigurationSection all = y.getConfigurationSection("crates");
		if (all != null) {
			for (String id : all.getKeys(false)) {
				ConfigurationSection c = all.getConfigurationSection(id);
				if (c == null) continue;
				List<Integer> at = c.getIntegerList("at");
				List<Reward> rewards = new ArrayList<>();
				double total = 0;
				for (Map<?, ?> m : c.getMapList("rewards")) {
					Reward r = reward(m);
					if (r == null) continue;
					rewards.add(r);
					total += r.weight();
				}
				if (rewards.isEmpty()) continue;
				crates.put(id.toLowerCase(Locale.ROOT), new Crate(id.toLowerCase(Locale.ROOT), c.getString("name", id), c.getString("color", "#FFFFFF"),
						at.size() >= 3 ? new int[] {at.get(0), at.get(1), at.get(2)} : null, rewards, total));
			}
		}
		keys.clear();
		YamlConfiguration k = YamlConfiguration.loadConfiguration(keyFile);
		for (String u : k.getKeys(false)) {
			ConfigurationSection s = k.getConfigurationSection(u);
			if (s == null) continue;
			try {
				Map<String, Integer> m = new HashMap<>();
				for (String c : s.getKeys(false)) if (s.getInt(c) > 0) m.put(c, s.getInt(c));
				keys.put(UUID.fromString(u), m);
			} catch (IllegalArgumentException ignored) {
			}
		}
	}

	private static double num(Object o, double def) {
		if (o instanceof Number n) return n.doubleValue();
		try {
			return o == null ? def : Double.parseDouble(String.valueOf(o));
		} catch (NumberFormatException e) {
			return def;
		}
	}

	private Reward reward(Map<?, ?> m) {
		double weight = num(m.get("weight"), 1);
		int amount = (int) num(m.get("amount"), 1);
		int[] shards = null;
		if (m.get("shards") instanceof List<?> l && l.size() >= 2) shards = new int[] {(int) num(l.get(0), 0), (int) num(l.get(1), 0)};
		else if (m.get("shards") != null) shards = new int[] {(int) num(m.get("shards"), 0), (int) num(m.get("shards"), 0)};
		Material item = m.get("item") == null ? null : Material.matchMaterial(String.valueOf(m.get("item")));
		int cages = (int) num(m.get("cages"), 0);
		String key = m.get("key") == null ? null : String.valueOf(m.get("key")).toLowerCase(Locale.ROOT);
		List<String> commands = new ArrayList<>();
		if (m.get("commands") instanceof List<?> l) for (Object o : l) commands.add(String.valueOf(o));
		Material icon = m.get("icon") == null ? null : Material.matchMaterial(String.valueOf(m.get("icon")));
		String label = m.get("label") == null ? null : String.valueOf(m.get("label"));
		if (shards != null) {
			if (icon == null) icon = shards[1] >= 1_000_000 ? Material.AMETHYST_CLUSTER : shards[1] >= 100_000 ? Material.AMETHYST_SHARD : Material.PRISMARINE_SHARD;
			if (label == null) label = "<gold>" + LagSkyblock.shards(shards[0]) + (shards[1] > shards[0] ? " - " + LagSkyblock.shards(shards[1]) : "");
		} else if (cages > 0) {
			if (icon == null) icon = Material.SPAWNER;
			if (label == null) label = "<#B040FF>" + cages + "× your best cage";
			amount = cages;
		} else if (key != null) {
			if (icon == null) icon = Material.TRIAL_KEY;
			if (label == null) label = "<#38E0FF>" + amount + "× " + key + " key";
		} else if (item != null) {
			if (icon == null) icon = item;
			if (label == null) label = "<white>" + amount + "× " + pretty(item);
		} else if (!commands.isEmpty()) {
			if (icon == null) icon = Material.NETHER_STAR;
			if (label == null) label = "<white>Mystery reward";
		} else {
			return null;
		}
		return new Reward(weight, label, icon, amount, shards, item, cages, key, commands);
	}

	private static String pretty(Material m) {
		StringBuilder b = new StringBuilder();
		for (String w : m.name().toLowerCase(Locale.ROOT).split("_")) b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
		return b.toString().trim();
	}

	void save() {
		YamlConfiguration y = new YamlConfiguration();
		keys.forEach((u, m) -> m.forEach((c, n) -> {
			if (n > 0) y.set(u + "." + c, n);
		}));
		try {
			y.save(keyFile);
		} catch (Exception e) {
			plugin.getLogger().warning("Couldn't save keys.yml: " + e);
		}
	}

	int keys(UUID u, String crate) {
		Map<String, Integer> m = keys.get(u);
		return m == null ? 0 : m.getOrDefault(crate, 0);
	}

	/** Give (or take, negative) keys. Works for offline players too. */
	void give(UUID u, String crate, int amount) {
		Map<String, Integer> m = keys.computeIfAbsent(u, k -> new HashMap<>());
		m.put(crate, Math.max(0, m.getOrDefault(crate, 0) + amount));
		save();
		Player p = Bukkit.getPlayer(u);
		Crate c = crates.get(crate);
		if (p != null && amount > 0 && c != null) {
			p.sendMessage(Spawners.mm("<dark_gray>» <gray>You got <white>" + amount + "× <color:" + c.color() + "><bold>" + c.name() + "</bold></color> key"
					+ (amount == 1 ? "" : "s") + "<gray>. Open it at the Crate Vaults <#9A9A9A>(/crates)"));
			p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 1.2f);
		}
	}

	// ---------------------------------------------------------------- the crates at spawn

	private Location blockAt(Crate c) {
		if (c.at() == null) return null;
		List<?> o = plugin.getConfig().getList("spawn.origin");
		double ox = 0, oy = 100, oz = 0;
		if (o != null && o.size() >= 3) {
			ox = num(o.get(0), 0);
			oy = num(o.get(1), 100);
			oz = num(o.get(2), 0);
		}
		World w = Bukkit.getWorlds().get(0);
		return new Location(w, ox + c.at()[0], oy + c.at()[1], oz + c.at()[2]);
	}

	private Crate crateAt(Block b) {
		for (Crate c : crates.values()) {
			Location l = blockAt(c);
			if (l != null && l.getWorld().equals(b.getWorld()) && l.getBlockX() == b.getX() && l.getBlockY() == b.getY() && l.getBlockZ() == b.getZ()) return c;
		}
		return null;
	}

	/** Floating name over each crate; re-made whenever it goes missing. */
	private void labels() {
		for (Crate c : crates.values()) {
			Location l = blockAt(c);
			if (l == null || !l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) continue;
			TextDisplay d = labels.get(c.id());
			if (d != null && d.isValid()) continue;
			Location at = l.clone().add(0.5, 1.55, 0.5);
			for (Entity e : at.getWorld().getNearbyEntities(at, 1.5, 2, 1.5)) {
				if (e.getPersistentDataContainer().has(tag, PersistentDataType.BYTE)) e.remove();
			}
			labels.put(c.id(), at.getWorld().spawn(at, TextDisplay.class, t -> {
				t.text(Spawners.mm("<color:" + c.color() + "><bold>" + c.name().toUpperCase(Locale.ROOT) + "</bold></color>\n<white>Right-click <gray>open\n<white>Left-click <gray>rewards"));
				t.setBillboard(Display.Billboard.CENTER);
				t.setShadowed(true);
				t.setDefaultBackground(false);
				t.setBackgroundColor(org.bukkit.Color.fromARGB(110, 0, 0, 0));
				t.setPersistent(false);
				t.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
			}));
		}
	}

	void shutdown() {
		for (TextDisplay d : labels.values()) if (d != null && d.isValid()) d.remove();
		labels.clear();
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onInteract(PlayerInteractEvent e) {
		if (e.getClickedBlock() == null || e.getHand() != EquipmentSlot.HAND) return;
		if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.LEFT_CLICK_BLOCK) return;
		Crate c = crateAt(e.getClickedBlock());
		if (c == null) return;
		e.setCancelled(true);
		Player p = e.getPlayer();
		long now = System.currentTimeMillis();
		if (now - cooldown.getOrDefault(p.getUniqueId(), 0L) < 400) return;
		cooldown.put(p.getUniqueId(), now);
		if (e.getAction() == Action.LEFT_CLICK_BLOCK) preview(p, c);
		else open(p, c);
	}

	// ---------------------------------------------------------------- opening

	private static String rarity(double pct) {
		return pct < 2 ? "<#FFB020><bold>LEGENDARY" : pct < 8 ? "<#B040FF><bold>EPIC" : pct < 20 ? "<#38E0FF><bold>RARE" : "<white><bold>COMMON";
	}

	private ItemStack icon(Crate c, Reward r, boolean odds) {
		ItemStack it = new ItemStack(r.icon() == null || !r.icon().isItem() ? Material.CHEST : r.icon(), Math.max(1, Math.min(64, r.item() != null ? r.amount() : 1)));
		ItemMeta im = it.getItemMeta();
		double pct = r.weight() / c.total() * 100;
		im.displayName(Spawners.mm(r.label()));
		List<Component> lore = new ArrayList<>();
		lore.add(Spawners.mm(rarity(pct)));
		if (odds) lore.add(Spawners.mm("<gray>Chance: <white>" + (pct < 1 ? String.format("%.2f", pct) : String.format("%.1f", pct)) + "%"));
		im.lore(lore);
		if (pct < 8) im.setEnchantmentGlintOverride(true);
		it.setItemMeta(im);
		return it;
	}

	private void preview(Player p, Crate c) {
		Menu m = new Menu();
		int rows = Math.min(4, (c.rewards().size() + 6) / 7);
		m.inv = Bukkit.createInventory(m, (rows + 2) * 9, Gui.title(c.name().toUpperCase(Locale.ROOT), "you have " + keys(p.getUniqueId(), c.id()) + " key(s)", "#4B1E8C"));
		Gui.frame(m.inv, Material.PURPLE_STAINED_GLASS_PANE);
		List<Integer> slots = Gui.balanced(1, rows, c.rewards().size());
		for (int i = 0; i < c.rewards().size() && i < slots.size(); i++) m.inv.setItem(slots.get(i), icon(c, c.rewards().get(i), true));
		p.openInventory(m.inv);
		p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f);
	}

	private Reward roll(Crate c) {
		double r = ThreadLocalRandom.current().nextDouble(c.total());
		for (Reward w : c.rewards()) {
			r -= w.weight();
			if (r < 0) return w;
		}
		return c.rewards().get(c.rewards().size() - 1);
	}

	private void open(Player p, Crate c) {
		if (keys(p.getUniqueId(), c.id()) <= 0) {
			p.sendMessage(Spawners.mm("<red>You need a <white>" + c.name() + "</white> key. <gray>Left-click the crate to see what's inside."));
			p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
			return;
		}
		give(p.getUniqueId(), c.id(), -1);
		Reward won = roll(c);
		// the prize is decided and paid now; the spin is only for show, so closing early or logging out loses nothing
		pay(p, c, won);
		Menu m = new Menu();
		m.inv = Bukkit.createInventory(m, 27, Gui.title(c.name().toUpperCase(Locale.ROOT), null, "#4B1E8C"));
		Gui.frame(m.inv, Material.PURPLE_STAINED_GLASS_PANE);
		p.openInventory(m.inv);
		final int[] step = {0};
		Bukkit.getScheduler().runTaskTimer(plugin, task -> {
			if (!p.isOnline() || p.getOpenInventory().getTopInventory() != m.inv) {
				task.cancel();
				return;
			}
			int s = step[0]++;
			if (s < 14) {
				for (int col = 1; col <= 7; col++) m.inv.setItem(9 + col, icon(c, col == 4 && s == 13 ? won : roll(c), false));
				p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 0.8f + s * 0.06f);
			} else {
				for (int col = 1; col <= 7; col++) if (col != 4) m.inv.setItem(9 + col, Gui.pane(Material.GRAY_STAINED_GLASS_PANE));
				m.inv.setItem(13, icon(c, won, true));
				m.inv.setItem(4, Gui.pane(Material.LIME_STAINED_GLASS_PANE));
				m.inv.setItem(22, Gui.pane(Material.LIME_STAINED_GLASS_PANE));
				p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.3f);
				task.cancel();
			}
		}, 2L, 3L);
		Location l = blockAt(c);
		if (l != null) l.getWorld().spawnParticle(Particle.END_ROD, l.clone().add(0.5, 1.2, 0.5), 25, 0.4, 0.5, 0.4, 0.03);
	}

	private void pay(Player p, Crate c, Reward r) {
		String got = r.label();
		if (r.shards() != null && plugin.eco() != null) {
			double s = r.shards()[1] > r.shards()[0] ? ThreadLocalRandom.current().nextInt(r.shards()[0], r.shards()[1] + 1) : r.shards()[0];
			plugin.eco().depositPlayer(p, s);
			got = "<gold>" + LagSkyblock.shards(s);
		}
		if (r.item() != null && r.item().isItem()) {
			int left = r.amount();
			while (left > 0) {
				ItemStack it = new ItemStack(r.item(), Math.min(left, r.item().getMaxStackSize()));
				left -= it.getAmount();
				for (ItemStack over : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), over);
			}
		}
		if (r.cages() > 0 && plugin.spawners != null) plugin.spawners.giveCages(p, r.cages());
		if (r.key() != null && crates.containsKey(r.key())) give(p.getUniqueId(), r.key(), r.amount());
		for (String cmd : r.commands()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", p.getName()));
		p.sendMessage(Spawners.mm("<dark_gray>» <color:" + c.color() + "><bold>" + c.name() + "</bold></color> <gray>gave you " + got));
		if (r.weight() / c.total() * 100 < 3) {
			Bukkit.broadcast(Spawners.mm("<dark_gray>» <white>" + p.getName() + " <gray>pulled " + r.label() + " <gray>from the <color:" + c.color() + "><bold>"
					+ c.name() + "</bold></color><gray>!"));
		}
	}

	@EventHandler
	public void onClick(InventoryClickEvent e) {
		if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
	}

	@EventHandler
	public void onDrag(InventoryDragEvent e) {
		if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
	}

	// ---------------------------------------------------------------- votes (NuVotifier, optional)

	@SuppressWarnings("unchecked")
	private void hookVotes() {
		try {
			Class<? extends org.bukkit.event.Event> ev = (Class<? extends org.bukkit.event.Event>) Class.forName("com.vexsoftware.votifier.model.VotifierEvent");
			Bukkit.getPluginManager().registerEvent(ev, this, EventPriority.NORMAL, (listener, event) -> {
				try {
					if (!ev.isInstance(event)) return;
					Object vote = ev.getMethod("getVote").invoke(event);
					String name = String.valueOf(vote.getClass().getMethod("getUsername").invoke(vote));
					voted(name);
				} catch (Throwable t) {
					plugin.getLogger().warning("Vote handling failed: " + t);
				}
			}, plugin);
			plugin.getLogger().info("Votes hooked: each vote gives a vote key");
		} catch (ClassNotFoundException e) {
			plugin.getLogger().info("NuVotifier not installed: votes won't give keys (crate give <player> vote 1 still works)");
		}
	}

	private void voted(String name) {
		if (!crates.containsKey("vote")) return;
		Player on = Bukkit.getPlayerExact(name);
		OfflinePlayer op = on != null ? on : Bukkit.getOfflinePlayerIfCached(name);
		if (op == null) {
			plugin.getLogger().info("Vote for unknown player " + name + " ignored");
			return;
		}
		give(op.getUniqueId(), "vote", 1);
		Bukkit.broadcast(Spawners.mm("<dark_gray>» <white>" + name + " <gray>voted and got a <color:#7CFF6B><bold>Vote key</bold></color><gray>. <#9A9A9A>/vote"));
	}

	// ---------------------------------------------------------------- commands

	@Override
	public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
		if (cmd.getName().equalsIgnoreCase("keys") || a.length == 0 || !s.hasPermission("lag.skyblock.admin")) {
			if (!(s instanceof Player p)) {
				s.sendMessage("crate give <player> <crate> [amount] | crate giveall <crate> [amount] | crate reload   (crates: " + String.join(", ", crates.keySet()) + ")");
				return true;
			}
			p.sendMessage(Spawners.mm("<gradient:#B040FF:#38E0FF><bold>YOUR KEYS</bold></gradient>"));
			for (Crate c : crates.values()) {
				p.sendMessage(Spawners.mm("<dark_gray>▸ <color:" + c.color() + ">" + c.name() + " <dark_gray>× <white>" + keys(p.getUniqueId(), c.id())));
			}
			p.sendMessage(Spawners.mm("<gray>Open them at the Crate Vaults: follow the south-east bridge from spawn."));
			return true;
		}
		String sub = a[0].toLowerCase(Locale.ROOT);
		if (sub.equals("reload")) {
			load();
			shutdown();
			s.sendMessage("Crates reloaded: " + String.join(", ", crates.keySet()));
			return true;
		}
		if (sub.equals("give") && a.length >= 3) {
			String crate = a[2].toLowerCase(Locale.ROOT);
			if (!crates.containsKey(crate)) {
				s.sendMessage("No crate '" + crate + "'. Crates: " + String.join(", ", crates.keySet()));
				return true;
			}
			Player on = Bukkit.getPlayerExact(a[1]);
			OfflinePlayer op = on != null ? on : Bukkit.getOfflinePlayerIfCached(a[1]);
			if (op == null) {
				s.sendMessage("Unknown player " + a[1] + " (they have to have joined once)");
				return true;
			}
			int n = a.length >= 4 ? (int) num(a[3], 1) : 1;
			give(op.getUniqueId(), crate, n);
			s.sendMessage("Gave " + a[1] + " " + n + "x " + crate + " key");
			return true;
		}
		if (sub.equals("giveall") && a.length >= 2) {
			String crate = a[1].toLowerCase(Locale.ROOT);
			if (!crates.containsKey(crate)) {
				s.sendMessage("No crate '" + crate + "'. Crates: " + String.join(", ", crates.keySet()));
				return true;
			}
			int n = a.length >= 3 ? (int) num(a[2], 1) : 1;
			for (Player p : Bukkit.getOnlinePlayers()) give(p.getUniqueId(), crate, n);
			s.sendMessage("Gave everyone online " + n + "x " + crate + " key");
			return true;
		}
		s.sendMessage("crate give <player> <crate> [amount] | crate giveall <crate> [amount] | crate reload");
		return true;
	}
}
