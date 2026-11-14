package net.lag.lab;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Sandbots for the 1.8 lab (same commands as the factions server, no land/owner limits since it's a test server).
 *  /sandbot create | wand | speed <ticks> | toggle | clear | remove | list
 *  Wand: left-click a block face to add/remove a sand spot. Right-click a bot to pause, sneak+right-click to remove.
 */
@SuppressWarnings("deprecation")
final class Sandbots implements Listener {
	private static final String TAG = ChatColor.AQUA + "" + ChatColor.BOLD + "Sandbot";
	private static final String WAND = ChatColor.AQUA + "" + ChatColor.BOLD + "Sandbot Wand";
	private static final String ID_PREFIX = ChatColor.DARK_GRAY + "id ";

	static final class Bot {
		UUID id;
		String world;
		double x, y, z;
		boolean active = true;
		int every = 2;
		int counter = 0;
		final Set<String> spots = new LinkedHashSet<String>();
		UUID entity;

		Location loc() {
			World w = Bukkit.getWorld(world);
			return w == null ? null : new Location(w, x, y, z);
		}
	}

	private final JavaPlugin plugin;
	private final Map<UUID, Bot> bots = new HashMap<UUID, Bot>();
	private final File file;

	Sandbots(JavaPlugin plugin) {
		this.plugin = plugin;
		this.file = new File(plugin.getDataFolder(), "sandbots.yml");
		load();
		Bukkit.getScheduler().runTask(plugin, new Runnable() {
			public void run() {
				purgeOldStands();
				for (Bot b : bots.values()) spawnEntity(b);
			}
		});
		Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
			public void run() {
				refill();
			}
		}, 1L, 1L);
		Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
			public void run() {
				showSpots();
			}
		}, 10L, 10L);
	}

	void shutdown() {
		save();
		for (Bot b : bots.values()) removeEntity(b);
	}

	private static void say(Player p, String s) {
		p.sendMessage(ChatColor.LIGHT_PURPLE + "[Sandbot] " + ChatColor.GRAY + s);
	}

	private void refill() {
		for (Bot b : bots.values()) {
			if (!b.active || ++b.counter < Math.max(1, b.every)) continue;
			b.counter = 0;
			World w = Bukkit.getWorld(b.world);
			if (w == null) continue;
			for (String s : b.spots) {
				String[] p = s.split(",");
				Block blk = w.getBlockAt(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
				if (blk.getType() == Material.AIR) blk.setType(Material.SAND); // with physics, so it falls like placed sand
			}
		}
	}

	private void showSpots() {
		for (Player p : Bukkit.getOnlinePlayers()) {
			UUID id = wandBot(p.getItemInHand());
			Bot b = id == null ? null : bots.get(id);
			if (b == null || !p.getWorld().getName().equals(b.world)) continue;
			for (String s : b.spots) {
				String[] c = s.split(",");
				Location l = new Location(p.getWorld(), Integer.parseInt(c[0]) + 0.5, Integer.parseInt(c[1]) + 0.5, Integer.parseInt(c[2]) + 0.5);
				p.spigot().playEffect(l, b.active ? Effect.HAPPY_VILLAGER : Effect.WITCH_MAGIC, 0, 0, 0.2f, 0.2f, 0.2f, 0f, 3, 48);
			}
		}
	}

	// ---- entity
	private void purgeOldStands() {
		for (World w : Bukkit.getWorlds())
			for (ArmorStand a : w.getEntitiesByClass(ArmorStand.class))
				if (a.getCustomName() != null && a.getCustomName().contains("Sandbot")) a.remove();
	}

	private void spawnEntity(Bot b) {
		Location l = b.loc();
		if (l == null) return;
		removeEntity(b);
		ArmorStand a = l.getWorld().spawn(l, ArmorStand.class);
		a.setSmall(true);
		a.setGravity(false);
		a.setBasePlate(false);
		a.setArms(true);
		a.setHelmet(new ItemStack(Material.SAND));
		a.setItemInHand(new ItemStack(Material.SAND));
		a.setCustomName(b.active ? TAG + " " + ChatColor.GRAY + b.spots.size() + " spots - " + b.every + "t" : ChatColor.DARK_GRAY + "Sandbot (paused)");
		a.setCustomNameVisible(true);
		b.entity = a.getUniqueId();
	}

	private void removeEntity(Bot b) {
		if (b.entity == null) return;
		Location l = b.loc();
		if (l != null) for (Entity e : l.getWorld().getEntities()) if (e.getUniqueId().equals(b.entity)) e.remove();
		b.entity = null;
	}

	private Bot botOf(Entity e) {
		for (Bot b : bots.values()) if (e.getUniqueId().equals(b.entity)) return b;
		return null;
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onInteractBot(PlayerInteractAtEntityEvent e) {
		Bot b = botOf(e.getRightClicked());
		if (b == null) return;
		e.setCancelled(true);
		if (e.getPlayer().isSneaking()) {
			delete(b);
			say(e.getPlayer(), "Sandbot removed.");
		} else {
			b.active = !b.active;
			spawnEntity(b);
			save();
			say(e.getPlayer(), b.active ? "Running." : "Paused.");
		}
	}

	@EventHandler
	public void onManipulate(PlayerArmorStandManipulateEvent e) {
		if (botOf(e.getRightClicked()) != null) e.setCancelled(true);
	}

	@EventHandler
	public void onDamage(EntityDamageEvent e) {
		if (botOf(e.getEntity()) != null) e.setCancelled(true);
	}

	// ---- wand
	private ItemStack wand(Bot b) {
		ItemStack it = new ItemStack(Material.BLAZE_ROD);
		ItemMeta m = it.getItemMeta();
		m.setDisplayName(WAND);
		m.setLore(Arrays.asList(ChatColor.GRAY + "Left-click a block face to add/remove a sand spot",
				ChatColor.GRAY + "Spots sparkle while you hold this", ID_PREFIX + b.id));
		it.setItemMeta(m);
		return it;
	}

	private UUID wandBot(ItemStack it) {
		if (it == null || it.getType() != Material.BLAZE_ROD || !it.hasItemMeta() || !it.getItemMeta().hasLore()) return null;
		for (String line : it.getItemMeta().getLore())
			if (line.startsWith(ID_PREFIX)) try {
				return UUID.fromString(line.substring(ID_PREFIX.length()));
			} catch (IllegalArgumentException ignored) { }
		return null;
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void onWand(PlayerInteractEvent e) {
		if (e.getAction() != Action.LEFT_CLICK_BLOCK || e.getClickedBlock() == null) return;
		UUID id = wandBot(e.getItem());
		if (id == null) return;
		e.setCancelled(true);
		Player p = e.getPlayer();
		Bot b = bots.get(id);
		if (b == null) {
			say(p, ChatColor.RED + "That bot no longer exists.");
			return;
		}
		Block spot = e.getClickedBlock().getRelative(e.getBlockFace());
		String key = spot.getX() + "," + spot.getY() + "," + spot.getZ();
		if (b.spots.remove(key)) say(p, "Spot removed (" + b.spots.size() + ")");
		else {
			b.spots.add(key);
			p.playSound(p.getLocation(), Sound.DIG_SAND, 0.8f, 1.4f);
			say(p, "Spot added (" + b.spots.size() + ")");
		}
		spawnEntity(b);
		save();
	}

	// ---- commands
	private Bot nearest(Player p) {
		Bot best = null;
		double bestD = 64 * 64;
		for (Bot b : bots.values()) {
			if (!b.world.equals(p.getWorld().getName())) continue;
			double d = p.getLocation().distanceSquared(b.loc());
			if (d < bestD) {
				bestD = d;
				best = b;
			}
		}
		return best;
	}

	private void delete(Bot b) {
		removeEntity(b);
		bots.remove(b.id);
		save();
	}

	boolean command(Player p, String[] args) {
		String sub = args.length == 0 ? "help" : args[0].toLowerCase();
		Bot b;
		if (sub.equals("create") || sub.equals("place") || sub.equals("spawn")) {
			b = new Bot();
			b.id = UUID.randomUUID();
			Location l = p.getLocation();
			b.world = l.getWorld().getName();
			b.x = l.getBlockX() + 0.5;
			b.y = l.getY();
			b.z = l.getBlockZ() + 0.5;
			bots.put(b.id, b);
			spawnEntity(b);
			save();
			p.getInventory().addItem(wand(b));
			say(p, "Placed! Left-click block faces with the wand to mark sand spots.");
			return true;
		}
		if (sub.equals("list")) {
			if (bots.isEmpty()) say(p, "No sandbots. /sandbot create");
			for (Bot x : bots.values())
				say(p, x.world + " " + (int) x.x + " " + (int) x.y + " " + (int) x.z + " - " + x.spots.size() + " spots, " + x.every + "t, " + (x.active ? "running" : "paused"));
			return true;
		}
		if (sub.equals("help")) {
			say(p, ChatColor.WHITE + "/sandbot create" + ChatColor.GRAY + " - place a bot here (gives its wand)");
			say(p, ChatColor.WHITE + "/sandbot speed <ticks>" + ChatColor.GRAY + " - refill rate (1 = every tick, 20 = 1s)");
			say(p, ChatColor.WHITE + "/sandbot wand | toggle | clear | remove | list");
			say(p, "Right-click a bot to pause it, sneak + right-click to remove it.");
			return true;
		}
		b = nearest(p);
		if (b == null) {
			say(p, ChatColor.RED + "No sandbot within 64 blocks.");
			return true;
		}
		if (sub.equals("wand")) p.getInventory().addItem(wand(b));
		else if (sub.equals("remove") || sub.equals("delete")) {
			delete(b);
			say(p, "Sandbot removed.");
		} else if (sub.equals("clear")) {
			b.spots.clear();
			spawnEntity(b);
			save();
			say(p, "All spots cleared.");
		} else if (sub.equals("toggle")) {
			b.active = !b.active;
			spawnEntity(b);
			save();
			say(p, b.active ? "Running." : "Paused.");
		} else if (sub.equals("speed") || sub.equals("rate") || sub.equals("delay")) {
			if (args.length < 2) {
				say(p, "Refills every " + b.every + "t. /sandbot speed <ticks>");
				return true;
			}
			try {
				b.every = Math.max(1, Math.min(1200, Integer.parseInt(args[1])));
			} catch (NumberFormatException ex) {
				say(p, ChatColor.RED + "Use a number of ticks, e.g. /sandbot speed 1");
				return true;
			}
			b.counter = 0;
			spawnEntity(b);
			save();
			say(p, "Now refills every " + b.every + "t.");
		} else say(p, ChatColor.RED + "Unknown. /sandbot help");
		return true;
	}

	// ---- storage
	private void load() {
		if (!file.exists()) return;
		YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
		for (String k : y.getKeys(false)) {
			try {
				Bot b = new Bot();
				b.id = UUID.fromString(k);
				b.world = y.getString(k + ".world");
				b.x = y.getDouble(k + ".x");
				b.y = y.getDouble(k + ".y");
				b.z = y.getDouble(k + ".z");
				b.active = y.getBoolean(k + ".active", true);
				b.every = y.getInt(k + ".every", 2);
				b.spots.addAll(y.getStringList(k + ".spots"));
				bots.put(b.id, b);
			} catch (Exception ex) {
				plugin.getLogger().warning("Skipping bad sandbot entry " + k);
			}
		}
	}

	private void save() {
		YamlConfiguration y = new YamlConfiguration();
		for (Bot b : bots.values()) {
			String k = b.id.toString();
			y.set(k + ".world", b.world);
			y.set(k + ".x", b.x);
			y.set(k + ".y", b.y);
			y.set(k + ".z", b.z);
			y.set(k + ".active", b.active);
			y.set(k + ".every", b.every);
			y.set(k + ".spots", new ArrayList<String>(b.spots));
		}
		try {
			plugin.getDataFolder().mkdirs();
			y.save(file);
		} catch (Exception ex) {
			plugin.getLogger().warning("Couldn't save sandbots: " + ex.getMessage());
		}
	}
}
