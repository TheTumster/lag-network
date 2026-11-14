package net.lag.cannons;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.RedstoneWire;
import org.bukkit.block.data.type.Repeater;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;

import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * WorldEdit/FAWE pastes skip block updates, so redstone dust keeps whatever connections the schematic had
 * (often none) and cannons don't fire. This recalculates every dust's connections like vanilla would, then
 * pokes it so power flows. Runs automatically after //paste and on /fixredstone.
 */
final class RedstoneFix implements Listener {
	private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
	private final JavaPlugin plugin;

	RedstoneFix(JavaPlugin plugin) {
		this.plugin = plugin;
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void afterPaste(PlayerCommandPreprocessEvent e) {
		if (!plugin.getConfig().getBoolean("redstone-fix.after-paste", true)) return;
		String m = e.getMessage().toLowerCase(Locale.ROOT);
		if (!(m.startsWith("//paste") || m.startsWith("//stack") || m.startsWith("//move") || m.startsWith("/schem paste")
				|| m.startsWith("//schem paste") || m.startsWith("//place"))) return;
		Player p = e.getPlayer();
		Location at = p.getLocation();
		int r = plugin.getConfig().getInt("redstone-fix.radius", 48);
		long delay = plugin.getConfig().getLong("redstone-fix.delay-ticks", 40);
		// FAWE pastes asynchronously; give it a moment to finish
		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			int fixed = fix(at, r, true);
			if (fixed > 0) p.sendMessage(MiniMessage.miniMessage().deserialize("<dark_gray>[laG] <gray>Reconnected <white>" + fixed + "<gray> redstone."));
		}, delay);
	}

	boolean command(CommandSender sender, String[] args) {
		if (!(sender instanceof Player p)) {
			// console: fixredstone <radius> <world> <x> <y> <z>
			if (args.length < 5) {
				sender.sendMessage("Usage: fixredstone <radius> <world> <x> <y> <z>");
				return true;
			}
			try {
				World w = Bukkit.getWorld(args[1]);
				int r = Math.min(96, Math.max(1, Integer.parseInt(args[0])));
				int n = fix(new Location(w, Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4])), r);
				sender.sendMessage("Redstone fixed: " + n + " dust");
			} catch (RuntimeException ex) {
				sender.sendMessage("Bad arguments: " + ex.getMessage());
			}
			return true;
		}
		int r = 32;
		try {
			if (args.length > 0) r = Math.min(96, Math.max(1, Integer.parseInt(args[0])));
		} catch (NumberFormatException ignored) { }
		int fixed = fix(p.getLocation(), r);
		p.sendMessage(MiniMessage.miniMessage().deserialize("<gradient:#38E0FF:#B040FF>Redstone fixed</gradient> <gray>(" + fixed + " dust in a " + r + " block radius)"));
		return true;
	}

	/** Returns how many dust blocks were rewired. */
	int fix(Location center, int r) {
		return fix(center, r, false);
	}

	/** fullHeight: scan the whole column (tall cannons pasted from the ground reach far above you). */
	int fix(Location center, int r, boolean fullHeight) {
		World w = center.getWorld();
		int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();
		int minY = fullHeight ? w.getMinHeight() : Math.max(w.getMinHeight(), cy - r);
		int maxY = fullHeight ? w.getMaxHeight() - 1 : Math.min(w.getMaxHeight() - 1, cy + r);
		List<Block> wires = new ArrayList<>();
		for (int x = cx - r; x <= cx + r; x++) {
			for (int z = cz - r; z <= cz + r; z++) {
				if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
				for (int y = minY; y <= maxY; y++) {
					Block b = w.getBlockAt(x, y, z);
					if (b.getType() == Material.REDSTONE_WIRE) wires.add(b);
				}
			}
		}
		// 1) shapes, without physics so nothing pops off mid-way
		for (Block b : wires) {
			RedstoneWire data = (RedstoneWire) b.getBlockData();
			int connected = 0;
			for (BlockFace f : SIDES) {
				RedstoneWire.Connection c = connection(b, f);
				data.setFace(f, c);
				if (c != RedstoneWire.Connection.NONE) connected++;
			}
			if (connected == 1) {
				// a single connection extends straight through, like vanilla
				for (BlockFace f : SIDES) {
					if (data.getFace(f) != RedstoneWire.Connection.NONE && data.getFace(f.getOppositeFace()) == RedstoneWire.Connection.NONE) {
						data.setFace(f.getOppositeFace(), RedstoneWire.Connection.SIDE);
						break;
					}
				}
			} else if (connected == 0) {
				for (BlockFace f : SIDES) data.setFace(f, RedstoneWire.Connection.SIDE); // vanilla's default "cross"
			}
			b.setBlockData(data, false);
		}
		// 2) poke each one so power recalculates through the circuit
		for (Block b : wires) b.setBlockData(b.getBlockData(), true);
		return wires.size();
	}

	private static boolean solid(Block b) {
		return b.getType().isOccluding();
	}

	private RedstoneWire.Connection connection(Block wire, BlockFace f) {
		Block side = wire.getRelative(f);
		if (connectsTo(side.getBlockData(), f)) return RedstoneWire.Connection.SIDE;
		// climbing up the side of a block
		if (!solid(wire.getRelative(BlockFace.UP)) && solid(side) && side.getRelative(BlockFace.UP).getType() == Material.REDSTONE_WIRE) {
			return RedstoneWire.Connection.UP;
		}
		// going down a step
		if (!solid(side) && side.getRelative(BlockFace.DOWN).getType() == Material.REDSTONE_WIRE) return RedstoneWire.Connection.SIDE;
		return RedstoneWire.Connection.NONE;
	}

	private static boolean connectsTo(BlockData d, BlockFace dir) {
		Material m = d.getMaterial();
		if (m == Material.REDSTONE_WIRE) return true;
		if (d instanceof Repeater rep) return rep.getFacing() == dir || rep.getFacing() == dir.getOppositeFace();
		if (m == Material.OBSERVER && d instanceof Directional o) return o.getFacing() == dir;
		if (m == Material.COMPARATOR || m == Material.REDSTONE_TORCH || m == Material.REDSTONE_WALL_TORCH || m == Material.LEVER
				|| m == Material.REDSTONE_BLOCK || m == Material.TARGET || m == Material.TRAPPED_CHEST || m == Material.TRIPWIRE_HOOK
				|| m == Material.DAYLIGHT_DETECTOR || m == Material.DETECTOR_RAIL || m == Material.LECTERN
				|| m == Material.SCULK_SENSOR || m == Material.CALIBRATED_SCULK_SENSOR) return true;
		return Tag.BUTTONS.isTagged(m) || Tag.PRESSURE_PLATES.isTagged(m);
	}
}
