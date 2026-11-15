package net.lag.core;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerMoveEvent;

/** Spawn island protection on the realm servers: no building, no PvP, no explosion damage, no fall/void deaths near it. */
final class SpawnProtect implements Listener {
	private final LagCore plugin;
	private final HubBuild spawn;

	SpawnProtect(LagCore plugin, HubBuild spawn) {
		this.plugin = plugin;
		this.spawn = spawn;
	}

	private double radius() {
		return plugin.optNum(spawn.base + ".protect-radius", 40);
	}

	private boolean builder(Player p) {
		return p.hasPermission("lag.build");
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onBreak(BlockBreakEvent e) {
		if (!builder(e.getPlayer()) && spawn.near(e.getBlock().getLocation(), radius())) e.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onPlace(BlockPlaceEvent e) {
		if (!builder(e.getPlayer()) && spawn.near(e.getBlock().getLocation(), radius())) e.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onBucket(PlayerBucketEmptyEvent e) {
		if (!builder(e.getPlayer()) && spawn.near(e.getBlock().getLocation(), radius())) e.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onDamage(EntityDamageEvent e) {
		if (e.getEntity() instanceof Player p && spawn.near(p.getLocation(), radius())) e.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onPvp(EntityDamageByEntityEvent e) {
		if (e.getEntity() instanceof Player p && spawn.near(p.getLocation(), radius())) e.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onExplode(EntityExplodeEvent e) {
		e.blockList().removeIf(b -> spawn.near(b.getLocation(), radius()));
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onBlockExplode(BlockExplodeEvent e) {
		e.blockList().removeIf(b -> spawn.near(b.getLocation(), radius()));
	}

	/** Walked off the spawn island: back onto it instead of falling. */
	@EventHandler(ignoreCancelled = true)
	public void onFall(PlayerMoveEvent e) {
		var o = spawn.origin();
		var s = spawn.spawnPoint();
		if (o == null || s == null || !e.getTo().getWorld().equals(o.getWorld())) return;
		if (e.getTo().getY() < o.getY() - 50 && Math.abs(e.getTo().getX() - o.getX()) < 60 && Math.abs(e.getTo().getZ() - o.getZ()) < 60
				&& plugin.optBool(spawn.base + ".catch-falls", false)) {
			e.getPlayer().teleport(s);
			e.getPlayer().setFallDistance(0);
		}
	}
}
