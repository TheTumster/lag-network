package net.lag.factions;

import java.util.LinkedHashMap;
import java.util.Map;

import org.bukkit.entity.Player;

import dev.kitteh.factions.FLocation;
import dev.kitteh.factions.FPlayer;
import dev.kitteh.factions.Faction;
import dev.kitteh.factions.permissible.Relation;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Classic /f map: gold "____.[ (x,z) faction ].____" header, gray "-" wilderness, "+" you, and each faction's land as
 * its own symbol ("\" for yours in green; others get "/", "#", "$"... in their relation colour), with a symbol legend
 * underneath. Sent as one chat message so chat mods can't stack the rows. Hover a chunk for info; click wilderness
 * near you to claim it (FactionsUUID's normal rules).
 */
final class FMap {
	static final int W = Classic.WIDTH, H = 17, CLICK_RADIUS = 6;
	private static final String SYMBOLS = "/#$%&=?!ABCDEFGHJKLMNOPQRSTUVWXYZ";
	private final LagFactions plugin;

	FMap(LagFactions plugin) {
		this.plugin = plugin;
	}

	void show(Player p) {
		FPlayer me = F.fp(p);
		Faction mine = me != null && me.hasFaction() ? me.faction() : null;
		String world = p.getWorld().getName();
		int cx = p.getLocation().getBlockX() >> 4, cz = p.getLocation().getBlockZ() >> 4;
		Faction here = F.at(world, cx, cz);
		String hereName = here == null || here.isWilderness() ? "Wilderness" : here.isSafeZone() ? "SafeZone" : here.isWarZone() ? "WarZone" : here.tag();
		String hereCol = here == null || here.isWilderness() ? "<dark_green>" : colour(here, mine);

		Map<Integer, Character> symbol = new LinkedHashMap<>();
		Map<Integer, Faction> legend = new LinkedHashMap<>();
		int next = 0;

		TextComponent.Builder out = Component.text();
		String title = "(" + cx + "," + cz + ") " + hereName;
		out.append(Ui.mm(Classic.header(hereCol + Ui.esc("(" + cx + "," + cz + ") ") + Ui.esc(hereName), title))).append(Component.newline());
		for (int row = 0; row < H; row++) {
			int dz = row - H / 2;
			String runCol = null;
			StringBuilder run = new StringBuilder();
			for (int col = 0; col < W; col++) {
				int dx = col - W / 2;
				int x = cx + dx, z = cz + dz;
				Faction f = F.at(world, x, z);
				boolean centre = dx == 0 && dz == 0;
				boolean wild = f == null || f.isWilderness();
				String col0;
				char ch;
				if (centre) { col0 = "<aqua>"; ch = '+'; }
				else if (wild) { col0 = "<gray>"; ch = '-'; }
				else if (f.isSafeZone()) { col0 = "<gold>"; ch = '+'; }
				else if (f.isWarZone()) { col0 = "<dark_red>"; ch = '+'; }
				else if (mine != null && f.id() == mine.id()) { col0 = "<green>"; ch = '\\'; }
				else {
					Character s = symbol.get(f.id());
					if (s == null) {
						s = SYMBOLS.charAt(Math.min(next++, SYMBOLS.length() - 1));
						symbol.put(f.id(), s);
						legend.put(f.id(), f);
					}
					col0 = colour(f, mine);
					ch = s;
				}
				boolean near = Math.abs(dx) <= CLICK_RADIUS && Math.abs(dz) <= CLICK_RADIUS;
				boolean interactive = centre || !wild || (near && mine != null);
				if (!interactive) {
					if (!col0.equals(runCol)) {
						flush(out, runCol, run);
						runCol = col0;
					}
					run.append(ch);
					continue;
				}
				flush(out, runCol, run);
				runCol = null;
				Component cell = Component.text(String.valueOf(ch), tc(col0))
						.hoverEvent(HoverEvent.showText(Ui.mm(hover(f, mine, x, z, centre, !centre && near && wild && mine != null))));
				if (!centre && near && wild && mine != null) cell = cell.clickEvent(ClickEvent.runCommand("/f mapclaim " + x + " " + z));
				out.append(cell);
			}
			flush(out, runCol, run);
			out.append(Component.newline());
		}
		// legend: \ for your faction, then everyone else on the map
		TextComponent.Builder lg = Component.text();
		boolean any = false;
		if (mine != null) {
			lg.append(Component.text("\\: " + mine.tag(), NamedTextColor.GREEN));
			any = true;
		}
		for (var e : legend.entrySet()) {
			if (any) lg.append(Component.text(", ", NamedTextColor.GRAY));
			any = true;
			Faction f = e.getValue();
			lg.append(Component.text(symbol.get(e.getKey()) + ": " + f.tag(), tc(colour(f, mine))));
		}
		if (!any) lg.append(Component.text("No factions nearby", NamedTextColor.GRAY));
		out.append(lg.build());
		p.sendMessage(out.build());
	}

	private static void flush(TextComponent.Builder line, String col, StringBuilder run) {
		if (col != null && run.length() > 0) line.append(Component.text(run.toString(), tc(col)));
		run.setLength(0);
	}

	private static NamedTextColor tc(String tag) {
		return switch (tag) {
			case "<aqua>" -> NamedTextColor.AQUA;
			case "<gold>" -> NamedTextColor.GOLD;
			case "<dark_red>" -> NamedTextColor.DARK_RED;
			case "<green>" -> NamedTextColor.GREEN;
			case "<red>" -> NamedTextColor.RED;
			case "<white>" -> NamedTextColor.WHITE;
			default -> NamedTextColor.GRAY;
		};
	}

	private String colour(Faction f, Faction mine) {
		if (f == null || f.isWilderness()) return "<gray>";
		if (f.isSafeZone()) return "<gold>";
		if (f.isWarZone()) return "<dark_red>";
		if (mine != null && f.id() == mine.id()) return "<green>";
		Relation r = mine == null ? Relation.NEUTRAL : mine.relationTo(f);
		return r == Relation.ENEMY ? "<red>" : "<white>";
	}

	private String hover(Faction f, Faction mine, int x, int z, boolean centre, boolean claimable) {
		String pos = "<gray>(" + x + ", " + z + ")";
		String head = centre ? "<aqua>You are here\n" : "";
		if (f == null || f.isWilderness()) return head + "<dark_green>Wilderness " + pos + (claimable ? "\n<yellow>Click to claim" : "");
		if (f.isSafeZone()) return head + "<gold>SafeZone " + pos;
		if (f.isWarZone()) return head + "<dark_red>WarZone " + pos;
		return head + colour(f, mine) + Ui.esc(f.tag()) + " " + pos + "\n<gold>Land: <yellow>" + f.claimCount() + " <gold>Members: <yellow>" + f.members().size()
				+ "\n" + plugin.shields.status(f);
	}

	/** /f mapclaim x z (from a map click): claim through FactionsUUID's normal checks. */
	void claim(Player p, int x, int z) {
		FPlayer me = F.fp(p);
		if (me == null || !me.hasFaction()) { Ui.err(p, "You need a faction."); return; }
		int cx = p.getLocation().getBlockX() >> 4, cz = p.getLocation().getBlockZ() >> 4;
		if (Math.abs(x - cx) > CLICK_RADIUS || Math.abs(z - cz) > CLICK_RADIUS) { Ui.err(p, "Walk closer to claim that chunk."); return; }
		boolean ok = me.attemptClaim(me.faction(), new FLocation(p.getWorld().getName(), x, z), true);
		if (ok) show(p);
	}
}
