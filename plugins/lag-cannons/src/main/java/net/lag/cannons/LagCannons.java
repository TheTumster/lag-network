package net.lag.cannons;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.ChatColor;
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

	private Sandbots sandbots;
	private RedstoneFix redstoneFix;

	@Override
	public void onDisable() {
		if (sandbots != null) sandbots.shutdown();
	}

	@Override
	public void onEnable() {
		saveDefaultConfig();
		loadDurable();
		getServer().getPluginManager().registerEvents(this, this);
		getServer().getPluginManager().registerEvents(new WaterProof(this), this);
		getServer().getPluginManager().registerEvents(new ClassicFactions(), this); // only rewrites /f commands
		getServer().getPluginManager().registerEvents(new TntMerge(this), this);
		protect = new CannonProtect(this);
		getServer().getPluginManager().registerEvents(protect, this);
		getServer().getPluginManager().registerEvents(new PistonOrder(this), this);
		redstoneFix = new RedstoneFix(this);
		getServer().getPluginManager().registerEvents(redstoneFix, this);
		sandbots = new Sandbots(this);
		getServer().getPluginManager().registerEvents(sandbots, this);
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
		// 1.8 counts the fuse down past zero before exploding (81 ticks for an 80 fuse); modern versions explode at
		// zero (80). Cannons are timed to the tick, so give TNT the 1.8 fuse length.
		if (getConfig().getBoolean("tnt.legacy-fuse", true)) tnt.setFuseTicks(tnt.getFuseTicks() + 1);
		if (getConfig().getBoolean("tnt.random-kick", false)) return;
		double y = getConfig().getDouble("tnt.prime-y-velocity", 0.2);
		Vector v = tnt.getVelocity();
		// Only strip ignition jitter (tiny values). Leave real momentum (e.g. pushed/launched TNT) alone.
		if (Math.abs(v.getX()) <= 0.021 && Math.abs(v.getZ()) <= 0.021) {
			tnt.setVelocity(new Vector(0, Math.abs(v.getY() - 0.2) < 1e-6 ? y : v.getY(), 0));
		}
	}

	private CannonProtect protect;

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
					if (protect != null && protect.isProtected(b)) continue; // diamond-protected test cannon
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
		if (cmd.getName().equalsIgnoreCase("sandbot")) return sandbots.command(sender, args);
		if (cmd.getName().equalsIgnoreCase("fixredstone")) return redstoneFix.command(sender, args);
		if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
			reloadConfig();
			loadDurable();
			sandbots.restartTask();
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

	/** One TNT fill on factions: /tntfill and /tf just run /f tnt fill (from the faction TNT bank). */
	/**
	 * /tntfill [radius] [amount]: ops in creative get a free test fill (like the 1.8 lab); everyone else fills from
	 * their faction's TNT bank (/f tnt fill), which needs a faction.
	 */
	private boolean tntfill(CommandSender sender, String[] args) {
		if (!(sender instanceof Player p)) return true;
		if (p.hasPermission("lagcannons.tntfill.free") && p.getGameMode() == org.bukkit.GameMode.CREATIVE) {
			int r = 32, amount = 576;
			try {
				if (args.length > 0) r = Math.max(1, Math.min(64, Integer.parseInt(args[0])));
				if (args.length > 1) amount = Math.max(1, Math.min(576, Integer.parseInt(args[1])));
			} catch (NumberFormatException e) {
				p.sendMessage(ChatColor.RED + "Usage: /tntfill [radius] [amount per dispenser]");
				return true;
			}
			org.bukkit.Location c = p.getLocation();
			int n = 0;
			for (int x = c.getBlockX() - r; x <= c.getBlockX() + r; x++)
				for (int z = c.getBlockZ() - r; z <= c.getBlockZ() + r; z++)
					for (int y = Math.max(c.getWorld().getMinHeight(), c.getBlockY() - r); y <= Math.min(c.getWorld().getMaxHeight() - 1, c.getBlockY() + r); y++) {
						org.bukkit.block.Block b = c.getWorld().getBlockAt(x, y, z);
						if (b.getType() == Material.DISPENSER) {
							QuietFill.fill(b, amount);
							n++;
						}
					}
			p.sendMessage(ChatColor.LIGHT_PURPLE + "[laG] " + ChatColor.GRAY + "Test fill: " + ChatColor.WHITE + n + ChatColor.GRAY
					+ " dispensers, " + amount + " TNT each (radius " + r + "). Free because you're op in creative.");
			return true;
		}
		try {
			p.performCommand("f tnt fill" + ClassicFactions.fillDefaults(String.join(" ", args)));
		} catch (RuntimeException e) {
			p.sendMessage(ChatColor.RED + "TNT fill uses your faction's TNT bank: you need to be in a faction (/f create) with TNT in /f tnt.");
		}
		return true;
	}

	private static int count(Player p) {
		int n = 0;
		for (ItemStack it : p.getInventory().getContents()) if (it != null && it.getType() == Material.TNT) n += it.getAmount();
		return n;
	}
}
