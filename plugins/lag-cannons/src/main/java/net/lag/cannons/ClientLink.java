package net.lag.cannons;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Feeds the RelapsedMC Client (TheTumster/factions-client, ServerLink.java).
 * Only players running the client receive anything: it registers these channels on join, and Bukkit only
 * delivers plugin messages to players listening on the channel.
 *
 *  relapsed:mates  every 10 ticks: your online faction members on this server (never anyone else's).
 *  relapsed:crumbs every 10 ticks: TNT that went off in or right next to your faction's claims.
 *  relapsed:claims when you change chunk (and every 2s): claimed chunks around you + your relation to each.
 *  relapsed:home   every 2s: your faction's /f home, if it has one.
 *
 * Wire format (must match ServerLink.java): Minecraft VarInt-prefixed UTF-8 strings, big-endian numbers.
 *  mates:  varint n, then n x (utf name, utf dim, double x, double y, double z)
 *  crumbs: utf dim, varint n, then n x (int x, int y, int z)
 *  claims: utf dim, varint radius, varint nTags, nTags x utf tag, varint n, then n x (int cx, int cz, byte rel, varint tag)
 *          rel: 0 own, 1 ally, 2 truce, 3 neutral, 4 enemy, 5 safezone, 6 warzone
 *  home:   bool has, then if has: utf dim, double x, double y, double z
 *
 * FactionsUUID 4 is read by reflection (no compile dependency), same as the rest of this plugin.
 */
final class ClientLink implements Listener {
	static final String MATES = "relapsed:mates";
	static final String CRUMBS = "relapsed:crumbs";
	static final String CLAIMS = "relapsed:claims";
	static final String HOME = "relapsed:home";
	static final int CLAIM_RADIUS = 6; // chunks each way: 13x13 around you
	private static final int MAX_CRUMBS_PER_SEND = 500;

	private final JavaPlugin plugin;
	private final Fuuid fuuid;
	/** factionId -> world -> explosion block positions waiting to be sent. */
	private final Map<Integer, Map<World, Set<Long>>> pending = new HashMap<>();
	/** player -> last chunk we sent claims for (world hash ^ packed chunk). */
	private final Map<java.util.UUID, Long> lastClaimChunk = new HashMap<>();
	private int flushes = 0;

	ClientLink(JavaPlugin plugin) {
		this.plugin = plugin;
		this.fuuid = Fuuid.load(plugin);
	}

	void start() {
		if (fuuid == null) {
			plugin.getLogger().info("ClientLink: FactionsUUID not found, RelapsedMC Client features off on this server");
			return;
		}
		var m = Bukkit.getMessenger();
		m.registerOutgoingPluginChannel(plugin, MATES);
		m.registerOutgoingPluginChannel(plugin, CRUMBS);
		m.registerOutgoingPluginChannel(plugin, CLAIMS);
		m.registerOutgoingPluginChannel(plugin, HOME);
		Bukkit.getPluginManager().registerEvents(this, plugin);
		Bukkit.getScheduler().runTaskTimer(plugin, this::flush, 20L, 10L);
		plugin.getLogger().info("ClientLink: sending faction data to RelapsedMC Client users");
	}

	// ---------------------------------------------------------------- crumbs

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onExplode(EntityExplodeEvent e) {
		if (!(e.getEntity() instanceof TNTPrimed)) return;
		Location l = e.getLocation();
		World w = l.getWorld();
		if (w == null) return;
		// Count hits in a claim and on the buffer right outside it (walls usually sit on the claim edge).
		Set<Integer> factions = new LinkedHashSet<>(3);
		int bx = l.getBlockX(), by = l.getBlockY(), bz = l.getBlockZ();
		int[][] probe = { { 0, 0 }, { 16, 0 }, { -16, 0 }, { 0, 16 }, { 0, -16 } };
		for (int[] o : probe) {
			int id = fuuid.factionIdAt(new Location(w, bx + o[0], by, bz + o[1]));
			if (id != Fuuid.NONE) factions.add(id);
		}
		if (factions.isEmpty()) return;
		long key = pack(bx, by, bz);
		for (int id : factions) {
			pending.computeIfAbsent(id, k -> new HashMap<>()).computeIfAbsent(w, k -> new LinkedHashSet<>()).add(key);
		}
	}

	// ---------------------------------------------------------------- sending

	private void flush() {
		boolean slow = ++flushes % 4 == 0; // every 2s
		for (Player p : Bukkit.getOnlinePlayers()) {
			if (p.getListeningPluginChannels().contains(CLAIMS)) {
				Location l = p.getLocation();
				long here = ((long) l.getWorld().getName().hashCode() << 40) ^ (((long) (l.getBlockX() >> 4) & 0xFFFFF) << 20) ^ ((l.getBlockZ() >> 4) & 0xFFFFF);
				Long last = lastClaimChunk.get(p.getUniqueId());
				if (slow || last == null || last != here) {
					lastClaimChunk.put(p.getUniqueId(), here);
					p.sendPluginMessage(plugin, CLAIMS, claimsPacket(p, l));
				}
			}
			if (slow && p.getListeningPluginChannels().contains(HOME)) {
				Location home = fuuid.homeOf(p);
				var out = new Buf();
				out.bool(home != null && home.getWorld() != null);
				if (home != null && home.getWorld() != null) {
					out.utf(dim(home.getWorld()));
					out.dbl(home.getX());
					out.dbl(home.getY());
					out.dbl(home.getZ());
				}
				p.sendPluginMessage(plugin, HOME, out.bytes());
			}
		}
		if (slow) lastClaimChunk.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);

		Map<Integer, List<Player>> byFaction = new HashMap<>();
		for (Player p : Bukkit.getOnlinePlayers()) {
			if (!p.getListeningPluginChannels().contains(MATES)) continue;
			int id = fuuid.factionIdOf(p);
			if (id == Fuuid.NONE) {
				p.sendPluginMessage(plugin, MATES, matesPacket(List.of())); // clears the list (left your faction)
				continue;
			}
			byFaction.computeIfAbsent(id, k -> new ArrayList<>()).add(p);
		}

		for (var entry : byFaction.entrySet()) {
			// Everyone in the faction who is online on this server, client or not.
			List<Player> members = fuuid.onlineMembers(entry.getKey());
			for (Player viewer : entry.getValue()) {
				List<Player> others = new ArrayList<>(members.size());
				for (Player m : members) if (m != viewer && m.isOnline() && viewer.canSee(m)) others.add(m);
				viewer.sendPluginMessage(plugin, MATES, matesPacket(others));
			}

			Map<World, Set<Long>> hits = pending.remove(entry.getKey());
			if (hits == null) continue;
			for (var h : hits.entrySet()) {
				byte[] pkt = crumbsPacket(h.getKey(), h.getValue());
				for (Player viewer : entry.getValue()) {
					if (viewer.getListeningPluginChannels().contains(CRUMBS)) viewer.sendPluginMessage(plugin, CRUMBS, pkt);
				}
			}
		}
		pending.clear(); // hits for factions with nobody online using the client
	}

	private static byte[] matesPacket(List<Player> mates) {
		var out = new Buf();
		out.varInt(mates.size());
		for (Player m : mates) {
			Location l = m.getLocation();
			out.utf(m.getName());
			out.utf(dim(l.getWorld()));
			out.dbl(l.getX());
			out.dbl(l.getY());
			out.dbl(l.getZ());
		}
		return out.bytes();
	}

	private static byte[] crumbsPacket(World w, Set<Long> hits) {
		var out = new Buf();
		out.utf(dim(w));
		int n = Math.min(hits.size(), MAX_CRUMBS_PER_SEND);
		out.varInt(n);
		int i = 0;
		for (long k : hits) {
			if (i++ >= n) break;
			out.i32(unpackX(k));
			out.i32(unpackY(k));
			out.i32(unpackZ(k));
		}
		return out.bytes();
	}

	private byte[] claimsPacket(Player p, Location at) {
		World w = at.getWorld();
		int pcx = at.getBlockX() >> 4, pcz = at.getBlockZ() >> 4;
		List<String> tags = new ArrayList<>();
		Map<String, Integer> tagIdx = new HashMap<>();
		var rows = new Buf();
		int n = 0;
		for (int dx = -CLAIM_RADIUS; dx <= CLAIM_RADIUS; dx++) {
			for (int dz = -CLAIM_RADIUS; dz <= CLAIM_RADIUS; dz++) {
				int cx = pcx + dx, cz = pcz + dz;
				Fuuid.Claim c = fuuid.claimAt(p, new Location(w, (cx << 4) + 8, 64, (cz << 4) + 8));
				if (c == null) continue;
				Integer ti = tagIdx.get(c.tag());
				if (ti == null) {
					ti = tags.size();
					tags.add(c.tag());
					tagIdx.put(c.tag(), ti);
				}
				rows.i32(cx);
				rows.i32(cz);
				rows.u8(c.rel());
				rows.varInt(ti);
				n++;
			}
		}
		var out = new Buf();
		out.utf(dim(w));
		out.varInt(CLAIM_RADIUS);
		out.varInt(tags.size());
		for (String t : tags) out.utf(t);
		out.varInt(n);
		out.raw(rows.bytes());
		return out.bytes();
	}

	/** Same id the client reads from level.dimension(), e.g. minecraft:overworld. */
	private static String dim(World w) {
		return w == null ? "" : w.getKey().toString();
	}

	private static long pack(int x, int y, int z) {
		return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
	}

	private static int unpackX(long k) {
		return (int) (k >> 38);
	}

	private static int unpackZ(long k) {
		return (int) (k << 26 >> 38);
	}

	private static int unpackY(long k) {
		return (int) (k << 52 >> 52);
	}

	/** Minimal Minecraft-style buffer writer. */
	private static final class Buf {
		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
		private final DataOutputStream out = new DataOutputStream(bytes);

		void varInt(int v) {
			try {
				while ((v & ~0x7F) != 0) {
					out.writeByte((v & 0x7F) | 0x80);
					v >>>= 7;
				}
				out.writeByte(v);
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
		}

		void utf(String s) {
			byte[] b = s.getBytes(StandardCharsets.UTF_8);
			varInt(b.length);
			bytes.writeBytes(b);
		}

		void bool(boolean b) {
			bytes.write(b ? 1 : 0);
		}

		void u8(int b) {
			bytes.write(b & 0xFF);
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

		void i32(int v) {
			try {
				out.writeInt(v);
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
		}

		byte[] bytes() {
			return bytes.toByteArray();
		}
	}

	/** FactionsUUID 4 (dev.kitteh.factions) through reflection. */
	private static final class Fuuid {
		static final int NONE = Integer.MIN_VALUE;

		private final Object fplayers;
		private final Object board;
		private final Method fpGet, fpFaction, facId, facNormal, facOnline, boardAt;
		private final Constructor<?> flocNew;
		private final Method factionsById;
		private final Object factions;
		private final Method relTo, facTag, facSafe, facWar, facWild, facHasHome, facHome;

		/** A claimed chunk as seen by one player. */
		record Claim(int rel, String tag) { }

		private Fuuid(ClassLoader cl) throws ReflectiveOperationException {
			Class<?> cFPlayers = Class.forName("dev.kitteh.factions.FPlayers", true, cl);
			Class<?> cFPlayer = Class.forName("dev.kitteh.factions.FPlayer", true, cl);
			Class<?> cFaction = Class.forName("dev.kitteh.factions.Faction", true, cl);
			Class<?> cFactions = Class.forName("dev.kitteh.factions.Factions", true, cl);
			Class<?> cBoard = Class.forName("dev.kitteh.factions.Board", true, cl);
			Class<?> cFLoc = Class.forName("dev.kitteh.factions.FLocation", true, cl);
			fplayers = cFPlayers.getMethod("fPlayers").invoke(null);
			board = cBoard.getMethod("board").invoke(null);
			factions = cFactions.getMethod("factions").invoke(null);
			fpGet = cFPlayers.getMethod("get", OfflinePlayer.class);
			fpFaction = cFPlayer.getMethod("faction");
			facId = cFaction.getMethod("id");
			facNormal = cFaction.getMethod("isNormal");
			facOnline = cFaction.getMethod("membersOnlineAsPlayers");
			boardAt = cBoard.getMethod("factionAt", cFLoc);
			flocNew = cFLoc.getConstructor(Location.class);
			factionsById = cFactions.getMethod("get", int.class);
			Class<?> cPart = Class.forName("dev.kitteh.factions.Participator", true, cl);
			relTo = cPart.getMethod("relationTo", cPart);
			facTag = cFaction.getMethod("tag");
			facSafe = cFaction.getMethod("isSafeZone");
			facWar = cFaction.getMethod("isWarZone");
			facWild = cFaction.getMethod("isWilderness");
			facHasHome = cFaction.getMethod("hasHome");
			facHome = cFaction.getMethod("home");
		}

		static Fuuid load(JavaPlugin plugin) {
			var fp = Bukkit.getPluginManager().getPlugin("FactionsUUID");
			if (fp == null) fp = Bukkit.getPluginManager().getPlugin("Factions");
			if (fp == null) return null;
			try {
				return new Fuuid(fp.getClass().getClassLoader());
			} catch (Throwable t) {
				plugin.getLogger().warning("ClientLink: couldn't hook FactionsUUID (" + t + ")");
				return null;
			}
		}

		private int idOf(Object faction) throws ReflectiveOperationException {
			if (faction == null || !(Boolean) facNormal.invoke(faction)) return NONE;
			return (Integer) facId.invoke(faction);
		}

		int factionIdOf(Player p) {
			try {
				return idOf(fpFaction.invoke(fpGet.invoke(fplayers, p)));
			} catch (Throwable t) {
				return NONE;
			}
		}

		int factionIdAt(Location l) {
			try {
				return idOf(boardAt.invoke(board, flocNew.newInstance(l)));
			} catch (Throwable t) {
				return NONE;
			}
		}

		/** null for wilderness. */
		Claim claimAt(Player viewer, Location l) {
			try {
				Object f = boardAt.invoke(board, flocNew.newInstance(l));
				if (f == null || (Boolean) facWild.invoke(f)) return null;
				if ((Boolean) facSafe.invoke(f)) return new Claim(5, "SafeZone");
				if ((Boolean) facWar.invoke(f)) return new Claim(6, "WarZone");
				Object fp = fpGet.invoke(fplayers, viewer);
				String rel = ((Enum<?>) relTo.invoke(fp, f)).name();
				int code = switch (rel) {
					case "MEMBER" -> 0;
					case "ALLY" -> 1;
					case "TRUCE" -> 2;
					case "ENEMY" -> 4;
					default -> 3;
				};
				String tag = String.valueOf(facTag.invoke(f));
				return new Claim(code, tag.length() > 32 ? tag.substring(0, 32) : tag);
			} catch (Throwable t) {
				return null;
			}
		}

		/** Your faction's home, or null. */
		Location homeOf(Player p) {
			try {
				Object f = fpFaction.invoke(fpGet.invoke(fplayers, p));
				if (f == null || !(Boolean) facNormal.invoke(f) || !(Boolean) facHasHome.invoke(f)) return null;
				return (Location) facHome.invoke(f);
			} catch (Throwable t) {
				return null;
			}
		}

		@SuppressWarnings("unchecked")
		List<Player> onlineMembers(int id) {
			try {
				Object f = factionsById.invoke(factions, id);
				return f == null ? List.of() : (List<Player>) facOnline.invoke(f);
			} catch (Throwable t) {
				return List.of();
			}
		}
	}
}
