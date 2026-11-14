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

	@Override
	protected void paste() throws Exception {
		int n = 0;
		try (BufferedReader r = new BufferedReader(new FileReader(new File(getDataFolder(), "blocks.txt")))) {
			String s;
			while ((s = r.readLine()) != null) {
				String[] p = s.split(" ");
				Block b = world.getBlockAt(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
				b.setTypeIdAndData(Integer.parseInt(p[3]), (byte) Integer.parseInt(p[4]), false);
				n++;
			}
		}
		say("placed " + n + " blocks");
	}

	@Override
	protected void afterPaste() { }

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
