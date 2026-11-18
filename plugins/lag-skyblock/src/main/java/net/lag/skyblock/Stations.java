package net.lag.skyblock;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * The living parts of the Shattered World spawn (laGCore builds the blocks): the station NPCs (the Collector, the
 * Cage Keeper, the Gen Smith, the Merchant), the 13 caged mobs in the Cage Sanctum, the Hall of Champions board
 * (live island top + last season's champions) and the Season Obelisk sign. Nothing here is saved: it's re-made on
 * every start and whenever something goes missing.
 */
final class Stations implements Listener {
	private final LagSkyblock plugin;
	private final NamespacedKey key, cmdKey;
	private final List<Entity> spawned = new ArrayList<>();
	private TextDisplay board, obelisk;
	private final Map<UUID, Long> clickCooldown = new HashMap<>();

	Stations(LagSkyblock plugin) {
		this.plugin = plugin;
		this.key = new NamespacedKey(plugin, "station");
		this.cmdKey = new NamespacedKey(plugin, "station_cmd");
		// after laGCore has built the spawn (it builds one tick after enabling)
		Bukkit.getScheduler().runTaskLater(plugin, this::respawn, 60L);
		Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 200L, 200L);
	}

	World world() {
		return Bukkit.getWorlds().get(0);
	}

	private Location at(List<?> off, float yaw) {
		double[] o = origin();
		Location l = new Location(world(), o[0] + num(off.get(0)), o[1] + num(off.get(1)), o[2] + num(off.get(2)), yaw, 0);
		return l;
	}

	private double[] origin() {
		List<?> l = plugin.getConfig().getList("spawn.origin");
		if (l == null || l.size() < 3) return new double[] {0, 100, 0};
		return new double[] {num(l.get(0)), num(l.get(1)), num(l.get(2))};
	}

	private static double num(Object o) {
		return o instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(o));
	}

	private <T extends Entity> T mark(T e, String command) {
		e.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
		if (command != null) e.getPersistentDataContainer().set(cmdKey, PersistentDataType.STRING, command);
		e.setPersistent(false);
		spawned.add(e);
		return e;
	}

	void clear() {
		for (Entity e : spawned) e.remove();
		spawned.clear();
		board = null;
		obelisk = null;
		for (Entity e : world().getEntities()) if (e.getPersistentDataContainer().has(key)) e.remove();
	}

	void respawn() {
		clear();
		ConfigurationSection npcs = plugin.getConfig().getConfigurationSection("spawn.npcs");
		if (npcs != null) {
			for (String id : npcs.getKeys(false)) {
				ConfigurationSection n = npcs.getConfigurationSection(id);
				if (n == null) continue;
				List<?> off = n.getList("at");
				if (off == null || off.size() < 3) continue;
				Location l = at(off, (float) n.getDouble("yaw", 0));
				npc(l, n.getString("type", "VILLAGER"), n.getString("command", ""));
				text(l.clone().add(0, 2.55, 0), n.getString("name", id) + "\n" + n.getString("sub", ""), 1.0f, null);
			}
		}
		// the caged mobs, one per tier
		List<?> cages = plugin.getConfig().getList("spawn.cages");
		if (cages != null) {
			for (int i = 0; i < cages.size() && i < plugin.tiers.size(); i++) {
				if (!(cages.get(i) instanceof List<?> off) || off.size() < 3) continue;
				LagSkyblock.Tier t = plugin.tiers.get(i);
				Location l = at(off, 0);
				caged(l, t);
				text(l.clone().add(0, 4.9, 0), "<color:" + t.color() + "><bold>" + t.name().toUpperCase() + "</bold>\n<gray>Tier " + t.number()
						+ " <dark_gray>· <gold>" + LagSkyblock.shards(t.price()), 0.8f, null);
			}
		}
		List<?> b = plugin.getConfig().getList("spawn.champions");
		if (b != null && b.size() >= 3) board = text(at(b, 180), "", 0.9f, "#C0101018");
		List<?> ob = plugin.getConfig().getList("spawn.obelisk");
		if (ob != null && ob.size() >= 3) obelisk = text(at(ob, 0), "", 1.3f, null);
		refresh();
	}

	private void npc(Location l, String type, String command) {
		EntityType et;
		try {
			et = EntityType.valueOf(type.equalsIgnoreCase("WANDERING_TRADER") ? "WANDERING_TRADER" : "VILLAGER");
		} catch (IllegalArgumentException e) {
			et = EntityType.VILLAGER;
		}
		Entity e = l.getWorld().spawnEntity(l, et);
		if (e instanceof Villager v) {
			try {
				var prof = org.bukkit.Registry.VILLAGER_PROFESSION.get(NamespacedKey.minecraft(type.toLowerCase()));
				if (prof != null) v.setProfession(prof);
			} catch (Throwable ignored) { }
			v.setVillagerLevel(5);
		}
		if (e instanceof Mob m) {
			m.setAI(false);
			m.setSilent(true);
			m.setInvulnerable(true);
			m.setCollidable(false);
			m.setRemoveWhenFarAway(false);
			m.setRotation(l.getYaw(), 0);
		}
		mark(e, command);
	}

	private void caged(Location l, LagSkyblock.Tier t) {
		EntityType et;
		try {
			et = EntityType.valueOf(t.mob().toUpperCase());
		} catch (IllegalArgumentException e) {
			return;
		}
		Entity e = l.getWorld().spawnEntity(l, et);
		if (e instanceof Slime s) s.setSize(1);
		if (e instanceof LivingEntity le) {
			le.setAI(false);
			le.setSilent(true);
			le.setInvulnerable(true);
			le.setCollidable(false);
			le.setRemoveWhenFarAway(false);
			le.setRotation(270, 0); // look at the Keeper in the middle-ish
		}
		mark(e, "spawners");
	}

	private TextDisplay text(Location l, String mm, float scale, String background) {
		TextDisplay d = l.getWorld().spawn(l, TextDisplay.class, t -> {
			t.text(Spawners.mm(mm));
			t.setBillboard(background == null ? Display.Billboard.CENTER : Display.Billboard.FIXED);
			t.setShadowed(true);
			t.setLineWidth(400);
			t.setViewRange(4f);
			t.setBrightness(new Display.Brightness(15, 15));
			if (background == null) t.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
			else {
				long v = Long.parseLong(background.replace("#", ""), 16);
				t.setBackgroundColor(org.bukkit.Color.fromARGB((int) (v >> 24) & 255, (int) (v >> 16) & 255, (int) (v >> 8) & 255, (int) v & 255));
			}
			t.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
		});
		return mark(d, null);
	}

	/** Every 10 s: the board and the obelisk; anything that went missing comes back. */
	void refresh() {
		boolean missing = false;
		for (Entity e : spawned) missing |= !e.isValid();
		if (missing && !spawned.isEmpty() && world().isChunkLoaded((int) origin()[0] >> 4, (int) origin()[2] >> 4)) {
			respawn();
			return;
		}
		if (board != null && board.isValid()) board.text(Spawners.mm(boardText()));
		if (obelisk != null && obelisk.isValid() && plugin.season != null) {
			obelisk.text(Spawners.mm("<gradient:#FFD84D:#FF8A3D><bold>SEASON " + plugin.season.number() + "</bold></gradient>\n"
					+ (plugin.season.left() != null ? "<gray>Ends in <white>" + plugin.season.left() + "\n" : "")
					+ "<dark_gray><italic>Every month the void takes it back.\n<white>/season"));
		}
	}

	private String boardText() {
		StringBuilder sb = new StringBuilder("<gradient:#FFD84D:#FF8A3D><bold>HALL OF CHAMPIONS</bold></gradient>\n<gray>Island top <dark_gray>· <gray>placed cage value\n\n");
		List<IslandTop.Entry> r = plugin.top == null ? List.of() : plugin.top.ranking();
		for (int i = 0; i < 10; i++) {
			String place = i == 0 ? "<gradient:#FFD84D:#FF8A3D><bold>#1</bold>" : i == 1 ? "<#D9E2EC><bold>#2</bold>" : i == 2 ? "<#E0915A><bold>#3</bold>" : "<gray>#" + (i + 1);
			if (i < r.size()) sb.append(place).append(" <white>").append(r.get(i).display().replace("<", "\\<")).append(" <dark_gray>· <gold>").append(LagSkyblock.shards(r.get(i).value()));
			else sb.append(place).append(" <dark_gray>unclaimed");
			sb.append("\n");
		}
		String last = lastChampions();
		if (last != null) sb.append("\n").append(last);
		sb.append("\n<dark_gray>/is top");
		return sb.toString();
	}

	/** "Season N champions: names" from the newest season snapshot, if any. */
	private String lastChampions() {
		File dir = new File(plugin.getDataFolder(), "seasons");
		File[] files = dir.listFiles((d, n) -> n.startsWith("season-") && n.endsWith(".yml"));
		if (files == null || files.length == 0) return null;
		File newest = files[0];
		for (File f : files) if (f.lastModified() > newest.lastModified()) newest = f;
		YamlConfiguration y = YamlConfiguration.loadConfiguration(newest);
		String island = y.getString("top.1.island");
		if (island == null) return null;
		return "<gray>Season " + y.getInt("season") + " champions: <gradient:#FFD84D:#FF8A3D>" + island.replace("<", "\\<") + "</gradient>";
	}

	// ---------------------------------------------------------------- clicks, and nothing hurts them

	@EventHandler(priority = EventPriority.LOWEST)
	public void onClick(PlayerInteractEntityEvent e) {
		String cmd = e.getRightClicked().getPersistentDataContainer().get(cmdKey, PersistentDataType.STRING);
		if (!e.getRightClicked().getPersistentDataContainer().has(key)) return;
		e.setCancelled(true);
		Player p = e.getPlayer();
		long now = System.currentTimeMillis();
		Long last = clickCooldown.get(p.getUniqueId());
		if (last != null && now - last < 400) return;
		clickCooldown.put(p.getUniqueId(), now);
		if (cmd != null && !cmd.isEmpty()) p.performCommand(cmd);
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onClickAt(PlayerInteractAtEntityEvent e) {
		if (e.getRightClicked().getPersistentDataContainer().has(key)) e.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onDamage(EntityDamageEvent e) {
		if (e.getEntity().getPersistentDataContainer().has(key)) e.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onBurn(EntityCombustEvent e) {
		if (e.getEntity().getPersistentDataContainer().has(key)) e.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onTarget(EntityTargetEvent e) {
		if (e.getEntity().getPersistentDataContainer().has(key)) e.setCancelled(true);
	}
}
