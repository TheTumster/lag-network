package net.lag.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * RelapsedMC Teams: a crew that works on every realm, separate from factions.
 *
 * Every server's laGCore shares one SQLite file (../shared/teams.db), so a team made on the hub is the same team on
 * Factions, Skyblock, SMP and Cannons. Team chat and notices go through a table every server polls, so /tc reaches
 * teammates on other realms too.
 *
 * Commands (work without the client): /team create|invite|accept|deny|leave|kick|disband|info, /tc <message>
 * RelapsedMC Client extras (plugin channels, format must match the client's TeamLink.java):
 *   S2C relapsed:team    team state: utf name, utf leader, bool youLead, varint n x (utf name, bool online, utf realm),
 *                        varint n x (utf team, utf by)  [invites],  varint n x (varint id, utf name, utf by, varint size, long time) [schems]
 *   S2C relapsed:tmates  teammates on this server: varint n x (utf name, utf dim, double x, y, z)
 *   S2C relapsed:ping    utf from, utf kind, utf dim, double x, y, z
 *   S2C relapsed:schem_data  varint id, utf name, varint total, varint index, varint len, bytes
 *   C2S relapsed:schem_up    utf name, varint total, varint index, varint len, bytes
 *   C2S relapsed:schem_get   varint id
 *   C2S relapsed:schem_del   varint id
 */
final class Teams implements TabExecutor, Listener, PluginMessageListener {
	static final String C_TEAM = "relapsed:team", C_MATES = "relapsed:tmates", C_PING = "relapsed:ping",
			C_DATA = "relapsed:schem_data", C_UP = "relapsed:schem_up", C_GET = "relapsed:schem_get", C_DEL = "relapsed:schem_del";
	private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
	private static final int CHUNK = 30_000;
	private static final int MAX_SCHEM = 4 * 1024 * 1024;

	private final LagCore plugin;
	private final String realm;
	private final int maxSize;
	private final File sharedDir;
	private Connection db;

	/** Online players on THIS server -> team id (0 = none). Refreshed from the database every few seconds. */
	private final Map<UUID, Integer> teamOf = new ConcurrentHashMap<>();
	/** Latest state packet per player (built async, sent on the main thread). */
	private final Map<UUID, byte[]> statePackets = new ConcurrentHashMap<>();
	private volatile long lastChatId = 0;
	private final Map<UUID, Upload> uploads = new HashMap<>();
	private int ticks = 0;

	private static final class Upload {
		String name;
		byte[][] parts;
		int got;
	}

	Teams(LagCore plugin) {
		this.plugin = plugin;
		this.realm = plugin.getConfig().getString("role", "hub");
		this.maxSize = plugin.getConfig().getInt("teams.max-size", 8);
		this.sharedDir = new File(plugin.getConfig().getString("teams.shared-dir", "../shared"));
	}

	boolean start() {
		try {
			sharedDir.mkdirs();
			Class.forName("org.sqlite.JDBC");
			db = DriverManager.getConnection("jdbc:sqlite:" + new File(sharedDir, "teams.db").getAbsolutePath());
			try (Statement s = db.createStatement()) {
				s.execute("PRAGMA journal_mode=WAL");
				s.execute("PRAGMA busy_timeout=3000");
				s.execute("CREATE TABLE IF NOT EXISTS teams(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE COLLATE NOCASE, leader TEXT, created INTEGER)");
				s.execute("CREATE TABLE IF NOT EXISTS members(uuid TEXT PRIMARY KEY, team INTEGER, name TEXT, joined INTEGER)");
				s.execute("CREATE TABLE IF NOT EXISTS invites(team INTEGER, name TEXT COLLATE NOCASE, by TEXT, ts INTEGER, PRIMARY KEY(team, name))");
				s.execute("CREATE TABLE IF NOT EXISTS presence(uuid TEXT PRIMARY KEY, name TEXT, realm TEXT, ts INTEGER)");
				s.execute("CREATE TABLE IF NOT EXISTS chat(id INTEGER PRIMARY KEY AUTOINCREMENT, team INTEGER, sender TEXT, msg TEXT, ts INTEGER)");
				s.execute("CREATE TABLE IF NOT EXISTS schems(id INTEGER PRIMARY KEY AUTOINCREMENT, team INTEGER, name TEXT, by TEXT, size INTEGER, ts INTEGER, file TEXT)");
				try (ResultSet r = s.executeQuery("SELECT COALESCE(MAX(id),0) FROM chat")) {
					lastChatId = r.next() ? r.getLong(1) : 0;
				}
			}
		} catch (Exception e) {
			plugin.getLogger().warning("Teams off: couldn't open ../shared/teams.db (" + e + ")");
			return false;
		}
		var m = Bukkit.getMessenger();
		for (String c : new String[] {C_TEAM, C_MATES, C_PING, C_DATA}) m.registerOutgoingPluginChannel(plugin, c);
		for (String c : new String[] {C_UP, C_GET, C_DEL}) m.registerIncomingPluginChannel(plugin, c, this);
		for (String cmd : new String[] {"team", "tc"}) {
			var pc = plugin.getCommand(cmd);
			if (pc != null) {
				pc.setExecutor(this);
				pc.setTabCompleter(this);
			}
		}
		Bukkit.getPluginManager().registerEvents(this, plugin);
		Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 10L);
		for (Player p : Bukkit.getOnlinePlayers()) refreshAsync(p);
		plugin.getLogger().info("Teams on (shared: " + sharedDir.getAbsolutePath() + ")");
		return true;
	}

	void stop() {
		try {
			if (db != null) db.close();
		} catch (SQLException ignored) { }
	}

	// ================================================================ loop

	private void tick() {
		ticks++;
		// teammates on this server, twice a second
		for (Player p : Bukkit.getOnlinePlayers()) {
			int t = teamOf.getOrDefault(p.getUniqueId(), 0);
			if (!p.getListeningPluginChannels().contains(C_MATES)) continue;
			List<Player> mates = new ArrayList<>();
			if (t != 0) {
				for (Player o : Bukkit.getOnlinePlayers()) {
					if (o != p && teamOf.getOrDefault(o.getUniqueId(), 0) == t && p.canSee(o)) mates.add(o);
				}
			}
			Buf b = new Buf();
			b.varInt(mates.size());
			for (Player o : mates) {
				Location l = o.getLocation();
				b.utf(o.getName());
				b.utf(l.getWorld().getKey().toString());
				b.dbl(l.getX());
				b.dbl(l.getY());
				b.dbl(l.getZ());
			}
			p.sendPluginMessage(plugin, C_MATES, b.bytes());
		}
		// team chat / notices from every realm, twice a second
		Bukkit.getScheduler().runTaskAsynchronously(plugin, this::pollChat);
		// presence + membership + client state every 5 seconds
		if (ticks % 10 == 0) {
			List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
			Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
				for (Player p : online) refresh(p);
			});
		}
		for (Player p : Bukkit.getOnlinePlayers()) {
			byte[] pkt = statePackets.remove(p.getUniqueId());
			if (pkt != null && p.getListeningPluginChannels().contains(C_TEAM)) p.sendPluginMessage(plugin, C_TEAM, pkt);
		}
	}

	private void refreshAsync(Player p) {
		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> refresh(p));
	}

	/** (async) presence row, team id, and the client's state packet. */
	private synchronized void refresh(Player p) {
		try {
			UUID id = p.getUniqueId();
			try (PreparedStatement s = db.prepareStatement("INSERT INTO presence(uuid,name,realm,ts) VALUES(?,?,?,?) "
					+ "ON CONFLICT(uuid) DO UPDATE SET name=excluded.name, realm=excluded.realm, ts=excluded.ts")) {
				s.setString(1, id.toString());
				s.setString(2, p.getName());
				s.setString(3, realm);
				s.setLong(4, System.currentTimeMillis());
				s.executeUpdate();
			}
			// keep the stored name current (renames)
			try (PreparedStatement s = db.prepareStatement("UPDATE members SET name=? WHERE uuid=?")) {
				s.setString(1, p.getName());
				s.setString(2, id.toString());
				s.executeUpdate();
			}
			int team = teamIdOf(id);
			teamOf.put(id, team);
			statePackets.put(id, statePacket(p, team));
		} catch (Exception e) {
			plugin.getLogger().fine("team refresh: " + e);
		}
	}

	private synchronized void pollChat() {
		try (PreparedStatement s = db.prepareStatement("SELECT id, team, sender, msg FROM chat WHERE id > ? ORDER BY id")) {
			s.setLong(1, lastChatId);
			List<Object[]> rows = new ArrayList<>();
			try (ResultSet r = s.executeQuery()) {
				while (r.next()) rows.add(new Object[] {r.getLong(1), r.getInt(2), r.getString(3), r.getString(4)});
			}
			if (rows.isEmpty()) return;
			lastChatId = (Long) rows.get(rows.size() - 1)[0];
			Bukkit.getScheduler().runTask(plugin, () -> {
				for (Object[] row : rows) deliver((Integer) row[1], (String) row[2], (String) row[3]);
			});
		} catch (Exception ignored) { }
	}

	private void deliver(int team, String sender, String msg) {
		var c = sender == null || sender.isEmpty()
				? LagCore.mm("<#FF4DD2>[Team]</#FF4DD2> <gray>" + LagCore.esc(msg))
				: LagCore.mm("<#FF4DD2>[Team]</#FF4DD2> <white>" + LagCore.esc(sender) + "<dark_gray>: <#FFD6F2>" + LagCore.esc(msg));
		for (Player p : Bukkit.getOnlinePlayers()) {
			if (teamOf.getOrDefault(p.getUniqueId(), 0) == team) {
				p.sendMessage(c);
				refreshAsync(p); // membership or schem list may have changed
			}
		}
	}

	// ================================================================ events

	@EventHandler
	public void onJoin(PlayerJoinEvent e) {
		refreshAsync(e.getPlayer());
		Player p = e.getPlayer();
		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			List<String> inv = invitesFor(p.getName());
			if (!inv.isEmpty()) Bukkit.getScheduler().runTask(plugin, () -> p.sendMessage(LagCore.mm(
					"<#FF4DD2>[Team]</#FF4DD2> <gray>You're invited to <white>" + LagCore.esc(String.join(", ", inv))
							+ "</white>. <white>/team accept</white> to join.")));
		});
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent e) {
		UUID id = e.getPlayer().getUniqueId();
		teamOf.remove(id);
		uploads.remove(id);
		// show as offline right away (unless they're just switching realms: the next server marks them online again)
		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			synchronized (this) {
				try (PreparedStatement s = db.prepareStatement("UPDATE presence SET ts=0 WHERE uuid=? AND realm=?")) {
					s.setString(1, id.toString());
					s.setString(2, realm);
					s.executeUpdate();
				} catch (SQLException ignored) { }
			}
		});
	}

	// ================================================================ commands

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!(sender instanceof Player p)) {
			sender.sendMessage("Players only.");
			return true;
		}
		if (command.getName().equalsIgnoreCase("tc")) {
			if (args.length == 0) {
				say(p, "<gray>/tc <message> <dark_gray>- talk to your team on every realm");
				return true;
			}
			String msg = String.join(" ", args);
			async(p, () -> {
				int t = teamIdOf(p.getUniqueId());
				if (t == 0) return "<red>You're not in a team. <gray>/team create <name>";
				post(t, p.getName(), msg.length() > 256 ? msg.substring(0, 256) : msg);
				return null;
			});
			return true;
		}
		String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
		String arg = args.length > 1 ? args[1] : null;
		switch (sub) {
			case "create" -> async(p, () -> create(p, arg));
			case "invite", "add" -> async(p, () -> invite(p, arg));
			case "accept", "join" -> async(p, () -> accept(p, arg));
			case "deny", "decline" -> async(p, () -> deny(p, arg));
			case "leave", "quit" -> async(p, () -> leave(p));
			case "kick", "remove" -> async(p, () -> kick(p, arg));
			case "disband", "delete" -> async(p, () -> disband(p));
			case "ping" -> ping(p, args);
			case "info", "list", "who" -> async(p, () -> info(p));
			default -> say(p, "<gray>/team create <name> <dark_gray>| <gray>invite <player> <dark_gray>| <gray>accept <dark_gray>| "
					+ "<gray>deny <dark_gray>| <gray>leave <dark_gray>| <gray>kick <player> <dark_gray>| <gray>disband <dark_gray>| <gray>/tc <msg>");
		}
		return true;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
		if (command.getName().equalsIgnoreCase("tc")) return List.of();
		if (args.length == 1) {
			List<String> out = new ArrayList<>();
			for (String s : List.of("create", "invite", "accept", "deny", "leave", "kick", "disband", "info")) {
				if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
			}
			return out;
		}
		if (args.length == 2 && (args[0].equalsIgnoreCase("invite") || args[0].equalsIgnoreCase("kick"))) {
			List<String> out = new ArrayList<>();
			for (Player o : Bukkit.getOnlinePlayers()) {
				if (o.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(o.getName());
			}
			return out;
		}
		return List.of();
	}

	/** Run a database action off the main thread; a returned string is shown to the player. */
	private void async(Player p, java.util.concurrent.Callable<String> work) {
		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			String reply;
			synchronized (this) {
				try {
					reply = work.call();
				} catch (Exception e) {
					reply = "<red>Something went wrong, try again.";
					plugin.getLogger().warning("team command failed: " + e);
				}
			}
			refresh(p);
			String r = reply;
			if (r != null) Bukkit.getScheduler().runTask(plugin, () -> say(p, r));
		});
	}

	private void say(Player p, String mini) {
		p.sendMessage(LagCore.mm("<#FF4DD2>[Team]</#FF4DD2> " + mini));
	}

	private String create(Player p, String name) throws SQLException {
		if (name == null || !NAME.matcher(name).matches()) return "<red>Team names: 3-16 letters, numbers or _";
		if (teamIdOf(p.getUniqueId()) != 0) return "<red>Leave your team first.";
		try (PreparedStatement s = db.prepareStatement("INSERT INTO teams(name,leader,created) VALUES(?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
			s.setString(1, name);
			s.setString(2, p.getUniqueId().toString());
			s.setLong(3, System.currentTimeMillis());
			s.executeUpdate();
			try (ResultSet k = s.getGeneratedKeys()) {
				k.next();
				addMember(k.getInt(1), p);
			}
		} catch (SQLException e) {
			if (String.valueOf(e.getMessage()).contains("UNIQUE")) return "<red>That name is taken.";
			throw e;
		}
		return "<green>Created <white>" + LagCore.esc(name) + "</white>. <gray>Invite friends with <white>/team invite <player>";
	}

	private String invite(Player p, String who) throws SQLException {
		int t = teamIdOf(p.getUniqueId());
		if (t == 0) return "<red>You're not in a team. <gray>/team create <name>";
		if (!isLeader(p.getUniqueId(), t)) return "<red>Only the team leader can invite.";
		if (who == null || !Pattern.matches("^\\w{2,16}$", who)) return "<red>/team invite <player>";
		if (memberCount(t) >= maxSize) return "<red>Your team is full (" + maxSize + ").";
		try (PreparedStatement s = db.prepareStatement("SELECT 1 FROM members WHERE team=? AND name=? COLLATE NOCASE")) {
			s.setInt(1, t);
			s.setString(2, who);
			try (ResultSet r = s.executeQuery()) {
				if (r.next()) return "<gray>" + LagCore.esc(who) + " is already on your team.";
			}
		}
		try (PreparedStatement s = db.prepareStatement("INSERT OR REPLACE INTO invites(team,name,by,ts) VALUES(?,?,?,?)")) {
			s.setInt(1, t);
			s.setString(2, who);
			s.setString(3, p.getName());
			s.setLong(4, System.currentTimeMillis());
			s.executeUpdate();
		}
		String team = teamName(t);
		Bukkit.getScheduler().runTask(plugin, () -> {
			Player o = Bukkit.getPlayerExact(who);
			if (o != null) {
				say(o, "<white>" + LagCore.esc(p.getName()) + "</white> invited you to <white>" + LagCore.esc(team)
						+ "</white>. <green><click:run_command:'/team accept " + LagCore.esc(team) + "'>[Accept]</click></green> "
						+ "<red><click:run_command:'/team deny " + LagCore.esc(team) + "'>[Deny]</click></red>");
				refreshAsync(o);
			}
		});
		post(t, "", p.getName() + " invited " + who + ".");
		return "<green>Invited <white>" + LagCore.esc(who) + "</white>. <gray>They can accept from any realm.";
	}

	private String accept(Player p, String teamName) throws SQLException {
		if (teamIdOf(p.getUniqueId()) != 0) return "<red>Leave your team first.";
		Integer t = inviteTeam(p.getName(), teamName);
		if (t == null) return "<red>No invite" + (teamName == null ? "" : " from " + LagCore.esc(teamName)) + ".";
		if (memberCount(t) >= maxSize) return "<red>That team is full.";
		addMember(t, p);
		try (PreparedStatement s = db.prepareStatement("DELETE FROM invites WHERE name=? COLLATE NOCASE")) {
			s.setString(1, p.getName());
			s.executeUpdate();
		}
		post(t, "", p.getName() + " joined the team.");
		return "<green>You joined <white>" + LagCore.esc(teamName(t)) + "</white>. <gray>Team chat: <white>/tc <msg>";
	}

	private String deny(Player p, String teamName) throws SQLException {
		Integer t = inviteTeam(p.getName(), teamName);
		if (t == null) return "<gray>No invite to deny.";
		try (PreparedStatement s = db.prepareStatement("DELETE FROM invites WHERE team=? AND name=? COLLATE NOCASE")) {
			s.setInt(1, t);
			s.setString(2, p.getName());
			s.executeUpdate();
		}
		return "<gray>Invite declined.";
	}

	private String leave(Player p) throws SQLException {
		int t = teamIdOf(p.getUniqueId());
		if (t == 0) return "<gray>You're not in a team.";
		if (isLeader(p.getUniqueId(), t)) {
			// hand it to the longest-serving member, or disband if alone
			String next = null;
			try (PreparedStatement s = db.prepareStatement("SELECT uuid FROM members WHERE team=? AND uuid<>? ORDER BY joined LIMIT 1")) {
				s.setInt(1, t);
				s.setString(2, p.getUniqueId().toString());
				try (ResultSet r = s.executeQuery()) {
					if (r.next()) next = r.getString(1);
				}
			}
			if (next == null) return disband(p);
			try (PreparedStatement s = db.prepareStatement("UPDATE teams SET leader=? WHERE id=?")) {
				s.setString(1, next);
				s.setInt(2, t);
				s.executeUpdate();
			}
		}
		removeMember(p.getUniqueId());
		post(t, "", p.getName() + " left the team.");
		return "<gray>You left the team.";
	}

	private String kick(Player p, String who) throws SQLException {
		int t = teamIdOf(p.getUniqueId());
		if (t == 0 || !isLeader(p.getUniqueId(), t)) return "<red>Only the team leader can kick.";
		if (who == null) return "<red>/team kick <player>";
		if (who.equalsIgnoreCase(p.getName())) return "<red>Use /team leave or /team disband.";
		int n;
		try (PreparedStatement s = db.prepareStatement("DELETE FROM members WHERE team=? AND name=? COLLATE NOCASE")) {
			s.setInt(1, t);
			s.setString(2, who);
			n = s.executeUpdate();
		}
		if (n == 0) {
			try (PreparedStatement s = db.prepareStatement("DELETE FROM invites WHERE team=? AND name=? COLLATE NOCASE")) {
				s.setInt(1, t);
				s.setString(2, who);
				return s.executeUpdate() > 0 ? "<gray>Invite for " + LagCore.esc(who) + " cancelled." : "<red>" + LagCore.esc(who) + " isn't on your team.";
			}
		}
		post(t, "", who + " was removed from the team.");
		Bukkit.getScheduler().runTask(plugin, () -> {
			Player o = Bukkit.getPlayerExact(who);
			if (o != null) {
				say(o, "<gray>You were removed from the team.");
				refreshAsync(o);
			}
		});
		return null;
	}

	private String disband(Player p) throws SQLException {
		int t = teamIdOf(p.getUniqueId());
		if (t == 0) return "<gray>You're not in a team.";
		if (!isLeader(p.getUniqueId(), t)) return "<red>Only the team leader can disband.";
		post(t, "", "The team was disbanded.");
		for (String sql : new String[] {"DELETE FROM members WHERE team=?", "DELETE FROM invites WHERE team=?", "DELETE FROM teams WHERE id=?"}) {
			try (PreparedStatement s = db.prepareStatement(sql)) {
				s.setInt(1, t);
				s.executeUpdate();
			}
		}
		// schematics go with it
		List<String> files = new ArrayList<>();
		try (PreparedStatement s = db.prepareStatement("SELECT file FROM schems WHERE team=?")) {
			s.setInt(1, t);
			try (ResultSet r = s.executeQuery()) {
				while (r.next()) files.add(r.getString(1));
			}
		}
		for (String f : files) new File(f).delete();
		try (PreparedStatement s = db.prepareStatement("DELETE FROM schems WHERE team=?")) {
			s.setInt(1, t);
			s.executeUpdate();
		}
		return "<gray>Team disbanded.";
	}

	private String info(Player p) throws SQLException {
		int t = teamIdOf(p.getUniqueId());
		if (t == 0) {
			List<String> inv = invitesFor(p.getName());
			return inv.isEmpty() ? "<gray>No team yet. <white>/team create <name>"
					: "<gray>Invites: <white>" + LagCore.esc(String.join(", ", inv)) + "</white>. <white>/team accept <name>";
		}
		StringBuilder sb = new StringBuilder("<white>" + LagCore.esc(teamName(t)) + "<dark_gray>: ");
		for (String[] m : members(t)) {
			sb.append(m[1].equals("1") ? "<green>" : "<gray>").append(LagCore.esc(m[0]));
			if (m[1].equals("1")) sb.append(" <dark_gray>(").append(LagCore.esc(m[2])).append(")");
			sb.append("<dark_gray>, ");
		}
		return sb.substring(0, sb.length() - "<dark_gray>, ".length());
	}

	/** /team ping x y z [kind]: client's ping key. Teammates on this server see it. */
	private void ping(Player p, String[] a) {
		int t = teamOf.getOrDefault(p.getUniqueId(), 0);
		if (t == 0 || a.length < 4) return;
		double x, y, z;
		try {
			x = Double.parseDouble(a[1]);
			y = Double.parseDouble(a[2]);
			z = Double.parseDouble(a[3]);
		} catch (NumberFormatException e) {
			return;
		}
		if (p.getLocation().distanceSquared(new Location(p.getWorld(), x, y, z)) > 512 * 512) return;
		String kind = a.length > 4 ? a[4].toLowerCase(Locale.ROOT) : "here";
		if (!Set.of("here", "enemy", "base").contains(kind)) kind = "here";
		Buf b = new Buf();
		b.utf(p.getName());
		b.utf(kind);
		b.utf(p.getWorld().getKey().toString());
		b.dbl(x);
		b.dbl(y);
		b.dbl(z);
		byte[] pkt = b.bytes();
		for (Player o : Bukkit.getOnlinePlayers()) {
			if (teamOf.getOrDefault(o.getUniqueId(), 0) == t && o.getWorld() == p.getWorld()
					&& o.getListeningPluginChannels().contains(C_PING)) o.sendPluginMessage(plugin, C_PING, pkt);
		}
	}

	// ================================================================ schematics (client only)

	@Override
	public void onPluginMessageReceived(String channel, Player p, byte[] data) {
		try {
			DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
			switch (channel) {
				case C_UP -> uploadChunk(p, in);
				case C_GET -> download(p, readVarInt(in));
				case C_DEL -> {
					int id = readVarInt(in);
					async(p, () -> deleteSchem(p, id));
				}
				default -> { }
			}
		} catch (Exception e) {
			plugin.getLogger().fine("bad team packet from " + p.getName() + ": " + e);
		}
	}

	private void uploadChunk(Player p, DataInputStream in) throws IOException {
		String name = readUtf(in);
		int total = readVarInt(in), index = readVarInt(in);
		byte[] part = new byte[readVarInt(in)];
		in.readFully(part);
		if (total <= 0 || total > MAX_SCHEM / CHUNK + 1 || index < 0 || index >= total || part.length > CHUNK) return;
		Upload u = uploads.get(p.getUniqueId());
		if (index == 0 || u == null || !u.name.equals(name) || u.parts.length != total) {
			u = new Upload();
			u.name = name;
			u.parts = new byte[total][];
			uploads.put(p.getUniqueId(), u);
		}
		if (u.parts[index] == null) {
			u.parts[index] = part;
			u.got++;
		}
		if (u.got < total) return;
		uploads.remove(p.getUniqueId());
		ByteArrayOutputStream all = new ByteArrayOutputStream();
		for (byte[] b : u.parts) all.writeBytes(b);
		byte[] file = all.toByteArray();
		String safe = name.replaceAll("[^A-Za-z0-9 _.\\-]", "_");
		if (safe.length() > 64) safe = safe.substring(safe.length() - 64);
		String ext = safe.toLowerCase(Locale.ROOT);
		if (!(ext.endsWith(".litematic") || ext.endsWith(".schem") || ext.endsWith(".schematic") || ext.endsWith(".nbt"))) {
			say(p, "<red>Only .litematic, .schem, .schematic or .nbt files.");
			return;
		}
		String fname = safe;
		async(p, () -> {
			int t = teamIdOf(p.getUniqueId());
			if (t == 0) return "<red>Join a team to share schematics.";
			File dir = new File(sharedDir, "schems/" + t);
			dir.mkdirs();
			File out = new File(dir, System.currentTimeMillis() + "_" + fname);
			Files.write(out.toPath(), file);
			try (PreparedStatement s = db.prepareStatement("INSERT INTO schems(team,name,by,size,ts,file) VALUES(?,?,?,?,?,?)")) {
				s.setInt(1, t);
				s.setString(2, fname);
				s.setString(3, p.getName());
				s.setInt(4, file.length);
				s.setLong(5, System.currentTimeMillis());
				s.setString(6, out.getPath());
				s.executeUpdate();
			}
			post(t, "", p.getName() + " shared " + fname + " (open the client menu > Team to download).");
			return null;
		});
	}

	private void download(Player p, int id) {
		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			String name, path;
			synchronized (this) {
				try (PreparedStatement s = db.prepareStatement(
						"SELECT name, file FROM schems s JOIN members m ON m.team = s.team WHERE s.id=? AND m.uuid=?")) {
					s.setInt(1, id);
					s.setString(2, p.getUniqueId().toString());
					try (ResultSet r = s.executeQuery()) {
						if (!r.next()) return;
						name = r.getString(1);
						path = r.getString(2);
					}
				} catch (SQLException e) {
					return;
				}
			}
			byte[] file;
			try {
				file = Files.readAllBytes(new File(path).toPath());
			} catch (IOException e) {
				Bukkit.getScheduler().runTask(plugin, () -> say(p, "<red>That schematic's file is missing."));
				return;
			}
			int total = Math.max(1, (file.length + CHUNK - 1) / CHUNK);
			List<byte[]> pkts = new ArrayList<>();
			for (int i = 0; i < total; i++) {
				int from = i * CHUNK, len = Math.min(CHUNK, file.length - from);
				Buf b = new Buf();
				b.varInt(id);
				b.utf(name);
				b.varInt(total);
				b.varInt(i);
				b.varInt(Math.max(0, len));
				if (len > 0) b.raw(java.util.Arrays.copyOfRange(file, from, from + len));
				pkts.add(b.bytes());
			}
			// a few chunks per tick so big files don't flood the connection
			int[] i = {0};
			Bukkit.getScheduler().runTaskTimer(plugin, task -> {
				for (int k = 0; k < 4 && i[0] < pkts.size(); k++) {
					if (!p.isOnline()) {
						task.cancel();
						return;
					}
					p.sendPluginMessage(plugin, C_DATA, pkts.get(i[0]++));
				}
				if (i[0] >= pkts.size()) task.cancel();
			}, 1L, 1L);
		});
	}

	private String deleteSchem(Player p, int id) throws SQLException {
		int t = teamIdOf(p.getUniqueId());
		String file = null, by = null;
		try (PreparedStatement s = db.prepareStatement("SELECT file, by FROM schems WHERE id=? AND team=?")) {
			s.setInt(1, id);
			s.setInt(2, t);
			try (ResultSet r = s.executeQuery()) {
				if (r.next()) {
					file = r.getString(1);
					by = r.getString(2);
				}
			}
		}
		if (file == null) return null;
		if (!by.equalsIgnoreCase(p.getName()) && !isLeader(p.getUniqueId(), t)) return "<red>Only whoever shared it (or the leader) can delete it.";
		new File(file).delete();
		try (PreparedStatement s = db.prepareStatement("DELETE FROM schems WHERE id=?")) {
			s.setInt(1, id);
			s.executeUpdate();
		}
		return "<gray>Deleted.";
	}

	// ================================================================ database helpers (call inside synchronized)

	private void post(int team, String sender, String msg) throws SQLException {
		try (PreparedStatement s = db.prepareStatement("INSERT INTO chat(team,sender,msg,ts) VALUES(?,?,?,?)")) {
			s.setInt(1, team);
			s.setString(2, sender);
			s.setString(3, msg);
			s.setLong(4, System.currentTimeMillis());
			s.executeUpdate();
		}
		// keep the table small: a day of chat is plenty for "deliver to other servers"
		try (PreparedStatement s = db.prepareStatement("DELETE FROM chat WHERE ts < ?")) {
			s.setLong(1, System.currentTimeMillis() - 86_400_000L);
			s.executeUpdate();
		}
	}

	private int teamIdOf(UUID id) throws SQLException {
		try (PreparedStatement s = db.prepareStatement("SELECT team FROM members WHERE uuid=?")) {
			s.setString(1, id.toString());
			try (ResultSet r = s.executeQuery()) {
				return r.next() ? r.getInt(1) : 0;
			}
		}
	}

	private String teamName(int t) throws SQLException {
		try (PreparedStatement s = db.prepareStatement("SELECT name FROM teams WHERE id=?")) {
			s.setInt(1, t);
			try (ResultSet r = s.executeQuery()) {
				return r.next() ? r.getString(1) : "?";
			}
		}
	}

	private boolean isLeader(UUID id, int t) throws SQLException {
		try (PreparedStatement s = db.prepareStatement("SELECT 1 FROM teams WHERE id=? AND leader=?")) {
			s.setInt(1, t);
			s.setString(2, id.toString());
			try (ResultSet r = s.executeQuery()) {
				return r.next();
			}
		}
	}

	private int memberCount(int t) throws SQLException {
		try (PreparedStatement s = db.prepareStatement("SELECT COUNT(*) FROM members WHERE team=?")) {
			s.setInt(1, t);
			try (ResultSet r = s.executeQuery()) {
				return r.next() ? r.getInt(1) : 0;
			}
		}
	}

	private void addMember(int t, Player p) throws SQLException {
		try (PreparedStatement s = db.prepareStatement("INSERT OR REPLACE INTO members(uuid,team,name,joined) VALUES(?,?,?,?)")) {
			s.setString(1, p.getUniqueId().toString());
			s.setInt(2, t);
			s.setString(3, p.getName());
			s.setLong(4, System.currentTimeMillis());
			s.executeUpdate();
		}
	}

	private void removeMember(UUID id) throws SQLException {
		try (PreparedStatement s = db.prepareStatement("DELETE FROM members WHERE uuid=?")) {
			s.setString(1, id.toString());
			s.executeUpdate();
		}
	}

	private Integer inviteTeam(String player, String teamName) throws SQLException {
		String sql = teamName == null
				? "SELECT i.team FROM invites i WHERE i.name=? COLLATE NOCASE ORDER BY i.ts DESC LIMIT 1"
				: "SELECT i.team FROM invites i JOIN teams t ON t.id=i.team WHERE i.name=? COLLATE NOCASE AND t.name=? COLLATE NOCASE";
		try (PreparedStatement s = db.prepareStatement(sql)) {
			s.setString(1, player);
			if (teamName != null) s.setString(2, teamName);
			try (ResultSet r = s.executeQuery()) {
				return r.next() ? r.getInt(1) : null;
			}
		}
	}

	private List<String> invitesFor(String player) {
		List<String> out = new ArrayList<>();
		synchronized (this) {
			try (PreparedStatement s = db.prepareStatement("SELECT t.name FROM invites i JOIN teams t ON t.id=i.team WHERE i.name=? COLLATE NOCASE")) {
				s.setString(1, player);
				try (ResultSet r = s.executeQuery()) {
					while (r.next()) out.add(r.getString(1));
				}
			} catch (SQLException ignored) { }
		}
		return out;
	}

	/** name, online ("1"/"0"), realm: online = seen by any server in the last 15s. */
	private List<String[]> members(int t) throws SQLException {
		List<String[]> out = new ArrayList<>();
		try (PreparedStatement s = db.prepareStatement("SELECT m.name, p.realm, p.ts FROM members m LEFT JOIN presence p ON p.uuid=m.uuid "
				+ "WHERE m.team=? ORDER BY m.joined")) {
			s.setInt(1, t);
			try (ResultSet r = s.executeQuery()) {
				long now = System.currentTimeMillis();
				while (r.next()) {
					boolean on = r.getLong(3) > now - 15_000;
					out.add(new String[] {r.getString(1), on ? "1" : "0", r.getString(2) == null ? "" : r.getString(2)});
				}
			}
		}
		return out;
	}

	private byte[] statePacket(Player p, int t) throws SQLException {
		Buf b = new Buf();
		if (t == 0) {
			b.utf("");
			b.utf("");
			b.bool(false);
			b.varInt(0);
		} else {
			String leader = "";
			try (PreparedStatement s = db.prepareStatement("SELECT m.name FROM teams t JOIN members m ON m.uuid=t.leader WHERE t.id=?")) {
				s.setInt(1, t);
				try (ResultSet r = s.executeQuery()) {
					if (r.next()) leader = r.getString(1);
				}
			}
			b.utf(teamName(t));
			b.utf(leader);
			b.bool(isLeader(p.getUniqueId(), t));
			List<String[]> ms = members(t);
			b.varInt(ms.size());
			for (String[] m : ms) {
				b.utf(m[0]);
				b.bool(m[1].equals("1"));
				b.utf(m[2]);
			}
		}
		// invites
		List<String[]> inv = new ArrayList<>();
		try (PreparedStatement s = db.prepareStatement("SELECT t.name, i.by FROM invites i JOIN teams t ON t.id=i.team WHERE i.name=? COLLATE NOCASE")) {
			s.setString(1, p.getName());
			try (ResultSet r = s.executeQuery()) {
				while (r.next()) inv.add(new String[] {r.getString(1), r.getString(2)});
			}
		}
		b.varInt(t == 0 ? inv.size() : 0);
		if (t == 0) for (String[] i : inv) {
			b.utf(i[0]);
			b.utf(i[1]);
		}
		// schematics
		List<Object[]> sc = new ArrayList<>();
		if (t != 0) {
			try (PreparedStatement s = db.prepareStatement("SELECT id, name, by, size, ts FROM schems WHERE team=? ORDER BY ts DESC LIMIT 50")) {
				s.setInt(1, t);
				try (ResultSet r = s.executeQuery()) {
					while (r.next()) sc.add(new Object[] {r.getInt(1), r.getString(2), r.getString(3), r.getInt(4), r.getLong(5)});
				}
			}
		}
		b.varInt(sc.size());
		for (Object[] s : sc) {
			b.varInt((Integer) s[0]);
			b.utf((String) s[1]);
			b.utf((String) s[2]);
			b.varInt((Integer) s[3]);
			b.i64((Long) s[4]);
		}
		return b.bytes();
	}

	// ================================================================ wire helpers

	private static int readVarInt(DataInputStream in) throws IOException {
		int v = 0, shift = 0, b;
		do {
			b = in.readUnsignedByte();
			v |= (b & 0x7F) << shift;
			shift += 7;
			if (shift > 35) throw new IOException("varint too long");
		} while ((b & 0x80) != 0);
		return v;
	}

	private static String readUtf(DataInputStream in) throws IOException {
		int n = readVarInt(in);
		if (n > 1024) throw new IOException("string too long");
		byte[] b = new byte[n];
		in.readFully(b);
		return new String(b, StandardCharsets.UTF_8);
	}

	private static final class Buf {
		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
		private final DataOutputStream out = new DataOutputStream(bytes);

		void varInt(int v) {
			while ((v & ~0x7F) != 0) {
				bytes.write((v & 0x7F) | 0x80);
				v >>>= 7;
			}
			bytes.write(v);
		}

		void utf(String s) {
			byte[] b = s.getBytes(StandardCharsets.UTF_8);
			varInt(b.length);
			bytes.writeBytes(b);
		}

		void bool(boolean b) {
			bytes.write(b ? 1 : 0);
		}

		void raw(byte[] b) {
			bytes.writeBytes(b);
		}

		void dbl(double d) {
			try {
				out.writeDouble(d);
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
		}

		void i64(long l) {
			try {
				out.writeLong(l);
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
		}

		byte[] bytes() {
			return bytes.toByteArray();
		}
	}
}
