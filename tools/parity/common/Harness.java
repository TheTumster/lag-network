package lag.parity;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;
import java.util.Properties;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Dispenser;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

/**
 * Pastes a cannon into an empty world, loads it the way a player would (TNT in every dispenser, ice broken
 * into water, clock lever on, button pressed) and records every TNT / falling block, dispense, piston move and
 * explosion tick by tick to trace.csv. The same steps run on 1.8 and on our 1.21 server so the traces line up.
 * Ticks in the trace are relative to the button press.
 */
public abstract class Harness extends JavaPlugin implements Listener {
	protected Properties cfg = new Properties();
	protected World world;
	protected int ox, oy, oz, sw, sh, sl;
	private BufferedWriter out;
	private int tick = 0;
	private int t0 = Integer.MIN_VALUE; // tick the button was pressed
	private boolean recording = false;
	private int pastedBlocks = -1;

	protected abstract World createVoidWorld(String name);
	protected abstract void keepLoaded(int cx, int cz);
	protected abstract void paste() throws Exception;
	/** Presses a button / flips a lever exactly like a player click would (block-update-wise). */
	protected abstract boolean press(Block b) throws Exception;
	protected abstract void afterPaste();
	protected abstract void makeWater(Block b);
	/** Redstone-relevant state of a block in a version-neutral form ("wire:7", "disp:T", "pist:E", "rep:on"...), or null. */
	protected abstract String rsState(Block b);
	/** Fill a dispenser without notifying comparators (experiment); false = use the normal API fill. */
	protected boolean fillQuietly(Block b) { return false; }

	protected int[] xyz(String s) {
		String[] p = s.split(",");
		return new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim())};
	}

	protected void say(String s) {
		getLogger().info("[PARITY] " + s);
	}

	@Override
	public void onEnable() {
		try {
			cfg.load(new FileReader(new File(getDataFolder(), "harness.properties")));
			int[] o = xyz(cfg.getProperty("origin", "0,1,0"));
			ox = o[0]; oy = o[1]; oz = o[2];
			int[] s = xyz(cfg.getProperty("size"));
			sw = s[0]; sh = s[1]; sl = s[2];
			out = new BufferedWriter(new FileWriter(new File(getDataFolder(), "trace.csv")), 1 << 20);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		Bukkit.getPluginManager().registerEvents(this, this);
		Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
			public void run() {
				tick();
			}
		}, 1L, 1L);
	}

	private int settleTicks() {
		return Integer.parseInt(cfg.getProperty("settle-ticks", "100"));
	}

	private void tick() {
		tick++;
		try {
			if (tick == 40) {
				world = createVoidWorld("parity");
				for (int cx = (ox >> 4) - 8; cx <= ((ox + sw) >> 4) + 8; cx++)
					for (int cz = (oz >> 4) - 8; cz <= ((oz + sl) >> 4) + 8; cz++) keepLoaded(cx, cz);
				world.setSpawnLocation(ox + sw / 2, oy + sh - 1, oz + sl / 2);
				say("world ready, pasting " + sw + "x" + sh + "x" + sl + " at " + ox + "," + oy + "," + oz);
				paste();
			} else if (tick == 120) {
				pastedBlocks = countBlocks();
				say("pasted blocks: " + pastedBlocks);
				afterPaste();
			} else if (tick == 159) {
				snapshot("prefill");
			} else if (tick == 160) {
				int d = 0;
				for (int x = 0; x < sw; x++)
					for (int y = 0; y < sh; y++)
						for (int z = 0; z < sl; z++) {
							Block b = world.getBlockAt(ox + x, oy + y, oz + z);
							if (b.getType() == Material.DISPENSER) {
								BlockState st = b.getState();
								if (st instanceof Dispenser) {
									if (!fillQuietly(b)) for (int i = 0; i < 9; i++) ((Dispenser) st).getInventory().setItem(i, new ItemStack(Material.TNT, 64));
									d++;
								}
							}
						}
				say("filled dispensers: " + d);
				String ice = cfg.getProperty("ice", "");
				if (!ice.isEmpty()) for (String p : ice.split(";")) {
					int[] c = xyz(p);
					makeWater(world.getBlockAt(c[0], c[1], c[2]));
					say("ice -> water at " + p);
				}
			} else if (tick == 160 + settleTicks()) {
				recording = true;
				String lever = cfg.getProperty("lever", "");
				if (!lever.isEmpty() && Boolean.parseBoolean(cfg.getProperty("use-lever", "true"))) {
					int[] c = xyz(lever);
					Block b = world.getBlockAt(c[0], c[1], c[2]);
					say("lever " + b.getType() + " pressed=" + press(b) + " at " + lever);
				}
			} else if (tick == 180 + settleTicks()) {
				snapshot("pre");
				String button = cfg.getProperty("button", "");
				t0 = tick;
				if (!button.isEmpty()) {
					int[] c = xyz(button);
					Block b = world.getBlockAt(c[0], c[1], c[2]);
					say("button " + b.getType() + " pressed=" + press(b) + " at " + button);
				}
			}
			if (recording) sample();
			int record = Integer.parseInt(cfg.getProperty("record-ticks", "400"));
			if (t0 != Integer.MIN_VALUE && tick >= t0 + record) finish();
		} catch (Exception e) {
			e.printStackTrace();
			say("FAILED: " + e);
			finish();
		}
	}

	private int rel() {
		return t0 == Integer.MIN_VALUE ? tick - (180 + settleTicks()) : tick - t0;
	}

	private void line(String s) {
		try {
			out.write(s);
			out.write('\n');
		} catch (IOException ignored) { }
	}

	private static String f(double d) {
		return String.format(Locale.ROOT, "%.5f", d);
	}

	private void sample() {
		int r = rel();
		for (Entity e : world.getEntities()) {
			String kind = e instanceof TNTPrimed ? "T" : e instanceof FallingBlock ? "S" : null;
			if (kind == null) continue;
			Location l = e.getLocation();
			Vector v = e.getVelocity();
			int fuse = e instanceof TNTPrimed ? ((TNTPrimed) e).getFuseTicks() : e.getTicksLived();
			line("E," + r + "," + kind + "," + e.getEntityId() + "," + f(l.getX()) + "," + f(l.getY()) + "," + f(l.getZ()) + ","
					+ f(v.getX()) + "," + f(v.getY()) + "," + f(v.getZ()) + "," + fuse);
		}
	}

	private void snapshotX(String phase) { snapshot(phase); }

	private void snapshot(String phase) {
		for (int x = 0; x < sw; x++)
			for (int y = 0; y < sh; y++)
				for (int z = 0; z < sl; z++) {
					Block b = world.getBlockAt(ox + x, oy + y, oz + z);
					String st = rsState(b);
					if (st != null) line("S," + phase + "," + b.getX() + "," + b.getY() + "," + b.getZ() + "," + st);
				}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onRedstone(BlockRedstoneEvent e) {
		if (!recording || e.getBlock().getWorld() != world) return;
		Block b = e.getBlock();
		line("R," + rel() + "," + b.getX() + "," + b.getY() + "," + b.getZ() + "," + e.getOldCurrent() + "," + e.getNewCurrent());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onExplode(EntityExplodeEvent e) {
		if (!recording || e.getLocation().getWorld() != world) return;
		Location l = e.getLocation();
		line("X," + rel() + "," + f(l.getX()) + "," + f(l.getY()) + "," + f(l.getZ()) + "," + e.blockList().size() + "," + (e.isCancelled() ? 1 : 0));
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onDispense(BlockDispenseEvent e) {
		if (!recording || e.getBlock().getWorld() != world) return;
		Block b = e.getBlock();
		line("D," + rel() + "," + b.getX() + "," + b.getY() + "," + b.getZ() + "," + e.getItem().getType());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onExtend(BlockPistonExtendEvent e) {
		if (!recording || e.getBlock().getWorld() != world) return;
		Block b = e.getBlock();
		line("P," + rel() + "," + b.getX() + "," + b.getY() + "," + b.getZ() + ",E");
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onRetract(BlockPistonRetractEvent e) {
		if (!recording || e.getBlock().getWorld() != world) return;
		Block b = e.getBlock();
		line("P," + rel() + "," + b.getX() + "," + b.getY() + "," + b.getZ() + ",R");
	}

	private int countBlocks() {
		int n = 0;
		for (int x = 0; x < sw; x++)
			for (int y = 0; y < sh; y++)
				for (int z = 0; z < sl; z++) {
					Material m = world.getBlockAt(ox + x, oy + y, oz + z).getType();
					if (m != Material.AIR && !m.name().contains("WATER")) n++;
				}
		return n;
	}

	private boolean done = false;

	private void finish() {
		if (done) return;
		done = true;
		int left = world == null ? -1 : countBlocks();
		line("I," + pastedBlocks + "," + left + "," + ox + "," + oy + "," + oz + "," + sw + "," + sh + "," + sl);
		say("cannon blocks: pasted " + pastedBlocks + ", left after firing " + left);
		try {
			out.close();
		} catch (IOException ignored) { }
		say("DONE");
		Bukkit.shutdown();
	}
}
