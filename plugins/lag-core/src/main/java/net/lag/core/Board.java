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
		String role = plugin.getConfig().getString("role", "hub");
		String realm = role.substring(0, 1).toUpperCase() + role.substring(1);
		line(o, 6, Component.empty());
		line(o, 5, LagCore.mm("<gray>Realm <white>" + realm));
		line(o, 4, LagCore.mm("<gray>Online <white>" + plugin.count("ALL")));
		line(o, 3, LagCore.mm("<gray>Ping <white>" + p.getPing() + "ms"));
		line(o, 2, Component.text(" "));
		line(o, 1, LagCore.mm(plugin.getConfig().getString("scoreboard.footer", "<gradient:#38E0FF:#B040FF>RelapsedMC</gradient>")));
	}

	private void line(Objective o, int score, Component text) {
		var s = o.getScore("l" + score);
		s.setScore(score);
		s.customName(text);
	}
}
