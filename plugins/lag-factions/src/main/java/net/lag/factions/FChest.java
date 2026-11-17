package net.lag.factions;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import dev.kitteh.factions.Faction;
import dev.kitteh.factions.permissible.Role;

/** /f chest: one shared chest per faction (everyone online sees the same inventory). Rows come from the chest upgrade. */
final class FChest implements Listener {
	private final LagFactions plugin;
	private final File dir;
	private final Map<Integer, Holder> open = new HashMap<>();

	private final class Holder implements InventoryHolder {
		final int faction;
		Inventory inv;

		Holder(int faction) {
			this.faction = faction;
		}

		@Override
		public Inventory getInventory() {
			return inv;
		}
	}

	FChest(LagFactions plugin) {
		this.plugin = plugin;
		this.dir = new File(plugin.getDataFolder(), "chests");
		Bukkit.getScheduler().runTaskTimer(plugin, this::saveAll, 6000L, 6000L); // every 5 min
	}

	void open(Player p) {
		Faction f = F.of(p);
		if (f == null) { Ui.err(p, "You need a faction."); return; }
		if (!F.atLeast(p, Role.NORMAL)) { Ui.err(p, "Recruits can't open the faction chest."); return; }
		int rows = Math.max(1, Math.min(6, (int) plugin.upgrades.value(f, Upgrades.Type.CHEST)));
		Holder h = open.get(f.id());
		if (h == null || h.inv.getSize() != rows * 9) {
			ItemStack[] items = h != null ? h.inv.getContents() : load(f.id());
			if (h != null) for (var v : List.copyOf(h.inv.getViewers())) v.closeInventory();
			h = new Holder(f.id());
			h.inv = Bukkit.createInventory(h, rows * 9, Ui.mm(Ui.GRAD + "<bold>FACTION CHEST</bold></gradient> <dark_gray>• " + Ui.esc(f.tag())));
			for (int i = 0; i < Math.min(items.length, rows * 9); i++) h.inv.setItem(i, items[i]);
			// anything that no longer fits (shouldn't happen: rows only grow) goes back to the opener
			for (int i = rows * 9; i < items.length; i++) if (items[i] != null) p.getInventory().addItem(items[i]);
			open.put(f.id(), h);
		}
		p.openInventory(h.inv);
	}

	private ItemStack[] load(int id) {
		File f = new File(dir, id + ".yml");
		if (!f.exists()) return new ItemStack[0];
		List<?> list = YamlConfiguration.loadConfiguration(f).getList("items");
		if (list == null) return new ItemStack[0];
		ItemStack[] out = new ItemStack[list.size()];
		for (int i = 0; i < out.length; i++) out[i] = list.get(i) instanceof ItemStack it ? it : null;
		return out;
	}

	private void save(Holder h) {
		YamlConfiguration y = new YamlConfiguration();
		y.set("items", java.util.Arrays.asList(h.inv.getContents()));
		try {
			dir.mkdirs();
			y.save(new File(dir, h.faction + ".yml"));
		} catch (IOException ex) {
			plugin.getLogger().warning("Couldn't save faction chest " + h.faction + ": " + ex.getMessage());
		}
	}

	void saveAll() {
		for (Holder h : open.values()) save(h);
	}

	@EventHandler
	public void onClose(InventoryCloseEvent e) {
		if (e.getInventory().getHolder(false) instanceof Holder h) save(h);
	}
}
