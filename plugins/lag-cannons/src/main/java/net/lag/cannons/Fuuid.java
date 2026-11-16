package net.lag.cannons;

import java.lang.reflect.Method;
import java.util.UUID;

import org.bukkit.OfflinePlayer;

/**
 * Tiny reflection bridge to FactionsUUID 4.x (dev.kitteh.factions), so laGCannons still loads on servers without it
 * (the cannon test server). Every method returns null/false when FactionsUUID isn't there.
 */
final class Fuuid {
	private static boolean ok;
	private static Object fPlayers, factions;
	private static Method fpGet, fpFaction, fpHasFaction, fpRole, facById, facByTag, facId, facTag, facNormal, facAll, facClaims, facSize, facOnline;

	private Fuuid() { }

	static boolean present() {
		if (ok) return true;
		try {
			Class<?> fps = Class.forName("dev.kitteh.factions.FPlayers");
			Class<?> fs = Class.forName("dev.kitteh.factions.Factions");
			Class<?> fp = Class.forName("dev.kitteh.factions.FPlayer");
			Class<?> f = Class.forName("dev.kitteh.factions.Faction");
			fPlayers = fps.getMethod("fPlayers").invoke(null);
			factions = fs.getMethod("factions").invoke(null);
			fpGet = fps.getMethod("get", UUID.class);
			fpFaction = fp.getMethod("faction");
			fpHasFaction = fp.getMethod("hasFaction");
			fpRole = fp.getMethod("role");
			facById = fs.getMethod("get", int.class);
			facByTag = fs.getMethod("get", String.class);
			facId = f.getMethod("id");
			facTag = f.getMethod("tag");
			facNormal = f.getMethod("isNormal");
			facAll = fs.getMethod("all");
			facClaims = f.getMethod("claimCount");
			facSize = f.getMethod("size");
			facOnline = f.getMethod("membersOnlineAsPlayers");
			ok = fPlayers != null && factions != null;
		} catch (Throwable t) {
			ok = false;
		}
		return ok;
	}

	private static Object fp(OfflinePlayer p) throws Exception {
		return fpGet.invoke(fPlayers, p.getUniqueId());
	}

	/** The player's faction, or null if they have none (wilderness). */
	static Object faction(OfflinePlayer p) {
		if (!present()) return null;
		try {
			Object fp = fp(p);
			if (fp == null || !(boolean) fpHasFaction.invoke(fp)) return null;
			return fpFaction.invoke(fp);
		} catch (Throwable t) {
			return null;
		}
	}

	static boolean hasFaction(OfflinePlayer p) {
		return faction(p) != null;
	}

	/** Role enum name: ADMIN, COLEADER, MODERATOR, NORMAL, RECRUIT (null if factionless). */
	static String role(OfflinePlayer p) {
		if (faction(p) == null) return null;
		try {
			return ((Enum<?>) fpRole.invoke(fp(p))).name();
		} catch (Throwable t) {
			return null;
		}
	}

	static boolean isModOrAbove(OfflinePlayer p) {
		String r = role(p);
		return r != null && (r.equals("MODERATOR") || r.equals("COLEADER") || r.equals("ADMIN"));
	}

	/** A normal (player-made) faction by id, or null if it was disbanded. */
	static Object byId(int id) {
		if (!present()) return null;
		try {
			Object f = facById.invoke(factions, id);
			return f != null && (boolean) facNormal.invoke(f) ? f : null;
		} catch (Throwable t) {
			return null;
		}
	}

	static Object byTag(String tag) {
		if (!present()) return null;
		try {
			Object f = facByTag.invoke(factions, tag);
			return f != null && (boolean) facNormal.invoke(f) ? f : null;
		} catch (Throwable t) {
			return null;
		}
	}

	static int id(Object faction) {
		try {
			return (int) facId.invoke(faction);
		} catch (Throwable t) {
			return Integer.MIN_VALUE;
		}
	}

	static String tag(Object faction) {
		try {
			return (String) facTag.invoke(faction);
		} catch (Throwable t) {
			return "?";
		}
	}

	/** All player-made factions (no wilderness/safezone/warzone). */
	static java.util.List<Object> all() {
		java.util.List<Object> out = new java.util.ArrayList<>();
		if (!present()) return out;
		try {
			for (Object f : (java.util.List<?>) facAll.invoke(factions)) {
				if ((boolean) facNormal.invoke(f)) out.add(f);
			}
		} catch (Throwable ignored) { }
		return out;
	}

	static int claims(Object faction) {
		try {
			return (int) facClaims.invoke(faction);
		} catch (Throwable t) {
			return 0;
		}
	}

	static int size(Object faction) {
		try {
			return (int) facSize.invoke(faction);
		} catch (Throwable t) {
			return 0;
		}
	}

	static int online(Object faction) {
		try {
			return ((java.util.List<?>) facOnline.invoke(faction)).size();
		} catch (Throwable t) {
			return 0;
		}
	}
}
