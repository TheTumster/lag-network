package net.lag.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * /help as a menu, the same on every realm. Top row: the network commands (help.network). Below: this realm's own
 * (help.realm.<role>), in the realm's colour. Bottom: Discord, Website, Close, Store.
 * Lines are "/command|what it does|ICON". Click runs it; commands that need an argument (<player>) are put in chat instead.
 */
final class HelpMenu implements Listener {
	private final LagCore plugin;
	private final NamespacedKey KEY;

	HelpMenu(LagCore plugin) {
		this.plugin = plugin;
		this.KEY = new NamespacedKey(plugin, "help");
	}

	private static final class Holder implements InventoryHolder {
		Inventory inv;

		@Override
		public Inventory getInventory() {
			return inv;
		}
	}

	private static Component t(String mm) {
		return LagCore.mm(mm).decoration(TextDecoration.ITALIC, false);
	}

	private ItemStack item(Material mat, String name, List<String> lore, String action) {
		ItemStack it = new ItemStack(mat);
		ItemMeta m = it.getItemMeta();
		m.displayName(t(name));
		List<Component> l = new ArrayList<>();
		for (String s : lore) l.add(t(s));
		m.lore(l);
		m.addItemFlags(ItemFlag.values());
		if (action != null) m.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, action);
		it.setItemMeta(m);
		return it;
	}

	private static Material mat(String name, Material def) {
		Material m = name == null ? null : Material.matchMaterial(name.trim());
		return m == null || !m.isItem() ? def : m;
	}

	private static ItemStack pane(Material m) {
		ItemStack it = new ItemStack(m);
		ItemMeta im = it.getItemMeta();
		im.setHideTooltip(true);
		it.setItemMeta(im);
		return it;
	}

	void open(Player p) {
		var cfg = plugin.getConfig();
		String role = cfg.getString("role", "hub").toLowerCase(Locale.ROOT);
		String realm = role.substring(0, 1).toUpperCase(Locale.ROOT) + role.substring(1);
		String accent = cfg.getString("help.accent." + role, "#FF6B6B");
		List<String> net = cfg.getStringList("help.network");
		List<String> mine = cfg.getStringList("help.realm." + role);
		int realmRows = Math.max(1, (mine.size() + 6) / 7);
		int rows = Math.min(6, 2 + 1 + realmRows + 1); // top edge, network row, realm rows, bottom bar
		Holder h = new Holder();
		Inventory inv = Bukkit.createInventory(h, rows * 9, LagCore.mm("<#FF5C5C><bold>RelapsedMC</bold></#FF5C5C> <dark_gray>• <" + accent + ">Help · " + realm));
		h.inv = inv;
		// frame: light-red edge, dark inside, realm colour under the realm section
		ItemStack edge = pane(Material.RED_STAINED_GLASS_PANE), inner = pane(Material.BLACK_STAINED_GLASS_PANE),
				soft = pane(Material.PINK_STAINED_GLASS_PANE);
		for (int i = 0; i < rows * 9; i++) {
			int r = i / 9, c = i % 9;
			boolean e = r == 0 || r == rows - 1 || c == 0 || c == 8;
			inv.setItem(i, e ? ((r + c) % 2 == 0 ? edge : soft) : inner);
		}
		inv.setItem(4, item(Material.KNOWLEDGE_BOOK, "<gradient:#FF5C5C:#FFA19A><bold>RelapsedMC Help</bold></gradient>",
				List.of("<#9AA3B8>You're on <" + accent + "><bold>" + realm, "", "<#9AA3B8>Click a command to use it."), null));
		// row 1: network commands (centred, up to 7)
		place(inv, 9, net, "#FF6B6B", "Everywhere", 7);
		// realm rows
		for (int r = 0; r < realmRows && 2 + r < rows - 1; r++) {
			List<String> chunk = mine.subList(r * 7, Math.min(mine.size(), r * 7 + 7));
			place(inv, (2 + r) * 9, chunk, accent, realm, 7);
		}
		int b = (rows - 1) * 9;
		inv.setItem(b + 1, item(Material.AMETHYST_SHARD, "<#FFA19A><bold>Discord</bold>", List.of("<#9AA3B8>Join the community"), "cmd:discord"));
		inv.setItem(b + 3, item(Material.MAP, "<#FFA19A><bold>Website</bold>", List.of("<#9AA3B8>RelapsedMC.com"), "cmd:website"));
		inv.setItem(b + 4, item(Material.BARRIER, "<#FF6B6B><bold>Close</bold>", List.of(), "close"));
		inv.setItem(b + 5, item(Material.COMPASS, "<#FFA19A><bold>Realms</bold>", List.of("<#9AA3B8>Open the realm menu"), "cmd:server"));
		inv.setItem(b + 7, item(Material.GOLD_INGOT, "<#FFD24D><bold>Store</bold>", List.of("<#9AA3B8>Cosmetic ranks"), "cmd:store"));
		p.openInventory(inv);
		p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
	}

	/** Lay up to max entries centred in the inner 7 columns of the row starting at rowStart. */
	private void place(Inventory inv, int rowStart, List<String> lines, String accent, String section, int max) {
		int n = Math.min(max, lines.size());
		int first = rowStart + 1 + (7 - n) / 2;
		for (int i = 0; i < n; i++) {
			String[] p = lines.get(i).split("\\|", 3);
			String cmd = p[0].trim(), desc = p.length > 1 ? p[1].trim() : "";
			Material icon = mat(p.length > 2 ? p[2] : null, Material.PAPER);
			boolean needsArg = cmd.contains("<");
			List<String> lore = new ArrayList<>();
			lore.add("<#9AA3B8>" + LagCore.esc(desc));
			lore.add("");
			lore.add("<#5E6478>" + LagCore.esc(section));
			lore.add(needsArg ? "<" + accent + ">▶ Click to type it" : "<" + accent + ">▶ Click to run");
			inv.setItem(first + i, item(icon, "<" + accent + "><bold>" + LagCore.esc(cmd) + "</bold>", lore,
					(needsArg ? "type:" : "run:") + cmd));
		}
	}

	@EventHandler
	public void onClick(InventoryClickEvent e) {
		if (!(e.getInventory().getHolder() instanceof Holder)) return;
		e.setCancelled(true);
		if (!(e.getWhoClicked() instanceof Player p) || e.getCurrentItem() == null || !e.getCurrentItem().hasItemMeta()) return;
		String a = e.getCurrentItem().getItemMeta().getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
		if (a == null) return;
		p.closeInventory();
		p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
		if (a.equals("close")) return;
		if (a.startsWith("cmd:")) {
			p.performCommand(a.substring(4));
		} else if (a.startsWith("run:")) {
			p.performCommand(a.substring(5)); // drop the leading "/"
		} else if (a.startsWith("type:")) {
			String full = a.substring(5), base = full.split(" <")[0];
			p.sendMessage(LagCore.mm("<#FF6B6B>▌ <click:suggest_command:'" + base.replace("'", "") + " '><white><bold><u>" + LagCore.esc(full)
					+ "</u></bold></white></click>  <#9AA3B8>click to put it in chat"));
		}
	}

	@EventHandler
	public void onDrag(InventoryDragEvent e) {
		if (e.getInventory().getHolder() instanceof Holder) e.setCancelled(true);
	}
}
