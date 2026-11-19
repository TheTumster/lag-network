package net.lag.skyblock;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.kyori.adventure.text.Component;
import net.lag.skyblock.LagSkyblock.Tier;

/**
 * Cages run 24/7. While nobody is near a cage (so its island isn't loaded and RoseStacker can't spawn real mobs), we
 * count the mobs it would have made, at the same rate, and bank their loot value and heads in the island's Offline
 * Vault. Anyone on the island opens it (/vault, or it pops up on login): Sell all pays the clicker, Claim heads gives
 * the heads as items. With someone nearby the cage spawns real mobs into the grinder as normal, so it's never both.
 */
final class Vault implements Listener, CommandExecutor {
	private static final int SELL = 38, CLOSE = 40, HEADS = 42, INFO = 4;

	private final LagSkyblock plugin;
	private final File file;
	/** island id -> per tier index: [mobs counted, shards banked, heads banked (fractional)] */
	private final Map<String, double[][]> banks = new HashMap<>();
	private boolean dirty;

	private static final class Menu implements InventoryHolder {
		Inventory inv;
		String island;

		@Override
		public Inventory getInventory() {
			return inv;
		}
	}

	Vault(LagSkyblock plugin) {
		this.plugin = plugin;
		this.file = new File(plugin.getDataFolder(), "vault.yml");
		load();
		Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1200L, 1200L);
	}

	private boolean enabled() {
		return plugin.getConfig().getBoolean("offline-cages.enabled", true);
	}

	// ---------------------------------------------------------------- storage

	private double[][] bank(String id) {
		return banks.computeIfAbsent(id, k -> new double[plugin.tiers.size()][3]);
	}

	private void load() {
		YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
		for (String id : y.getKeys(false)) {
			var sec = y.getConfigurationSection(id);
			if (sec == null) continue;
			double[][] b = bank(id);
			for (Tier t : plugin.tiers) {
				List<Double> v = sec.getDoubleList(t.mob());
				if (v.size() >= 3) for (int i = 0; i < 3; i++) b[t.index()][i] = v.get(i);
			}
		}
	}

	void save() {
		if (!dirty) return;
		YamlConfiguration y = new YamlConfiguration();
		banks.forEach((id, b) -> {
			for (Tier t : plugin.tiers) {
				double[] r = b[t.index()];
				if (r[0] > 0 || r[1] > 0 || r[2] > 0) y.set(id + "." + t.mob(), List.of(r[0], r[1], r[2]));
			}
		});
		try {
			y.save(file);
			dirty = false;
		} catch (Exception e) {
			plugin.getLogger().warning("Couldn't save vault.yml: " + e);
		}
	}

	// ---------------------------------------------------------------- counting

	private Tier tier(String mob) {
		for (Tier t : plugin.tiers) if (t.mob().equals(mob)) return t;
		return null;
	}

	/** Mobs a single cage makes per minute, from the RoseStacker settings in config. */
	private double perMinute() {
		double delay = Math.max(20, plugin.getConfig().getDouble("rosestacker.spawn-delay", 400));
		double count = plugin.getConfig().getDouble("rosestacker.spawn-count", 2);
		return count * (1200.0 / delay) * plugin.getConfig().getDouble("offline-cages.rate", 1.0);
	}

	/** Every minute: bank one minute of mobs for every cage nobody is standing near. */
	private void tick() {
		if (!enabled() || plugin.top == null) {
			save();
			return;
		}
		double range = plugin.getConfig().getDouble("rosestacker.player-activation-range", 24) + 2;
		double rangeSq = range * range;
		double perMin = perMinute();
		double headChance = plugin.getConfig().getDouble("head-chance", 1.0) / 100.0;
		for (IslandTop.Rec r : plugin.top.spawners()) {
			Tier t = tier(r.mob());
			if (t == null || r.stack() <= 0) continue;
			World w = Bukkit.getWorld(r.world());
			if (w == null) continue;
			Location at = new Location(w, r.x() + 0.5, r.y() + 0.5, r.z() + 0.5);
			if (w.isChunkLoaded(r.x() >> 4, r.z() >> 4) && someoneNear(w, at, rangeSq)) continue; // real mobs spawn
			String id = Islands.id(Islands.at(at));
			if (id == null) continue;
			double mobs = perMin * r.stack();
			double[] b = bank(id)[t.index()];
			b[0] += mobs;
			b[1] += mobs * t.value();
			b[2] += mobs * headChance;
			dirty = true;
		}
		save();
	}

	private static boolean someoneNear(World w, Location at, double rangeSq) {
		for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(at) <= rangeSq) return true;
		return false;
	}

	// ---------------------------------------------------------------- the menu

	/** The island this player can open the vault of: the one they stand on if a member, else their own. */
	private Object islandOf(Player p) {
		Object here = Islands.at(p.getLocation());
		if (here != null && Islands.member(here, p.getUniqueId())) return here;
		World w = Islands.islandWorld();
		return w == null ? null : Islands.of(w, p.getUniqueId());
	}

	private double total(String id, int col) {
		double[][] b = banks.get(id);
		if (b == null) return 0;
		double s = 0;
		for (double[] r : b) s += col == 2 ? Math.floor(r[2]) : r[col];
		return s;
	}

	void open(Player p, boolean welcome) {
		Object island = islandOf(p);
		String id = Islands.id(island);
		if (id == null) {
			p.sendMessage(Spawners.mm("<red>You don't have an island yet. <gray>(/is create)"));
			return;
		}
		Menu m = new Menu();
		m.island = id;
		m.inv = Bukkit.createInventory(m, 45, welcome ? Gui.title("WHILE YOU WERE AWAY", null, "#4B1E8C") : Gui.title("OFFLINE VAULT", null, "#4B1E8C"));
		draw(m);
		p.openInventory(m.inv);
		p.playSound(p.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 0.5f, 1.3f);
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

	private void draw(Menu m) {
		Gui.frame(m.inv, Material.PURPLE_STAINED_GLASS_PANE);
		double[][] b = banks.get(m.island);
		List<Tier> show = new ArrayList<>();
		if (b != null) for (Tier t : plugin.tiers) {
			double[] r = b[t.index()];
			if (r[0] >= 1 || r[1] > 0 || r[2] >= 1) show.add(t);
		}
		List<Integer> slots = Gui.balanced(1, 3, show.size());
		int slot = 0;
		if (b != null) {
			for (Tier t : show) {
				double[] r = b[t.index()];
				if (slot >= slots.size()) break;
				ItemStack it;
				try {
					it = Bukkit.getItemFactory().createItemStack(plugin.headSpec(t, false));
				} catch (Exception e) {
					it = new ItemStack(Material.SPAWNER);
				}
				ItemMeta im = it.getItemMeta();
				im.displayName(Spawners.mm("<color:" + t.color() + "><bold>" + t.name().toUpperCase()));
				im.lore(List.of(
						Spawners.mm("<gray>Mobs: <white>" + String.format("%,d", (long) r[0])),
						Spawners.mm("<gray>Loot worth: <gold>" + LagSkyblock.shards(r[1])),
						Spawners.mm("<gray>Heads: <white>" + (long) Math.floor(r[2]))));
				it.setItemMeta(im);
				m.inv.setItem(slots.get(slot++), it);
			}
		}
		if (slot == 0) {
			m.inv.setItem(22, named(Material.GLASS_BOTTLE, "<white>Nothing banked yet", List.of(
					"<#9A9A9A>Your cages bank loot here while", "<#9A9A9A>nobody is near them.")));
		}
		m.inv.setItem(INFO, named(Material.CLOCK, "<#38E0FF><bold>YOUR CAGES NEVER STOP", List.of(
				"<gray>When nobody's near your cages they keep",
				"<gray>going at full speed and bank here instead.",
				"<gray>When you're there, real mobs spawn",
				"<gray>into your grinder as normal.",
				"",
				"<#9A9A9A>Shared with your whole island.")));
		double shards = total(m.island, 1);
		long heads = (long) total(m.island, 2);
		m.inv.setItem(SELL, named(shards > 0 ? Material.GOLD_BLOCK : Material.GRAY_DYE, "<gold><bold>SELL ALL", List.of(
				"<gray>The Collector pays <gold>" + LagSkyblock.shards(shards),
				"",
				shards > 0 ? "<#7CFF6B>▶ Click to sell" : "<#9A9A9A>Nothing to sell")));
		m.inv.setItem(HEADS, named(heads > 0 ? Material.SKELETON_SKULL : Material.GRAY_DYE, "<#FFB020><bold>CLAIM HEADS", List.of(
				"<gray>Heads banked: <white>" + heads,
				"<gray>Proof for the next cage <white>(/spawners)",
				"",
				heads > 0 ? "<#7CFF6B>▶ Click to take them" : "<#9A9A9A>No heads yet")));
		m.inv.setItem(CLOSE, named(Material.BARRIER, "<red>Close", List.of()));
	}

	@EventHandler
	public void onClick(InventoryClickEvent e) {
		if (!(e.getInventory().getHolder() instanceof Menu m)) return;
		e.setCancelled(true);
		if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
		switch (e.getRawSlot()) {
			case SELL -> sellAll(p, m.island);
			case HEADS -> claimHeads(p, m.island);
			case CLOSE -> {
				p.closeInventory();
				return;
			}
			default -> {
				return;
			}
		}
		draw(m);
	}

	@EventHandler
	public void onDrag(InventoryDragEvent e) {
		if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
	}

	private void sellAll(Player p, String id) {
		double[][] b = banks.get(id);
		double shards = total(id, 1);
		if (b == null || shards <= 0 || plugin.eco() == null) {
			p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
			return;
		}
		for (double[] r : b) {
			r[0] = 0;
			r[1] = 0;
		}
		dirty = true;
		save();
		plugin.eco().depositPlayer(p, shards);
		p.sendMessage(Spawners.mm("<#9A9A9A>» <gray>The Collector bought your cages' loot for <gold><bold>" + LagSkyblock.shards(shards)));
		p.sendActionBar(Spawners.mm("<gold>+" + LagSkyblock.shards(shards)));
		p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.3f);
	}

	private void claimHeads(Player p, String id) {
		double[][] b = banks.get(id);
		if (b == null || plugin.spawners == null) return;
		long given = 0;
		boolean full = false;
		for (Tier t : plugin.tiers) {
			double[] r = b[t.index()];
			int n = (int) Math.min(Integer.MAX_VALUE, Math.floor(r[2]));
			if (n <= 0) continue;
			ItemStack head = plugin.spawners.head(t);
			if (head == null) continue;
			int left = n;
			while (left > 0) {
				ItemStack h = head.clone();
				h.setAmount(Math.min(left, h.getMaxStackSize()));
				int want = h.getAmount();
				var over = p.getInventory().addItem(h);
				int kept = over.values().stream().mapToInt(ItemStack::getAmount).sum();
				left -= want - kept;
				if (kept > 0) {
					full = true;
					break;
				}
			}
			r[2] -= n - left;
			given += n - left;
			if (full) break;
		}
		if (given > 0) {
			dirty = true;
			save();
			p.sendMessage(Spawners.mm("<#9A9A9A>» <gray>Took <white>" + given + "</white> heads from the vault."));
			p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.7f, 1.0f);
		}
		if (full) p.sendMessage(Spawners.mm("<red>Your inventory is full - the rest stay in the vault."));
	}

	/** On join: if the island banked anything, say so and pop the vault open. */
	@EventHandler
	public void onJoin(PlayerJoinEvent e) {
		if (!enabled()) return;
		Player p = e.getPlayer();
		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			if (!p.isOnline()) return;
			World w = Islands.islandWorld();
			String id = Islands.id(w == null ? null : Islands.of(w, p.getUniqueId()));
			if (id == null) return;
			double shards = total(id, 1);
			long heads = (long) total(id, 2);
			if (shards < 1 && heads < 1) return;
			p.sendMessage(Spawners.mm("<#9A9A9A>» <gray>While you were away your cages banked <gold>" + LagSkyblock.shards(shards)
					+ "</gold> <gray>and <white>" + heads + "</white> heads. <#38E0FF><click:run_command:'/vault'><hover:show_text:'Open the vault'>[OPEN VAULT]</hover></click>"));
			if (p.getOpenInventory().getTopInventory().getHolder() == null || p.getOpenInventory().getType() == org.bukkit.event.inventory.InventoryType.CRAFTING) {
				open(p, true);
			}
		}, 60L);
	}

	@Override
	public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
		if (!(s instanceof Player p)) {
			s.sendMessage("Players only.");
			return true;
		}
		open(p, false);
		return true;
	}

}
