package net.lag.core;

import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Ranks: "OWNER Name" in chat, the tab list and above heads, on every server.
 * A player's rank = rank-players.<name> in config, else the first LuckPerms group they're in (group.<rank> permission).
 * How each rank looks = ranks.<rank> (MiniMessage). Chat: "<rank> Name » message".
 */
final class Ranks implements Listener {
	private final LagCore plugin;

	Ranks(LagCore plugin) {
		this.plugin = plugin;
		Bukkit.getScheduler().runTaskTimer(plugin, this::applyAll, 40L, 40L);
	}

	/** Rank key (owner, admin, ...) or null for members. */
	String rankOf(Player p) {
		String forced = plugin.getConfig().getString("rank-players." + p.getName());
		if (forced == null) forced = plugin.getConfig().getString("rank-players." + p.getUniqueId());
		if (forced != null) return forced.toLowerCase(Locale.ROOT);
		var sec = plugin.getConfig().getConfigurationSection("ranks");
		if (sec != null) {
			for (String g : sec.getKeys(false)) {
				if (p.isPermissionSet("group." + g) && p.hasPermission("group." + g)) return g;
			}
		}
		return null;
	}

	/** The rank tag (MiniMessage), empty for members. */
	String tag(Player p) {
		String r = rankOf(p);
		if (r == null) return "";
		// getString(path) (no default argument) so the plugin's built-in config is used when the file has no ranks section
		String t = plugin.getConfig().getString("ranks." + r);
		return t != null ? t : "<bold>" + r.toUpperCase(Locale.ROOT) + "</bold>";
	}

	private int weight(String rank) {
		var sec = plugin.getConfig().getConfigurationSection("ranks");
		if (sec == null || rank == null) return 99;
		int i = 0;
		for (String k : sec.getKeys(false)) {
			if (k.equals(rank)) return i;
			i++;
		}
		return 99;
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onJoin(PlayerJoinEvent e) {
		Bukkit.getScheduler().runTaskLater(plugin, this::applyAll, 5L);
	}

	/** Tab names + nametag teams on every player's scoreboard (each player has their own sidebar board). */
	void applyAll() {
		for (Player p : Bukkit.getOnlinePlayers()) {
			String t = tag(p);
			p.playerListName(LagCore.mm((t.isEmpty() ? "" : t + " ") + "<white>" + p.getName()));
		}
		for (Player viewer : Bukkit.getOnlinePlayers()) {
			Scoreboard sb = viewer.getScoreboard();
			for (Player p : Bukkit.getOnlinePlayers()) {
				String r = rankOf(p);
				// team names sort the tab list: higher ranks first
				String name = String.format(Locale.ROOT, "r%02d_%s", weight(r), r == null ? "member" : r);
				Team team = sb.getTeam(name);
				if (team == null) {
					team = sb.registerNewTeam(name);
					String t = tag(p);
					if (!t.isEmpty()) team.prefix(LagCore.mm(t + " "));
					team.color(NamedTextColor.WHITE);
				}
				if (!team.hasEntry(p.getName())) team.addEntry(p.getName());
			}
		}
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onChat(AsyncChatEvent e) {
		if (!plugin.getConfig().getBoolean("chat.enabled", true)) return;
		Player p = e.getPlayer();
		String t = tag(p);
		Component head = LagCore.mm((t.isEmpty() ? "" : t + " ") + (t.isEmpty() ? "<gray>" : "<white>") + p.getName() + " <dark_gray>» ");
		boolean staff = rankOf(p) != null;
		e.renderer((source, displayName, message, viewer) -> head.append(message.color(staff ? NamedTextColor.WHITE : NamedTextColor.GRAY)));
	}
}
