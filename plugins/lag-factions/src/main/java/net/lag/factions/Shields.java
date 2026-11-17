package net.lag.factions;

import java.io.File;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import dev.kitteh.factions.Faction;
import dev.kitteh.factions.permissible.Role;
import net.kyori.adventure.title.Title;

/**
 * Raid shields. Every faction is shielded 18h a day and raidable for one 6-hour window that starts at an hour it picks
 * (Eastern time). While shielded, explosions break nothing in its claims and falling sand can't land there.
 * Moving the window is allowed any time but only takes effect 24h later, so nobody can dodge a raid they see coming.
 */
final class Shields implements Listener {
	static final int WINDOW = 6;
	private final LagFactions plugin;
	private final File file;
	/** faction id -> window start hour (0-23, Eastern) */
	private final Map<Integer, Integer> start = new HashMap<>();
	/** faction id -> pending start hour + when it takes effect (epoch ms) */
	private final Map<Integer, int[]> pendingHour = new HashMap<>();
	private final Map<Integer, Long> pendingAt = new HashMap<>();
	private final Map<Integer, Boolean> lastOpen = new HashMap<>();
	private final Set<Integer> warned = new HashSet<>();
	private final Map<UUID, Integer> lastSeenFaction = new HashMap<>();

	Shields(LagFactions plugin) {
		this.plugin = plugin;
		this.file = new File(plugin.getDataFolder(), "shields.yml");
		load();
		Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 100L, 200L); // every 10s
	}

	// ---------------------------------------------------------------- data

	private void load() {
		if (!file.exists()) return;
		YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
		var sec = y.getConfigurationSection("factions");
		if (sec == null) return;
		for (String k : sec.getKeys(false)) {
			try {
				int id = Integer.parseInt(k);
				start.put(id, sec.getInt(k + ".start"));
				if (sec.contains(k + ".pending")) {
					pendingHour.put(id, new int[] {sec.getInt(k + ".pending")});
					pendingAt.put(id, sec.getLong(k + ".pending-at"));
				}
			} catch (NumberFormatException ignored) { }
		}
	}

	private void save() {
		YamlConfiguration y = new YamlConfiguration();
		for (var e : start.entrySet()) y.set("factions." + e.getKey() + ".start", e.getValue());
		for (var e : pendingHour.entrySet()) {
			y.set("factions." + e.getKey() + ".pending", e.getValue()[0]);
			y.set("factions." + e.getKey() + ".pending-at", pendingAt.get(e.getKey()));
		}
		try {
			plugin.getDataFolder().mkdirs();
			y.save(file);
		} catch (IOException ex) {
			plugin.getLogger().warning("Couldn't save shields.yml: " + ex.getMessage());
		}
	}

	private int defaultStart() {
		return plugin.getConfig().getInt("shield.default-start", 20);
	}

	/** Window start hour in effect now (applies a pending change once it's due). */
	int startHour(Faction f) {
		int id = f.id();
		Long at = pendingAt.get(id);
		if (at != null && System.currentTimeMillis() >= at) {
			start.put(id, pendingHour.remove(id)[0]);
			pendingAt.remove(id);
			save();
		}
		return start.getOrDefault(id, defaultStart());
	}

	Integer pending(Faction f) {
		startHour(f);
		int[] p = pendingHour.get(f.id());
		return p == null ? null : p[0];
	}

	long pendingIn(Faction f) {
		Long at = pendingAt.get(f.id());
		return at == null ? 0 : at - System.currentTimeMillis();
	}

	boolean enabled() {
		return plugin.getConfig().getBoolean("shield.enabled", true);
	}

	/** Raidable right now? (inside its 6h window, Eastern time) */
	boolean raidable(Faction f) {
		if (!enabled()) return true;
		int s = startHour(f);
		int h = ZonedDateTime.now(Ui.EST).getHour();
		return ((h - s + 24) % 24) < WINDOW;
	}

	boolean shielded(Faction f) {
		return f != null && f.isNormal() && !raidable(f);
	}

	/** ms until the state flips (window opens if shielded, closes if raidable). */
	long untilFlip(Faction f) {
		int s = startHour(f);
		ZonedDateTime now = ZonedDateTime.now(Ui.EST);
		ZonedDateTime open = now.withHour(s).withMinute(0).withSecond(0).withNano(0);
		if (raidable(f)) {
			if (open.isAfter(now)) open = open.minusDays(1);
			return java.time.Duration.between(now, open.plusHours(WINDOW)).toMillis();
		}
		if (!open.isAfter(now)) open = open.plusDays(1);
		return java.time.Duration.between(now, open).toMillis();
	}

	String windowText(int s) {
		return Ui.hour(s) + "–" + Ui.hour(s + WINDOW) + " EST";
	}

	/** One-line status, MiniMessage. */
	String status(Faction f) {
		if (f == null || !f.isNormal()) return "";
		if (!enabled()) return Ui.RED + "Shields off";
		return raidable(f)
				? Ui.RED + "<bold>RAIDABLE</bold> " + Ui.LABEL + "for " + "<white>" + Ui.duration(untilFlip(f))
				: Ui.GREEN + "<bold>SHIELDED</bold> " + Ui.LABEL + "raidable in " + "<white>" + Ui.duration(untilFlip(f));
	}

	// ---------------------------------------------------------------- changing the window

	void choose(Player p, int hour) {
		Faction f = F.of(p);
		if (f == null) { Ui.err(p, "You need a faction."); return; }
		if (!F.atLeast(p, Role.COLEADER)) { Ui.err(p, "Only the leader and co-leaders can move the raid window."); return; }
		int cur = startHour(f);
		long delay = plugin.getConfig().getLong("shield.change-delay-hours", 24) * 3_600_000L;
		if (hour == cur && pending(f) == null) { Ui.err(p, "That's already your raid window."); return; }
		if (hour == cur) {
			pendingHour.remove(f.id());
			pendingAt.remove(f.id());
			save();
			Ui.ok(p, "Cancelled the window change. Staying on <white>" + windowText(cur) + "</white>.");
		} else {
			pendingHour.put(f.id(), new int[] {hour});
			pendingAt.put(f.id(), System.currentTimeMillis() + delay);
			save();
			notifyFaction(f, Ui.LABEL + "<white>" + Ui.esc(p.getName()) + "</white> moved the raid window to <white>"
					+ windowText(hour) + "</white>. It takes effect in <white>" + Ui.duration(delay) + "</white>.");
		}
	}

	private void notifyFaction(Faction f, String mm) {
		for (Player o : f.membersOnlineAsPlayers()) Ui.msg(o, mm);
	}

	// ---------------------------------------------------------------- GUI

	void open(Player p) {
		Faction f = F.of(p);
		if (f == null) { Ui.err(p, "You need a faction. <white>/f create <name>"); return; }
		int cur = startHour(f);
		Integer pend = pending(f);
		boolean canEdit = F.atLeast(p, Role.COLEADER);
		Gui g = new Gui(6, Ui.GRAD + "<bold>RAID SHIELD</bold></gradient> <dark_gray>• " + Ui.esc(f.tag()));
		g.frame();
		boolean raid = raidable(f);
		g.set(4, Ui.item(raid ? Material.TNT : Material.SHIELD, true,
				raid ? Ui.RED + "<bold>RAIDABLE NOW" : Ui.GREEN + "<bold>SHIELDED",
				"",
				Ui.LABEL + "Raid window <white>" + windowText(cur),
				raid ? Ui.LABEL + "Shield returns in <white>" + Ui.duration(untilFlip(f))
						: Ui.LABEL + "Window opens in <white>" + Ui.duration(untilFlip(f)),
				pend == null ? "" : Ui.GOLD + "Moving to " + windowText(pend) + " in " + Ui.duration(pendingIn(f)),
				"",
				Ui.DIM + "Shielded 18h a day: explosions and",
				Ui.DIM + "sand do nothing in your claims."));
		// 24 hours: rows 2-4 (slots 10-16, 19-25, 28-34) + 37-39 = 24
		int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 39, 40, 41};
		for (int h = 0; h < 24; h++) {
			final int hour = h;
			boolean isCur = h == cur, isPend = pend != null && h == pend;
			Material m = isCur ? Material.LIME_STAINED_GLASS : isPend ? Material.YELLOW_STAINED_GLASS
					: (h >= 18 || h < 2) ? Material.PURPLE_STAINED_GLASS : Material.GRAY_STAINED_GLASS;
			String name = (isCur ? Ui.GREEN : isPend ? Ui.GOLD : "<white>") + "<bold>" + Ui.hour(h) + "</bold>"
					+ Ui.LABEL + " → " + Ui.hour(h + WINDOW) + " EST";
			String l1 = isCur ? Ui.GREEN + "Current raid window" : isPend ? Ui.GOLD + "Takes effect in " + Ui.duration(pendingIn(f)) : "";
			String l2 = !canEdit ? Ui.DIM + "Leader / co-leader only" : isCur ? (pend != null ? Ui.CYAN + "Click to cancel the change" : "")
					: Ui.CYAN + "Click to move here " + Ui.DIM + "(24h delay)";
			g.set(slots[h], Ui.item(m, isCur, name, l1, l2), c -> {
				if (!canEdit) return;
				choose(c.player(), hour);
				open(c.player());
			});
		}
		g.set(49, Ui.item(Material.ARROW, Ui.LABEL + "Back to /f menu"), c -> plugin.menu.open(c.player()));
		g.open(p);
	}

	// ---------------------------------------------------------------- protection

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onExplode(EntityExplodeEvent e) {
		if (enabled()) e.blockList().removeIf(this::protectedBlock);
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onExplode(BlockExplodeEvent e) {
		if (enabled()) e.blockList().removeIf(this::protectedBlock);
	}

	private final Map<Long, Boolean> tickCache = new HashMap<>();
	private long cacheTick = -1;

	private boolean protectedBlock(Block b) {
		long t = Bukkit.getCurrentTick();
		if (t != cacheTick) { tickCache.clear(); cacheTick = t; }
		long key = ((long) (b.getX() >> 4) << 32) ^ ((b.getZ() >> 4) & 0xFFFFFFFFL) ^ ((long) b.getWorld().hashCode() << 16);
		return tickCache.computeIfAbsent(key, k -> shielded(F.at(b)));
	}

	/** Sand (any falling block) can't land inside a shielded faction's claim. */
	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onLand(EntityChangeBlockEvent e) {
		if (!enabled() || !(e.getEntity() instanceof FallingBlock fb) || e.getTo() == Material.AIR) return;
		if (protectedBlock(e.getBlock())) {
			e.setCancelled(true);
			fb.remove();
		}
	}

	/** Entering another faction's land shows its shield state on the action bar. */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onMove(PlayerMoveEvent e) {
		if (!enabled() || e.getTo() == null) return;
		if ((e.getFrom().getBlockX() >> 4) == (e.getTo().getBlockX() >> 4) && (e.getFrom().getBlockZ() >> 4) == (e.getTo().getBlockZ() >> 4)) return;
		Faction f = F.at(e.getTo());
		int id = f != null && f.isNormal() ? f.id() : Integer.MIN_VALUE;
		Integer last = lastSeenFaction.put(e.getPlayer().getUniqueId(), id);
		if (last != null && last == id) return;
		if (id == Integer.MIN_VALUE) return;
		e.getPlayer().sendActionBar(Ui.mm("<white><bold>" + Ui.esc(f.tag()) + "</bold> <dark_gray>• " + status(f)));
	}

	// ---------------------------------------------------------------- alerts

	private void tick() {
		if (!enabled()) return;
		for (Faction f : dev.kitteh.factions.Factions.factions().all()) {
			if (!f.isNormal()) continue;
			boolean open = raidable(f);
			Boolean was = lastOpen.put(f.id(), open);
			long flip = untilFlip(f);
			if (!open && flip <= 10 * 60_000L && warned.add(f.id())) {
				for (Player o : f.membersOnlineAsPlayers()) {
					Ui.msg(o, Ui.GOLD + "Your shield drops in <white>" + Ui.duration(flip) + "</white>. Raid window: <white>" + windowText(startHour(f)));
					o.playSound(o.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 0.6f);
				}
			}
			if (was == null || was == open) continue;
			if (open) {
				warned.remove(f.id());
				for (Player o : f.membersOnlineAsPlayers()) {
					o.showTitle(Title.title(Ui.mm(Ui.RED + "<bold>SHIELD DOWN"), Ui.mm(Ui.LABEL + "Raidable until <white>" + Ui.hour(startHour(f) + WINDOW) + " EST")));
					o.playSound(o.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.5f, 1.2f);
				}
				if (plugin.getConfig().getBoolean("shield.announce", true) && f.claimCount() > 0)
					Bukkit.broadcast(Ui.mm(Ui.PREFIX + "<white><bold>" + Ui.esc(f.tag()) + "</bold></white> " + Ui.LABEL + "is now " + Ui.RED + "<bold>RAIDABLE</bold>"
							+ Ui.LABEL + " until <white>" + Ui.hour(startHour(f) + WINDOW) + " EST"));
			} else {
				for (Player o : f.membersOnlineAsPlayers()) {
					o.showTitle(Title.title(Ui.mm(Ui.GREEN + "<bold>SHIELD UP"), Ui.mm(Ui.LABEL + "You're safe until <white>" + Ui.hour(startHour(f)) + " EST")));
					o.playSound(o.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 1.2f);
				}
			}
		}
	}
}
