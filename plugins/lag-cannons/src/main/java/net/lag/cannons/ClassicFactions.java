package net.lag.cannons;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

/**
 * Classic 1.8 (SaberFactions-style) /f commands, mapped onto FactionsUUID 4's command tree.
 * e.g. /f who -> /f show, /f ally X -> /f relation ally X, /f tntfill 10 64 -> /f tnt fill 10 64
 */
final class ClassicFactions implements Listener {
	private static final Map<String, String> MAP = new HashMap<>();

	static {
		// info
		MAP.put("who", "show");
		MAP.put("f", "show");
		MAP.put("info", "show");
		MAP.put("i", "show");
		MAP.put("bal", "money balance");
		MAP.put("balance", "money balance");
		MAP.put("deposit", "money deposit");
		MAP.put("withdraw", "money withdraw");
		MAP.put("pay", "money send");
		// relations
		MAP.put("ally", "relation ally");
		MAP.put("enemy", "relation enemy");
		MAP.put("truce", "relation truce");
		MAP.put("neutral", "relation neutral");
		// settings
		MAP.put("sethome", "set home");
		MAP.put("setwarp", "set warp");
		MAP.put("desc", "set description");
		MAP.put("description", "set description");
		MAP.put("tag", "set tag");
		MAP.put("rename", "set tag");
		MAP.put("open", "set open");
		MAP.put("perms", "set perm");
		MAP.put("perm", "set perm");
		MAP.put("title", "set title");
		MAP.put("boom", "set explosions");
		// roles
		MAP.put("promote", "role promote");
		MAP.put("demote", "role demote");
		MAP.put("mod", "role moderator");
		MAP.put("coleader", "role coleader");
		MAP.put("leader", "role admin");
		MAP.put("owner", "role admin");
		MAP.put("recruit", "role recruit");
		// tnt bank
		MAP.put("tntfill", "tnt fill");
		MAP.put("tf", "tnt fill");
		MAP.put("tntbank", "tnt info");
		MAP.put("tntbal", "tnt info");
		MAP.put("siphon", "tnt siphon");
		// toggles
		MAP.put("sc", "toggle seechunk");
		MAP.put("seechunk", "toggle seechunk");
		MAP.put("sb", "toggle scoreboard");
		// chat
		MAP.put("c", "chat");
		// lists
		MAP.put("claims", "list claims");
		MAP.put("invites", "list invites");
		MAP.put("bans", "list bans");
		// storage
		MAP.put("chest", "vault");
		MAP.put("pv", "vault");
	}

	/** /f c a -> /f chat ally, etc. */
	private static final Map<String, String> CHAT = Map.of(
			"a", "ally", "f", "faction", "p", "public", "t", "truce", "m", "mod", "pub", "public", "fac", "faction");

	/** /f tnt bal -> /f tnt info */
	private static final Map<String, String> TNT = Map.of("bal", "info", "balance", "info", "d", "deposit", "w", "withdraw", "add", "deposit", "take", "withdraw");

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onCommand(PlayerCommandPreprocessEvent e) {
		String msg = e.getMessage();
		String[] parts = msg.substring(1).split(" ", -1);
		if (parts.length < 2) return;
		String root = parts[0].toLowerCase(Locale.ROOT);
		if (!(root.equals("f") || root.equals("fac") || root.equals("faction") || root.equals("factions"))) return;

		String sub = parts[1].toLowerCase(Locale.ROOT);
		String rest = parts.length > 2 ? " " + String.join(" ", java.util.Arrays.copyOfRange(parts, 2, parts.length)) : "";

		if (sub.equals("c") || sub.equals("chat")) {
			if (parts.length > 2) {
				String mode = CHAT.get(parts[2].toLowerCase(Locale.ROOT));
				if (mode != null) rest = " " + mode + (parts.length > 3 ? " " + String.join(" ", java.util.Arrays.copyOfRange(parts, 3, parts.length)) : "");
			}
			e.setMessage("/f chat" + rest);
			return;
		}
		if (sub.equals("tnt") && parts.length > 2) {
			String mapped = TNT.get(parts[2].toLowerCase(Locale.ROOT));
			if (mapped != null) {
				e.setMessage("/f tnt " + mapped + (parts.length > 3 ? " " + String.join(" ", java.util.Arrays.copyOfRange(parts, 3, parts.length)) : ""));
			}
			return;
		}
		String target = MAP.get(sub);
		if (target != null) e.setMessage("/f " + target + rest);
	}
}
