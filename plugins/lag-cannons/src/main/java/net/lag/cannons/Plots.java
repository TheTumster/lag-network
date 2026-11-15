package net.lag.cannons;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Cannon test plots (config plots.*). A "plots" world of PLOT x PLOT plots separated by ROAD-wide roads, flat at y=60.
 * /plot auto|claim|home|add|remove|clear|delete|info|list. Only the owner and trusted players build in a plot; TNT, sand,
 * pistons and water can't affect another plot (TNT and sand are traced back to the plot they came from).
 */
final class Plots implements Listener, CommandExecutor, TabCompleter {
	static final int SURFACE = 60;

	private final JavaPlugin plugin;
	private final File file;
	private final YamlConfiguration data;
	private final NamespacedKey originKey;
	private final int plot, road, cell;
	private World world;
	private final Set<String> clearing = new HashSet<>();

	Plots(JavaPlugin plugin) {
		this.plugin = plugin;
		this.plot = plugin.getConfig().getInt("plots.size", 128);
		this.road = plugin.getConfig().getInt("plots.road", 7);
		this.cell = plot + road;
		this.file = new File(plugin.getDataFolder(), "plots.yml");
		this.data = YamlConfiguration.loadConfiguration(file);
		this.originKey = new NamespacedKey(plugin, "plot");
	}

	// ---------------------------------------------------------------- world

	final class Gen extends ChunkGenerator {
		@Override
		public void generateSurface(WorldInfo info, Random random, int chunkX, int chunkZ, ChunkData d) {
			int min = d.getMinHeight();
			for (int x = 0; x < 16; x++) {
				for (int z = 0; z < 16; z++) {
					int wx = chunkX * 16 + x, wz = chunkZ * 16 + z;
					d.setBlock(x, min, z, Material.BEDROCK);
					for (int y = min + 1; y < SURFACE - 3; y++) d.setBlock(x, y, z, Material.STONE);
					boolean onRoad = isRoad(wx) || isRoad(wz);
					if (onRoad) {
						for (int y = SURFACE - 3; y < SURFACE; y++) d.setBlock(x, y, z, Material.DEEPSLATE);
						int lx = Math.floorMod(wx, cell), lz = Math.floorMod(wz, cell);
						boolean edge = lx == road - 1 || lz == road - 1 || lx == 0 || lz == 0;
						boolean centre = lx == road / 2 || lz == road / 2;
						d.setBlock(x, SURFACE, z, centre ? Material.POLISHED_BLACKSTONE_BRICKS : Material.POLISHED_DEEPSLATE);
						if (edge && !(isRoad(wx) && isRoad(wz))) d.setBlock(x, SURFACE + 1, z, Material.POLISHED_BLACKSTONE_BRICK_SLAB);
					} else {
						for (int y = SURFACE - 3; y < SURFACE; y++) d.setBlock(x, y, z, Material.DIRT);
						d.setBlock(x, SURFACE, z, Material.GRASS_BLOCK);
					}
				}
			}
		}

		@Override
		public Location getFixedSpawnLocation(World w, Random r) {
			return new Location(w, road / 2.0, SURFACE + 1, road / 2.0);
		}
	}

	boolean isRoad(int c) {
		return Math.floorMod(c, cell) < road;
	}

	void start() {
		if (!plugin.getConfig().getBoolean("plots.enabled", false)) return;
		String name = plugin.getConfig().getString("plots.world", "plots");
		world = Bukkit.getWorld(name);
		if (world == null) {
			world = new WorldCreator(name).generator(new Gen()).generateStructures(false).createWorld();
		}
		if (world == null) return;
		world.setDifficulty(Difficulty.PEACEFUL);
		world.setSpawnFlags(false, false);
		for (GameRule<?> g : GameRule.values()) {
			String n = g.getName().toLowerCase(Locale.ROOT);
			if (n.contains("daylight") || n.contains("advance_time") || n.contains("weather") || n.contains("mob_spawning") || n.contains("spawn_mobs")) {
				if (g.getType() == Boolean.class) set(world, g, false);
			}
		}
		world.setTime(6000);
		if (Bukkit.getPluginManager().getPlugin("WorldGuard") != null) {
			try {
				PlotRegions.setupWorld(world);
				for (String id : ids()) region(id);
			} catch (Throwable t) {
				plugin.getLogger().warning("WorldGuard plot regions unavailable: " + t);
			}
		}
		plugin.getLogger().info("Plot world '" + name + "' ready (" + plot + "x" + plot + " plots, " + ids().size() + " claimed)");
	}

	@SuppressWarnings("unchecked")
	private static <T> void set(World w, GameRule<?> g, T v) {
		w.setGameRule((GameRule<T>) g, v);
	}

	boolean enabled() {
		return world != null;
	}

	// ---------------------------------------------------------------- plot ids

	/** "x;z" of the plot at a location, or null on a road / other world. */
	String idAt(Location l) {
		if (world == null || l.getWorld() == null || !l.getWorld().equals(world)) return null;
		int x = l.getBlockX(), z = l.getBlockZ();
		if (isRoad(x) || isRoad(z)) return null;
		return Math.floorDiv(x, cell) + ";" + Math.floorDiv(z, cell);
	}

	private int[] xz(String id) {
		String[] p = id.split(";");
		return new int[] {Integer.parseInt(p[0]), Integer.parseInt(p[1])};
	}

	/** Inclusive block bounds {x1, z1, x2, z2} of a plot's interior. */
	int[] bounds(String id) {
		int[] c = xz(id);
		int x1 = c[0] * cell + road, z1 = c[1] * cell + road;
		return new int[] {x1, z1, x1 + plot - 1, z1 + plot - 1};
	}

	Location home(String id) {
		int[] b = bounds(id);
		Location l = new Location(world, (b[0] + b[2]) / 2.0 + 0.5, SURFACE + 1, b[1] - road / 2.0);
		l.setYaw(0); // facing +z, into the plot
		return l;
	}

	List<String> ids() {
		var s = data.getConfigurationSection("plots");
		return s == null ? new ArrayList<>() : new ArrayList<>(s.getKeys(false));
	}

	UUID owner(String id) {
		String o = data.getString("plots." + id + ".owner");
		return o == null ? null : UUID.fromString(o);
	}

	List<UUID> trusted(String id) {
		List<UUID> out = new ArrayList<>();
		for (String s : data.getStringList("plots." + id + ".trusted")) out.add(UUID.fromString(s));
		return out;
	}

	List<String> owned(UUID u) {
		List<String> out = new ArrayList<>();
		for (String id : ids()) if (u.equals(owner(id))) out.add(id);
		return out;
	}

	boolean canBuild(Player p, Location l) {
		if (p.hasPermission("lag.cannons.plot.admin") && p.getGameMode() == org.bukkit.GameMode.CREATIVE && p.isSneaking()) return true;
		String id = idAt(l);
		if (id == null) return p.hasPermission("lag.cannons.plot.admin");
		UUID o = owner(id);
		return o != null && (o.equals(p.getUniqueId()) || trusted(id).contains(p.getUniqueId()) || p.hasPermission("lag.cannons.plot.admin"));
	}

	private void save() {
		try {
			data.save(file);
		} catch (Exception e) {
			plugin.getLogger().warning("Couldn't save plots: " + e);
		}
	}

	private void region(String id) {
		if (Bukkit.getPluginManager().getPlugin("WorldGuard") == null) return;
		try {
			UUID o = owner(id);
			if (o == null) {
				PlotRegions.remove(world, id);
				return;
			}
			int[] b = bounds(id);
			PlotRegions.set(world, id, b[0], b[1], b[2], b[3], o, trusted(id));
		} catch (Throwable t) {
			plugin.getLogger().warning("Plot region " + id + ": " + t);
		}
	}

	private void border(String id, boolean claimed) {
		int[] b = bounds(id);
		BlockData slab = Bukkit.createBlockData(claimed ? Material.PURPUR_SLAB : Material.POLISHED_BLACKSTONE_BRICK_SLAB);
		for (int x = b[0] - 1; x <= b[2] + 1; x++) {
			world.getBlockAt(x, SURFACE + 1, b[1] - 1).setBlockData(slab, false);
			world.getBlockAt(x, SURFACE + 1, b[3] + 1).setBlockData(slab, false);
		}
		for (int z = b[1] - 1; z <= b[3] + 1; z++) {
			world.getBlockAt(b[0] - 1, SURFACE + 1, z).setBlockData(slab, false);
			world.getBlockAt(b[2] + 1, SURFACE + 1, z).setBlockData(slab, false);
		}
	}

	// ---------------------------------------------------------------- commands

	private void msg(CommandSender s, String m) {
		s.sendMessage(MiniMessage.miniMessage().deserialize("<dark_gray>[<gradient:#FF9F4D:#FF4D6D>Plots</gradient><dark_gray>] <gray>" + m));
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!(sender instanceof Player p)) {
			sender.sendMessage("Players only.");
			return true;
		}
		if (world == null) {
			msg(p, "Plots aren't enabled on this server.");
			return true;
		}
		String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
		int max = p.hasPermission("lag.cannons.plot.admin") ? 99 : plugin.getConfig().getInt("plots.max-per-player", 1);
		switch (sub) {
			case "auto" -> {
				if (owned(p.getUniqueId()).size() >= max) {
					msg(p, "You already have a plot. <white>/plot home");
					return true;
				}
				String id = nextFree();
				claim(p, id);
			}
			case "claim" -> {
				String id = idAt(p.getLocation());
				if (id == null) {
					msg(p, "Stand inside a plot to claim it, or use <white>/plot auto");
				} else if (owner(id) != null) {
					msg(p, "That plot is taken.");
				} else if (owned(p.getUniqueId()).size() >= max) {
					msg(p, "You already have a plot. <white>/plot home");
				} else {
					claim(p, id);
				}
			}
			case "home", "h", "tp" -> {
				List<String> mine = owned(p.getUniqueId());
				if (args.length > 1 && !sub.equals("home")) {
					OfflinePlayer t = Bukkit.getOfflinePlayer(args[1]);
					mine = owned(t.getUniqueId());
				}
				if (mine.isEmpty()) {
					msg(p, "No plot yet. Get one with <white>/plot auto");
				} else {
					p.teleport(home(mine.get(0)));
					msg(p, "Welcome to your plot.");
				}
			}
			case "add", "trust", "remove", "untrust" -> {
				String id = idAt(p.getLocation());
				if (id == null || !(p.getUniqueId().equals(owner(id)) || p.hasPermission("lag.cannons.plot.admin"))) {
					msg(p, "Stand in your own plot to do that.");
					return true;
				}
				if (args.length < 2) {
					msg(p, "/plot " + sub + " <player>");
					return true;
				}
				OfflinePlayer t = Bukkit.getOfflinePlayer(args[1]);
				List<String> tr = data.getStringList("plots." + id + ".trusted");
				boolean add = sub.equals("add") || sub.equals("trust");
				if (add) {
					if (!tr.contains(t.getUniqueId().toString())) tr.add(t.getUniqueId().toString());
				} else {
					tr.remove(t.getUniqueId().toString());
				}
				data.set("plots." + id + ".trusted", tr);
				save();
				region(id);
				msg(p, (add ? "Added <white>" : "Removed <white>") + args[1] + "<gray>.");
			}
			case "clear", "reset" -> {
				String id = idAt(p.getLocation());
				if (id == null || !(p.getUniqueId().equals(owner(id)) || p.hasPermission("lag.cannons.plot.admin"))) {
					msg(p, "Stand in your own plot to clear it.");
					return true;
				}
				if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
					msg(p, "This wipes everything in your plot. Type <white>/plot clear confirm");
					return true;
				}
				clear(id, p);
			}
			case "delete", "unclaim" -> {
				String id = idAt(p.getLocation());
				if (id == null || !(p.getUniqueId().equals(owner(id)) || p.hasPermission("lag.cannons.plot.admin"))) {
					msg(p, "Stand in your own plot to delete it.");
					return true;
				}
				if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
					msg(p, "This clears and gives up your plot. Type <white>/plot delete confirm");
					return true;
				}
				data.set("plots." + id, null);
				save();
				region(id);
				border(id, false);
				clear(id, p);
			}
			case "info", "i" -> {
				String id = idAt(p.getLocation());
				if (id == null) {
					msg(p, "You're on a road.");
					return true;
				}
				UUID o = owner(id);
				List<String> names = new ArrayList<>();
				for (UUID u : trusted(id)) names.add(String.valueOf(Bukkit.getOfflinePlayer(u).getName()));
				msg(p, "Plot <white>" + id + "<gray> · owner <white>" + (o == null ? "nobody" : Bukkit.getOfflinePlayer(o).getName())
						+ (names.isEmpty() ? "" : "<gray> · added <white>" + String.join(", ", names)));
			}
			default -> {
				msg(p, "<white>/plot auto <gray>get a plot · <white>/plot home <gray>go to it · <white>/plot add <player>"
						+ " <gray>· <white>/plot remove <player> <gray>· <white>/plot clear confirm <gray>· <white>/plot info");
			}
		}
		return true;
	}

	@Override
	public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
		if (a.length == 1) return List.of("auto", "claim", "home", "add", "remove", "clear", "delete", "info");
		return null;
	}

	private String nextFree() {
		// spiral out from 0;0
		int x = 0, z = 0, dx = 0, dz = -1;
		for (int i = 0; i < 100000; i++) {
			String id = x + ";" + z;
			if (owner(id) == null && !clearing.contains(id)) return id;
			if (x == z || (x < 0 && x == -z) || (x > 0 && x == 1 - z)) {
				int t = dx;
				dx = -dz;
				dz = t;
			}
			x += dx;
			z += dz;
		}
		return "0;0";
	}

	private void claim(Player p, String id) {
		data.set("plots." + id + ".owner", p.getUniqueId().toString());
		data.set("plots." + id + ".name", p.getName());
		data.set("plots." + id + ".claimed", System.currentTimeMillis());
		save();
		region(id);
		border(id, true);
		p.teleport(home(id));
		msg(p, "Plot <white>" + id + "<gray> is yours (" + plot + "x" + plot + "). Build, paste with <white>//schem<gray>, and <white>/tntfill<gray> for free.");
	}

	/** Restore a plot to fresh ground, a few columns per tick so the server doesn't freeze. */
	void clear(String id, CommandSender who) {
		if (!clearing.add(id)) return;
		int[] b = bounds(id);
		int min = world.getMinHeight() + 1, max = world.getMaxHeight();
		BlockData air = Bukkit.createBlockData(Material.AIR), stone = Bukkit.createBlockData(Material.STONE),
				dirt = Bukkit.createBlockData(Material.DIRT), grass = Bukkit.createBlockData(Material.GRASS_BLOCK);
		for (Entity e : world.getEntities()) {
			if (!(e instanceof Player) && id.equals(idAt(e.getLocation()))) e.remove();
		}
		final int[] col = {0};
		final int total = plot * plot;
		Bukkit.getScheduler().runTaskTimer(plugin, task -> {
			int done = 0;
			while (col[0] < total && done < 48) {
				int x = b[0] + col[0] % plot, z = b[1] + col[0] / plot;
				for (int y = min; y < max; y++) {
					Block bl = world.getBlockAt(x, y, z);
					BlockData want = y < SURFACE - 3 ? stone : y < SURFACE ? dirt : y == SURFACE ? grass : air;
					if (bl.getType() != want.getMaterial()) bl.setBlockData(want, false);
				}
				col[0]++;
				done++;
			}
			if (col[0] >= total) {
				task.cancel();
				clearing.remove(id);
				if (who instanceof Player p && p.isOnline()) msg(p, "Plot <white>" + id + "<gray> is fresh again.");
			}
		}, 1L, 1L);
		msg(who, "Clearing plot <white>" + id + "<gray>...");
	}

	// ---------------------------------------------------------------- protection

	private boolean inWorld(Location l) {
		return world != null && l.getWorld() != null && l.getWorld().equals(world);
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onBreak(BlockBreakEvent e) {
		if (inWorld(e.getBlock().getLocation()) && !canBuild(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onPlace(BlockPlaceEvent e) {
		if (inWorld(e.getBlock().getLocation()) && !canBuild(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onBucket(PlayerBucketEmptyEvent e) {
		if (inWorld(e.getBlock().getLocation()) && !canBuild(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onFill(PlayerBucketFillEvent e) {
		if (inWorld(e.getBlock().getLocation()) && !canBuild(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
	}

	/** No pressing someone else's buttons/levers or opening their dispensers. */
	@EventHandler(priority = EventPriority.HIGH)
	public void onUse(PlayerInteractEvent e) {
		if (e.getClickedBlock() == null || !inWorld(e.getClickedBlock().getLocation())) return;
		if (e.getAction() == Action.RIGHT_CLICK_BLOCK || e.getAction() == Action.PHYSICAL) {
			if (!canBuild(e.getPlayer(), e.getClickedBlock().getLocation())) e.setCancelled(true);
		}
	}

	/** Remember which plot TNT / falling sand came from. */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onSpawn(EntitySpawnEvent e) {
		Entity en = e.getEntity();
		if (!(en instanceof TNTPrimed) && !(en instanceof FallingBlock)) return;
		if (!inWorld(en.getLocation())) return;
		String id = idAt(en.getLocation());
		en.getPersistentDataContainer().set(originKey, PersistentDataType.STRING, id == null ? "road" : id);
	}

	private String origin(Entity en) {
		String o = en.getPersistentDataContainer().get(originKey, PersistentDataType.STRING);
		return o != null ? o : idAt(en.getLocation());
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onExplode(EntityExplodeEvent e) {
		if (!inWorld(e.getLocation())) return;
		String from = origin(e.getEntity());
		e.blockList().removeIf(b -> from == null || "road".equals(from) || !from.equals(idAt(b.getLocation())));
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onBlockExplode(BlockExplodeEvent e) {
		if (!inWorld(e.getBlock().getLocation())) return;
		String from = idAt(e.getBlock().getLocation());
		e.blockList().removeIf(b -> from == null || !from.equals(idAt(b.getLocation())));
	}

	/** Falling sand only lands in the plot it came from. */
	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onLand(EntityChangeBlockEvent e) {
		if (!(e.getEntity() instanceof FallingBlock fb) || !inWorld(e.getBlock().getLocation())) return;
		if (e.getTo() == Material.AIR) return; // starting to fall
		String from = origin(fb);
		String to = idAt(e.getBlock().getLocation());
		if (to == null || from == null || !from.equals(to)) {
			e.setCancelled(true);
			fb.remove();
		}
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onFlow(BlockFromToEvent e) {
		if (!inWorld(e.getBlock().getLocation())) return;
		String a = idAt(e.getBlock().getLocation()), b = idAt(e.getToBlock().getLocation());
		if (a == null || !a.equals(b)) e.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onPush(BlockPistonExtendEvent e) {
		if (!inWorld(e.getBlock().getLocation())) return;
		String home = idAt(e.getBlock().getLocation());
		BlockFace f = e.getDirection();
		for (Block b : e.getBlocks()) {
			if (home == null || !home.equals(idAt(b.getRelative(f).getLocation()))) {
				e.setCancelled(true);
				return;
			}
		}
		if (home == null || !home.equals(idAt(e.getBlock().getRelative(f).getLocation()))) e.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onPull(BlockPistonRetractEvent e) {
		if (!inWorld(e.getBlock().getLocation())) return;
		String home = idAt(e.getBlock().getLocation());
		for (Block b : e.getBlocks()) {
			if (home == null || !home.equals(idAt(b.getLocation()))) {
				e.setCancelled(true);
				return;
			}
		}
	}
}
