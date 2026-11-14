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

	public static void open(LagCore plugin, Player p) {
		Holder h = new Holder();
		Inventory inv = Bukkit.createInventory(h, 27, LagCore.mm("<gradient:#38E0FF:#B040FF><bold>" + plugin.networkName() + "</bold></gradient>"));
		h.inv = inv;

		ItemStack pane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
		ItemMeta pm = pane.getItemMeta();
		pm.displayName(Component.text(" "));
		pane.setItemMeta(pm);
		for (int i = 0; i < 27; i++) inv.setItem(i, pane);

		ConfigurationSection realms = plugin.getConfig().getConfigurationSection("realms");
		if (realms != null) {
			for (String key : realms.getKeys(false)) {
				ConfigurationSection r = realms.getConfigurationSection(key);
				Material icon = Material.matchMaterial(r.getString("icon", "STONE"));
				ItemStack it = new ItemStack(icon == null ? Material.STONE : icon);
				ItemMeta m = it.getItemMeta();
				m.displayName(LagCore.mm(r.getString("name", key)).decoration(TextDecoration.ITALIC, false));
				List<Component> lore = new ArrayList<>();
				lore.add(LagCore.mm("<dark_gray>" + plugin.count(r.getString("server", key)) + " playing").decoration(TextDecoration.ITALIC, false));
				lore.add(Component.empty());
				for (String l : r.getStringList("lore")) lore.add(LagCore.mm(l).decoration(TextDecoration.ITALIC, false));
				m.lore(lore);
				m.setEnchantmentGlintOverride(true);
				m.addItemFlags(ItemFlag.values());
				m.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "realm"),
						org.bukkit.persistence.PersistentDataType.STRING, r.getString("server", key));
				it.setItemMeta(m);
				inv.setItem(r.getInt("slot", 13), it);
			}
		}
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
		String server = it.getItemMeta().getPersistentDataContainer().get(new org.bukkit.NamespacedKey(plugin, "realm"),
				org.bukkit.persistence.PersistentDataType.STRING);
		if (server == null) return;
		p.closeInventory();
		p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.6f);
		p.sendMessage(LagCore.mm("<gray>Sending you to <#38E0FF>" + server + "<gray>..."));
		plugin.send(p, server);
	}

	@EventHandler
	public void onDrag(InventoryDragEvent e) {
		if (e.getInventory().getHolder() instanceof Holder) e.setCancelled(true);
	}
}
