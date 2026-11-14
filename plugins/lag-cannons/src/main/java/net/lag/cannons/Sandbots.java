package net.lag.cannons;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Sandbots: little bots that keep refilling sand at the spots you mark, so sand cannons can fire over and over
 * without someone standing there placing sand.
 *
 *  /sandbot create        spawn a bot where you stand (you get its wand)
 *  /sandbot wand          get the wand for the nearest of your bots
 *  Wand: left-click a block face to add/remove a sand spot on it. Spots glow while you hold the wand.
 *  Right-click a bot to pause/resume it, sneak + right-click to remove it.
 *  /sandbot list | remove | clear | toggle
 */
final class Sandbots implements Listener {
	private static final MiniMessage MM = MiniMessage.miniMessage();

	static final class Bot {
		UUID id;
		UUID owner;
		String world;
		double x, y, z;
		boolean active = true;
		final Set<String> spots = new LinkedHashSet<>(); // "x,y,z"
		UUID entity;

		Location loc() {
			World w = Bukkit.getWorld(world);
			return w == null ? null : new Location(w, x, y, z);
		}
	}

	private final JavaPlugin plugin;
	private final NamespacedKey botKey;
	private final NamespacedKey wandKey;
	private final Map<UUID, Bot> bots = new HashMap<>();
	private final File file;
	private BukkitTask task;
	private BukkitTask glowTask;

	Sandbots(JavaPlugin plugin) {
		this.plugin = plugin;
		this.botKey = new NamespacedKey(plugin, "sandbot");
		this.wandKey = new NamespacedKey(plugin, "sandbot_wand");
		this.file = new File(plugin.getDataFolder(), "sandbots.yml");
		load();
		// spawn entities once worlds are ready
		Bukkit.getScheduler().runTask(plugin, () -> bots.values().forEach(this::spawnEntity));
		restartTask();
		glowTask = Bukkit.getScheduler().runTaskTimer(plugin, this::showSpots, 10L, 10L);
	}

	private static void say(CommandSender s, String mm) {
		s.sendMessage(MM.deserialize(mm));
	}

	void restartTask() {
		if (task != null) task.cancel();
		long every = Math.max(1, plugin.getConfig().getLong("sandbots.interval-ticks", 20));
		task = Bukkit.getScheduler().runTaskTimer(plugin, this::refill, every, every);
	}

	void shutdown() {
		save();
		if (task != null) task.cancel();
		if (glowTask != null) glowTask.cancel();
		for (Bot b : bots.values()) removeEntity(b);
	}

	// ---------------------------------------------------------------- refilling

	private Material material() {
		Material m = Material.matchMaterial(plugin.getConfig().getString("sandbots.material", "SAND"));
		return m != null && m.hasGravity() ? m : Material.SAND;
	}

	private void refill() {
		Material mat = material();
		for (Bot b : bots.values()) {
			if (!b.active) continue;
			World w = Bukkit.getWorld(b.world);
			if (w == null || !w.isChunkLoaded((int) Math.floor(b.x) >> 4, (int) Math.floor(b.z) >> 4)) continue;
			for (String s : b.spots) {
				String[] p = s.split(",");
				Block blk = w.getBlockAt(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
				if (blk.getType().isAir()) blk.setType(mat, true);
			}
		}
	}

	private void showSpots() {
		for (Player p : Bukkit.getOnlinePlayers()) {
			UUID botId = wandBot(p.getInventory().getItemInMainHand());
			if (botId == null) continue;
			Bot b = bots.get(botId);
			if (b == null || !p.getWorld().getName().equals(b.world)) continue;
			Particle.DustOptions dust = new Particle.DustOptions(b.active ? Color.fromRGB(56, 224, 255) : Color.fromRGB(176, 64, 255), 1.2f);
			for (String s : b.spots) {
				String[] c = s.split(",");
				p.spawnParticle(Particle.DUST, Integer.parseInt(c[0]) + 0.5, Integer.parseInt(c[1]) + 0.5, Integer.parseInt(c[2]) + 0.5, 3, 0.2, 0.2, 0.2, 0, dust);
			}
		}
	}

	// ---------------------------------------------------------------- entity

	private void spawnEntity(Bot b) {
		Location l = b.loc();
		if (l == null) return;
		removeEntity(b);
		ArmorStand as = l.getWorld().spawn(l, ArmorStand.class, a -> {
			a.setSmall(true);
			a.setGravity(false);
			a.setInvulnerable(true);
			a.setPersistent(false);
			a.setBasePlate(false);
			a.setArms(true);
			a.getEquipment().setHelmet(new ItemStack(Material.SAND));
			a.getEquipment().setItemInMainHand(new ItemStack(Material.SAND));
			a.customName(MM.deserialize(b.active ? "<gradient:#38E0FF:#B040FF><bold>Sandbot</bold></gradient> <gray>" + b.spots.size() + " spots"
					: "<dark_gray>Sandbot <gray>(paused)"));
			a.setCustomNameVisible(true);
			a.getPersistentDataContainer().set(botKey, PersistentDataType.STRING, b.id.toString());
		});
		b.entity = as.getUniqueId();
	}

	private void removeEntity(Bot b) {
		if (b.entity == null) return;
		Entity e = Bukkit.getEntity(b.entity);
		if (e != null) e.remove();
		b.entity = null;
	}

	private Bot botOf(Entity e) {
		String id = e.getPersistentDataContainer().get(botKey, PersistentDataType.STRING);
		return id == null ? null : bots.get(UUID.fromString(id));
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onInteractBot(PlayerInteractAtEntityEvent e) {
		Bot b = botOf(e.getRightClicked());
		if (b == null) return;
		e.setCancelled(true);
		Player p = e.getPlayer();
		if (!canManage(p, b)) {
			say(p, "<red>That's not your sandbot.");
			return;
		}
		if (p.isSneaking()) {
			delete(b);
			say(p, "<gray>Sandbot removed.");
		} else {
			b.active = !b.active;
			spawnEntity(b);
			save();
			say(p, b.active ? "<#38E0FF>Sandbot running." : "<gray>Sandbot paused.");
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onManipulate(PlayerArmorStandManipulateEvent e) {
		if (botOf(e.getRightClicked()) != null) e.setCancelled(true);
	}

	@EventHandler(ignoreCancelled = true)
	public void onDamage(EntityDamageEvent e) {
		if (botOf(e.getEntity()) != null) e.setCancelled(true);
	}

	// ---------------------------------------------------------------- wand

	private ItemStack wand(Bot b) {
		ItemStack it = new ItemStack(Material.BLAZE_ROD);
		ItemMeta m = it.getItemMeta();
		m.displayName(MM.deserialize("<gradient:#38E0FF:#B040FF><bold>Sandbot Wand</bold></gradient>").decoration(TextDecoration.ITALIC, false));
		m.lore(List.of(
				MM.deserialize("<gray>Left-click a block face to add/remove a sand spot").decoration(TextDecoration.ITALIC, false),
				MM.deserialize("<gray>Spots glow while you hold this").decoration(TextDecoration.ITALIC, false)));
		m.setEnchantmentGlintOverride(true);
		m.getPersistentDataContainer().set(wandKey, PersistentDataType.STRING, b.id.toString());
		it.setItemMeta(m);
		return it;
	}

	private UUID wandBot(ItemStack it) {
		if (it == null || it.getType() != Material.BLAZE_ROD || !it.hasItemMeta()) return null;
		String id = it.getItemMeta().getPersistentDataContainer().get(wandKey, PersistentDataType.STRING);
		return id == null ? null : UUID.fromString(id);
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void onWand(PlayerInteractEvent e) {
		if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.LEFT_CLICK_BLOCK || e.getClickedBlock() == null) return;
		UUID id = wandBot(e.getItem());
		if (id == null) return;
		e.setCancelled(true);
		Player p = e.getPlayer();
		Bot b = bots.get(id);
		if (b == null) {
			say(p, "<red>That bot no longer exists.");
			return;
		}
		Block spot = e.getClickedBlock().getRelative(e.getBlockFace());
		String key = spot.getX() + "," + spot.getY() + "," + spot.getZ();
		if (b.spots.remove(key)) {
			say(p, "<gray>Spot removed <dark_gray>(" + b.spots.size() + ")");
		} else {
			Location bl = b.loc();
			int range = plugin.getConfig().getInt("sandbots.range", 16);
			if (bl == null || !bl.getWorld().equals(spot.getWorld()) || bl.distanceSquared(spot.getLocation()) > (double) range * range) {
				say(p, "<red>Too far from the bot (max " + range + " blocks).");
				return;
			}
			if (b.spots.size() >= plugin.getConfig().getInt("sandbots.max-spots", 64)) {
				say(p, "<red>This bot is at its spot limit.");
				return;
			}
			if (!canBuild(p, spot, e.getClickedBlock())) {
				say(p, "<red>You can't build there.");
				return;
			}
			b.spots.add(key);
			p.playSound(p.getLocation(), Sound.BLOCK_SAND_PLACE, 0.8f, 1.4f);
			say(p, "<#38E0FF>Spot added <dark_gray>(" + b.spots.size() + ")");
		}
		spawnEntity(b);
		save();
	}

	/** Ask the server's protection plugins (Factions, WorldGuard...) whether this player could place a block here. */
	private boolean canBuild(Player p, Block spot, Block against) {
		BlockPlaceEvent test = new BlockPlaceEvent(spot, spot.getState(), against, new ItemStack(material()), p, true, EquipmentSlot.HAND);
		Bukkit.getPluginManager().callEvent(test);
		return !test.isCancelled() && test.canBuild();
	}

	// ---------------------------------------------------------------- commands

	private boolean canManage(Player p, Bot b) {
		return b.owner.equals(p.getUniqueId()) || p.hasPermission("lag.cannons.admin");
	}

	private Bot nearest(Player p) {
		Bot best = null;
		double bestD = 64;
		for (Bot b : bots.values()) {
			if (!canManage(p, b) || !b.world.equals(p.getWorld().getName())) continue;
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

	boolean command(CommandSender sender, String[] args) {
		if (!(sender instanceof Player p)) return true;
		String sub = args.length == 0 ? "help" : args[0].toLowerCase();
		switch (sub) {
			case "create", "place", "spawn" -> {
				long mine = bots.values().stream().filter(b -> b.owner.equals(p.getUniqueId())).count();
				int max = plugin.getConfig().getInt("sandbots.max-bots-per-player", 5);
				if (mine >= max && !p.hasPermission("lag.cannons.admin")) {
					say(p, "<red>You already have " + max + " sandbots.");
					return true;
				}
				Block at = p.getLocation().getBlock();
				if (!canBuild(p, at, at.getRelative(0, -1, 0))) {
					say(p, "<red>You can't place a sandbot here.");
					return true;
				}
				Bot b = new Bot();
				b.id = UUID.randomUUID();
				b.owner = p.getUniqueId();
				Location l = p.getLocation();
				b.world = l.getWorld().getName();
				b.x = l.getBlockX() + 0.5;
				b.y = l.getY();
				b.z = l.getBlockZ() + 0.5;
				bots.put(b.id, b);
				spawnEntity(b);
				save();
				p.getInventory().addItem(wand(b));
				say(p, "<gradient:#38E0FF:#B040FF>Sandbot placed!</gradient> <gray>Left-click block faces with the wand to mark sand spots.");
			}
			case "wand" -> {
				Bot b = nearest(p);
				if (b == null) say(p, "<red>No sandbot of yours within 8 blocks.");
				else p.getInventory().addItem(wand(b));
			}
			case "remove", "delete" -> {
				Bot b = nearest(p);
				if (b == null) say(p, "<red>No sandbot of yours within 8 blocks.");
				else {
					delete(b);
					say(p, "<gray>Sandbot removed.");
				}
			}
			case "clear" -> {
				Bot b = nearest(p);
				if (b == null) say(p, "<red>No sandbot of yours within 8 blocks.");
				else {
					b.spots.clear();
					spawnEntity(b);
					save();
					say(p, "<gray>All spots cleared.");
				}
			}
			case "toggle" -> {
				Bot b = nearest(p);
				if (b == null) say(p, "<red>No sandbot of yours within 8 blocks.");
				else {
					b.active = !b.active;
					spawnEntity(b);
					save();
					say(p, b.active ? "<#38E0FF>Sandbot running." : "<gray>Sandbot paused.");
				}
			}
			case "list" -> {
				List<String> lines = new ArrayList<>();
				for (Bot b : bots.values()) {
					if (!canManage(p, b)) continue;
					lines.add("<gray>• <white>" + b.world + " " + (int) b.x + " " + (int) b.y + " " + (int) b.z
							+ " <gray>" + b.spots.size() + " spots " + (b.active ? "<#38E0FF>running" : "<dark_gray>paused"));
				}
				if (lines.isEmpty()) say(p, "<gray>You have no sandbots. <white>/sandbot create");
				else lines.forEach(l -> say(p, l));
			}
			default -> {
				say(p, "<gradient:#38E0FF:#B040FF><bold>Sandbots</bold></gradient>");
				say(p, "<white>/sandbot create <gray>- place a bot here (gives you its wand)");
				say(p, "<white>/sandbot wand <gray>- wand for your nearest bot");
				say(p, "<white>/sandbot toggle | clear | remove | list");
				say(p, "<gray>Wand: left-click block faces to add/remove sand spots.");
				say(p, "<gray>Right-click a bot to pause it, sneak + right-click to remove it.");
			}
		}
		return true;
	}

	// ---------------------------------------------------------------- storage

	private void load() {
		if (!file.exists()) return;
		YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
		for (String k : y.getKeys(false)) {
			try {
				Bot b = new Bot();
				b.id = UUID.fromString(k);
				b.owner = UUID.fromString(y.getString(k + ".owner"));
				b.world = y.getString(k + ".world");
				b.x = y.getDouble(k + ".x");
				b.y = y.getDouble(k + ".y");
				b.z = y.getDouble(k + ".z");
				b.active = y.getBoolean(k + ".active", true);
				b.spots.addAll(y.getStringList(k + ".spots"));
				bots.put(b.id, b);
			} catch (Exception ex) {
				plugin.getLogger().warning("Skipping bad sandbot entry " + k);
			}
		}
	}

	private void save() {
		YamlConfiguration y = new YamlConfiguration();
		for (Iterator<Bot> it = bots.values().iterator(); it.hasNext(); ) {
			Bot b = it.next();
			String k = b.id.toString();
			y.set(k + ".owner", b.owner.toString());
			y.set(k + ".world", b.world);
			y.set(k + ".x", b.x);
			y.set(k + ".y", b.y);
			y.set(k + ".z", b.z);
			y.set(k + ".active", b.active);
			y.set(k + ".spots", new ArrayList<>(b.spots));
		}
		try {
			plugin.getDataFolder().mkdirs();
			y.save(file);
		} catch (Exception ex) {
			plugin.getLogger().warning("Couldn't save sandbots: " + ex.getMessage());
		}
	}
}
