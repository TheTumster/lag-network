package net.lag.core;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
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

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;

/** The realm picker: a chest menu with one glowing item per realm and live player counts. */
public final class Selector implements Listener {
	private final LagCore plugin;

	Selector(LagCore plugin) {
		this.plugin = plugin;
	}

	private static final class Holder implements InventoryHolder {
		Inventory inv;

		@Override
		public Inventory getInventory() {
			return inv;
		}
	}

	private static ItemStack pane(Material m) {
		ItemStack pane = new ItemStack(m);
		ItemMeta pm = pane.getItemMeta();
		pm.displayName(Component.text(" "));
		pm.setHideTooltip(true);
		pane.setItemMeta(pm);
		return pane;
	}

	/** Light-red glass frame, black inside (same as /help). */
	static void border(Inventory inv) {
		int size = inv.getSize(), rows = size / 9;
		ItemStack inner = pane(Material.BLACK_STAINED_GLASS_PANE);
		Material[] grad = {Material.RED_STAINED_GLASS_PANE, Material.PINK_STAINED_GLASS_PANE};   // light-red brand frame
		for (int i = 0; i < size; i++) {
			int r = i / 9, c = i % 9;
			boolean edge = r == 0 || r == rows - 1 || c == 0 || c == 8;
			inv.setItem(i, edge ? pane(grad[(r + c) % 2]) : inner);
		}
	}

	private static final org.bukkit.NamespacedKey BLANK = new org.bukkit.NamespacedKey("relapsed", "blank");
	private static final String[] ACCENT = {"LIME", "RED", "ORANGE", "LIGHT_BLUE", "PURPLE", "CYAN"};

	/** One realm's item: name, live count, lore, and the realm to send to (none while it's coming soon). */
	private static ItemStack realmItem(LagCore plugin, String key, ConfigurationSection r, Material icon, boolean invisible) {
		ItemStack it = new ItemStack(icon);
		ItemMeta m = it.getItemMeta();
		m.displayName(LagCore.mm(r.getString("name", key)).decoration(TextDecoration.ITALIC, false));
		List<Component> lore = new ArrayList<>();
		int n = plugin.count(r.getString("server", key));
		lore.add(LagCore.mm("<white>" + n + " <gray>playing").decoration(TextDecoration.ITALIC, false));
		lore.add(Component.empty());
		for (String l : r.getStringList("lore")) lore.add(LagCore.mm(l).decoration(TextDecoration.ITALIC, false));
		if (r.getBoolean("coming-soon", false)) lore.add(LagCore.mm("<#F4C542>Coming soon").decoration(TextDecoration.ITALIC, false));
		m.lore(lore);
		m.addItemFlags(ItemFlag.values());
		if (invisible) m.setItemModel(BLANK);
		else m.setEnchantmentGlintOverride(!r.getBoolean("coming-soon", false));
		if (!r.getBoolean("coming-soon", false))
			m.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "realm"), org.bukkit.persistence.PersistentDataType.STRING, r.getString("server", key));
		else
			m.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "soon"), org.bukkit.persistence.PersistentDataType.STRING, key);
		it.setItemMeta(m);
		return it;
	}

	private static ItemStack button(LagCore plugin, Material mat, String name, String lore, String action) {
		ItemStack it = new ItemStack(mat);
		ItemMeta m = it.getItemMeta();
		m.displayName(LagCore.mm(name).decoration(TextDecoration.ITALIC, false));
		m.lore(List.of(LagCore.mm(lore).decoration(TextDecoration.ITALIC, false)));
		m.addItemFlags(ItemFlag.values());
		m.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "button"), org.bukkit.persistence.PersistentDataType.STRING, action);
		it.setItemMeta(m);
		return it;
	}

	/**
	 * The realm picker. A strip per realm (rows 0-3: icon, name, PLAY) and a bottom bar (Discord, Close, Store).
	 * With the pack, the strips are drawn art and the rest of each strip is invisible clickable items; without it,
	 * the strips are rows of the realm's colour glass.
	 */
	public static void open(LagCore plugin, Player p) {
		Holder h = new Holder();
		boolean art = plugin.pack != null && plugin.pack.has(p);
		Inventory inv = Bukkit.createInventory(h, 45, LagCore.mm(art
				? plugin.pack.menuTitle(p, Pack.MENU_SELECTOR, "<white><bold>CHOOSE A REALM</bold>")
				: "<gradient:#FF5C5C:#FFA19A><bold>" + plugin.networkName() + "</bold></gradient> <dark_gray>» <gray>Choose a realm"));
		h.inv = inv;
		if (!art) {
			ItemStack dark = pane(Material.BLACK_STAINED_GLASS_PANE);
			for (int i = 0; i < 45; i++) inv.setItem(i, dark);
		}
		ConfigurationSection realms = plugin.section("realms");
		int row = 0;
		if (realms != null) {
			for (String key : realms.getKeys(false)) {
				if (row > 3) break;
				ConfigurationSection r = realms.getConfigurationSection(key);
				Material icon = Material.matchMaterial(r.getString("icon", "STONE"));
				if (icon == null) icon = Material.STONE;
				inv.setItem(row * 9, realmItem(plugin, key, r, icon, false));
				Material glass = Material.matchMaterial(ACCENT[row % ACCENT.length] + "_STAINED_GLASS_PANE");
				for (int c = 1; c < 9; c++) {
					if (art) inv.setItem(row * 9 + c, realmItem(plugin, key, r, icon, true));
					else inv.setItem(row * 9 + c, realmItem(plugin, key, r, c == 8 ? Material.LIME_DYE : glass, false));
				}
				row++;
			}
		}
		inv.setItem(36, button(plugin, Material.AMETHYST_SHARD, "<#FFA19A><bold>Discord</bold>", "<gray>Join the community", "discord"));
		inv.setItem(40, button(plugin, Material.BARRIER, "<#FF4D6D><bold>Close</bold>", "<gray>Back to the hub", "close"));
		inv.setItem(44, button(plugin, Material.GOLD_INGOT, "<#FFD24D><bold>Store</bold>", "<gray>Ranks and cosmetics", "store"));
		p.openInventory(inv);
		p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.4f);
	}

	@EventHandler
	public void onClick(InventoryClickEvent e) {
		if (!(e.getInventory().getHolder() instanceof Holder)) return;
		e.setCancelled(true);
		if (!(e.getWhoClicked() instanceof Player p)) return;
		ItemStack it = e.getCurrentItem();
		if (it == null || !it.hasItemMeta()) return;
		var pdc = it.getItemMeta().getPersistentDataContainer();
		String button = pdc.get(new org.bukkit.NamespacedKey(plugin, "button"), org.bukkit.persistence.PersistentDataType.STRING);
		if (button != null) {
			p.closeInventory();
			if (!"close".equals(button)) {
				String url = plugin.getConfig().getString("links." + button, "");
				p.sendMessage(LagCore.mm(url.isEmpty() ? "<gray>Coming soon." : "<gray>" + ("discord".equals(button) ? "Discord" : "Store") + ": <click:open_url:'" + url + "'><#FF6B6B><u>" + url + "</u></click>"));
			}
			p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
			return;
		}
		String soon = pdc.get(new org.bukkit.NamespacedKey(plugin, "soon"), org.bukkit.persistence.PersistentDataType.STRING);
		if (soon != null) {
			p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
			p.sendActionBar(LagCore.mm("<gradient:#FFA19A:#FF5C5C><bold>" + soon.toUpperCase() + "</bold></gradient> <gray>is coming soon"));
			return;
		}
		String server = pdc.get(new org.bukkit.NamespacedKey(plugin, "realm"), org.bukkit.persistence.PersistentDataType.STRING);
		if (server == null) return;
		p.closeInventory();
		p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.6f);
		p.sendMessage(LagCore.mm("<gray>Sending you to <#FF6B6B>" + server + "<gray>..."));
		plugin.send(p, server);
	}

	@EventHandler
	public void onDrag(InventoryDragEvent e) {
		if (e.getInventory().getHolder() instanceof Holder) e.setCancelled(true);
	}
}
