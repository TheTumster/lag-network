package lag.parity;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.generator.ChunkGenerator;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.session.ClipboardHolder;

/** Our 1.21.11 factions server side: pastes with FAWE exactly like //paste does, and clicks via the vanilla methods. */
public class H121 extends Harness {
	@Override
	protected World createVoidWorld(String name) {
		return Bukkit.createWorld(new WorldCreator(name).generator(new ChunkGenerator() { }));
	}

	@Override
	protected void keepLoaded(int cx, int cz) {
		world.addPluginChunkTicket(cx, cz, this);
	}

	@Override
	protected void paste() throws Exception {
		File f = new File(getDataFolder(), "cannon.schematic");
		ClipboardFormat fmt = ClipboardFormats.findByFile(f);
		Clipboard cb;
		try (ClipboardReader r = fmt.getReader(new FileInputStream(f))) {
			cb = r.read();
		}
		try (EditSession es = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
			// place the schematic's corner at the origin (same spot the 1.8 side uses), whatever its copy offset
			BlockVector3 off = cb.getRegion().getMinimumPoint().subtract(cb.getOrigin());
			Operations.complete(new ClipboardHolder(cb).createPaste(es).to(BlockVector3.at(ox, oy, oz).subtract(off)).ignoreAirBlocks(false).build());
		}
		say("FAWE pasted with format " + fmt.getName());
	}

	@Override
	protected void afterPaste() {
		int wires = 0, dot = 0;
		for (int x = 0; x < sw; x++)
			for (int y = 0; y < sh; y++)
				for (int z = 0; z < sl; z++) {
					Block b = world.getBlockAt(ox + x, oy + y, oz + z);
					if (b.getType() == Material.REDSTONE_WIRE) {
						wires++;
						String s = b.getBlockData().getAsString();
						if (!s.contains("=side") && !s.contains("=up")) dot++;
					}
				}
		say("redstone dust after paste: " + wires + " (" + dot + " with no connections)");
		if (Boolean.parseBoolean(cfg.getProperty("fix-redstone", "false"))) {
			int cx = ox + sw / 2, cz = oz + sl / 2;
			for (int y = oy; y < oy + sh; y += 64) {
				Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "fixredstone 64 " + world.getName() + " " + cx + " " + (y + 32) + " " + cz);
			}
			say("ran /fixredstone over the whole cannon");
		}
	}

	@Override
	protected void makeWater(Block b) {
		b.setType(Material.WATER, true);
	}

	@Override
	protected String rsState(Block b) {
		org.bukkit.block.data.BlockData d = b.getBlockData();
		Material m = b.getType();
		if (m == Material.REDSTONE_WIRE) return "wire:" + ((org.bukkit.block.data.AnaloguePowerable) d).getPower();
		if (d instanceof org.bukkit.block.data.type.Dispenser disp) return "disp:" + (disp.isTriggered() ? "T" : "-");
		if (d instanceof org.bukkit.block.data.type.Piston p) return "pist:" + (p.isExtended() ? "E" : "-");
		if (d instanceof org.bukkit.block.data.type.Repeater r) return "rep:" + (r.isPowered() ? "on" : "off");
		if (d instanceof org.bukkit.block.data.type.Comparator c) return "comp:" + (c.isPowered() ? "on" : "off");
		if (m == Material.REDSTONE_TORCH || m == Material.REDSTONE_WALL_TORCH) return "torch:" + (((org.bukkit.block.data.Lightable) d).isLit() ? "on" : "off");
		if (m == Material.LEVER || m.name().endsWith("_BUTTON")) return "sw:" + (((org.bukkit.block.data.Powerable) d).isPowered() ? "on" : "off");
		return null;
	}

	@Override
	protected boolean press(Block b) throws Exception {
		Object lvl = world.getClass().getMethod("getHandle").invoke(world);
		Class<?> bpC = Class.forName("net.minecraft.core.BlockPos");
		Object pos = bpC.getConstructor(int.class, int.class, int.class).newInstance(b.getX(), b.getY(), b.getZ());
		Class<?> levelC = Class.forName("net.minecraft.world.level.Level");
		Object state = levelC.getMethod("getBlockState", bpC).invoke(lvl, pos);
		Object block = Class.forName("net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase").getMethod("getBlock").invoke(state);
		for (Method m : block.getClass().getMethods()) {
			if ((m.getName().equals("pull") || m.getName().equals("press")) && m.getParameterCount() == 4) {
				m.invoke(block, state, lvl, pos, null);
				return true;
			}
		}
		say("no pull()/press() on " + block.getClass() + "; falling back to Bukkit power toggle");
		org.bukkit.block.data.BlockData d = b.getBlockData();
		if (d instanceof org.bukkit.block.data.Powerable p) {
			p.setPowered(!p.isPowered());
			b.setBlockData(p, true);
			return true;
		}
		return false;
	}
}
