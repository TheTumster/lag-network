package net.lag.core;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.kyori.adventure.title.Title;

/**
 * Hub parkour (course from tools/hub/genspawn.py -> resources/parkour.json, offsets from the spawn floor centre).
 * Gold plate = start / finish, blackstone plates = checkpoints (in order). Falling puts you back on your
 * last checkpoint; the timer runs in the action bar; best times are kept in plugins/laGCore/parkour.yml.
 */
final class Parkour implements Listener {
	private static final class Run {
		long start;
		int checkpoint = -1; // -1 = at the start pad
	}

	private final LagCore plugin;
	private final HubBuild build;
	private final HubFx fx;
	private final NamespacedKey itemKey;
	private final Map<UUID, Run> runs = new HashMap<>();
	private int[] start, finish, entrance;
	private final List<int[]> checkpoints = new ArrayList<>();
	private final File file;
	private final YamlConfiguration times;

	Parkour(LagCore plugin, HubBuild build, HubFx fx) {
		this.plugin = plugin;
		this.build = build;
		this.fx = fx;
		this.itemKey = new NamespacedKey(plugin, "parkour");
		this.file = new File(plugin.getDataFolder(), "parkour.yml");
		this.times = YamlConfiguration.loadConfiguration(file);
		try (var in = plugin.getResource("parkour.json")) {
			if (in != null) {
				JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
				start = arr(o.getAsJsonArray("start"));
				finish = arr(o.getAsJsonArray("finish"));
				if (o.has("entrance") && o.get("entrance").isJsonArray()) entrance = arr(o.getAsJsonArray("entrance"));
				for (var e : o.getAsJsonArray("checkpoints")) checkpoints.add(arr(e.getAsJsonArray()));
			}
		} catch (Exception e) {
			plugin.getLogger().warning("No parkour course: " + e);
		}
		Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
	}

	private static int[] arr(JsonArray a) {
		return new int[] {a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()};
	}

	boolean running(Player p) {
		return runs.containsKey(p.getUniqueId());
	}

	/** World location of a course point (plate position), or null if the hub isn't built. */
	Location at(int[] c) {
		Location o = build.origin();
		return o == null || c == null ? null : o.clone().add(c[0], c[1], c[2]);
	}

	Location startHologram() {
		Location l = at(start);
		return l == null ? null : l.add(0.5, 2.6, 0.5);
	}

	/** Leaderboard: under the entrance arch (falls back to the start pad). */
	Location boardHologram() {
		Location l = at(entrance != null ? entrance : start);
		return l == null ? null : l.add(0.5, entrance != null ? 3.2 : 4.2, 0.5);
	}

	Location finishHologram() {
		Location l = at(finish);
		return l == null ? null : l.add(0.5, 4.5, 0.5);
	}

	private boolean is(Block b, int[] c) {
		Location l = at(c);
		return l != null && b.getX() == l.getBlockX() && b.getY() == l.getBlockY() && b.getZ() == l.getBlockZ();
	}

	// ---------------------------------------------------------------- plates

	@EventHandler(priority = EventPriority.LOWEST)
	public void onPlate(PlayerInteractEvent e) {
		if (e.getAction() != Action.PHYSICAL || e.getClickedBlock() == null || start == null) return;
		Block b = e.getClickedBlock();
		Material t = b.getType();
		if (t != Material.LIGHT_WEIGHTED_PRESSURE_PLATE && t != Material.POLISHED_BLACKSTONE_PRESSURE_PLATE) return;
		Player p = e.getPlayer();
		e.setCancelled(true);
		if (is(b, start)) {
			begin(p);
		} else if (is(b, finish)) {
			end(p);
		} else {
			for (int i = 0; i < checkpoints.size(); i++) {
				if (is(b, checkpoints.get(i))) {
					checkpoint(p, i);
					break;
				}
			}
		}
	}

	private void begin(Player p) {
		Run r = runs.get(p.getUniqueId());
		if (r != null && r.checkpoint < 0 && System.currentTimeMillis() - r.start < 1500) return; // still on the pad
		r = new Run();
		r.start = System.currentTimeMillis();
		runs.put(p.getUniqueId(), r);
		p.setAllowFlight(false);
		p.setFlying(false);
		p.getInventory().setItem(7, item("checkpoint", Material.FEATHER, "<#38E0FF><bold>Last checkpoint</bold> <gray>(Right Click)"));
		p.getInventory().setItem(8, item("quit", Material.RED_BED, "<#FF4D6D><bold>Quit parkour</bold> <gray>(Right Click)"));
		p.showTitle(Title.title(LagCore.mm("<gradient:#FF5C5C:#FFA19A><bold>PARKOUR</bold></gradient>"), LagCore.mm("<gray>Go! <dark_gray>• <gray>" + (checkpoints.size()) + " checkpoints"),
				Title.Times.times(Duration.ZERO, Duration.ofMillis(1200), Duration.ofMillis(400))));
		p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.6f);
	}

	private void checkpoint(Player p, int i) {
		Run r = runs.get(p.getUniqueId());
		if (r == null) {
			p.sendActionBar(LagCore.mm("<gray>Step on the <gold>gold plate</gold> at the start to begin"));
			return;
		}
		if (i <= r.checkpoint) return;
		if (i > r.checkpoint + 1) {
			p.sendActionBar(LagCore.mm("<#FF4D6D>You skipped checkpoint " + (r.checkpoint + 2)));
			return;
		}
		r.checkpoint = i;
		p.sendMessage(LagCore.mm("<dark_gray>[<gradient:#FF5C5C:#FFA19A>Parkour</gradient><dark_gray>] <gray>Checkpoint <white>" + (i + 1) + "/" + checkpoints.size()
				+ " <dark_gray>• <white>" + fmt(System.currentTimeMillis() - r.start)));
		p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f + i * 0.1f);
		p.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, p.getLocation().add(0, 1, 0), 30, 0.5, 0.6, 0.5,
				new Particle.DustTransition(HubFx.CYAN, HubFx.PURPLE, 1.2f));
	}

	private void end(Player p) {
		Run r = runs.get(p.getUniqueId());
		if (r == null) return;
		if (r.checkpoint < checkpoints.size() - 1) {
			p.sendActionBar(LagCore.mm("<#FF4D6D>Hit every checkpoint first"));
			return;
		}
		long ms = System.currentTimeMillis() - r.start;
		stop(p);
		String key = "times." + p.getUniqueId();
		long best = times.getLong(key + ".ms", Long.MAX_VALUE);
		boolean pb = ms < best;
		long record = recordMs();
		if (pb) {
			times.set(key + ".ms", ms);
			times.set(key + ".name", p.getName());
			save();
		}
		p.showTitle(Title.title(LagCore.mm("<gradient:#FF5C5C:#FFA19A><bold>" + fmt(ms) + "</bold></gradient>"),
				LagCore.mm(pb ? "<gold><bold>NEW PERSONAL BEST!" : "<gray>Best: <white>" + fmt(best)),
				Title.Times.times(Duration.ZERO, Duration.ofSeconds(3), Duration.ofMillis(600))));
		p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
		p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), 60, 0.6, 0.8, 0.6, 0.3);
		if (pb && ms < record) {
			Bukkit.broadcast(LagCore.mm("<dark_gray>[<gradient:#FF5C5C:#FFA19A>Parkour</gradient><dark_gray>] <white>" + p.getName()
					+ " <gray>set a new <gold><bold>RECORD</bold></gold><gray>: <white>" + fmt(ms)));
		}
		build.holograms();
	}

	void stop(Player p) {
		if (runs.remove(p.getUniqueId()) == null) return;
		fx.giveHotbar(p);
		p.getInventory().setItem(7, null);
		if (plugin.getConfig().getBoolean("hub.double-jump", true)) p.setAllowFlight(true);
	}

	// ---------------------------------------------------------------- falling, items, flight

	@EventHandler(priority = EventPriority.LOW)
	public void onMove(PlayerMoveEvent e) {
		Run r = runs.get(e.getPlayer().getUniqueId());
		if (r == null) return;
		int[] cp = r.checkpoint < 0 ? start : checkpoints.get(r.checkpoint);
		Location back = at(cp);
		if (back == null) return;
		if (e.getTo().getY() < back.getY() - 8) back(e.getPlayer(), r);
	}

	private void back(Player p, Run r) {
		int[] cp = r.checkpoint < 0 ? start : checkpoints.get(r.checkpoint);
		Location l = at(cp);
		if (l == null) return;
		Location to = l.add(0.5, 0.1, 0.5);
		to.setYaw(p.getLocation().getYaw());
		p.teleport(to);
		p.setFallDistance(0);
		p.playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.4f);
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onItem(PlayerInteractEvent e) {
		if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
		ItemStack it = e.getItem();
		if (it == null || !it.hasItemMeta()) return;
		String id = it.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
		if (id == null) return;
		e.setCancelled(true);
		Player p = e.getPlayer();
		Run r = runs.get(p.getUniqueId());
		if ("checkpoint".equals(id) && r != null) back(p, r);
		if ("quit".equals(id)) {
			stop(p);
			p.teleport(plugin.spawn());
			p.sendActionBar(LagCore.mm("<gray>Parkour cancelled"));
		}
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onFly(PlayerToggleFlightEvent e) {
		if (running(e.getPlayer()) && e.getPlayer().getGameMode() != org.bukkit.GameMode.CREATIVE) {
			e.setCancelled(true);
			e.getPlayer().setAllowFlight(false);
		}
	}

	@EventHandler
	public void onTeleport(PlayerTeleportEvent e) {
		if (e.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN || e.getCause() == PlayerTeleportEvent.TeleportCause.UNKNOWN) return;
		if (running(e.getPlayer())) stop(e.getPlayer());
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent e) {
		runs.remove(e.getPlayer().getUniqueId());
	}

	private ItemStack item(String id, Material m, String name) {
		ItemStack it = fx.hubItem("pk-" + id, m, name);
		var meta = it.getItemMeta();
		meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, id);
		it.setItemMeta(meta);
		return it;
	}

	// ---------------------------------------------------------------- timer + leaderboard

	private void tick() {
		long now = System.currentTimeMillis();
		for (var en : runs.entrySet()) {
			Player p = Bukkit.getPlayer(en.getKey());
			if (p == null) continue;
			Run r = en.getValue();
			p.sendActionBar(LagCore.mm("<gradient:#FF5C5C:#FFA19A><bold>PARKOUR</bold></gradient> <dark_gray>• <white>" + fmt(now - r.start)
					+ " <dark_gray>• <gray>Checkpoint <white>" + (r.checkpoint + 1) + "/" + checkpoints.size()));
		}
	}

	static String fmt(long ms) {
		if (ms == Long.MAX_VALUE) return "--";
		return String.format(java.util.Locale.ROOT, "%d:%02d.%02d", ms / 60000, (ms / 1000) % 60, (ms / 10) % 100);
	}

	private long recordMs() {
		long best = Long.MAX_VALUE;
		var sec = times.getConfigurationSection("times");
		if (sec != null) for (String k : sec.getKeys(false)) best = Math.min(best, sec.getLong(k + ".ms", Long.MAX_VALUE));
		return best;
	}

	/** Top 5 for the start hologram. */
	String top() {
		List<long[]> list = new ArrayList<>();
		List<String> names = new ArrayList<>();
		var sec = times.getConfigurationSection("times");
		if (sec != null) {
			for (String k : sec.getKeys(false)) {
				list.add(new long[] {sec.getLong(k + ".ms"), names.size()});
				names.add(sec.getString(k + ".name", "?"));
			}
		}
		list.sort((a, b) -> Long.compare(a[0], b[0]));
		StringBuilder sb = new StringBuilder();
		String[] medal = {"<gold>", "<#C0C0C0>", "<#CD7F32>", "<gray>", "<gray>"};
		for (int i = 0; i < 5; i++) {
			sb.append("\n");
			if (i < list.size()) {
				sb.append(medal[i]).append("#").append(i + 1).append(" <white>").append(names.get((int) list.get(i)[1]))
						.append(" <dark_gray>- <#38E0FF>").append(fmt(list.get(i)[0]));
			} else {
				sb.append("<dark_gray>#").append(i + 1).append(" ---");
			}
		}
		return sb.toString();
	}

	private void save() {
		try {
			times.save(file);
		} catch (Exception e) {
			plugin.getLogger().warning("Couldn't save parkour times: " + e);
		}
	}
}
