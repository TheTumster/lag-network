package net.lag.factions;

import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;

import dev.kitteh.factions.Board;
import dev.kitteh.factions.FLocation;
import dev.kitteh.factions.FPlayer;
import dev.kitteh.factions.FPlayers;
import dev.kitteh.factions.Faction;
import dev.kitteh.factions.Factions;
import dev.kitteh.factions.permissible.Role;

/** FactionsUUID shortcuts. */
final class F {
	private F() { }

	static FPlayer fp(OfflinePlayer p) {
		return FPlayers.fPlayers().get(p);
	}

	/** The player's faction, or null when factionless. */
	static Faction of(OfflinePlayer p) {
		FPlayer f = fp(p);
		return f != null && f.hasFaction() ? f.faction() : null;
	}

	static boolean atLeast(OfflinePlayer p, Role r) {
		FPlayer f = fp(p);
		return f != null && f.hasFaction() && f.role() != null && f.role().isAtLeast(r);
	}

	static Faction at(Location l) {
		return Board.board().factionAt(new FLocation(l));
	}

	static Faction at(Block b) {
		return Board.board().factionAt(new FLocation(b));
	}

	static Faction at(String world, int cx, int cz) {
		return Board.board().factionAt(new FLocation(world, cx, cz));
	}

	static Faction byId(int id) {
		Faction f = Factions.factions().get(id);
		return f != null && f.isNormal() ? f : null;
	}

	static Faction byTag(String tag) {
		Faction f = Factions.factions().get(tag);
		return f != null && f.isNormal() ? f : null;
	}

	static String roleName(Role r) {
		if (r == null) return "";
		return switch (r) {
			case ADMIN -> "Leader";
			case COLEADER -> "Co-Leader";
			case MODERATOR -> "Mod";
			case NORMAL -> "Member";
			case RECRUIT -> "Recruit";
		};
	}

	static String roleStars(Role r) {
		if (r == null) return "";
		return switch (r) {
			case ADMIN -> "***";
			case COLEADER -> "**";
			case MODERATOR -> "*";
			default -> "";
		};
	}
}
