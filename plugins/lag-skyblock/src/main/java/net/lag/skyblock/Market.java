package net.lag.skyblock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
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

import net.kyori.adventure.text.Component;

/**
 * The Market (/market, the Merchant at spawn): buy-only basics and grinder parts, priced in Shards (config market).
 * Selling is the Collector's job. Left-click 1, right-click 16, shift-click 64.
 */
final class Market implements Listener, CommandExecutor {
	private final LagSkyblock plugin;
	private static final int[] TABS = {1, 3, 5, 7};
	private static final int CLOSE = 49;

	private static final class Menu implements InventoryHolder {
		Inventory inv;
		int tab;
		final List<Material> shown = new ArrayList<>();

		@Override
		public Inventory getInventory() {
			return inv;
		}
	}

	Market(LagSkyblock plugin) {
		this.plugin = plugin;
	}

	private Map<String, Map<Material, Double>> categories() {
		Map<String, Map<Material, Double>> out = new LinkedHashMap<>();
		ConfigurationSection s = plugin.getConfig().getConfigurationSection("market");
		if (s == null) return out;
		for (String cat : s.getKeys(false)) {
			ConfigurationSection c = s.getConfigurationSection(cat);
			if (c == null) continue;
			Map<Material, Double> items = new LinkedHashMap<>();
			for (String k : c.getKeys(false)) {
				Material m = Material.matchMaterial(k);
				if (m != null && m.isItem()) items.put(m, c.getDouble(k));
			}
			out.put(cat, items);
		}
		return out;
	}

	void open(Player p) {
		Menu m = new Menu();
		m.inv = Bukkit.createInventory(m, 54, Spawners.mm("<gradient:#7CFF6B:#38E0FF><bold>MARKET</bold></gradient> <dark_gray>· " + LagSkyblock.shards(bal(p))));
		draw(p, m);
		p.openInventory(m.inv);
		p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 0.6f, 1.1f);
	}

	private double bal(Player p) {
		return plugin.eco() == null ? 0 : plugin.eco().getBalance(p);
	}

	private static ItemStack pane(Material mat) {
		ItemStack it = new ItemStack(mat);
		ItemMeta im = it.getItemMeta();
		im.setHideTooltip(true);
		it.setItemMeta(im);
		return it;
	}

	private static ItemStack named(Material mat, String name, List<String> lore, boolean glow) {
		ItemStack it = new ItemStack(mat);
		ItemMeta im = it.getItemMeta();
		im.displayName(Spawners.mm(name));
		List<Component> l = new ArrayList<>();
		for (String s : lore) l.add(Spawners.mm(s));
		im.lore(l);
		if (glow) im.setEnchantmentGlintOverride(true);
		it.setItemMeta(im);
		return it;
	}

	private static final Material[] TAB_ICONS = {Material.BRICKS, Material.WHEAT, Material.HOPPER, Material.CRAFTING_TABLE};

	private void draw(Player p, Menu m) {
		Inventory inv = m.inv;
		for (int i = 0; i < 54; i++) inv.setItem(i, pane(Material.BLACK_STAINED_GLASS_PANE));
		for (int i = 0; i < 9; i++) inv.setItem(i, pane(i % 2 == 0 ? Material.LIME_STAINED_GLASS_PANE : Material.CYAN_STAINED_GLASS_PANE));
		var cats = new ArrayList<>(categories().entrySet());
		for (int i = 0; i < cats.size() && i < TABS.length; i++) {
			boolean on = i == m.tab;
			inv.setItem(TABS[i], named(TAB_ICONS[i % TAB_ICONS.length], (on ? "<#7CFF6B>▶ " : "<gray>") + "<bold>" + cats.get(i).getKey().toUpperCase(),
					List.of("<dark_gray>" + cats.get(i).getValue().size() + " items"), on));
		}
		m.shown.clear();
		if (m.tab < cats.size()) {
			int slot = 9;
			for (var e : cats.get(m.tab).getValue().entrySet()) {
				if (slot > 44) break;
				m.shown.add(e.getKey());
				String each = e.getValue() >= 100 ? LagSkyblock.shards(e.getValue()) : LagSkyblock.symbol + String.format("%,.0f", e.getValue());
				inv.setItem(slot++, named(e.getKey(), "<white>" + pretty(e.getKey()), List.of(
						"<gray>Price: <gold>" + each + " <gray>each",
						"",
						"<#38E0FF>▶ Left <gray>1  <#38E0FF>▶ Right <gray>16  <#38E0FF>▶ Shift <gray>64"), false));
			}
		}
		inv.setItem(CLOSE, named(Material.BARRIER, "<red>Close", List.of(), false));
	}

	private static String pretty(Material m) {
		String s = m.name().toLowerCase().replace('_', ' ');
		StringBuilder b = new StringBuilder();
		for (String w : s.split(" ")) b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
		return b.toString().trim();
	}

	private void buy(Player p, Material mat, double each, int amount) {
		if (plugin.eco() == null) {
			p.sendMessage(Spawners.mm("<red>The economy isn't running right now."));
			return;
		}
		amount = Math.min(amount, mat.getMaxStackSize() == 1 ? 1 : amount);
		double cost = each * amount;
		if (!plugin.eco().has(p, cost)) {
			p.sendMessage(Spawners.mm("<red>You need <white>" + LagSkyblock.shards(cost) + "</white> for " + amount + "× " + pretty(mat) + "."));
			p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.7f);
			return;
		}
		var left = p.getInventory().addItem(new ItemStack(mat, amount));
		int given = amount - left.values().stream().mapToInt(ItemStack::getAmount).sum();
		if (given <= 0) {
			p.sendMessage(Spawners.mm("<red>Make room in your inventory first."));
			return;
		}
		plugin.eco().withdrawPlayer(p, each * given);
		p.sendActionBar(Spawners.mm("<#7CFF6B>Bought " + given + "× " + pretty(mat) + " <dark_gray>· <gold>-" + LagSkyblock.shards(each * given)));
		p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.2f);
	}

	@EventHandler
	public void onClick(InventoryClickEvent e) {
		if (!(e.getInventory().getHolder() instanceof Menu m)) return;
		e.setCancelled(true);
		if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
		int s = e.getRawSlot();
		if (s == CLOSE) {
			p.closeInventory();
			return;
		}
		for (int i = 0; i < TABS.length; i++) {
			if (TABS[i] == s) {
				m.tab = i;
				draw(p, m);
				return;
			}
		}
		int idx = s - 9;
		if (idx < 0 || idx >= m.shown.size()) return;
		Material mat = m.shown.get(idx);
		var cats = new ArrayList<>(categories().values());
		if (m.tab >= cats.size()) return;
		Double each = cats.get(m.tab).get(mat);
		if (each == null) return;
		int amount = e.isShiftClick() ? 64 : e.getClick() == ClickType.RIGHT ? 16 : 1;
		buy(p, mat, each, amount);
	}

	@EventHandler
	public void onDrag(InventoryDragEvent e) {
		if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
	}

	@Override
	public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
		if (s instanceof Player p) open(p);
		return true;
	}
}
