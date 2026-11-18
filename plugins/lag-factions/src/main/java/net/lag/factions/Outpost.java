package net.lag.factions;

import java.io.File;
import java.io.IOException;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.generator.ChunkGenerator;

import dev.kitteh.factions.Faction;
import net.kyori.adventure.title.Title;

/**
 * The raiding outpost: its own void world with a real cannon base. A core room (obsidian, the core beacon in the
 * middle) wrapped in outpost.walls concentric walls (default 100), each wall 1 thick with a column of water between
 * it and the next, so TNT landing there does nothing until you unwater it with sand. Every 5th wall is obsidian.
 * Break the core by hand to capture: your faction gets outpost.boost % faster spawners + farms until someone else
 * takes it. After a capture the base rebuilds itself over outpost.rebuild-minutes (default 30).
 *
 * Inside the base only explosions break walls (no hand-mining, no buckets, no sponges, no pillaring over the top,
 * no flying, no elytra, no pearling over the walls). /f outpost tp goes to the platform south of the base.
 */
final class Outpost implements Listener {
	static final String WORLD = "outpost";
	static final int LAYOUT = 2;
	private static final int Y = 64, CORE_R = 6;

	private final LagFactions plugin;
	private final File file;
	private World world;
	private int owner = Integer.MIN_VALUE;
	private long ownedSince;
	private int walls, height, outer;
	private final BlockData air = Material.AIR.createBlockData(), water = Material.WATER.createBlockData(),
			cobble = Material.COBBLESTONE.createBlockData(), obby = Material.OBSIDIAN.createBlockData(),
			bedrock = Material.BEDROCK.createBlockData(), floor = Material.DEEPSLATE_TILES.createBlockData(),
			floor2 = Material.POLISHED_BLACKSTONE_BRICKS.createBlockData(), iron = Material.IRON_BLOCK.createBlockData(),
			beacon = Material.BEACON.createBlockData(), glass = Material.PURPLE_STAINED_GLASS.createBlockData(),
			crying = Material.CRYING_OBSIDIAN.createBlockData();

	// progressive (re)build: walks the managed box chunk by chunk
	private int task = -1;
	private long cursor, total;

	Outpost(LagFactions plugin) {
		this.plugin = plugin;
		this.file = new File(plugin.getDataFolder(), "outpost.yml");
		if (!enabled()) return;
		walls = Math.max(1, plugin.getConfig().getInt("outpost.walls", 100));
		height = Math.max(8, plugin.getConfig().getInt("outpost.wall-height", 30));
		outer = CORE_R + 2 + 2 * (walls - 1); // chebyshev radius of the outermost wall
		YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
		owner = y.getInt("owner", Integer.MIN_VALUE);
		ownedSince = y.getLong("since", 0);
		Bukkit.getScheduler().runTask(plugin, () -> {
			world = new WorldCreator(WORLD).generator(new Void()).createWorld();
			if (world == null) return;
			world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
			world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
			world.setSpawnLocation(0, Y + 1, outer + 20);
			if (y.getInt("layout", 0) < LAYOUT || y.getInt("walls", 0) != walls || y.getInt("height", 0) != height) {
				plugin.getLogger().info("Building the outpost (" + walls + " watered walls). It fills in over a few minutes.");
				rebuild(plugin.getConfig().getLong("outpost.first-build-minutes", 5) * 1200, false);
				y.set("layout", LAYOUT);
				y.set("walls", walls);
				y.set("height", height);
				saveState(y);
			}
		});
	}

	boolean enabled() {
		return plugin.getConfig().getBoolean("outpost.enabled", true);
	}

	double boost(Faction f) {
		return f != null && f.id() == owner ? plugin.getConfig().getDouble("outpost.boost", 25) : 0;
	}

	Faction owner() {
		return owner == Integer.MIN_VALUE ? null : F.byId(owner);
	}

	// ---------------------------------------------------------------- the base (computed, not stored)

	private int pad() {
		return outer + 30; // platform around the base + the approach
	}

	/** What belongs at x,y,z (null = not managed). */
	private BlockData template(int x, int y, int z) {
		int pad = pad();
		if (Math.abs(x) > pad || Math.abs(z) > pad) return null;
		int d = Math.max(Math.abs(x), Math.abs(z));
		if (y == Y - 1) return bedrock;
		if (y == Y) return d <= outer ? floor : ((x + z) % 7 == 0 ? floor2 : floor);
		if (y < Y || y > Y + height) return null;
		if (d > outer) return null; // outside the walls is yours to build cannons on
		if (d <= CORE_R) { // core room
			if (x == 0 && z == 0 && y == Y + 2) return beacon;
			if (x == 0 && z == 0 && y == Y + 3) return glass;
			if (Math.abs(x) <= 1 && Math.abs(z) <= 1 && y == Y + 1) return iron;
			if (y > Y + 7) return air;
			if (d == CORE_R || y == Y + 7) return y == Y + 4 ? crying : obby;
			return air;
		}
		if (d == CORE_R + 1) return air; // walk-around corridor
		int k = d - (CORE_R + 2); // 0 = first wall, 1 = water, 2 = second wall ...
		if (k % 2 == 1) return water;
		return (k / 2) % 5 == 4 ? obby : cobble;
	}

	private boolean inBase(Block b) {
		return world != null && b.getWorld().equals(world) && Math.max(Math.abs(b.getX()), Math.abs(b.getZ())) <= outer + 1;
	}

	private boolean isCore(Block b) {
		return world != null && b.getWorld().equals(world) && b.getX() == 0 && b.getY() == Y + 2 && b.getZ() == 0;
	}

	/** Restore everything to the template over `ticks` ticks, chunk by chunk (core last). */
	void rebuild(long ticks, boolean announce) {
		if (world == null) return;
		if (task != -1) Bukkit.getScheduler().cancelTask(task);
		int chunks = (2 * pad() + 16) / 16 + 1;
		int ys = height + 2; // Y-1 .. Y+height
		long perChunk = 16L * 16 * ys;
		total = (long) chunks * chunks * perChunk;
		cursor = 0;
		long perTick = Math.max(50, total / Math.max(1, ticks));
		int minC = (-pad()) >> 4;
		task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
			long end = Math.min(total, cursor + perTick);
			for (; cursor < end; cursor++) {
				long c = cursor / perChunk, w = cursor % perChunk;
				int cx = minC + (int) (c / chunks), cz = minC + (int) (c % chunks);
				int x = (cx << 4) + (int) (w / (16L * ys));
				int z = (cz << 4) + (int) ((w / ys) % 16);
				int y = Y - 1 + (int) (w % ys);
				if (x == 0 && z == 0 && y == Y + 2) continue; // core goes in last
				BlockData want = template(x, y, z);
				if (want == null) continue;
				Block b = world.getBlockAt(x, y, z);
				if (!b.getBlockData().matches(want)) b.setBlockData(want, false);
			}
			if (cursor >= total) {
				Bukkit.getScheduler().cancelTask(task);
				task = -1;
				world.getBlockAt(0, Y + 2, 0).setBlockData(beacon, false);
				if (announce) Bukkit.broadcast(Ui.mm(Ui.PREFIX + "The " + Ui.PURPLE + "<bold>OUTPOST</bold> " + Ui.LABEL + "has rebuilt. The core is up for grabs."));
			}
		}, 1L, 1L).getTaskId();
	}

	int rebuildPercent() {
		return task == -1 || total == 0 ? 100 : (int) (cursor * 100 / total);
	}

	void startRebuild() {
		rebuild(Math.max(20, plugin.getConfig().getLong("outpost.rebuild-minutes", 30) * 1200), true);
	}

	// ---------------------------------------------------------------- capture + rules

	private static boolean staff(Player p) {
		return p.getGameMode() == GameMode.CREATIVE && p.hasPermission("lag.factions.admin");
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onBreak(BlockBreakEvent e) {
		Block b = e.getBlock();
		if (world == null || !b.getWorld().equals(world)) return;
		if (isCore(b)) {
			e.setCancelled(true);
			if (task != -1) { Ui.err(e.getPlayer(), "The outpost is rebuilding (" + rebuildPercent() + "%). The core comes back last."); return; }
			Faction f = F.of(e.getPlayer());
			if (f == null) { Ui.err(e.getPlayer(), "You need a faction to capture the outpost."); return; }
			if (f.id() == owner) { Ui.err(e.getPlayer(), "Your faction already holds the outpost."); return; }
			capture(f);
			return;
		}
		if (staff(e.getPlayer())) return;
		BlockData t = template(b.getX(), b.getY(), b.getZ());
		if (t != null && !t.getMaterial().isAir() && t.getMaterial() != Material.WATER) e.setCancelled(true); // walls only break to explosions
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onPlace(BlockPlaceEvent e) {
		Block b = e.getBlockPlaced();
		if (!inBase(b) || staff(e.getPlayer())) return;
		Material m = b.getType();
		boolean coreRoom = Math.max(Math.abs(b.getX()), Math.abs(b.getZ())) <= CORE_R + 1;
		boolean sponge = m == Material.SPONGE || m == Material.WET_SPONGE;
		if (coreRoom || sponge || b.getY() >= Y + height - 1) {
			e.setCancelled(true);
			Ui.err(e.getPlayer(), coreRoom ? "You can't build in the core room." : sponge ? "No sponges at the outpost. Unwater it with sand." : "You can't build over the walls.");
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onBucket(PlayerBucketFillEvent e) {
		if (inBase(e.getBlock()) && !staff(e.getPlayer())) { e.setCancelled(true); Ui.err(e.getPlayer(), "No buckets at the outpost. Unwater it with sand."); }
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onBucket(PlayerBucketEmptyEvent e) {
		if (inBase(e.getBlock()) && !staff(e.getPlayer())) e.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onExplode(EntityExplodeEvent e) {
		if (world == null || !e.getLocation().getWorld().equals(world)) return;
		e.blockList().removeIf(b -> isCore(b) || b.getY() <= Y);
	}

	/** No flying, gliding or pearling over the walls. */
	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onFly(PlayerToggleFlightEvent e) {
		if (e.isFlying() && world != null && e.getPlayer().getWorld().equals(world) && e.getPlayer().getGameMode() == GameMode.SURVIVAL) {
			e.setCancelled(true);
			e.getPlayer().setAllowFlight(false);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onGlide(EntityToggleGlideEvent e) {
		if (e.isGliding() && world != null && e.getEntity().getWorld().equals(world)) e.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onPearl(PlayerTeleportEvent e) {
		if (world == null || e.getTo() == null || !e.getTo().getWorld().equals(world)) return;
		if (e.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL && e.getCause() != PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT) return;
		int dFrom = Math.max(Math.abs(e.getFrom().getBlockX()), Math.abs(e.getFrom().getBlockZ()));
		int dTo = Math.max(Math.abs(e.getTo().getBlockX()), Math.abs(e.getTo().getBlockZ()));
		// a pearl can move you along a breach, but not jump you inward past standing walls
		if (dTo < dFrom - 3 && dTo <= outer) {
			e.setCancelled(true);
			Ui.err(e.getPlayer(), "You can't pearl over the outpost walls.");
		}
	}

	/** No claiming in the outpost world. */
	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onClaim(PlayerCommandPreprocessEvent e) {
		if (world == null || !e.getPlayer().getWorld().equals(world)) return;
		String m = e.getMessage().toLowerCase();
		if (m.matches("^/(f|fac|faction|factions) (claim|mapclaim|autoclaim|claimat|fill).*")) {
			e.setCancelled(true);
			Ui.err(e.getPlayer(), "You can't claim in the outpost world.");
		}
	}

	private void capture(Faction f) {
		Faction old = owner();
		owner = f.id();
		ownedSince = System.currentTimeMillis();
		saveState(null);
		world.getBlockAt(0, Y + 2, 0).setType(Material.AIR, false);
		double boost = plugin.getConfig().getDouble("outpost.boost", 25);
		Bukkit.broadcast(Ui.mm(Ui.PREFIX + "<white><bold>" + Ui.esc(f.tag()) + "</bold></white> " + Ui.LABEL + "captured the " + Ui.PURPLE + "<bold>OUTPOST</bold>"
				+ (old == null ? "" : Ui.LABEL + " from <white>" + Ui.esc(old.tag())) + Ui.LABEL + "! " + Ui.GREEN + "+" + (int) boost + "% spawner + farm speed"));
		for (Player o : Bukkit.getOnlinePlayers()) {
			Faction of = F.of(o);
			boolean mine = of != null && of.id() == f.id();
			o.showTitle(Title.title(Ui.mm(Ui.PURPLE + "<bold>OUTPOST CAPTURED"), Ui.mm((mine ? Ui.GREEN : "<white>") + Ui.esc(f.tag()))));
			o.playSound(o.getLocation(), mine ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_WITHER_DEATH, 0.6f, 1f);
		}
		startRebuild();
	}

	private void saveState(YamlConfiguration y) {
		if (y == null) y = YamlConfiguration.loadConfiguration(file);
		y.set("owner", owner);
		y.set("since", ownedSince);
		try {
			plugin.getDataFolder().mkdirs();
			y.save(file);
		} catch (IOException ignored) { }
	}

	// ---------------------------------------------------------------- /f outpost

	void command(Player p, String[] args) {
		if (!enabled() || world == null) { Ui.err(p, "The outpost isn't open."); return; }
		if (args.length > 0 && (args[0].equalsIgnoreCase("tp") || args[0].equalsIgnoreCase("go"))) {
			teleport(p);
			return;
		}
		Faction o = owner();
		double boost = plugin.getConfig().getDouble("outpost.boost", 25);
		var out = net.kyori.adventure.text.Component.text();
		out.append(Ui.mm(Classic.header("<light_purple>Outpost", "Outpost"))).append(net.kyori.adventure.text.Component.newline());
		out.append(Ui.mm(Classic.line("Held by", o == null ? "nobody" : Ui.esc(o.tag()) + " <gray>for " + Ui.duration(System.currentTimeMillis() - ownedSince))))
				.append(net.kyori.adventure.text.Component.newline());
		out.append(Ui.mm(Classic.line("Base", walls + " watered walls <gray>(every 5th obsidian)"))).append(net.kyori.adventure.text.Component.newline());
		out.append(Ui.mm(Classic.line("Core", task == -1 ? "<green>up</green>" : "rebuilding " + rebuildPercent() + "%"))).append(net.kyori.adventure.text.Component.newline());
		out.append(Ui.mm("<gray>Cannon through, break the core (beacon) by hand. Holders get <green>+" + (int) boost + "%</green> spawner + farm speed."))
				.append(net.kyori.adventure.text.Component.newline());
		out.append(Ui.mm("<aqua>[Teleport]").hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Ui.mm("<gray>PvP and cannons everywhere")))
				.clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/f outpost tp")));
		p.sendMessage(out.build());
	}

	private void teleport(Player p) {
		p.teleport(world.getSpawnLocation().add(0.5, 0, 0.5).setDirection(new org.bukkit.util.Vector(0, 0, -1)));
		Ui.msg(p, Ui.LABEL + "Welcome to the outpost. " + walls + " watered walls stand between you and the core.");
	}

	/** The outpost page inside /f. */
	void open(Player p) {
		if (!enabled() || world == null) { Ui.err(p, "The outpost isn't open."); return; }
		Faction o = owner();
		double boost = plugin.getConfig().getDouble("outpost.boost", 25);
		Gui g = new Gui(3, Ui.GRAD + "<bold>OUTPOST</bold></gradient>");
		g.frame();
		g.set(11, Ui.item(Material.BEACON, true, Ui.PURPLE + "<bold>THE OUTPOST",
				Ui.LABEL + "Held by <white>" + (o == null ? "nobody" : Ui.esc(o.tag())),
				o == null ? "" : Ui.LABEL + "For <white>" + Ui.duration(System.currentTimeMillis() - ownedSince),
				task == -1 ? Ui.GREEN + "Core is up" : Ui.GOLD + "Rebuilding " + rebuildPercent() + "%",
				"", Ui.LABEL + walls + " watered walls around the core.", Ui.LABEL + "Break the core by hand to capture.",
				Ui.LABEL + "Holders get " + Ui.GREEN + "+" + (int) boost + "%" + Ui.LABEL + " spawner + farm speed."));
		g.set(15, Ui.item(Material.ENDER_PEARL, "<white><bold>GO THERE", Ui.LABEL + "PvP and cannons everywhere", "", Ui.CYAN + "Click to teleport"), c -> {
			c.player().closeInventory();
			teleport(c.player());
		});
		g.set(22, Ui.item(Material.ARROW, Ui.LABEL + "Back to /f menu"), c -> plugin.menu.open(c.player()));
		g.open(p);
	}

	/** Empty void chunks: the base is placed by the plugin. */
	static final class Void extends ChunkGenerator {
		@Override
		public boolean shouldGenerateNoise() { return false; }
		@Override
		public boolean shouldGenerateSurface() { return false; }
		@Override
		public boolean shouldGenerateCaves() { return false; }
		@Override
		public boolean shouldGenerateDecorations() { return false; }
		@Override
		public boolean shouldGenerateMobs() { return false; }
		@Override
		public boolean shouldGenerateStructures() { return false; }
		@Override
		public Location getFixedSpawnLocation(World world, java.util.Random random) {
			return new Location(world, 0.5, Y + 1, 240.5);
		}
	}
}
