package net.lag.cannons;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.Dispenser;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * laG Cannons v1
 *  - Predictable TNT: removes vanilla's random sideways kick on ignition (dispensers, flint & steel, chain reactions)
 *  - Obsidian (and friends) take N TNT hits instead of being immune, with water protection and regen
 *  - /tntfill to load every dispenser in a radius
 */
public final class LagCannons extends JavaPlugin implements Listener {
	private final Map<Long, Hit> damage = new HashMap<>();
	private final Map<Material, Integer> durable = new HashMap<>();

	private record Hit(String world, int hits, long last) { }

	@Override
	public void onEnable() {
		saveDefaultConfig();
		loadDurable();
		getServer().getPluginManager().registerEvents(this, this);
		// sweep healed blocks once a minute
		Bukkit.getScheduler().runTaskTimer(this, this::regen, 1200L, 1200L);
		getLogger().info("laG Cannons enabled (random-kick=" + getConfig().getBoolean("tnt.random-kick") + ")");
	}

	private void loadDurable() {
		durable.clear();
		var sec = getConfig().getConfigurationSection("durability.blocks");
		if (sec == null) return;
		for (String k : sec.getKeys(false)) {
			Material m = Material.matchMaterial(k);
			if (m != null) durable.put(m, Math.max(1, sec.getInt(k)));
		}
	}

	private static String msg(String s) {
		return s;
	}

	private static void send(CommandSender to, String mm) {
		to.sendMessage(MiniMessage.miniMessage().deserialize(mm));
	}

	// ---------------------------------------------------------------- predictable TNT

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onTntSpawn(EntitySpawnEvent e) {
		if (!(e.getEntity() instanceof TNTPrimed tnt)) return;
		if (getConfig().getBoolean("tnt.random-kick", false)) return;
		double y = getConfig().getDouble("tnt.prime-y-velocity", 0.2);
		Vector v = tnt.getVelocity();
		// Only strip ignition jitter (tiny values). Leave real momentum (e.g. pushed/launched TNT) alone.
		if (Math.abs(v.getX()) <= 0.021 && Math.abs(v.getZ()) <= 0.021) {
			tnt.setVelocity(new Vector(0, Math.abs(v.getY() - 0.2) < 1e-6 ? y : v.getY(), 0));
		}
	}

	// ---------------------------------------------------------------- obsidian durability

	private static long key(Block b) {
		return ((long) b.getX() & 0x3FFFFFF) << 38 | ((long) b.getZ() & 0x3FFFFFF) << 12 | ((long) b.getY() & 0xFFF);
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onExplode(EntityExplodeEvent e) {
		if (!getConfig().getBoolean("durability.enabled", true) || durable.isEmpty()) return;
		Entity src = e.getEntity();
		if (getConfig().getBoolean("durability.water-protects", true) && src.isInWater()) return;
		Block center = e.getLocation().getBlock();
		double r = getConfig().getDouble("durability.radius", 1.5);
		int ir = (int) Math.ceil(r);
		long now = System.currentTimeMillis();
		World w = center.getWorld();
		boolean drop = getConfig().getBoolean("durability.drop", false);

		for (int dx = -ir; dx <= ir; dx++) {
			for (int dy = -ir; dy <= ir; dy++) {
				for (int dz = -ir; dz <= ir; dz++) {
					if (dx * dx + dy * dy + dz * dz > r * r) continue;
					Block b = center.getRelative(dx, dy, dz);
					Integer max = durable.get(b.getType());
					if (max == null) continue;
					long k = key(b);
					Hit h = damage.get(k);
					int hits = (h != null && h.world().equals(w.getName()) ? h.hits() : 0) + 1;
					if (hits >= max) {
						damage.remove(k);
						if (drop) b.breakNaturally();
						else b.setType(Material.AIR);
					} else {
						damage.put(k, new Hit(w.getName(), hits, now));
					}
				}
			}
		}
	}

	private void regen() {
		long secs = getConfig().getLong("durability.regen-seconds", 300);
		if (secs <= 0) return;
		long cutoff = System.currentTimeMillis() - secs * 1000;
		Iterator<Hit> it = damage.values().iterator();
		while (it.hasNext()) if (it.next().last() < cutoff) it.remove();
	}

	@EventHandler
	public void onCheck(PlayerInteractEvent e) {
		if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || e.getItem() == null) return;
		Material checker = Material.matchMaterial(getConfig().getString("checker-item", "POTATO"));
		if (e.getItem().getType() != checker) return;
		Block b = e.getClickedBlock();
		Integer max = durable.get(b.getType());
		if (max == null) {
			send(e.getPlayer(), "<gray>That block isn't durability-based.");
			return;
		}
		Hit h = damage.get(key(b));
		int left = max - (h == null ? 0 : h.hits());
		send(e.getPlayer(), "<gradient:#38E0FF:#B040FF>" + b.getType().name().toLowerCase().replace('_', ' ')
				+ "</gradient> <white>" + left + "<gray>/" + max + " hits left");
		e.setCancelled(true);
	}

	// ---------------------------------------------------------------- commands

	@Override
	public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
		if (cmd.getName().equalsIgnoreCase("tntfill")) return tntfill(sender, args);
		if (cmd.getName().equalsIgnoreCase("/upload")) return SchemUpload.run(this, sender, args);
		if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
			reloadConfig();
			loadDurable();
			send(sender, "<#38E0FF>laG Cannons reloaded.");
		} else if (args.length > 0 && args[0].equalsIgnoreCase("clear")) {
			damage.clear();
			send(sender, "<#38E0FF>All block damage healed.");
		} else {
			send(sender, "<gray>random-kick: <white>" + getConfig().getBoolean("tnt.random-kick")
					+ " <gray>| damaged blocks: <white>" + damage.size());
			send(sender, "<gray>/lc reload  /lc clear");
		}
		return true;
	}

	private boolean tntfill(CommandSender sender, String[] args) {
		if (!(sender instanceof Player p)) return true;
		int maxR = getConfig().getInt("tntfill.radius-max", 25);
		int radius = 10, amount = 64;
		try {
			if (args.length > 0) radius = Math.min(maxR, Integer.parseInt(args[0]));
			if (args.length > 1) amount = Math.min(getConfig().getInt("tntfill.per-dispenser-max", 576), Integer.parseInt(args[1]));
		} catch (NumberFormatException ex) {
			send(p, "<gray>/tntfill [radius] [amount per dispenser]");
			return true;
		}
		boolean free = p.getGameMode() == GameMode.CREATIVE;
		int filled = 0, used = 0;
		Block c = p.getLocation().getBlock();
		outer:
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dy = -radius; dy <= radius; dy++) {
				for (int dz = -radius; dz <= radius; dz++) {
					Block b = c.getRelative(dx, dy, dz);
					if (!(b.getState() instanceof Dispenser d)) continue;
					int want = amount;
					if (!free) {
						int have = count(p);
						if (have <= 0) break outer;
						want = Math.min(want, have);
					}
					var left = ((Container) d).getInventory().addItem(new ItemStack(Material.TNT, want));
					int added = want - left.values().stream().mapToInt(ItemStack::getAmount).sum();
					if (added > 0) {
						if (!free) p.getInventory().removeItem(new ItemStack(Material.TNT, added));
						used += added;
						filled++;
					}
				}
			}
		}
		send(p, "<gradient:#38E0FF:#B040FF>TNT fill</gradient> <gray>loaded <white>" + used + "<gray> TNT into <white>" + filled + "<gray> dispensers.");
		return true;
	}

	private static int count(Player p) {
		int n = 0;
		for (ItemStack it : p.getInventory().getContents()) if (it != null && it.getType() == Material.TNT) n += it.getAmount();
		return n;
	}
}
