package net.lag.factions;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.bukkit.Bukkit;
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
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.generator.ChunkGenerator;

import dev.kitteh.factions.Faction;
import net.kyori.adventure.title.Title;

/**
 * The raiding outpost: its own void world with a fortress around a core (a beacon on a pedestal). Cannon through the
 * walls, then break the core by hand: your faction owns the outpost and its spawners + farms run faster
 * (outpost.boost %) until someone else takes it. After a capture the fortress rebuilds itself over 30 minutes
 * (outpost.rebuild-minutes), core last, so the new owners get a breather. /f outpost teleports there.
 */
final class Outpost implements Listener {
	static final String WORLD = "outpost";
	private static final int Y = 64, HALF = 15, H = 14;

	private final LagFactions plugin;
	private final File file;
	private final Map<Long, BlockData> template = new HashMap<>();
	private World world;
	private int owner = Integer.MIN_VALUE;
	private long ownedSince;
	private List<Long> rebuildQueue = new ArrayList<>();
	private int rebuildTotal;
	private int rebuildTask = -1;

	Outpost(LagFactions plugin) {
		this.plugin = plugin;
		this.file = new File(plugin.getDataFolder(), "outpost.yml");
		if (!enabled()) return;
		YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
		owner = y.getInt("owner", Integer.MIN_VALUE);
		ownedSince = y.getLong("since", 0);
		buildTemplate();
		Bukkit.getScheduler().runTask(plugin, () -> {
			world = new WorldCreator(WORLD).generator(new Void()).createWorld();
			if (world == null) return;
			world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
			world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
			world.setSpawnLocation(0, Y + 1, 70);
			if (!y.getBoolean("built", false)) {
				placeAll();
				y.set("built", true);
				saveState(y);
			}
		});
	}

	boolean enabled() {
		return plugin.getConfig().getBoolean("outpost.enabled", true);
	}

	/** Spawner/farm speed boost (percent) for the faction holding the outpost. */
	double boost(Faction f) {
		return f != null && f.id() == owner ? plugin.getConfig().getDouble("outpost.boost", 25) : 0;
	}

	Faction owner() {
		return owner == Integer.MIN_VALUE ? null : F.byId(owner);
	}

	// ---------------------------------------------------------------- the fortress

	private static long key(int x, int y, int z) {
		return ((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (z + 512);
	}

	private static int kx(long k) { return (int) (k >> 40) - 512; }
	private static int ky(long k) { return (int) ((k >> 20) & 0xFFFFF) - 512; }
	private static int kz(long k) { return (int) (k & 0xFFFFF) - 512; }

	private void t(int x, int y, int z, Material m) {
		template.put(key(x, y, z), m.createBlockData());
	}

	private void buildTemplate() {
		template.clear();
		Random r = new Random(1337);
		// island the fortress sits on + the approach to the south
		for (int x = -40; x <= 40; x++) {
			for (int z = -40; z <= 80; z++) {
				boolean island = x * x + z * z <= 40 * 40 || (Math.abs(x) <= 12 && z <= 80);
				if (!island) continue;
				t(x, Y - 1, z, Material.BEDROCK);
				t(x, Y, z, Math.abs(x) <= 2 && z > HALF + 4 ? Material.POLISHED_BLACKSTONE_BRICKS : (r.nextInt(5) == 0 ? Material.CRACKED_DEEPSLATE_BRICKS : Material.DEEPSLATE_TILES));
			}
		}
		// walls: cobble skin, then two layers of obsidian, then a water-proof inner obsidian shell; corner towers
		for (int x = -HALF; x <= HALF; x++) {
			for (int z = -HALF; z <= HALF; z++) {
				int d = Math.max(Math.abs(x), Math.abs(z));
				for (int y = Y + 1; y <= Y + H; y++) {
					Material m = null;
					if (d == HALF) m = (y % 4 == 0) ? Material.CRYING_OBSIDIAN : Material.COBBLESTONE;
					else if (d == HALF - 1 || d == HALF - 2) m = Material.OBSIDIAN;
					else if (d == HALF - 3) m = Material.COBBLESTONE;
					else if (y == Y + H && d < HALF - 3) m = Material.OBSIDIAN; // roof
					if (m != null) t(x, y, z, m);
				}
			}
		}
		// battlements
		for (int x = -HALF; x <= HALF; x++) {
			for (int z = -HALF; z <= HALF; z++) {
				if (Math.max(Math.abs(x), Math.abs(z)) == HALF && (x + z) % 2 == 0) t(x, Y + H + 1, z, Material.POLISHED_BLACKSTONE_BRICK_WALL);
			}
		}
		for (int cx : new int[] {-HALF, HALF}) {
			for (int cz : new int[] {-HALF, HALF}) {
				for (int x = cx - 3; x <= cx + 3; x++) {
					for (int z = cz - 3; z <= cz + 3; z++) {
						if (Math.max(Math.abs(x - cx), Math.abs(z - cz)) != 3) continue;
						for (int y = Y + 1; y <= Y + H + 6; y++) t(x, y, z, y > Y + H ? Material.RED_NETHER_BRICKS : Material.OBSIDIAN);
					}
				}
				t(cx, Y + H + 6, cz, Material.SOUL_CAMPFIRE);
			}
		}
		// inner vault around the core: obsidian box 7x7 with a crying obsidian band
		for (int x = -4; x <= 4; x++) {
			for (int z = -4; z <= 4; z++) {
				int d = Math.max(Math.abs(x), Math.abs(z));
				for (int y = Y + 1; y <= Y + 6; y++) {
					if (d == 4 || y == Y + 6) t(x, y, z, y == Y + 3 ? Material.CRYING_OBSIDIAN : Material.OBSIDIAN);
				}
			}
		}
		// the core
		for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) t(x, Y + 1, z, Material.IRON_BLOCK);
		t(0, Y + 2, 0, Material.BEACON);
		t(0, Y + 3, 0, Material.PURPLE_STAINED_GLASS);
	}

	private void placeAll() {
		for (var e : template.entrySet()) world.getBlockAt(kx(e.getKey()), ky(e.getKey()), kz(e.getKey())).setBlockData(e.getValue(), false);
	}

	private boolean inFortress(Block b) {
		return b.getWorld().equals(world) && Math.abs(b.getX()) <= HALF + 3 && Math.abs(b.getZ()) <= HALF + 3 && b.getY() >= Y && b.getY() <= Y + H + 7;
	}

	private boolean isCore(Block b) {
		return b.getWorld().equals(world) && b.getX() == 0 && b.getY() == Y + 2 && b.getZ() == 0;
	}

	// ---------------------------------------------------------------- capture

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onBreak(BlockBreakEvent e) {
		Block b = e.getBlock();
		if (world == null || !b.getWorld().equals(world)) return;
		if (isCore(b)) {
			e.setCancelled(true);
			if (rebuildTask != -1) { Ui.err(e.getPlayer(), "The outpost is rebuilding. The core comes back last."); return; }
			Faction f = F.of(e.getPlayer());
			if (f == null) { Ui.err(e.getPlayer(), "You need a faction to capture the outpost."); return; }
			if (f.id() == owner) { Ui.err(e.getPlayer(), "Your faction already holds the outpost."); return; }
			capture(e.getPlayer(), f);
			return;
		}
		// inside the fortress only explosions break things (and your own cannon blocks)
		if (inFortress(b) && template.containsKey(key(b.getX(), b.getY(), b.getZ()))) e.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onPlace(BlockPlaceEvent e) {
		Block b = e.getBlockPlaced();
		if (world != null && b.getWorld().equals(world) && Math.abs(b.getX()) <= 5 && Math.abs(b.getZ()) <= 5 && b.getY() <= Y + 6) {
			e.setCancelled(true); // no blocking the core room
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onExplode(EntityExplodeEvent e) {
		if (world == null || !e.getLocation().getWorld().equals(world)) return;
		e.blockList().removeIf(b -> isCore(b) || b.getType() == Material.BEDROCK);
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

	private void capture(Player p, Faction f) {
		Faction old = owner();
		owner = f.id();
		ownedSince = System.currentTimeMillis();
		saveState(null);
		world.getBlockAt(0, Y + 2, 0).setType(Material.AIR, false);
		double boost = plugin.getConfig().getDouble("outpost.boost", 25);
		Bukkit.broadcast(Ui.mm(Ui.PREFIX + "<white><bold>" + Ui.esc(f.tag()) + "</bold></white> " + Ui.LABEL + "captured the " + Ui.PURPLE + "<bold>OUTPOST</bold>"
				+ (old == null ? "" : Ui.LABEL + " from <white>" + Ui.esc(old.tag())) + Ui.LABEL + "! " + Ui.GREEN + "+" + (int) boost + "% spawner + farm speed"));
		for (Player o : Bukkit.getOnlinePlayers()) {
			boolean mine = F.of(o) != null && F.of(o).id() == f.id();
			o.showTitle(Title.title(Ui.mm(Ui.PURPLE + "<bold>OUTPOST CAPTURED"), Ui.mm((mine ? Ui.GREEN : "<white>") + Ui.esc(f.tag()))));
			o.playSound(o.getLocation(), mine ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_WITHER_DEATH, 0.6f, 1f);
		}
		startRebuild();
	}

	// ---------------------------------------------------------------- rebuild over 30 minutes

	void startRebuild() {
		if (rebuildTask != -1) Bukkit.getScheduler().cancelTask(rebuildTask);
		rebuildQueue = new ArrayList<>();
		for (var e : template.entrySet()) {
			long k = e.getKey();
			if (k == key(0, Y + 2, 0)) continue;
			Block b = world.getBlockAt(kx(k), ky(k), kz(k));
			if (!b.getBlockData().matches(e.getValue())) rebuildQueue.add(k);
		}
		// anything players left inside the fortress that isn't part of it gets cleared first
		for (int x = -HALF - 3; x <= HALF + 3; x++)
			for (int z = -HALF - 3; z <= HALF + 3; z++)
				for (int y = Y; y <= Y + H + 7; y++)
					if (!template.containsKey(key(x, y, z)) && !world.getBlockAt(x, y, z).getType().isAir()) world.getBlockAt(x, y, z).setType(Material.AIR, false);
		// bottom-up so walls rise out of the ground, core last
		rebuildQueue.sort((a, b) -> Integer.compare(ky(a), ky(b)));
		rebuildQueue.add(key(0, Y + 2, 0));
		rebuildTotal = rebuildQueue.size();
		long ticks = Math.max(20, plugin.getConfig().getLong("outpost.rebuild-minutes", 30) * 1200);
		int perRun = Math.max(1, (int) Math.ceil(rebuildTotal / (ticks / 20.0)));
		rebuildTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
			for (int i = 0; i < perRun && !rebuildQueue.isEmpty(); i++) {
				long k = rebuildQueue.remove(0);
				BlockData d = template.get(k);
				if (d != null) world.getBlockAt(kx(k), ky(k), kz(k)).setBlockData(d, false);
			}
			if (rebuildQueue.isEmpty()) {
				Bukkit.getScheduler().cancelTask(rebuildTask);
				rebuildTask = -1;
				Bukkit.broadcast(Ui.mm(Ui.PREFIX + Ui.LABEL + "The " + Ui.PURPLE + "<bold>OUTPOST</bold>" + Ui.LABEL + " has rebuilt. The core is up for grabs."));
			}
		}, 20L, 20L).getTaskId();
	}

	int rebuildPercent() {
		return rebuildTask == -1 || rebuildTotal == 0 ? 100 : 100 - rebuildQueue.size() * 100 / rebuildTotal;
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

	/** /f outpost in chat (tp: /f outpost tp). */
	void command(Player p, String[] args) {
		if (!enabled() || world == null) { Ui.err(p, "The outpost isn't open."); return; }
		if (args.length > 0 && (args[0].equalsIgnoreCase("tp") || args[0].equalsIgnoreCase("go"))) {
			p.teleport(world.getSpawnLocation().add(0.5, 0, 0.5).setDirection(new org.bukkit.util.Vector(0, 0, -1)));
			Ui.msg(p, Ui.LABEL + "Welcome to the outpost. The core is the beacon in the middle.");
			return;
		}
		Faction o = owner();
		double boost = plugin.getConfig().getDouble("outpost.boost", 25);
		var out = net.kyori.adventure.text.Component.text();
		out.append(Ui.mm(Classic.header("<light_purple>Outpost", "Outpost"))).append(net.kyori.adventure.text.Component.newline());
		out.append(Ui.mm(Classic.line("Held by", o == null ? "nobody" : Ui.esc(o.tag()) + " <gray>for " + Ui.duration(System.currentTimeMillis() - ownedSince))))
				.append(net.kyori.adventure.text.Component.newline());
		out.append(Ui.mm(Classic.line("Core", rebuildTask == -1 ? "<green>up</green>" : "rebuilding " + rebuildPercent() + "%"))).append(net.kyori.adventure.text.Component.newline());
		out.append(Ui.mm("<gray>Cannon in and break the core (beacon) by hand. Owners get <green>+" + (int) boost + "%</green> spawner + farm speed."))
				.append(net.kyori.adventure.text.Component.newline());
		out.append(Ui.mm("<aqua>[Teleport]").hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Ui.mm("<gray>PvP and cannons everywhere")))
				.clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/f outpost tp")));
		p.sendMessage(out.build());
	}

	void open(Player p) {
		if (!enabled() || world == null) { Ui.err(p, "The outpost isn't open."); return; }
		Faction o = owner();
		double boost = plugin.getConfig().getDouble("outpost.boost", 25);
		Gui g = new Gui(3, Ui.GRAD + "<bold>OUTPOST</bold></gradient>");
		g.frame();
		g.set(11, Ui.item(Material.BEACON, true, Ui.PURPLE + "<bold>THE OUTPOST",
				Ui.LABEL + "Held by <white>" + (o == null ? "nobody" : Ui.esc(o.tag())),
				o == null ? "" : Ui.LABEL + "For <white>" + Ui.duration(System.currentTimeMillis() - ownedSince),
				rebuildTask == -1 ? Ui.GREEN + "Core is up" : Ui.GOLD + "Rebuilding " + rebuildPercent() + "%",
				"", Ui.LABEL + "Cannon in, break the core (beacon)", Ui.LABEL + "by hand. Owners get " + Ui.GREEN + "+" + (int) boost + "%",
				Ui.LABEL + "spawner + farm speed."));
		g.set(15, Ui.item(Material.ENDER_PEARL, "<white><bold>GO THERE", Ui.LABEL + "PvP and cannons everywhere", "", Ui.CYAN + "Click to teleport"), c -> {
			c.player().closeInventory();
			c.player().teleport(world.getSpawnLocation().add(0.5, 0, 0.5).setDirection(new org.bukkit.util.Vector(0, 0, -1)));
			Ui.msg(c.player(), Ui.LABEL + "Welcome to the outpost. The core is the beacon in the middle.");
		});
		g.set(22, Ui.item(Material.ARROW, Ui.LABEL + "Back to /f menu"), c -> plugin.menu.open(c.player()));
		g.open(p);
	}

	/** Empty void chunks: the fortress is placed by the plugin. */
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
			return new Location(world, 0.5, Y + 1, 70.5);
		}
	}
}
