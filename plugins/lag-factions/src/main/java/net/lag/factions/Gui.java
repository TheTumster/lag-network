package net.lag.factions;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** Tiny chest-menu framework: fixed items + click handlers, nothing can be taken out. */
final class Gui implements InventoryHolder {
	record Click(Player player, ClickType type) { }

	private final Inventory inv;
	private final Map<Integer, Consumer<Click>> actions = new HashMap<>();

	Gui(int rows, String title) {
		inv = Bukkit.createInventory(this, rows * 9, Ui.mm(title));
	}

	@Override
	public Inventory getInventory() {
		return inv;
	}

	Gui set(int slot, ItemStack item) {
		inv.setItem(slot, item);
		return this;
	}

	Gui set(int slot, ItemStack item, Consumer<Click> action) {
		inv.setItem(slot, item);
		actions.put(slot, action);
		return this;
	}

	/** Dark glass background + brand-coloured border. */
	Gui frame() {
		ItemStack bg = Ui.item(Material.BLACK_STAINED_GLASS_PANE, " ");
		ItemStack edge = Ui.item(Material.PURPLE_STAINED_GLASS_PANE, " ");
		ItemStack edge2 = Ui.item(Material.CYAN_STAINED_GLASS_PANE, " ");
		int size = inv.getSize();
		for (int i = 0; i < size; i++) {
			int r = i / 9, c = i % 9;
			boolean border = r == 0 || r == size / 9 - 1 || c == 0 || c == 8;
			inv.setItem(i, border ? ((r + c) % 2 == 0 ? edge : edge2) : bg);
		}
		return this;
	}

	void open(Player p) {
		p.openInventory(inv);
	}

	static final class Events implements Listener {
		@EventHandler
		public void onClick(InventoryClickEvent e) {
			if (!(e.getInventory().getHolder(false) instanceof Gui g)) return;
			e.setCancelled(true);
			if (e.getClickedInventory() == null || e.getClickedInventory() != g.inv) return;
			Consumer<Click> a = g.actions.get(e.getSlot());
			if (a != null && e.getWhoClicked() instanceof Player p) {
				Ui.click(p);
				a.accept(new Click(p, e.getClick()));
			}
		}

		@EventHandler
		public void onDrag(InventoryDragEvent e) {
			if (e.getInventory().getHolder(false) instanceof Gui) e.setCancelled(true);
		}
	}
}
