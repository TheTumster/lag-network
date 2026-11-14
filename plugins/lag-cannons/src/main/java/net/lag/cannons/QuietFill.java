package net.lag.cannons;

import java.lang.reflect.Method;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.inventory.ItemStack;

/**
 * Fills a dispenser with TNT without telling neighbouring comparators (1.8 behaviour: filling with a command never
 * woke comparators; some cannons are built around that). Falls back to a normal fill if the internals change.
 */
final class QuietFill {
	private static Method getHandle, getBlockEntity, getItems, asNMSCopy, copy;
	private static java.lang.reflect.Constructor<?> posCtor;
	private static boolean broken;

	static void fill(Block b, int amount) {
		if (!broken) {
			try {
				World w = b.getWorld();
				if (getHandle == null) {
					getHandle = w.getClass().getMethod("getHandle");
					Class<?> bpC = Class.forName("net.minecraft.core.BlockPos");
					posCtor = bpC.getConstructor(int.class, int.class, int.class);
					getBlockEntity = Class.forName("net.minecraft.world.level.Level").getMethod("getBlockEntity", bpC);
					asNMSCopy = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack").getMethod("asNMSCopy", ItemStack.class);
				}
				Object be = getBlockEntity.invoke(getHandle.invoke(w), posCtor.newInstance(b.getX(), b.getY(), b.getZ()));
				if (getItems == null) {
					for (Class<?> c = be.getClass(); c != null && getItems == null; c = c.getSuperclass())
						for (Method m : c.getDeclaredMethods())
							if (m.getName().equals("getItems") && m.getParameterCount() == 0) getItems = m;
					getItems.setAccessible(true);
				}
				@SuppressWarnings("unchecked") List<Object> items = (List<Object>) getItems.invoke(be);
				int left = amount;
				for (int i = 0; i < items.size(); i++) {
					int n = Math.min(64, left);
					left -= n;
					Object stack = asNMSCopy.invoke(null, n > 0 ? new ItemStack(Material.TNT, n) : new ItemStack(Material.AIR));
					items.set(i, stack);
				}
				return;
			} catch (Throwable t) {
				broken = true;
			}
		}
		if (b.getState() instanceof Container c) {
			c.getInventory().clear();
			int left = amount;
			for (int i = 0; i < c.getInventory().getSize() && left > 0; i++) {
				int n = Math.min(64, left);
				c.getInventory().setItem(i, new ItemStack(Material.TNT, n));
				left -= n;
			}
		}
	}
}
