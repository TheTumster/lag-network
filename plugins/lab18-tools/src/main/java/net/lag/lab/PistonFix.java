package net.lag.lab;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.plugin.Plugin;

/**
 * 1.8 pistons powered "from above" (quasi-connectivity) only react if something happens to update them; when the
 * redstone changes somewhere else they can miss it and sit there (that's what breaks sand comps on plain 1.8).
 * When a redstone component updates a block, this also updates the piston directly under that block, in the same
 * tick, so it fires exactly when a correctly-updated piston would. Toggle with /pistonfix.
 */
final class PistonFix implements Listener {
	private static final Set<Material> SOURCES = EnumSet.noneOf(Material.class);
	static {
		for (Material m : Material.values()) {
			String n = m.name();
			if (n.equals("REDSTONE_WIRE") || n.startsWith("DIODE_BLOCK") || n.startsWith("REDSTONE_COMPARATOR") || n.startsWith("REDSTONE_TORCH")
					|| n.equals("REDSTONE_BLOCK") || n.equals("LEVER") || n.endsWith("_BUTTON") || n.endsWith("_PLATE") || n.equals("TRIPWIRE_HOOK")
					|| n.equals("DETECTOR_RAIL") || n.equals("DAYLIGHT_DETECTOR") || n.equals("TRAPPED_CHEST"))
				SOURCES.add(m);
		}
	}

	boolean enabled = true;
	/** pistons that are being pushed/pulled: where they'll land -> ticks left to wait */
	private final Map<Block, Integer> landing = new HashMap<Block, Integer>();

	PistonFix(Plugin plugin) {
		// fallback: if a landing wasn't caught by the physics hook, check it once it's there (expires after 8 ticks)
		Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
			public void run() {
				for (Iterator<Map.Entry<Block, Integer>> it = landing.entrySet().iterator(); it.hasNext(); ) {
					Map.Entry<Block, Integer> en = it.next();
					if (isPiston(en.getKey().getType())) {
						it.remove();
						update(en.getKey());
					} else if (en.getValue() <= 1) it.remove();
					else en.setValue(en.getValue() - 1);
				}
			}
		}, 1L, 1L);
	}

	private static boolean isPiston(Material t) {
		return t == Material.PISTON_BASE || t == Material.PISTON_STICKY_BASE;
	}

	/**
	 * Double piston extenders: a piston with a powered, retracted piston right behind it (facing the same way) must
	 * wait to be pushed. On 1.8 both get powered in the same tick and whichever is first in the (location-based)
	 * queue wins; if the front one extends first, the back one can't push it and the comp stays open.
	 * The front piston extends once it lands (see the landing check below).
	 */
	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onExtendOrder(BlockPistonExtendEvent e) {
		if (!enabled) return;
		Block front = e.getBlock();
		Block behind = front.getRelative(e.getDirection().getOppositeFace());
		if (!isPiston(behind.getType())) return;
		int fd = front.getData() & 7, bd = behind.getData();
		if ((bd & 7) != fd || (bd & 8) != 0) return; // not facing the same way, or already extended
		if (behind.isBlockIndirectlyPowered() || behind.getRelative(BlockFace.UP).isBlockIndirectlyPowered()) {
			e.setCancelled(true);
			landing.put(front.getRelative(e.getDirection()), 8);
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onExtend(BlockPistonExtendEvent e) {
		if (!enabled) return;
		for (Block b : e.getBlocks()) if (isPiston(b.getType())) landing.put(b.getRelative(e.getDirection()), 8);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onRetract(BlockPistonRetractEvent e) {
		if (!enabled || !e.isSticky()) return;
		BlockFace back = e.getDirection().getOppositeFace();
		for (Block b : e.getBlocks()) if (isPiston(b.getType())) landing.put(b.getRelative(back), 8);
	}

	/** A pushed piston lands and updates its neighbors (changed type = piston): make the landed pistons check power now. */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onLand(BlockPhysicsEvent e) {
		if (!enabled || busy || landing.isEmpty() || !isPiston(e.getChangedType())) return;
		for (Iterator<Block> it = landing.keySet().iterator(); it.hasNext(); ) {
			Block b = it.next();
			if (isPiston(b.getType())) {
				it.remove();
				update(b);
			}
		}
	}
	private boolean busy = false;
	private boolean broken = false;
	private Method getHandle, getType, getBlock, doPhysics;
	private Constructor<?> posCtor;

	private void init(World w) throws Exception {
		String v = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
		String nms = "net.minecraft.server." + v + ".";
		getHandle = w.getClass().getMethod("getHandle");
		Class<?> bp = Class.forName(nms + "BlockPosition");
		posCtor = bp.getConstructor(int.class, int.class, int.class);
		Class<?> worldC = Class.forName(nms + "World");
		getType = worldC.getMethod("getType", bp);
		getBlock = Class.forName(nms + "IBlockData").getMethod("getBlock");
		Class<?> blockC = Class.forName(nms + "Block");
		doPhysics = blockC.getMethod("doPhysics", worldC, bp, Class.forName(nms + "IBlockData"), blockC);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPhysics(BlockPhysicsEvent e) {
		if (!enabled || busy || broken || !SOURCES.contains(e.getChangedType())) return;
		Block below = e.getBlock().getRelative(BlockFace.DOWN);
		Material t = below.getType();
		if (t != Material.PISTON_BASE && t != Material.PISTON_STICKY_BASE) return;
		update(below);
	}

	private void update(Block b) {
		if (busy || broken) return;
		busy = true;
		try {
			if (doPhysics == null) init(b.getWorld());
			Object nw = getHandle.invoke(b.getWorld());
			Object pos = posCtor.newInstance(b.getX(), b.getY(), b.getZ());
			Object state = getType.invoke(nw, pos);
			Object block = getBlock.invoke(state);
			doPhysics.invoke(block, nw, pos, state, block);
		} catch (Throwable ex) {
			broken = true;
			Bukkit.getLogger().warning("[laGLab] piston fix disabled: " + ex);
		} finally {
			busy = false;
		}
	}
}
