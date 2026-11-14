package net.lag.lab;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.EnumSet;
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
		busy = true;
		try {
			if (doPhysics == null) init(below.getWorld());
			Object nw = getHandle.invoke(below.getWorld());
			Object pos = posCtor.newInstance(below.getX(), below.getY(), below.getZ());
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
