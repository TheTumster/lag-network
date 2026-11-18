package net.lag.core;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;

/**
 * In-world "Getting Started" panels (resources/panels.yml, one per server role). Built from display entities, one copy
 * per nearby player: their own head and name, tabs, and the card they're looking at lights up. Click a card to run its
 * action (cmd:<command>, tab:<id>, msg:<text>).
 */
final class InfoPanel implements Listener {
	private record Box(double u1, double v1, double u2, double v2) {
		boolean has(double u, double v) {
			return u >= u1 && u <= u2 && v >= v1 && v <= v2;
		}
	}

	private static final class Hit {
		Box box;
		String action;
		BlockDisplay face;
	}

	private static final class View {
		String tab;
		final List<Entity> ents = new ArrayList<>();      // everything (removed when the player leaves)
		final List<Entity> cardEnts = new ArrayList<>();  // just the current tab's cards (swapped on a tab change)
		final List<Hit> hits = new ArrayList<>();
		final List<Hit> cardHits = new ArrayList<>();
		final Map<String, BlockDisplay> tabFaces = new HashMap<>();
		final List<Entity> layer = new ArrayList<>();
		Hit hovered;
		long lastClick;
		boolean built;
	}

	/** Cards being drawn right now go into the card lists, so a tab change only swaps them. */
	private boolean drawingCards;
	/** Players waiting for their copy; a few are built per tick so a wave of joins doesn't spike the server. */
	private final java.util.ArrayDeque<UUID> queue = new java.util.ArrayDeque<>();
	private static final int BUILDS_PER_TICK = 2;

	private final LagCore plugin;
	private final HubBuild anchor;
	private final String role;
	private ConfigurationSection def;
	private final Map<UUID, View> views = new HashMap<>();
	private Location origin;     // bottom centre of the panel, on its front face
	private Vector f, r;         // f = way the panel faces, r = viewer's right
	private double width, height;

	InfoPanel(LagCore plugin, HubBuild anchor, String role) {
		this.plugin = plugin;
		this.anchor = anchor;
		this.role = role;
		load();
		Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 2L);
	}

	/** plugins/laGCore/panels.yml if it exists (live edits), else the one in the jar. */
	private void load() {
		YamlConfiguration y = new YamlConfiguration();
		java.io.File f = new java.io.File(plugin.getDataFolder(), "panels.yml");
		if (f.exists()) {
			y = YamlConfiguration.loadConfiguration(f);
		} else {
			try (var in = plugin.getResource("panels.yml")) {
				if (in != null) y = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
			} catch (Exception ignored) { }
		}
		def = y.getConfigurationSection(role);
	}

	/** /lc reload: re-read the layout and redraw everyone's copy. */
	void reload() {
		clear();
		origin = null;
		load();
	}

	boolean active() {
		return def != null;
	}

	private boolean place() {
		if (origin != null) return true;
		Location o = anchor.origin();
		if (o == null || def == null) return false;
		List<?> at = def.getList("at");
		origin = o.clone().add(num(at.get(0)), num(at.get(1)), num(at.get(2)));
		String facing = def.getString("facing", "south").toLowerCase();
		f = switch (facing) {
			case "north" -> new Vector(0, 0, -1);
			case "east" -> new Vector(1, 0, 0);
			case "west" -> new Vector(-1, 0, 0);
			default -> new Vector(0, 0, 1);
		};
		r = new Vector(f.getZ(), 0, -f.getX());
		width = def.getDouble("width", 6.4);
		height = def.getDouble("height", 3.6);
		return true;
	}

	private static double num(Object o) {
		return o instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(o));
	}

	private float yaw() {
		// display entities face south at yaw 0
		if (f.getX() > 0.5) return -90f;
		if (f.getX() < -0.5) return 90f;
		if (f.getZ() < -0.5) return 180f;
		return 0f;
	}

	private Location at(double u, double v, double d) {
		return origin.clone().add(r.clone().multiply(u)).add(0, v, 0).add(f.clone().multiply(d));
	}

	// ---------------------------------------------------------------- building one player's copy

	private <T extends Entity> T hide(T e, Player p) {
		e.setPersistent(false);
		e.setVisibleByDefault(false);
		p.showEntity(plugin, e);
		return e;
	}

	/** Flat colour rectangle (block display), u/v in blocks from the panel's bottom centre; d = how far in front. */
	private BlockDisplay rect(Player p, View v, double u1, double v1, double u2, double v2, double d, String block) {
		double w = u2 - u1, h = v2 - v1, t = 0.02;
		Location base = at((u1 + u2) / 2, v1, d);
		boolean alongZ = Math.abs(r.getZ()) > 0.5;
		Vector3f scale = alongZ ? new Vector3f((float) t, (float) h, (float) w) : new Vector3f((float) w, (float) h, (float) t);
		Vector3f tr = new Vector3f(-scale.x / 2, 0, -scale.z / 2);
		var data = Bukkit.createBlockData(block.contains(":") ? block : "minecraft:" + block);
		BlockDisplay b = base.getWorld().spawn(base, BlockDisplay.class, e -> {
			e.setBlock(data);
			e.setTransformation(new Transformation(tr, new AxisAngle4f(), scale, new AxisAngle4f()));
			e.setBrightness(new Display.Brightness(15, 15));
			e.setViewRange(1.5f);
			hide(e, p);
		});
		v.ents.add(b);
		if (drawingCards) v.cardEnts.add(b);
		return b;
	}

	private TextDisplay text(Player p, View v, double u, double vv, String mm, float scale, boolean left) {
		Location l = at(u, vv, 0.06);
		l.setYaw(yaw());
		TextDisplay t = l.getWorld().spawn(l, TextDisplay.class, e -> {
			e.text(LagCore.mm(mm.replace("{player}", p.getName())));
			e.setBillboard(Display.Billboard.FIXED);
			e.setDefaultBackground(false);
			e.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
			e.setShadowed(true);
			e.setLineWidth(1000);
			e.setAlignment(left ? TextDisplay.TextAlignment.LEFT : TextDisplay.TextAlignment.CENTER);
			e.setBrightness(new Display.Brightness(15, 15));
			e.setViewRange(1.5f);
			e.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
			hide(e, p);
		});
		v.ents.add(t);
		if (drawingCards) v.cardEnts.add(t);
		return t;
	}

	private void icon(Player p, View v, double u, double vv, String item, float scale) {
		Location l = at(u, vv, 0.12);
		l.setYaw(yaw() + 180f);
		ItemStack it;
		if ("PLAYER_HEAD".equalsIgnoreCase(item)) {
			it = new ItemStack(Material.PLAYER_HEAD);
			SkullMeta m = (SkullMeta) it.getItemMeta();
			m.setOwningPlayer(p);
			it.setItemMeta(m);
		} else {
			Material mat = Material.matchMaterial(item);
			it = new ItemStack(mat == null ? Material.BARRIER : mat);
		}
		ItemStack fin = it;
		final boolean cardsNow = drawingCards;
		ItemDisplay d = l.getWorld().spawn(l, ItemDisplay.class, e -> {
			e.setItemStack(fin);
			e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GUI);
			e.setBrightness(new Display.Brightness(15, 15));
			e.setViewRange(1.5f);
			e.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, 0.02f), new AxisAngle4f()));
			hide(e, p);
		});
		v.ents.add(d);
		if (cardsNow) v.cardEnts.add(d);
	}

	private void clickable(Player p, View v, Box b, String action, BlockDisplay face) {
		Hit h = new Hit();
		h.box = b;
		h.action = action;
		h.face = face;
		v.hits.add(h);
		if (drawingCards) v.cardHits.add(h);
	}

	/**
	 * Interaction boxes are square (same depth as width), so one per card would stick out in front of the panel and
	 * swallow clicks meant for other cards. Instead: a row of thin 0.5-wide columns whose front sits just in front of
	 * the panel face; any click on them goes to the card under the crosshair.
	 */
	private void clickLayer(Player p, View v) {
		double cw = 0.5;
		int cols = (int) Math.ceil(width / cw);
		for (int i = 0; i < cols; i++) {
			double u = -width / 2 + cw / 2 + i * cw;
			Location l = at(u, -0.05, 0.08 - cw / 2);
			Interaction it = l.getWorld().spawn(l, Interaction.class, e -> {
				e.setInteractionWidth((float) cw);
				e.setInteractionHeight((float) (height + 0.1));
				e.setResponsive(true);
				hide(e, p);
			});
			v.ents.add(it);
			v.layer.add(it);
		}
	}

	private void build(Player p, View v) {
		for (Entity e : v.ents) e.remove();
		v.ents.clear();
		v.cardEnts.clear();
		v.hits.clear();
		v.cardHits.clear();
		v.tabFaces.clear();
		v.layer.clear();
		v.hovered = null;
		v.built = true;
		double hw = width / 2;
		// frame: screen, accent bars
		rect(p, v, -hw - 0.06, -0.06, hw + 0.06, height + 0.06, -0.02, def.getString("frame", "polished_blackstone"));
		rect(p, v, -hw, 0, hw, height, 0, def.getString("background", "black_concrete"));
		rect(p, v, -hw, height - 0.05, 0, height, 0.01, "light_blue_concrete");
		rect(p, v, 0, height - 0.05, hw, height, 0.01, "purple_concrete");
		// header: everything centred (text displays always centre on their anchor)
		text(p, v, 0, height - 0.62, def.getString("title", "<gradient:#38E0FF:#B040FF><bold>GETTING STARTED</bold></gradient>"), (float) def.getDouble("title-size", 1.1), false);
		if (def.isString("footer")) text(p, v, 0, 0.1, def.getString("footer"), 0.45f, false);
		// tabs
		List<Map<?, ?>> tabs = def.getMapList("tabs");
		double tw = (width - 0.3) / Math.max(1, tabs.size());
		for (int i = 0; i < tabs.size(); i++) {
			String id = String.valueOf(tabs.get(i).get("id"));
			boolean on = id.equals(v.tab);
			double u1 = -hw + 0.15 + i * tw + 0.04, u2 = u1 + tw - 0.08;
			double t1 = height - 1.12, t2 = height - 0.8;
			BlockDisplay face = rect(p, v, u1, t1, u2, t2, 0.01, on ? "purple_concrete" : "gray_concrete");
			text(p, v, (u1 + u2) / 2, t1 + 0.08, "<white><bold>" + tabs.get(i).get("label"), 0.6f, false);
			clickable(p, v, new Box(u1, t1, u2, t2), "tab:" + id, face);
			v.tabFaces.put(id, face);
		}
		clickLayer(p, v);
		cards(p, v);
	}

	/** Swaps only the cards (and the tab colours) on a tab change: the frame, title and tabs stay put. */
	private void switchTab(Player p, View v, String id) {
		v.tab = id;
		if (v.hovered != null && v.cardHits.contains(v.hovered)) v.hovered = null;
		for (Entity e : v.cardEnts) e.remove();
		v.ents.removeAll(v.cardEnts);
		v.hits.removeAll(v.cardHits);
		v.cardEnts.clear();
		v.cardHits.clear();
		for (var e : v.tabFaces.entrySet()) {
			if (e.getValue().isValid()) e.getValue().setBlock(Bukkit.createBlockData(e.getKey().equals(id) ? Material.PURPLE_CONCRETE : Material.GRAY_CONCRETE));
		}
		cards(p, v);
	}

	private void cards(Player p, View v) {
		drawingCards = true;
		try {
			drawCards(p, v);
		} finally {
			drawingCards = false;
		}
	}

	private void drawCards(Player p, View v) {
		List<Map<?, ?>> tabs = def.getMapList("tabs");
		Map<?, ?> tab = tabs.stream().filter(t -> String.valueOf(t.get("id")).equals(v.tab)).findFirst().orElse(tabs.isEmpty() ? null : tabs.get(0));
		if (tab == null) return;
		Object cards = tab.get("cards");
		if (!(cards instanceof List<?> list)) return;
		for (Object o : list) {
			if (!(o instanceof Map<?, ?> c)) continue;
			List<?> bx = (List<?>) c.get("box");
			Box b = new Box(num(bx.get(0)), num(bx.get(1)), num(bx.get(2)), num(bx.get(3)));
			String color = String.valueOf(c.containsKey("color") ? c.get("color") : "gray_concrete");
			BlockDisplay face = rect(p, v, b.u1, b.v1, b.u2, b.v2, 0.01, color);
			if (c.containsKey("accent")) rect(p, v, b.u1, b.v2 - 0.05, b.u2, b.v2, 0.02, String.valueOf(c.get("accent")));
			double cu = (b.u1 + b.u2) / 2, top = b.v2;
			double y = top - 0.12;
			if (c.containsKey("icon")) {
				float isz = (float) (c.containsKey("icon-size") ? num(c.get("icon-size")) : 0.45);
				icon(p, v, cu, top - 0.12 - isz / 2, String.valueOf(c.get("icon")), isz);
				y = top - 0.2 - isz;
			}
			if (c.containsKey("title")) {
				text(p, v, cu, y - 0.18, String.valueOf(c.get("title")), (float) (c.containsKey("title-size") ? num(c.get("title-size")) : 0.7), false);
				y -= 0.24;
			}
			if (c.get("lines") instanceof List<?> lines) {
				StringBuilder sb = new StringBuilder();
				for (Object l : lines) sb.append(sb.length() > 0 ? "\n" : "").append(l);
				float ls = (float) (c.containsKey("line-size") ? num(c.get("line-size")) : 0.42);
				double lh = 0.25 * ls;
				text(p, v, cu, y - lh * lines.size() - 0.04, sb.toString(), ls, false);
			}
			if (c.containsKey("action")) clickable(p, v, b, String.valueOf(c.get("action")), face);
		}
	}

	// ---------------------------------------------------------------- per-player lifecycle, hover, click

	private void tick() {
		if (def == null || !place()) return;
		World w = origin.getWorld();
		double range = def.getDouble("range", 24);
		for (Player p : Bukkit.getOnlinePlayers()) {
			View v = views.get(p.getUniqueId());
			boolean near = p.getWorld().equals(w) && p.getLocation().distanceSquared(origin) < range * range;
			if (near && v == null) {
				v = new View();
				v.tab = def.getMapList("tabs").isEmpty() ? "" : String.valueOf(def.getMapList("tabs").get(0).get("id"));
				views.put(p.getUniqueId(), v);
				queue.add(p.getUniqueId());
			} else if (!near && v != null) {
				drop(p.getUniqueId());
				continue;
			}
			if (v != null && v.built) hover(p, v);
		}
		for (int i = 0; i < BUILDS_PER_TICK && !queue.isEmpty(); ) {
			UUID id = queue.poll();
			View v = views.get(id);
			Player p = Bukkit.getPlayer(id);
			if (v == null || p == null || v.built) continue;
			build(p, v);
			i++;
		}
		views.keySet().removeIf(id -> {
			if (Bukkit.getPlayer(id) == null) {
				View v = views.get(id);
				v.ents.forEach(Entity::remove);
				return true;
			}
			return false;
		});
	}

	private void drop(UUID id) {
		queue.remove(id);
		View v = views.remove(id);
		if (v != null) v.ents.forEach(Entity::remove);
	}

	private void hover(Player p, View v) {
		Hit found = aim(p, v);
		if (found == v.hovered) return;
		if (v.hovered != null && v.hovered.face.isValid()) v.hovered.face.setGlowing(false);
		v.hovered = found;
		if (found != null) {
			found.face.setGlowColorOverride(Color.fromRGB(0x38E0FF));
			found.face.setGlowing(true);
			p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.25f, 1.9f);
		}
	}

	/** The card under the player's crosshair, or null. */
	private Hit aim(Player p, View v) {
		Location eye = p.getEyeLocation();
		Vector dir = eye.getDirection();
		double denom = f.dot(dir);
		Hit found = null;
		if (denom < -0.05) {
			Vector toPlane = at(0, 0, 0.03).toVector().subtract(eye.toVector());
			double t = f.dot(toPlane) / denom;
			if (t > 0 && t < 14) {
				Vector hit = eye.toVector().add(dir.clone().multiply(t));
				Vector rel = hit.clone().subtract(origin.toVector());
				double u = rel.dot(r), vv = rel.getY();
				for (Hit h : v.hits) {
					if (h.box.has(u, vv)) {
						found = h;
						break;
					}
				}
			}
		}
		return found;
	}

	private void click(Player p, Entity e) {
		View v = views.get(p.getUniqueId());
		if (v == null) return;
		if (e != null && !v.layer.contains(e)) return;
		Hit h = aim(p, v);
		if (h == null) return;
		long now = System.currentTimeMillis();
		if (now - v.lastClick < 250) return;
		v.lastClick = now;
		run(p, v, h.action);
	}

	private void run(Player p, View v, String action) {
		p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
		if (action.startsWith("tab:")) {
			String id = action.substring(4);
			if (!id.equals(v.tab)) switchTab(p, v, id);
		} else if (action.startsWith("cmd:")) {
			String cmd = action.substring(4).replace("{player}", p.getName());
			Bukkit.getScheduler().runTask(plugin, () -> p.performCommand(cmd));
		} else if (action.startsWith("msg:")) {
			p.sendMessage(LagCore.mm(action.substring(4)));
		}
	}

	@EventHandler(ignoreCancelled = false)
	public void onRight(PlayerInteractEntityEvent e) {
		if (e.getRightClicked() instanceof Interaction) {
			if (views.containsKey(e.getPlayer().getUniqueId())) {
				click(e.getPlayer(), e.getRightClicked());
			}
		}
	}

	@EventHandler(ignoreCancelled = false)
	public void onLeft(PrePlayerAttackEntityEvent e) {
		if (e.getAttacked() instanceof Interaction && views.containsKey(e.getPlayer().getUniqueId())) {
			e.setCancelled(true);
			click(e.getPlayer(), e.getAttacked());
		}
	}

	/**
	 * The click entities only reach about 3 blocks (the game's entity reach), but a card lights up from much further.
	 * So any left click (arm swing) or right click while a card is lit counts as clicking it, at any distance.
	 */
	@EventHandler(ignoreCancelled = false)
	public void onSwing(org.bukkit.event.player.PlayerAnimationEvent e) {
		if (e.getAnimationType() != org.bukkit.event.player.PlayerAnimationType.ARM_SWING) return;
		View v = views.get(e.getPlayer().getUniqueId());
		if (v != null && v.built && aim(e.getPlayer(), v) != null) click(e.getPlayer(), null);
	}

	@EventHandler(ignoreCancelled = false, priority = org.bukkit.event.EventPriority.LOW)
	public void onUse(org.bukkit.event.player.PlayerInteractEvent e) {
		var a = e.getAction();
		if (a != org.bukkit.event.block.Action.RIGHT_CLICK_AIR && a != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
		if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
		View v = views.get(e.getPlayer().getUniqueId());
		if (v == null || !v.built || aim(e.getPlayer(), v) == null) return;
		e.setCancelled(true);
		click(e.getPlayer(), null);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent e) {
		drop(e.getPlayer().getUniqueId());
	}

	void clear() {
		for (UUID id : new ArrayList<>(views.keySet())) drop(id);
	}
}
