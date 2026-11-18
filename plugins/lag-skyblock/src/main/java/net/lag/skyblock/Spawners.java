package net.lag.skyblock;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.lag.skyblock.LagSkyblock.Tier;

/**
 * /spawners: the tier ladder. Tier 1 is open to everyone; trade in heads of the tier below to unlock the next one.
 * Unlocked tiers can be bought (left = 1, right = 8). Heads are proof only: they never sell.
 */
final class Spawners implements Listener, CommandExecutor {
	// the path through the menu: across the top row, down the right, back along the bottom row
	private static final int[] PATH = {10, 11, 12, 13, 14, 15, 16, 34, 33, 32, 31, 30, 29, 28};
	private static final int LINK = 25; // connects 16 and 34
	private static final int INFO = 47, PROOF = 49, CLOSE = 51;

	private final LagSkyblock plugin;
	private final NamespacedKey headKey;
	private final Map<UUID, Integer> highest = new HashMap<>(); // highest unlocked tier number (1-based)
	private final File file;

	private static final class Menu implements InventoryHolder {
		Inventory inv;

		@Override
		public Inventory getInventory() {
			return inv;
		}
	}

	Spawners(LagSkyblock plugin) {
		this.plugin = plugin;
		this.headKey = NamespacedKey.fromString(LagSkyblock.HEAD_KEY);
		this.file = new File(plugin.getDataFolder(), "unlocks.yml");
		load();
	}

	static Component mm(String s) {
		return MiniMessage.miniMessage().deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
	}

	void load() {
		highest.clear();
		YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
		for (String k : y.getKeys(false)) {
			try {
				highest.put(UUID.fromString(k), y.getInt(k));
			} catch (IllegalArgumentException ignored) { }
		}
	}

	void save() {
		YamlConfiguration y = new YamlConfiguration();
		highest.forEach((k, v) -> y.set(k.toString(), v));
		try {
			y.save(file);
		} catch (Exception e) {
			plugin.getLogger().warning("Could not save unlocks.yml: " + e);
		}
	}

	private int top(Player p) {
		return Math.max(1, highest.getOrDefault(p.getUniqueId(), 1));
	}

	private static String money(double d) {
		return LagSkyblock.shards(d);
	}

	// ---------------------------------------------------------------- heads

	/** Tier of a head item, or null if it isn't one of ours. */
	private Tier headTier(ItemStack it) {
		if (it == null || it.getType() != Material.PLAYER_HEAD || !it.hasItemMeta()) return null;
		String mob = it.getItemMeta().getPersistentDataContainer().get(headKey, PersistentDataType.STRING);
		if (mob == null) return null;
		for (Tier t : plugin.tiers) if (t.mob().equals(mob)) return t;
		return null;
	}

	private int countHeads(Player p, Tier t) {
		int n = 0;
		for (ItemStack it : p.getInventory().getStorageContents()) {
			if (headTier(it) == t) n += it.getAmount();
		}
		return n;
	}

	private void takeHeads(Player p, Tier t, int amount) {
		ItemStack[] inv = p.getInventory().getStorageContents();
		for (int i = 0; i < inv.length && amount > 0; i++) {
			if (headTier(inv[i]) != t) continue;
			int take = Math.min(amount, inv[i].getAmount());
			inv[i].setAmount(inv[i].getAmount() - take);
			amount -= take;
		}
		p.getInventory().setStorageContents(inv);
	}

	// ---------------------------------------------------------------- menu

	void open(Player p) {
		Menu m = new Menu();
		m.inv = Bukkit.createInventory(m, 54, mm("<gradient:#B040FF:#38E0FF><bold>SPAWNERS</bold></gradient> <dark_gray>· tier " + top(p) + "/" + plugin.tiers.size()));
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
		im.displayName(mm(name));
		List<Component> l = new ArrayList<>();
		for (String s : lore) l.add(mm(s));
		im.lore(l);
		it.setItemMeta(im);
		return it;
	}

	private void draw(Player p, Inventory inv) {
		int top = top(p);
		for (int i = 0; i < 54; i++) inv.setItem(i, pane(Material.BLACK_STAINED_GLASS_PANE));
		for (int i = 0; i < 9; i++) inv.setItem(i, pane(i % 2 == 0 ? Material.PURPLE_STAINED_GLASS_PANE : Material.MAGENTA_STAINED_GLASS_PANE));
		List<Tier> tiers = plugin.tiers;
		for (int i = 0; i < tiers.size() && i < PATH.length; i++) inv.setItem(PATH[i], tierItem(p, tiers.get(i), top));
		if (tiers.size() > 7) inv.setItem(LINK, pane(top >= 8 ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE));
		// unused path slots stay dark
		inv.setItem(INFO, named(Material.BOOK, "<#38E0FF><bold>HOW IT WORKS", List.of(
				"<gray>Cages spawn real mobs that stack up.",
				"<gray>Build a <white>grinder</white>: drop or burn the stack",
				"<gray>and the whole stack dies into your hoppers.",
				"<gray>Sell the loot to <white>the Collector</white> (/sell).",
				"",
				"<gray>1 in 100 mobs drops its <white>head</white>: proof.",
				"<gray>Trade heads in to unlock the <white>next cage</white>.",
				"",
				"<dark_gray>Stack spawners by placing them on each other.")));
		// next unlock progress
		if (top < plugin.tiers.size()) {
			Tier next = plugin.tiers.get(top), cur = plugin.tiers.get(top - 1);
			int have = countHeads(p, cur);
			int pct = (int) Math.min(100, have * 100L / Math.max(1, next.unlock()));
			String bar = "<#7CFF6B>" + "|".repeat(pct / 5) + "<dark_gray>" + "|".repeat(20 - pct / 5);
			inv.setItem(PROOF, named(Material.SKELETON_SKULL, "<#FFB020><bold>NEXT CAGE: " + next.name().toUpperCase(), List.of(
					"<gray>Proof needed: <white>" + next.unlock() + "× " + cur.name() + " Head",
					"<gray>You carry: <white>" + have,
					bar + " <gray>" + pct + "%",
					"",
					"<dark_gray>Heads drop from " + cur.name() + " cages.")));
		} else {
			inv.setItem(PROOF, named(Material.NETHER_STAR, "<gradient:#FFD84D:#FF8A3D><bold>EVERY CAGE UNLOCKED", List.of(
					"<gray>You've mastered the whole ladder.", "<gray>Now stack them: <white>/is top")));
		}
		inv.setItem(CLOSE, named(Material.BARRIER, "<red>Close", List.of()));
	}

	private ItemStack tierItem(Player p, Tier t, int top) {
		int n = t.number();
		String head = "<color:" + t.color() + ">";
		if (n <= top) {
			ItemStack it = Bukkit.getItemFactory().createItemStack(plugin.headSpec(t, false));
			ItemMeta im = it.getItemMeta();
			im.displayName(mm(head + "<bold>" + t.name().toUpperCase() + "</bold> <dark_gray>· <gray>Tier " + n));
			im.lore(lore(List.of(
					"<#7CFF6B>✔ Unlocked",
					"",
					"<gray>Cage: <white>" + money(t.price()),
					"<gray>Loot: <gold>" + money(t.value() * 360) + "<gray>/hour per cage",
					"",
					"<#38E0FF>▶ Left-click <gray>buy 1",
					"<#38E0FF>▶ Right-click <gray>buy 8")));
			im.setEnchantmentGlintOverride(true);
			it.setItemMeta(im);
			return it;
		}
		if (n == top + 1) {
			Tier prev = plugin.tiers.get(t.index() - 1);
			int have = countHeads(p, prev);
			boolean ready = have >= t.unlock();
			ItemStack it = Bukkit.getItemFactory().createItemStack(plugin.headSpec(t, false));
			ItemMeta im = it.getItemMeta();
			im.displayName(mm(head + "<bold>" + t.name().toUpperCase() + "</bold> <dark_gray>· <gray>Tier " + n));
			im.lore(lore(List.of(
					"<#FFB020>🔒 Locked",
					"",
					"<gray>Unlock with <white>" + t.unlock() + "× " + prev.name() + " Head",
					"<gray>You have: " + (ready ? "<#7CFF6B>" : "<red>") + have + "<gray>/" + t.unlock(),
					"",
					"<gray>Cage: <white>" + money(t.price()),
					"<gray>Loot: <gold>" + money(t.value() * 360) + "<gray>/hour per cage",
					"",
					ready ? "<#7CFF6B>▶ Click to unlock" : "<dark_gray>Collect more " + prev.name() + " heads")));
			it.setItemMeta(im);
			return it;
		}
		return named(Material.GRAY_DYE, "<dark_gray><bold>???</bold> <dark_gray>· Tier " + n, List.of(
				"<dark_gray>Unlock the tier before this first."));
	}

	private static List<Component> lore(List<String> lines) {
		List<Component> l = new ArrayList<>();
		for (String s : lines) l.add(mm(s));
		return l;
	}

	@EventHandler
	public void onClick(InventoryClickEvent e) {
		if (!(e.getInventory().getHolder() instanceof Menu)) return;
		e.setCancelled(true);
		if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
		int slot = e.getRawSlot();
		if (slot == CLOSE) {
			p.closeInventory();
			return;
		}
		for (int i = 0; i < plugin.tiers.size() && i < PATH.length; i++) {
			if (PATH[i] != slot) continue;
			Tier t = plugin.tiers.get(i);
			int top = top(p);
			if (t.number() <= top) buy(p, t, e.getClick() == ClickType.RIGHT || e.getClick() == ClickType.SHIFT_RIGHT ? 8 : 1);
			else if (t.number() == top + 1) unlock(p, t);
			else p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.7f);
			draw(p, e.getInventory());
			return;
		}
	}

	/** The tier head players collect: proof that counts toward the next unlock. */
	ItemStack head(Tier t) {
		try {
			return Bukkit.getItemFactory().createItemStack(plugin.headSpec(t, true));
		} catch (Exception e) {
			return null;
		}
	}

	/** A short note when a spawner goes down. */
	@EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
	public void onPlace(org.bukkit.event.block.BlockPlaceEvent e) {
		if (e.getBlockPlaced().getType() != Material.SPAWNER) return;
		Player p = e.getPlayer();
		Bukkit.getScheduler().runTask(plugin, () -> {
			// stacking onto an existing spawner cancels the place; RoseStacker shows the new stack size then
			if (e.getBlockPlaced().getType() == Material.SPAWNER) {
				p.sendActionBar(mm("<#7CFF6B>Spawner placed <dark_gray>· <gray>mobs spawn while you're nearby. Build a grinder: drop them, burn them."));
			}
		});
	}

	@EventHandler
	public void onDrag(InventoryDragEvent e) {
		if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
	}

	private void unlock(Player p, Tier t) {
		Tier prev = plugin.tiers.get(t.index() - 1);
		if (countHeads(p, prev) < t.unlock()) {
			p.sendMessage(mm("<red>You need <white>" + t.unlock() + "× " + prev.name() + " Head</white> to unlock " + t.name() + "."));
			p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.7f);
			return;
		}
		takeHeads(p, prev, t.unlock());
		highest.put(p.getUniqueId(), t.number());
		save();
		p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
		p.showTitle(net.kyori.adventure.title.Title.title(mm("<color:" + t.color() + "><bold>" + t.name().toUpperCase() + " UNLOCKED"),
				mm("<gray>Tier " + t.number() + " spawners are now for sale")));
		Bukkit.broadcast(mm("<dark_gray>» <white>" + p.getName() + " <gray>unlocked <color:" + t.color() + "><bold>" + t.name() + "</bold> <gray>spawners <dark_gray>(tier " + t.number() + ")"));
	}

	private int spawnerCount(Player p) {
		int n = 0;
		for (ItemStack it : p.getInventory().getStorageContents()) if (it != null && it.getType() == Material.SPAWNER) n += it.getAmount();
		return n;
	}

	private void buy(Player p, Tier t, int amount) {
		if (plugin.eco() == null) {
			p.sendMessage(mm("<red>The economy isn't running right now."));
			return;
		}
		double cost = t.price() * amount;
		if (!plugin.eco().has(p, cost)) {
			p.sendMessage(mm("<red>You need <white>" + money(cost) + "</white> for " + amount + "× " + t.name() + " spawner."));
			p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.7f);
			return;
		}
		if (p.getInventory().firstEmpty() == -1) {
			p.sendMessage(mm("<red>Make room in your inventory first."));
			return;
		}
		int before = spawnerCount(p);
		// RoseStacker: /rs give spawner <player> <type> <stackSize> [amount] -> `amount` single spawners
		Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "rs give spawner " + p.getName() + " " + t.mob() + " 1 " + amount);
		if (spawnerCount(p) <= before) {
			p.sendMessage(mm("<red>Couldn't give the spawner - you were not charged. Tell staff."));
			plugin.getLogger().warning("RoseStacker give failed for " + t.mob());
			return;
		}
		plugin.eco().withdrawPlayer(p, cost);
		p.sendMessage(mm("<#7CFF6B>Bought <white>" + amount + "× <color:" + t.color() + ">" + t.name() + " Spawner</color></white> for <gold>" + money(cost)));
		p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);
	}

	@Override
	public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
		if (!(s instanceof Player p)) {
			s.sendMessage("Players only.");
			return true;
		}
		open(p);
		return true;
	}
}
