package net.lag.cannons;

import java.lang.reflect.Method;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.inventory.ItemStack;

/**
 * Fills a dispenser with TNT.
 */
final class QuietFill {
	private static Method getHandle, getBlockEntity, getItems, asNMSCopy, copy;
	private static java.lang.reflect.Constructor<?> posCtor;
	private static boolean broken;

	/** Normal fill: comparators next to the dispenser update (cannons use full-dispenser comparators to arm clocks). */
	static void fill(Block b, int amount) {
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
