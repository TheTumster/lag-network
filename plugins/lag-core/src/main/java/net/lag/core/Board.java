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
				LagCore.mm("<gradient:#FF5C5C:#FFA19A><bold>" + plugin.networkName() + "</bold></gradient>"));
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
		String grad = "<gradient:#FF5C5C:#FFA19A:#FF5C5C:" + phase + ">";
		Pack pk = plugin.pack;
		boolean art = pk != null && pk.has(p);
		o.displayName(LagCore.mm(art ? Pack.glyph(Pack.LOGO_LINE) : grad + "<bold>" + plugin.networkName() + "</bold></gradient>"));
		String role = plugin.getConfig().getString("role", "hub");
		String realm = role.substring(0, 1).toUpperCase() + role.substring(1);
		// premium layout: date + realm strip, small-caps section headers, gray labels / white values, gradient IP
		String L = "<#9AA3B8>", V = "<white><bold>", D = "<#5E6478>";
		String pad = "  ";
		int ping = p.getPing();
		String pingCol = ping < 80 ? "<#5CFF8A><bold>" : ping < 150 ? "<#FFAA45><bold>" : "<#FF4F64><bold>";
		String sIcon = art ? Pack.glyph(Pack.I_STAR) + " " : "";
		String pIcon = art ? Pack.glyph(Pack.I_PLAYERS) + " " : "";
		String bIcon = art ? Pack.glyph(Pack.I_BOLT) + " " : "";
		String tIcon = art ? Pack.glyph(Pack.I_TNT) + " " : "";
		java.util.List<Component> lines = new java.util.ArrayList<>();
		boolean hubRole = "hub".equalsIgnoreCase(role);
		lines.add(LagCore.mm(D + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("MM/dd/yy"))
				+ " <dark_gray>• " + D + sc(realm) + (hubRole ? " <dark_gray>• " + V + plugin.count("ALL") + " " + D + sc("online") : "")));
		lines.add(Component.empty());
		lines.add(LagCore.mm(sIcon + "<#FF6B6B><bold><u>" + sc("you")));
		lines.add(LagCore.mm(pad + L + sc("rank") + " " + rank(p)));
		boolean facBoard = "factions".equalsIgnoreCase(role) && Papi.present();
		if (!facBoard) lines.add(LagCore.mm(pad + L + sc("ping") + " " + pingCol + ping + "ms")); // factions: room for the shield line
		lines.add(Component.text(" "));
		if (facBoard) {
			String f = Papi.apply(p, "%factionsuuid_faction_name%").trim();
			lines.add(LagCore.mm(tIcon + "<#FF4F64><bold><u>" + sc("faction")));
			if (f.isEmpty() || f.toLowerCase(java.util.Locale.ROOT).contains("wilderness")) {
				lines.add(LagCore.mm(pad + L + sc("none") + " " + D + "• " + V + "/f create"));
				lines.add(LagCore.mm(pad + L + sc("power") + " " + V + Papi.apply(p, "%factionsuuid_player_power%")));
			} else {
				lines.add(LagCore.mm(pad + V + "<bold>" + LagCore.esc(f)));
				lines.add(LagCore.mm(pad + L + sc("online") + " " + V + Papi.apply(p, "%factionsuuid_faction_online%")
						+ D + "/" + V + Papi.apply(p, "%factionsuuid_faction_size%")
						+ " " + D + "• " + L + sc("land") + " " + V + Papi.apply(p, "%factionsuuid_faction_claims%")));
				lines.add(LagCore.mm(pad + L + sc("power") + " " + V + Papi.apply(p, "%factionsuuid_faction_power%")
						+ " " + D + "• " + L + sc("tnt") + " " + V + Papi.apply(p, "%factionsuuid_faction_tnt_balance%")));
				String shield = MapInfo.shield(p);
				if (!shield.isEmpty()) lines.add(LagCore.mm(pad + shield));
			}
			lines.add(Component.text("  "));
			lines.add(LagCore.mm(bIcon + "<#A855FF><bold><u>" + sc(plugin.getConfig().getString("map.name", "RELAPSE #1"))));
			lines.add(LagCore.mm(pad + MapInfo.countdown(plugin)));
		} else {
			var realms = plugin.section("realms");
			if (hubRole && realms != null) {
				lines.add(LagCore.mm(bIcon + "<#A855FF><bold><u>" + sc("realms")));
				for (String k : realms.getKeys(false)) {
					String server = realms.getString(k + ".server", k);
					lines.add(LagCore.mm(pad + L + sc(k) + " " + V + plugin.count(server)));
				}
			} else {
				lines.add(LagCore.mm(pIcon + "<#A855FF><bold><u>" + sc("network")));
				lines.add(LagCore.mm(pad + L + sc("online") + " " + V + plugin.count("ALL")));
				lines.add(LagCore.mm(pad + L + sc("realm") + " " + V + realm));
			}
		}
		lines.add(Component.text("   "));
		String foot = plugin.getConfig().getString("scoreboard.footer", "");
		lines.add(LagCore.mm(foot.toLowerCase().contains("play.") ? foot : grad + "play.RelapsedMC.com</gradient>"));
		int score = lines.size();
		for (int n = 1; n <= 15; n++) {
			if (n > score) o.getScoreboard().resetScores("l" + n);
		}
		for (Component c : lines) line(o, score--, c);
		// tab list: logo, realm strip; footer with live stats, IP and links
		String bar = grad + "<strikethrough>                                        </strikethrough></gradient>";
		p.sendPlayerListHeaderAndFooter(
				LagCore.mm("\n" + (art ? Pack.glyph(Pack.LOGO_TAB) + "\n\n\n" : grad + "<bold>" + plugin.networkName() + "</bold></gradient>\n")
						+ "<#9AA3B8>" + sc("you're on") + " <white><bold><u>" + sc(realm) + "</bold>\n" + bar),
				LagCore.mm(bar + "\n<#9AA3B8>" + sc("online") + " <white><bold>" + plugin.count("ALL")
						+ "</bold>   <#2B3048>|   <#9AA3B8>" + sc("ping") + " " + pingCol + ping + "ms"
						+ "   <#2B3048>|   <#9AA3B8>" + sc("realm") + " <white><bold>" + realm + "</bold>"
						+ "\n\n" + grad + "<bold>play.RelapsedMC.com</bold></gradient>"
						+ "\n<#5CFF8A>" + sc("skyblock") + " <#2B3048>•</#2B3048> <#FF4F64>" + sc("factions") + " <#2B3048>•</#2B3048> <#45C8FF>" + sc("smp") + " <#2B3048>•</#2B3048> <#FFAA45>" + sc("1.8 cannons") + "\n"));
	}

	/** First matching LuckPerms group (group.<name> permission) from config "ranks"; ops count as the first rank. */
	private String rank(Player p) {
		String t = plugin.ranks == null ? "" : plugin.ranks.tag(p);
		return t.isEmpty() ? "<gray>Member" : t;
	}

	private static final String SC_FROM = "abcdefghijklmnopqrstuvwxyz", SC_TO = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";

	/** Small caps: "Rank" -> "ʀᴀɴᴋ" (digits and symbols unchanged). */
	static String sc(String s) {
		StringBuilder b = new StringBuilder(s.length());
		for (char c : s.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
			int i = SC_FROM.indexOf(c);
			b.append(i < 0 ? c : SC_TO.charAt(i));
		}
		return b.toString();
	}

	private void line(Objective o, int score, Component text) {
		var s = o.getScore("l" + score);
		s.setScore(score);
		s.customName(text);
	}
}
