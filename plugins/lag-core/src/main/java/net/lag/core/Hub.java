package net.lag.core;

import java.time.Duration;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;

import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;

/** Hub-only behaviour: selector compass, no damage/hunger/building, double jump, void rescue, join title. */
final class Hub implements Listener {
	private final LagCore plugin;

	Hub(LagCore plugin, Selector selector) {
		this.plugin = plugin;
	}

	private boolean builder(Player p) {
		return p.hasPermission("lag.build") && p.getGameMode() == GameMode.CREATIVE;
	}

	private ItemStack selectorItem() {
		ItemStack it = new ItemStack(Material.RECOVERY_COMPASS);
		ItemMeta m = it.getItemMeta();
		m.displayName(LagCore.mm("<gradient:#38E0FF:#B040FF><bold>Server Selector</bold></gradient> <gray>(Right Click)").decoration(TextDecoration.ITALIC, false));
		m.setEnchantmentGlintOverride(true);
		m.addItemFlags(ItemFlag.values());
		it.setItemMeta(m);
		return it;
	}

	void toSpawn(Player p) {
		p.teleport(plugin.spawn());
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onJoin(PlayerJoinEvent e) {
		Player p = e.getPlayer();
		e.joinMessage(null);
		toSpawn(p);
		if (!builder(p)) p.setGameMode(GameMode.ADVENTURE);
		p.getInventory().clear();
		p.getInventory().setItem(4, selectorItem());
		p.getInventory().setHeldItemSlot(4);
		p.setHealth(p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
		p.setFoodLevel(20);
		p.setAllowFlight(plugin.getConfig().getBoolean("hub.double-jump", true));
		if (plugin.getConfig().getBoolean("hub.join-title", true) && plugin.hubFx == null) {
			p.showTitle(Title.title(
					LagCore.mm("<gradient:#38E0FF:#B040FF><bold>" + plugin.networkName() + "</bold></gradient>"),
					LagCore.mm("<gray>Right click the compass to pick a realm"),
					Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(700))));
		}
		p.playSound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.6f, 1.2f);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent e) {
		e.quitMessage(null);
	}

	@EventHandler
	public void onInteract(PlayerInteractEvent e) {
		ItemStack it = e.getItem();
		if (it != null && it.getType() == Material.RECOVERY_COMPASS
				&& (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK)) {
			e.setCancelled(true);
			Selector.open(plugin, e.getPlayer());
			return;
		}
		if (!builder(e.getPlayer()) && e.getAction() == Action.PHYSICAL) e.setCancelled(true); // no trampling plates/farmland
	}

	@EventHandler
	public void onDamage(EntityDamageEvent e) {
		if (e.getEntity() instanceof Player) e.setCancelled(true);
	}

	@EventHandler
	public void onHunger(FoodLevelChangeEvent e) {
		e.setCancelled(true);
	}

	@EventHandler
	public void onBreak(BlockBreakEvent e) {
		if (!builder(e.getPlayer())) e.setCancelled(true);
	}

	@EventHandler
	public void onPlace(BlockPlaceEvent e) {
		if (!builder(e.getPlayer())) e.setCancelled(true);
	}

	@EventHandler
	public void onDrop(PlayerDropItemEvent e) {
		if (!builder(e.getPlayer())) e.setCancelled(true);
	}

	@EventHandler
	public void onSwap(PlayerSwapHandItemsEvent e) {
		if (!builder(e.getPlayer())) e.setCancelled(true);
	}

	@EventHandler
	public void onInvClick(InventoryClickEvent e) {
		if (e.getWhoClicked() instanceof Player p && !builder(p) && e.getClickedInventory() == p.getInventory()) e.setCancelled(true);
	}

	@EventHandler
	public void onMove(PlayerMoveEvent e) {
		Player p = e.getPlayer();
		if (e.getTo().getY() < plugin.getConfig().getInt("hub.void-y", -64)) {
			toSpawn(p);
			p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.2f);
		}
		// re-arm double jump once you land
		if (plugin.getConfig().getBoolean("hub.double-jump", true) && !builder(p) && p.isOnGround() && !p.getAllowFlight()) {
			p.setAllowFlight(true);
		}
	}

	@EventHandler
	public void onDoubleJump(PlayerToggleFlightEvent e) {
		Player p = e.getPlayer();
		if (builder(p) || p.getGameMode() == GameMode.SPECTATOR) return;
		if (!plugin.getConfig().getBoolean("hub.double-jump", true)) return;
		e.setCancelled(true);
		p.setAllowFlight(false);
		p.setFlying(false);
		Vector v = p.getLocation().getDirection().multiply(1.4).setY(0.9);
		p.setVelocity(v);
		p.getWorld().spawnParticle(Particle.END_ROD, p.getLocation(), 14, 0.3, 0.1, 0.3, 0.03);
		p.playSound(p.getLocation(), Sound.ENTITY_BREEZE_JUMP, 0.6f, 1.3f);
	}
}
