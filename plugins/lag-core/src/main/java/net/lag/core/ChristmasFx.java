package net.lag.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * The Christmas hub's moving set pieces, all display entities (no blocks change except the tree's twinkle):
 * Santa's sleigh and reindeer circling the sky, a toy train looping the Great Tree inside the Ice Palace,
 * the tree's bulbs twinkling, fireworks over the tree, and northern lights waving behind the palace.
 * Each piece is a group of BlockDisplays sharing one anchor; turning the anchor's yaw turns the whole model,
 * and a short teleport interpolation keeps the motion smooth between updates.
 * Options: hub.christmas.fx.{sleigh,train,twinkle,fireworks,aurora}: true/false.
 */
final class ChristmasFx {

	private final LagCore plugin;
	private final NamespacedKey tag;
	private final String base;
	private Location o;

	ChristmasFx(LagCore plugin, NamespacedKey tag, String base) {
		this.plugin = plugin;
		this.tag = tag;
		this.base = base;
	}

	private boolean on(String what) {
		return plugin.optBool(base + ".christmas.fx." + what, true);
	}

	// ------------------------------------------------------------------ models

	/** A group of block displays that move together. Parts are boxes in model space: +z is forward. */
	private final class Model {
		final List<BlockDisplay> parts = new ArrayList<>();

		void part(Material m, double x0, double y0, double z0, double sx, double sy, double sz, boolean glow, float scale) {
			Location at = o.clone();
			BlockDisplay d = o.getWorld().spawn(at, BlockDisplay.class, b -> {
				b.setBlock(m.createBlockData());
				b.setTransformation(new Transformation(new Vector3f((float) (x0 * scale), (float) (y0 * scale), (float) (z0 * scale)),
						new AxisAngle4f(), new Vector3f((float) (sx * scale), (float) (sy * scale), (float) (sz * scale)), new AxisAngle4f()));
				b.setTeleportDuration(4);
				b.setViewRange(8f);
				b.setPersistent(false);
				if (glow) b.setBrightness(new Display.Brightness(15, 15));
				b.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
			});
			parts.add(d);
		}

		void moveTo(double x, double y, double z, float yaw) {
			Location l = new Location(o.getWorld(), o.getX() + x, o.getY() + y, o.getZ() + z, yaw, 0f);
			for (BlockDisplay d : parts) if (d.isValid()) d.teleport(l);
		}

		boolean valid() {
			for (BlockDisplay d : parts) if (!d.isValid()) return false;
			return !parts.isEmpty();
		}
	}

	private Model sleigh;
	private final List<Model> train = new ArrayList<>();
	private final List<TextDisplay> aurora = new ArrayList<>();
	private final List<Block> bulbs = new ArrayList<>();

	// sleigh path: a wide circle over the hub
	private static final double SL_CX = 0.5, SL_CZ = -20.5, SL_R = 62, SL_Y = 46;
	// the train's track round the Great Tree (tools/hub/xmas_palace.py TRACK_R, tree at 0,-103, track top y 5)
	private static final double TR_CX = 0.5, TR_CZ = -102.5, TR_R = 22.5, TR_Y = 5.5;

	void spawn(Location origin) {
		this.o = origin.clone();
		sleigh = null;
		train.clear();
		aurora.clear();
		if (on("sleigh")) buildSleigh();
		if (on("train")) buildTrain();
		if (on("aurora")) buildAurora();
		if (on("twinkle")) findBulbs();
	}

	boolean valid() {
		if (sleigh != null && !sleigh.valid()) return false;
		for (Model m : train) if (!m.valid()) return false;
		for (TextDisplay t : aurora) if (!t.isValid()) return false;
		return true;
	}

	private void buildSleigh() {
		Model m = new Model();
		float s = 2.2f;
		Material RED = Material.RED_CONCRETE, GOLD = Material.GOLD_BLOCK;
		// runners with curled fronts
		for (int side : new int[] {-1, 1}) {
			m.part(GOLD, side * 0.7 - 0.06, 0, -1.4, 0.12, 0.12, 2.8, true, s);
			m.part(GOLD, side * 0.7 - 0.06, 0.12, 1.3, 0.12, 0.5, 0.12, true, s);
			m.part(GOLD, side * 0.7 - 0.06, 0.12, -0.9, 0.12, 0.2, 0.1, true, s);
			m.part(GOLD, side * 0.7 - 0.06, 0.12, 0.6, 0.12, 0.2, 0.1, true, s);
		}
		m.part(RED, -0.8, 0.3, -1.2, 1.6, 0.8, 2.2, false, s);                 // body
		m.part(RED, -0.8, 1.1, -1.2, 1.6, 0.6, 0.3, false, s);                 // high back
		m.part(GOLD, -0.82, 1.0, -1.22, 1.64, 0.08, 2.24, true, s);            // gold trim
		m.part(Material.BROWN_WOOL, -0.55, 1.1, -1.15, 1.1, 0.9, 0.75, false, s);   // the sack
		m.part(Material.LIME_WOOL, -0.7, 1.1, -0.35, 0.4, 0.35, 0.4, false, s);    // presents
		m.part(Material.LIGHT_BLUE_WOOL, 0.3, 1.1, -0.4, 0.4, 0.45, 0.4, false, s);
		// Santa at the reins
		m.part(Material.RED_WOOL, -0.3, 1.1, 0.0, 0.6, 0.65, 0.45, false, s);
		m.part(Material.WHITE_WOOL, -0.31, 1.1, 0.0, 0.62, 0.08, 0.47, false, s);
		m.part(Material.WHITE_TERRACOTTA, -0.2, 1.75, 0.05, 0.4, 0.35, 0.35, false, s);
		m.part(Material.WHITE_WOOL, -0.2, 1.68, 0.38, 0.4, 0.2, 0.05, false, s);  // beard
		m.part(Material.RED_WOOL, -0.21, 2.1, 0.04, 0.42, 0.25, 0.37, false, s);  // hat
		m.part(Material.WHITE_WOOL, -0.06, 2.3, 0.15, 0.12, 0.12, 0.12, false, s);
		// reindeer: two pairs and Rudolph in front
		double[][] deer = {{-0.55, 3.0}, {0.55, 3.0}, {-0.55, 4.7}, {0.55, 4.7}, {0, 6.4}};
		for (int i = 0; i < deer.length; i++) reindeer(m, deer[i][0], deer[i][1], i == deer.length - 1, s);
		// the reins
		for (int side : new int[] {-1, 1})
			m.part(GOLD, side * 0.35 - 0.02, 1.05, 0.9, 0.04, 0.04, 5.3, true, s);
		sleigh = m;
	}

	private void reindeer(Model m, double x, double z, boolean rudolph, float s) {
		Material B = Material.BROWN_TERRACOTTA, L = Material.DARK_OAK_PLANKS, A = Material.STRIPPED_BIRCH_WOOD;
		m.part(B, x - 0.22, 0.6, z - 0.5, 0.44, 0.45, 1.0, false, s);              // body
		m.part(B, x - 0.12, 0.95, z + 0.4, 0.24, 0.45, 0.22, false, s);            // neck
		m.part(B, x - 0.14, 1.3, z + 0.45, 0.28, 0.25, 0.4, false, s);             // head
		m.part(rudolph ? Material.REDSTONE_BLOCK : Material.BLACK_CONCRETE, x - 0.06, 1.35, z + 0.85, 0.12, 0.12, 0.08, rudolph, s);
		for (int side : new int[] {-1, 1}) {
			m.part(A, x + side * 0.12 - 0.03, 1.55, z + 0.5, 0.06, 0.35, 0.06, false, s);      // antlers
			m.part(A, side > 0 ? x + 0.12 : x - 0.12 - 0.18, 1.75, z + 0.5, 0.18, 0.06, 0.06, false, s);
		}
		for (double lx : new double[] {-0.17, 0.09}) {
			m.part(L, x + lx, 0.0, z - 0.45, 0.08, 0.62, 0.08, false, s);
			m.part(L, x + lx, 0.0, z + 0.37, 0.08, 0.62, 0.08, false, s);
		}
		m.part(Material.WHITE_WOOL, x - 0.06, 0.95, z - 0.58, 0.12, 0.12, 0.1, false, s);   // tail
	}

	private void buildTrain() {
		float s = 1.6f;
		Model eng = new Model();
		eng.part(Material.BLACK_CONCRETE, -0.45, 0.35, -0.3, 0.9, 0.8, 1.5, false, s);      // boiler
		eng.part(Material.GOLD_BLOCK, -0.47, 0.6, 0.3, 0.94, 0.1, 0.1, true, s);
		eng.part(Material.GOLD_BLOCK, -0.47, 0.6, 0.85, 0.94, 0.1, 0.1, true, s);
		eng.part(Material.RED_CONCRETE, -0.6, 0.35, -1.0, 1.2, 1.25, 0.7, false, s);        // cab
		eng.part(Material.BLACK_CONCRETE, -0.7, 1.6, -1.1, 1.4, 0.1, 0.9, false, s);        // cab roof
		eng.part(Material.YELLOW_STAINED_GLASS, -0.45, 1.05, -0.32, 0.9, 0.35, 0.04, true, s);
		eng.part(Material.BLACK_CONCRETE, -0.15, 1.15, 0.8, 0.3, 0.55, 0.3, false, s);       // chimney
		eng.part(Material.RED_CONCRETE, -0.5, 0.05, 1.2, 1.0, 0.3, 0.25, false, s);          // cowcatcher
		eng.part(Material.OCHRE_FROGLIGHT, -0.12, 0.7, 1.2, 0.24, 0.24, 0.08, true, s);      // headlamp
		wheels(eng, -0.8, 0.9, s);
		train.add(eng);
		Material[] colors = {Material.LIME_CONCRETE, Material.LIGHT_BLUE_CONCRETE, Material.RED_CONCRETE, Material.PURPLE_CONCRETE};
		Material[] gifts = {Material.RED_WOOL, Material.YELLOW_WOOL, Material.WHITE_WOOL, Material.LIME_WOOL};
		for (int i = 0; i < colors.length; i++) {
			Model car = new Model();
			car.part(Material.BLACK_CONCRETE, -0.5, 0.3, -0.85, 1.0, 0.1, 1.7, false, s);    // chassis
			car.part(colors[i], -0.5, 0.4, -0.85, 1.0, 0.45, 1.7, false, s);                 // body
			car.part(Material.GOLD_BLOCK, -0.52, 0.8, -0.87, 1.04, 0.06, 1.74, true, s);
			car.part(gifts[i], -0.35, 0.85, -0.6, 0.35, 0.35, 0.35, false, s);
			car.part(gifts[(i + 1) % 4], 0.0, 0.85, -0.1, 0.32, 0.45, 0.32, false, s);
			car.part(gifts[(i + 2) % 4], -0.3, 0.85, 0.3, 0.3, 0.3, 0.3, false, s);
			wheels(car, -0.6, 0.6, s);
			train.add(car);
		}
	}

	private void wheels(Model m, double zBack, double zFront, float s) {
		for (int side : new int[] {-1, 1})
			for (double wz : new double[] {zBack, zFront})
				m.part(Material.RED_CONCRETE, side * 0.5 - (side > 0 ? 0 : 0.12), 0.0, wz - 0.15, 0.12, 0.32, 0.32, false, s);
	}

	private void buildAurora() {
		int n = 34;
		for (int i = 0; i < n; i++) {
			double u = i / (double) (n - 1);
			double x = -105 + u * 210;
			double z = -112 + Math.sin(u * Math.PI * 2.3) * 14;
			double y = 118 + Math.sin(u * Math.PI * 1.7) * 8;
			// green -> teal -> purple across the sky
			int r = (int) (40 + 140 * Math.max(0, u - 0.55) * 2), g = (int) (255 - 150 * u), b = (int) (140 + 100 * u);
			Color c = Color.fromARGB(0x55, Math.min(255, r), Math.max(60, g), Math.min(255, b));
			Location at = o.clone().add(x, y, z);
			TextDisplay t = o.getWorld().spawn(at, TextDisplay.class, d -> {
				d.text(net.kyori.adventure.text.Component.text("  "));
				d.setBackgroundColor(c);
				d.setBillboard(Display.Billboard.VERTICAL);
				d.setBrightness(new Display.Brightness(15, 15));
				d.setViewRange(12f);
				d.setPersistent(false);
				d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(48f, 110f, 1f), new AxisAngle4f()));
				d.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
			});
			aurora.add(t);
		}
	}

	private void findBulbs() {
		bulbs.clear();
		World w = o.getWorld();
		int ox = o.getBlockX(), oy = o.getBlockY(), oz = o.getBlockZ();
		for (int x = -21; x <= 21; x++)
			for (int z = -124; z <= -82; z++)
				for (int y = 11; y <= 90; y++) {
					Block b = w.getBlockAt(ox + x, oy + y, oz + z);
					if (BULBS.contains(b.getType())) bulbs.add(b);
				}
	}

	private static final List<Material> BULBS = List.of(Material.SHROOMLIGHT, Material.OCHRE_FROGLIGHT, Material.SEA_LANTERN,
			Material.PEARLESCENT_FROGLIGHT, Material.VERDANT_FROGLIGHT);

	// ------------------------------------------------------------------ the animation (called every 2 ticks)

	void tick(long t) {
		if (o == null) return;
		ThreadLocalRandom r = ThreadLocalRandom.current();
		if (t % 4 == 0 && sleigh != null) {
			double a = (t % 1200) / 1200.0 * Math.PI * 2;                  // a lap a minute
			double x = SL_CX + Math.cos(a) * SL_R, z = SL_CZ + Math.sin(a) * SL_R;
			double y = SL_Y + Math.sin(a * 3) * 3;
			sleigh.moveTo(x, y, z, (float) Math.toDegrees(a));
			// a trail of sparkles behind
			Location tail = o.clone().add(x - Math.cos(a + Math.PI / 2) * 3.5, y + 1.5, z - Math.sin(a + Math.PI / 2) * 3.5);
			o.getWorld().spawnParticle(Particle.END_ROD, tail, 4, 0.6, 0.4, 0.6, 0.01);
		}
		if (t % 4 == 0 && !train.isEmpty()) {
			double a0 = (t % 800) / 800.0 * Math.PI * 2;                   // a lap every 40 s
			double gap = 4.6 / TR_R;
			for (int i = 0; i < train.size(); i++) {
				double a = a0 - i * gap;
				train.get(i).moveTo(TR_CX + Math.cos(a) * TR_R, TR_Y, TR_CZ + Math.sin(a) * TR_R, (float) Math.toDegrees(a));
			}
			if (t % 8 == 0) {
				double a = a0 + 0.04;
				Location chimney = o.clone().add(TR_CX + Math.cos(a) * TR_R, TR_Y + 2.9, TR_CZ + Math.sin(a) * TR_R);
				o.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, chimney, 1, 0.05, 0.05, 0.05, 0.01);
			}
		}
		if (t % 10 == 0 && !bulbs.isEmpty()) {
			for (int i = 0; i < 6; i++) {
				Block b = bulbs.get(r.nextInt(bulbs.size()));
				Material next = BULBS.get(r.nextInt(BULBS.size()));
				if (BULBS.contains(b.getType())) b.setType(next, false);
			}
		}
		if (t % 40 == 0 && !aurora.isEmpty()) {
			double ph = t / 40.0 * 0.35;
			for (int i = 0; i < aurora.size(); i++) {
				TextDisplay d = aurora.get(i);
				if (!d.isValid()) continue;
				float sy = (float) (100 + 40 * Math.sin(ph + i * 0.45));
				float ty = (float) (6 * Math.sin(ph * 0.7 + i * 0.3));
				d.setInterpolationDelay(0);
				d.setInterpolationDuration(40);
				d.setTransformation(new Transformation(new Vector3f(0, ty, 0), new AxisAngle4f(), new Vector3f(48f, sy, 1f), new AxisAngle4f()));
			}
		}
		if (on("fireworks") && t % 1800 == 0 && t > 0) fireworks();
	}

	/** A short show over the tree and the palace spires. */
	private void fireworks() {
		World w = o.getWorld();
		ThreadLocalRandom r = ThreadLocalRandom.current();
		Color[] cs = {Color.RED, Color.LIME, Color.fromRGB(0xF4C542), Color.WHITE, Color.fromRGB(0x38E0FF), Color.fromRGB(0xB040FF)};
		FireworkEffect.Type[] types = {FireworkEffect.Type.BALL_LARGE, FireworkEffect.Type.STAR, FireworkEffect.Type.BURST, FireworkEffect.Type.BALL};
		for (int i = 0; i < 10; i++) {
			final int k = i;
			Bukkit.getScheduler().runTaskLater(plugin, () -> {
				Location at = o.clone().add(r.nextDouble(-34, 34), r.nextDouble(88, 116), -103 + r.nextDouble(-26, 26));
				Firework fw = w.spawn(at, Firework.class, f -> {
					FireworkMeta m = f.getFireworkMeta();
					m.addEffect(FireworkEffect.builder().with(types[k % types.length]).withColor(cs[r.nextInt(cs.length)], cs[r.nextInt(cs.length)])
							.withFade(Color.WHITE).trail(true).flicker(k % 2 == 0).build());
					m.setPower(0);
					f.setFireworkMeta(m);
					f.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
				});
				Bukkit.getScheduler().runTaskLater(plugin, () -> { if (fw.isValid()) fw.detonate(); }, 2L);
			}, i * 9L);
		}
	}

	/** Every entity this class made carries the hub's hologram tag, so the hub's clean-up removes them too. */
	static boolean ours(Entity e, NamespacedKey tag) {
		return e.getPersistentDataContainer().has(tag, PersistentDataType.BYTE);
	}
}
