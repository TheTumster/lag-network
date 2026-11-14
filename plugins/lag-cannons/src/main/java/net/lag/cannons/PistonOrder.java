package net.lag.cannons;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Piston;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Double piston extenders (sand comps): a piston with a powered piston right behind it facing the same way waits to
 * be pushed, and on power-off the front piston pulls its head in before the sticky pulls it back. Without this the
 * (location-based) block event order makes every other piston in a row jam. Same fix as the 1.8 lab.
 */
final class PistonOrder implements Listener {
	private final JavaPlugin plugin;
	private final Map<Block, Block> waiting = new HashMap<>();
	private final Set<Block> bypass = new HashSet<>();
	private boolean broken;
	private Method getHandle, neighborChanged, getBlockState, getBlock;
	private Constructor<?> posCtor;

	PistonOrder(JavaPlugin plugin) {
		this.plugin = plugin;
	}

	private boolean enabled() {
		return plugin.getConfig().getBoolean("piston-order-fix", true);
	}

	private static Piston piston(Block b) {
		return b.getBlockData() instanceof Piston p ? p : null;
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onExtend(BlockPistonExtendEvent e) {
		if (!enabled()) return;
		Block front = e.getBlock();
		Piston fp = piston(front);
		if (fp == null) return;
		Block behind = front.getRelative(fp.getFacing().getOppositeFace());
		Piston bp = piston(behind);
		if (bp == null || bp.getFacing() != fp.getFacing() || bp.isExtended()) return;
		if (behind.isBlockIndirectlyPowered() || behind.getRelative(BlockFace.UP).isBlockIndirectlyPowered()) e.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onRetractOrder(BlockPistonRetractEvent e) {
		if (!enabled() || !e.isSticky()) return;
		Block sticky = e.getBlock();
		if (bypass.remove(sticky)) return;
		Piston sp = piston(sticky);
		if (sp == null) return;
		BlockFace dir = sp.getFacing();
		Block front = sticky.getRelative(dir).getRelative(dir);
		Piston fp = piston(front);
		if (fp == null || !fp.isExtended() || fp.getFacing() != dir) return;
		e.setCancelled(true);
		waiting.put(front, sticky);
		update(front);
		Bukkit.getScheduler().runTask(plugin, () -> {
			Block st = waiting.remove(front);
			if (st != null) {
				bypass.add(st);
				update(st);
			}
		});
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onRetracted(BlockPistonRetractEvent e) {
		Block sticky = waiting.remove(e.getBlock());
		if (sticky != null) {
			bypass.add(sticky);
			update(sticky);
		}
	}

	/** Same as a neighbor update hitting this piston: it re-checks its power right now. */
	private void update(Block b) {
		if (broken) return;
		try {
			World w = b.getWorld();
			if (neighborChanged == null) {
				getHandle = w.getClass().getMethod("getHandle");
				Class<?> bpC = Class.forName("net.minecraft.core.BlockPos");
				posCtor = bpC.getConstructor(int.class, int.class, int.class);
				Class<?> levelC = Class.forName("net.minecraft.world.level.Level");
				getBlockState = levelC.getMethod("getBlockState", bpC);
				getBlock = Class.forName("net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase").getMethod("getBlock");
				for (Method m : levelC.getMethods())
					if (m.getName().equals("neighborChanged") && m.getParameterCount() == 3 && m.getParameterTypes()[0] == bpC) neighborChanged = m;
				if (neighborChanged == null) throw new NoSuchMethodException("Level.neighborChanged(BlockPos, Block, ?)");
			}
			Object lvl = getHandle.invoke(w);
			Object pos = posCtor.newInstance(b.getX(), b.getY(), b.getZ());
			Object block = getBlock.invoke(getBlockState.invoke(lvl, pos));
			neighborChanged.invoke(lvl, pos, block, null);
		} catch (Throwable ex) {
			broken = true;
			plugin.getLogger().warning("piston order fix: couldn't update pistons (" + ex + ")");
		}
	}
}
