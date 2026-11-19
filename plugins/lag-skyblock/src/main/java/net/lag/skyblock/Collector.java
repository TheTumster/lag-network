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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.kyori.adventure.text.Component;

/**
 * The Collector: the only buyer on Skyblock. One price list: spawner loot (tagged, priced from the cage ladder),
 * gen ore drops and crops (config collector.ores / collector.crops). /sell sells everything sellable you carry,
 * /sell hand the stack in your hand, /collector (or clicking him at spawn) opens his price list.
 */
final class Collector implements Listener, CommandExecutor {
	private final LagSkyblock plugin;
	/** tagged spawner loot: material -> [price, tier index] */
	private final Map<Material, double[]> loot = new LinkedHashMap<>();
	private final Map<Material, Double> ores = new LinkedHashMap<>(), crops = new LinkedHashMap<>();
	private static final int TAB_LOOT = 2, TAB_ORES = 4, TAB_CROPS = 6, HAND = 46, ALL = 48, WORTH = 50, CLOSE = 52;

	private static final class Menu implements InventoryHolder {
		Inventory inv;
		int tab = TAB_LOOT;

		@Override
		public Inventory getInventory() {
			return inv;
		}
	}

	Collector(LagSkyblock plugin) {
		this.plugin = plugin;
		load();
	}

	void load() {
		loot.clear();
		ores.clear();
		crops.clear();
		for (LagSkyblock.Tier t : plugin.tiers) for (Material m : t.loot().keySet()) loot.put(m, new double[] {t.itemPrice(), t.index()});
		read("collector.ores", ores);
		read("collector.crops", crops);
	}

	private void read(String path, Map<Material, Double> into) {
		ConfigurationSection s = plugin.getConfig().getConfigurationSection(path);
		if (s == null) return;
		for (String k : s.getKeys(false)) {
			Material m = Material.matchMaterial(k);
			if (m != null) into.put(m, s.getDouble(k));
		}
	}

	/** What one of this item sells for, 0 if the Collector won't take it. */
	double price(ItemStack it) {
		if (it == null || it.getType().isAir()) return 0;
		if (Loot.isLoot(it)) {
			double[] p = loot.get(it.getType());
			return p == null ? 0 : p[0];
		}
		if (it.hasItemMeta() && (it.getItemMeta().hasDisplayName() || it.getItemMeta().hasEnchants())) return 0; // named/enchanted gear isn't scrap
		Double p = ores.get(it.getType());
		if (p == null) p = crops.get(it.getType());
		return p == null ? 0 : p;
	}

	double worth(Player p) {
		double total = 0;
		for (ItemStack it : p.getInventory().getStorageContents()) total += price(it) * (it == null ? 0 : it.getAmount());
		return total;
	}

	/** Sells every sellable stack (hand only if handOnly). Returns Shards paid. */
	double sell(Player p, boolean handOnly) {
		if (plugin.eco() == null) {
			p.sendMessage(Spawners.mm("<red>The economy isn't running right now."));
			return 0;
		}
		double total = 0;
		long count = 0;
		if (handOnly) {
			ItemStack it = p.getInventory().getItemInMainHand();
			double each = price(it);
			if (each > 0) {
				total = each * it.getAmount();
				count = it.getAmount();
				p.getInventory().setItemInMainHand(null);
			}
		} else {
			ItemStack[] inv = p.getInventory().getStorageContents();
			for (int i = 0; i < inv.length; i++) {
				double each = price(inv[i]);
				if (each <= 0) continue;
				total += each * inv[i].getAmount();
				count += inv[i].getAmount();
				inv[i] = null;
			}
			p.getInventory().setStorageContents(inv);
		}
		if (count == 0) {
			p.sendMessage(Spawners.mm("<gray>The Collector doesn't want anything you're carrying. <#9A9A9A>(/collector shows what he buys)"));
			p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
			return 0;
		}
		plugin.eco().depositPlayer(p, total);
		p.sendMessage(Spawners.mm("<#9A9A9A>» <gray>The Collector took <white>" + String.format("%,d", count) + "</white> items for <gold><bold>" + LagSkyblock.shards(total)));
		p.sendActionBar(Spawners.mm("<gold>+" + LagSkyblock.shards(total)));
		p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.3f);
		return total;
	}

	// ---------------------------------------------------------------- menu

	void open(Player p) {
		Menu m = new Menu();
		m.inv = Bukkit.createInventory(m, 54, Gui.title("THE COLLECTOR", "price list", "#0E5C6B"));
		draw(p, m);
		p.openInventory(m.inv);
		p.playSound(p.getLocation(), Sound.ENTITY_WANDERING_TRADER_YES, 0.6f, 1.0f);
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

	private static String pretty(Material m) {
		String s = m.name().toLowerCase().replace('_', ' ');
		StringBuilder b = new StringBuilder();
		for (String w : s.split(" ")) b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
		return b.toString().trim();
	}

	private String priceText(double p) {
		return p >= 100 ? LagSkyblock.shards(p) : LagSkyblock.symbol + String.format("%,.2f", p);
	}

	private void draw(Player p, Menu m) {
		Inventory inv = m.inv;
		Gui.frame(inv, Material.CYAN_STAINED_GLASS_PANE);
		inv.setItem(TAB_LOOT, named(Material.SPAWNER, (m.tab == TAB_LOOT ? "<#38E0FF>▶ " : "<gray>") + "<bold>SPAWNER LOOT", List.of(
				"<gray>Drops from your cages.", "<#9A9A9A>Only loot from cages sells at these prices."), m.tab == TAB_LOOT));
		inv.setItem(TAB_ORES, named(Material.DIAMOND_ORE, (m.tab == TAB_ORES ? "<#38E0FF>▶ " : "<gray>") + "<bold>GEN ORES", List.of(
				"<gray>What your ore generator makes."), m.tab == TAB_ORES));
		inv.setItem(TAB_CROPS, named(Material.WHEAT, (m.tab == TAB_CROPS ? "<#38E0FF>▶ " : "<gray>") + "<bold>CROPS", List.of(
				"<gray>Farm it, sell it."), m.tab == TAB_CROPS));
		// what this tab lists, then laid out centred on the panel
		List<ItemStack> items = new ArrayList<>();
		if (m.tab == TAB_LOOT) {
			for (LagSkyblock.Tier t : plugin.tiers) {
				for (var e : t.loot().entrySet()) {
					items.add(named(e.getKey(), "<color:" + t.color() + "><bold>" + pretty(e.getKey()), List.of(
							"<gray>Pays: <gold>" + priceText(t.itemPrice()) + " <gray>each",
							"<#9A9A9A>From " + t.name() + " cages (tier " + t.number() + ")"), false));
				}
			}
		} else {
			Map<Material, Double> list = m.tab == TAB_ORES ? ores : crops;
			for (var e : list.entrySet()) {
				items.add(named(e.getKey(), "<white><bold>" + pretty(e.getKey()), List.of("<gray>Pays: <gold>" + priceText(e.getValue()) + " <gray>each"), false));
			}
		}
		List<Integer> slots = Gui.balanced(1, 4, items.size());
		for (int i = 0; i < items.size() && i < slots.size(); i++) inv.setItem(slots.get(i), items.get(i));
		double hand = price(p.getInventory().getItemInMainHand()) * p.getInventory().getItemInMainHand().getAmount();
		double all = worth(p);
		inv.setItem(HAND, named(Material.HOPPER, "<#38E0FF><bold>SELL HAND", List.of(
				hand > 0 ? "<gray>Your hand is worth <gold>" + LagSkyblock.shards(hand) : "<#9A9A9A>He doesn't want what's in your hand.",
				"", "<#9A9A9A>/sell hand"), false));
		inv.setItem(ALL, named(Material.GOLD_BLOCK, "<gold><bold>SELL EVERYTHING", List.of(
				all > 0 ? "<gray>Everything sellable you carry: <gold><bold>" + LagSkyblock.shards(all) : "<#9A9A9A>Nothing to sell.",
				"", all > 0 ? "<#7CFF6B>▶ Click to sell" : "<#9A9A9A>Grind, mine or farm first.", "<#9A9A9A>/sell"), all > 0));
		inv.setItem(WORTH, named(Material.BOOK, "<#B040FF><bold>WHO IS HE?", List.of(
				"<gray>He's putting the old world back",
				"<gray>together, piece by piece. He buys",
				"<gray>everything you pull out of it and",
				"<gray>pays in <white>Shards" + " <gray>(" + LagSkyblock.symbol + ")."), false));
		inv.setItem(CLOSE, named(Material.BARRIER, "<red>Close", List.of(), false));
	}

	@EventHandler
	public void onClick(InventoryClickEvent e) {
		if (!(e.getInventory().getHolder() instanceof Menu m)) return;
		e.setCancelled(true);
		if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
		int s = e.getRawSlot();
		switch (s) {
			case TAB_LOOT, TAB_ORES, TAB_CROPS -> m.tab = s;
			case HAND -> sell(p, true);
			case ALL -> sell(p, false);
			case CLOSE -> {
				p.closeInventory();
				return;
			}
			default -> {
				return;
			}
		}
		draw(p, m);
	}

	@EventHandler
	public void onDrag(InventoryDragEvent e) {
		if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
	}

	@Override
	public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
		if (!(s instanceof Player p)) {
			s.sendMessage("Players only.");
			return true;
		}
		if (cmd.getName().equalsIgnoreCase("collector")) open(p);
		else sell(p, args.length > 0 && args[0].equalsIgnoreCase("hand"));
		return true;
	}
}
