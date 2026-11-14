package lag.parity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.util.Random;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.generator.ChunkGenerator;

/** Paper 1.8.8 side: places the schematic block by block (no physics, like MCEdit/WorldEdit 6) and clicks via NMS. */
@SuppressWarnings("deprecation")
public class H18 extends Harness {
	@Override
	protected World createVoidWorld(String name) {
		return Bukkit.createWorld(new WorldCreator(name).generator(new ChunkGenerator() {
			@Override
			public byte[] generate(World w, Random r, int x, int z) {
				return new byte[32768];
			}
		}));
	}

	@Override
	protected void keepLoaded(int cx, int cz) {
		world.loadChunk(cx, cz, true);
	}

	@EventHandler
	public void noUnload(ChunkUnloadEvent e) {
		if (e.getWorld() == world) e.setCancelled(true);
	}

	// blocks that hang on / sit on another block; placed after everything else, like WorldEdit does
	private static final java.util.Set<Integer> FRAGILE = new java.util.HashSet<Integer>(java.util.Arrays.asList(
			6, 26, 27, 28, 31, 32, 37, 38, 39, 40, 50, 51, 55, 59, 63, 64, 65, 66, 68, 69, 70, 71, 72, 75, 76, 77, 78, 83, 93, 94,
			96, 106, 111, 115, 127, 131, 132, 140, 141, 142, 143, 144, 147, 148, 149, 150, 157, 167, 171, 175, 193, 194, 195, 196, 197));

	@Override
	protected void paste() throws Exception {
		java.util.List<String[]> later = new java.util.ArrayList<String[]>();
		int n = 0;
		try (BufferedReader r = new BufferedReader(new FileReader(new File(getDataFolder(), "blocks.txt")))) {
			String s;
			while ((s = r.readLine()) != null) {
				String[] p = s.split(" ");
				if (FRAGILE.contains(Integer.parseInt(p[3]))) later.add(p);
				else place(p);
				n++;
			}
		}
		for (String[] p : later) place(p);
		say("placed " + n + " blocks (" + later.size() + " attached ones last)");
	}

	private void place(String[] p) {
		Block b = world.getBlockAt(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
		b.setTypeIdAndData(Integer.parseInt(p[3]), (byte) Integer.parseInt(p[4]), false);
	}

	@Override
	protected void afterPaste() {
		// report anything that didn't survive the paste (popped off / changed)
		int bad = 0;
		StringBuilder sb = new StringBuilder();
		try (BufferedReader r = new BufferedReader(new FileReader(new File(getDataFolder(), "blocks.txt")))) {
			String s;
			while ((s = r.readLine()) != null) {
				String[] p = s.split(" ");
				Block b = world.getBlockAt(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
				int id = Integer.parseInt(p[3]);
				boolean same = b.getTypeId() == id || (id == 93 && b.getTypeId() == 94) || (id == 94 && b.getTypeId() == 93)
						|| (id == 75 && b.getTypeId() == 76) || (id == 76 && b.getTypeId() == 75) || (id == 149 && b.getTypeId() == 150) || (id == 150 && b.getTypeId() == 149);
				if (!same) {
					if (bad++ < 25) sb.append(" ").append(p[0]).append(",").append(p[1]).append(",").append(p[2]).append(":").append(id).append("->").append(b.getTypeId());
				}
			}
		} catch (Exception e) {
			say("diff failed " + e);
		}
		say("blocks not as in schematic after paste: " + bad + sb);
	}

	@Override
	protected void makeWater(Block b) {
		// breaking ice leaves a flowing-water source block (id 8)
		b.setTypeIdAndData(8, (byte) 0, true);
	}

	@Override
	protected boolean press(Block b) throws Exception {
		String v = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
		String nms = "net.minecraft.server." + v + ".";
		Object nw = world.getClass().getMethod("getHandle").invoke(world);
		Class<?> bpC = Class.forName(nms + "BlockPosition");
		Object pos = bpC.getConstructor(int.class, int.class, int.class).newInstance(b.getX(), b.getY(), b.getZ());
		Class<?> wC = Class.forName(nms + "World");
		Object ibd = wC.getMethod("getType", bpC).invoke(nw, pos);
		Object block = Class.forName(nms + "IBlockData").getMethod("getBlock").invoke(ibd);
		Object up = Class.forName(nms + "EnumDirection").getEnumConstants()[1];
		for (Method m : block.getClass().getMethods()) {
			if (m.getName().equals("interact") && m.getParameterTypes().length == 8) {
				return (Boolean) m.invoke(block, nw, pos, ibd, null, up, 0.5f, 0.5f, 0.5f);
			}
		}
		say("no interact() found on " + block.getClass());
		return false;
	}
}
