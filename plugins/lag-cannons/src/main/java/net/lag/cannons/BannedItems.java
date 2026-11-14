package net.lag.cannons;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

/** Classic factions PvP: no shields, maces or spears. Can't be crafted, used or kept (config banned-items). */
final class BannedItems implements Listener {
	private final JavaPlugin plugin;

	BannedItems(JavaPlugin plugin) {
		this.plugin = plugin;
	}

	boolean banned(ItemStack it) {
		if (it == null || it.getType() == Material.AIR) return false;
		String n = it.getType().name();
		List<String> list = plugin.getConfig().getStringList("banned-items");
		for (String b : list) {
			b = b.toUpperCase();
			if (b.startsWith("*") ? n.endsWith(b.substring(1)) : n.equals(b)) return true;
		}
		return false;
	}

	private boolean bypass(HumanEntity p) {
		return p.hasPermission("lagcannons.banneditems.bypass");
	}

	void strip(HumanEntity p) {
		if (bypass(p)) return;
		PlayerInventory inv = p.getInventory();
		boolean any = false;
		for (int i = 0; i < inv.getSize(); i++) {
			if (banned(inv.getItem(i))) { inv.setItem(i, null); any = true; }
		}
		if (banned(p.getItemOnCursor())) { p.setItemOnCursor(null); any = true; }
		if (any && p instanceof Player pl) pl.sendMessage("§c[laG] Shields, maces and spears are disabled here.");
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void onCraft(PrepareItemCraftEvent e) {
		if (banned(e.getInventory().getResult())) e.getInventory().setResult(null);
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void onSmith(PrepareSmithingEvent e) {
		if (banned(e.getResult())) e.setResult(null);
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onPickup(EntityPickupItemEvent e) {
		if (e.getEntity() instanceof Player p && !bypass(p) && banned(e.getItem().getItemStack())) {
			e.setCancelled(true);
			e.getItem().remove();
		}
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void onClick(InventoryClickEvent e) {
		if (bypass(e.getWhoClicked())) return;
		if (banned(e.getCurrentItem()) || banned(e.getCursor())) {
			e.setCancelled(true);
			if (banned(e.getCurrentItem())) e.setCurrentItem(null);
			plugin.getServer().getScheduler().runTask(plugin, () -> strip(e.getWhoClicked()));
		}
	}

	@EventHandler
	public void onClose(InventoryCloseEvent e) {
		strip(e.getPlayer());
	}

	@EventHandler
	public void onJoin(PlayerJoinEvent e) {
		strip(e.getPlayer());
	}

	@EventHandler
	public void onHeld(PlayerItemHeldEvent e) {
		if (banned(e.getPlayer().getInventory().getItem(e.getNewSlot()))) strip(e.getPlayer());
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void onSwap(PlayerSwapHandItemsEvent e) {
		if (!bypass(e.getPlayer()) && (banned(e.getMainHandItem()) || banned(e.getOffHandItem()))) {
			e.setCancelled(true);
			strip(e.getPlayer());
		}
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void onUse(PlayerInteractEvent e) {
		if (!bypass(e.getPlayer()) && banned(e.getItem())) {
			e.setCancelled(true);
			strip(e.getPlayer());
		}
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onHit(EntityDamageByEntityEvent e) {
		if (e.getDamager() instanceof LivingEntity d) {
			EntityEquipment eq = d.getEquipment();
			if (eq != null && banned(eq.getItemInMainHand()) && !(d instanceof Player p && bypass(p))) {
				e.setCancelled(true);
				if (d instanceof Player p) strip(p);
			}
		}
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onDispense(BlockDispenseEvent e) {
		if (banned(e.getItem())) e.setCancelled(true);
	}
}
