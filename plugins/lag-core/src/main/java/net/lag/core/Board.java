package net.lag.core;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;

/** Sidebar: network name, realm, players online, ping. Lines are fixed-score entries with custom display names. */
final class Board implements Listener {
	private final LagCore plugin;

	Board(LagCore plugin) {
		this.plugin = plugin;
	}

	@EventHandler
	public void onJoin(PlayerJoinEvent e) {
		setup(e.getPlayer());
	}

	private void setup(Player p) {
		Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
		Objective o = sb.registerNewObjective("lag", Criteria.DUMMY,
				LagCore.mm("<gradient:#38E0FF:#B040FF><bold>" + plugin.networkName() + "</bold></gradient>"));
		o.setDisplaySlot(DisplaySlot.SIDEBAR);
		o.numberFormat(NumberFormat.blank());
		p.setScoreboard(sb);
		refresh(p);
	}

	void refreshAll() {
		for (Player p : Bukkit.getOnlinePlayers()) refresh(p);
	}

	private void refresh(Player p) {
		Objective o = p.getScoreboard().getObjective("lag");
		if (o == null) {
			setup(p);
			return;
		}
		String phase = String.format(java.util.Locale.ROOT, "%.2f", ((System.currentTimeMillis() / 1000.0 * 0.25) % 2.0) - 1.0);
		String grad = "<gradient:#38E0FF:#B040FF:#38E0FF:" + phase + ">";
		Pack pk = plugin.pack;
		boolean art = pk != null && pk.has(p);
		o.displayName(LagCore.mm(art ? Pack.glyph(Pack.LOGO_LINE) : grad + "<bold>" + plugin.networkName().toUpperCase() + "</bold></gradient>"));
		String role = plugin.getConfig().getString("role", "hub");
		String realm = role.substring(0, 1).toUpperCase() + role.substring(1);
		String head = "<gradient:#38E0FF:#B040FF><bold>";
		String bullet = "  <dark_gray>▸ ";
		int ping = p.getPing();
		String pingCol = ping < 80 ? "<#7CFF6B>" : ping < 150 ? "<yellow>" : "<#FF4D6D>";
		String ic = (art ? Pack.glyph(Pack.I_STAR) : "<#38E0FF>✦") + " ";
		String ip = (art ? Pack.glyph(Pack.I_PLAYERS) : "<#B040FF>✦") + " ";
		String it = (art ? Pack.glyph(Pack.I_BOLT) : "<#FF4DD2>✦") + " ";
		java.util.List<Component> lines = new java.util.ArrayList<>();
		lines.add(LagCore.mm("<dark_gray><strikethrough>                      </strikethrough>"));
		lines.add(LagCore.mm(ic + head + "YOU</bold></gradient>"));
		lines.add(LagCore.mm(bullet + "<gray>Rank " + rank(p)));
		lines.add(LagCore.mm(bullet + "<gray>Ping " + pingCol + ping + "ms"));
		lines.add(Component.text(" "));
		lines.add(LagCore.mm(ip + head + "NETWORK</bold></gradient>"));
		lines.add(LagCore.mm(bullet + "<gray>Online <white>" + plugin.count("ALL")));
		lines.add(LagCore.mm(bullet + "<gray>Realm <white>" + realm));
		var realms = plugin.section("realms");
		if ("hub".equalsIgnoreCase(role) && realms != null) {
			lines.add(Component.text("  "));
			lines.add(LagCore.mm(it + head + "REALMS</bold></gradient>"));
			for (String k : realms.getKeys(false)) {
				String server = realms.getString(k + ".server", k);
				lines.add(LagCore.mm(bullet + "<gray>" + k.substring(0, 1).toUpperCase() + k.substring(1) + " <white>" + plugin.count(server)));
			}
		}
		lines.add(Component.text("   "));
		lines.add(LagCore.mm(plugin.getConfig().getString("scoreboard.footer", "<gradient:#38E0FF:#B040FF>play.relapsedmc.net</gradient>")));
		int score = lines.size();
		for (int n = 1; n <= 15; n++) {
			if (n > score) o.getScoreboard().resetScores("l" + n);
		}
		for (Component c : lines) line(o, score--, c);
		// tab list
		p.sendPlayerListHeaderAndFooter(
				LagCore.mm("\n" + (art ? Pack.glyph(Pack.LOGO_TAB) + "\n\n\n" : grad + "<bold>" + plugin.networkName().toUpperCase() + "</bold></gradient>\n")
						+ "<gray>You're on <white>" + realm + "\n"),
				LagCore.mm("\n<gray>Online <white>" + plugin.count("ALL") + " <dark_gray>• <gray>Ping <white>" + p.getPing() + "ms\n"
						+ "<dark_gray>Skyblock • Factions • SMP • 1.8 Cannons\n"));
	}

	/** First matching LuckPerms group (group.<name> permission) from config "ranks"; ops count as the first rank. */
	private String rank(Player p) {
		String t = plugin.ranks == null ? "" : plugin.ranks.tag(p);
		return t.isEmpty() ? "<gray>Member" : t;
	}

	private void line(Objective o, int score, Component text) {
		var s = o.getScore("l" + score);
		s.setScore(score);
		s.customName(text);
	}
}
