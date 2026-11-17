package net.lag.factions;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.bukkit.entity.Player;

import dev.kitteh.factions.FPlayer;
import dev.kitteh.factions.Faction;
import dev.kitteh.factions.permissible.Relation;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;

/** /f who: a clean faction card in chat instead of FactionsUUID's wall of text. */
final class Who {
	private final LagFactions plugin;

	Who(LagFactions plugin) {
		this.plugin = plugin;
	}

	static String relColor(Relation r) {
		if (r == null) return "<white>";
		return switch (r) {
			case MEMBER -> Ui.GREEN;
			case ENEMY -> Ui.RED;
			case ALLY, TRUCE -> Ui.PURPLE;
			default -> "<white>";
		};
	}

	static String relName(Relation r) {
		if (r == null) return "Neutral";
		return switch (r) {
			case MEMBER -> "Your faction";
			case ENEMY -> "Enemy";
			case ALLY -> "Ally";
			case TRUCE -> "Truce";
			default -> "Neutral";
		};
	}

	void show(Player viewer, Faction f) {
		FPlayer me = F.fp(viewer);
		Relation rel = me != null && me.hasFaction() ? me.faction().relationTo(f) : Relation.NEUTRAL;
		String rc = relColor(rel);
		String bar = Ui.GRAD + "<strikethrough>                                                              </strikethrough></gradient>";
		List<FPlayer> members = new ArrayList<>(f.members());
		members.sort(Comparator.comparingInt((FPlayer m) -> m.role() == null ? 99 : m.role().ordinal())
				.thenComparing((FPlayer m) -> !m.isOnline()).thenComparing(FPlayer::name, String.CASE_INSENSITIVE_ORDER));
		long online = members.stream().filter(FPlayer::isOnline).count();
		FPlayer leader = f.admin();

		viewer.sendMessage(Component.empty());
		viewer.sendMessage(Ui.mm(bar));
		viewer.sendMessage(Ui.mm(" " + rc + "<bold>" + Ui.esc(f.tag()).toUpperCase() + "</bold> <dark_gray>• " + rc + relName(rel)
				+ (f.description() == null || f.description().isBlank() ? "" : "\n " + Ui.DIM + "<italic>" + Ui.esc(f.description()))));
		viewer.sendMessage(Component.empty());
		viewer.sendMessage(Ui.mm(" " + Ui.LABEL + "Leader <white>" + (leader == null ? "-" : Ui.esc(leader.name()))
				+ "   " + Ui.DIM + "|   " + Ui.LABEL + "Members <white>" + members.size() + Ui.DIM + "/" + "<white>" + f.memberLimit()
				+ Ui.LABEL + " (" + Ui.GREEN + online + Ui.LABEL + " online)"));
		viewer.sendMessage(Ui.mm(" " + Ui.LABEL + "Power <white>" + f.power() + Ui.DIM + "/" + "<white>" + f.powerMax()
				+ "   " + Ui.DIM + "|   " + Ui.LABEL + "Land <white>" + f.claimCount()
				+ "   " + Ui.DIM + "|   " + Ui.LABEL + "TNT <white>" + String.format("%,d", f.tntBank())));
		if (plugin.shields != null)
			viewer.sendMessage(Ui.mm(" " + Ui.LABEL + "Shield " + plugin.shields.status(f) + "   " + Ui.DIM + "|   " + Ui.LABEL + "Window <white>"
					+ plugin.shields.windowText(plugin.shields.startHour(f))));
		try {
			viewer.sendMessage(Ui.mm(" " + Ui.LABEL + "Founded <white>" + DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.of("America/New_York")).format(f.founded())));
		} catch (Exception ignored) { }
		// members: one line, hover for role + power, click to message
		Component line = Ui.mm(" " + Ui.LABEL + "Roster ");
		boolean first = true;
		for (FPlayer m : members) {
			if (!first) line = line.append(Ui.mm(Ui.DIM + ", "));
			first = false;
			String col = m.isOnline() ? Ui.GREEN : "<gray>";
			Component name = Ui.mm(col + F.roleStars(m.role()) + Ui.esc(m.name()))
					.hoverEvent(HoverEvent.showText(Ui.mm("<white><bold>" + Ui.esc(m.name()) + "</bold>\n" + Ui.LABEL + F.roleName(m.role())
							+ "\n" + Ui.LABEL + "Power <white>" + (int) m.power() + "/" + (int) m.powerMax()
							+ "\n" + (m.isOnline() ? Ui.GREEN + "Online" : Ui.DIM + "Offline"))))
					.clickEvent(ClickEvent.suggestCommand("/msg " + m.name() + " "));
			line = line.append(name);
		}
		viewer.sendMessage(line);
		viewer.sendMessage(Ui.mm(bar));
	}
}
