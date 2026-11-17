package net.lag.factions;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.entity.Player;

import dev.kitteh.factions.FLocation;
import dev.kitteh.factions.FPlayer;
import dev.kitteh.factions.Faction;
import dev.kitteh.factions.permissible.Relation;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;

/**
 * /f map: a square chunk grid with an info panel beside it, sent as ONE chat message (so chat mods can't stack
 * identical rows into "[x10]"). North is up. You = cyan, your land = green, enemies = red, neutral = white,
 * safe zone = gold, war zone = dark red, wilderness = dark. Hover a chunk for details; click wilderness near you
 * to claim it (FactionsUUID's normal claim rules).
 */
final class FMap {
	static final int W = 21, H = 11, CLICK_RADIUS = 6;
	private static final String CELL = "█";
	private static final String WILD = "<#353A46>";
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

		// the info panel shown to the right of the grid, one entry per grid row
		List<String> panel = new ArrayList<>();
		panel.add(Ui.DIM + "      N");
		panel.add(Ui.DIM + "   W " + Ui.CYAN + "✦" + Ui.DIM + " E    " + Ui.LABEL + "facing <white>" + facing(p.getLocation().getYaw()));
		panel.add(Ui.DIM + "      S");
		panel.add("");
		panel.add(Ui.LABEL + "Here  " + here(here, mine));
		panel.add(Ui.LABEL + "Chunk <white>" + cx + Ui.DIM + ", <white>" + cz);
		if (mine != null) {
			panel.add(Ui.LABEL + "Land  <white>" + mine.claimCount() + Ui.DIM + " • " + Ui.LABEL + "Power <white>" + mine.power() + Ui.DIM + "/" + "<white>" + mine.powerMax());
			panel.add(Ui.LABEL + "You   " + plugin.shields.status(mine));
		} else {
			panel.add(Ui.LABEL + "No faction: <white>/f create");
			panel.add("");
		}
		panel.add("");
		panel.add(Ui.GREEN + CELL + Ui.LABEL + " yours  " + Ui.RED + CELL + Ui.LABEL + " enemy  <white>" + CELL + Ui.LABEL + " neutral");
		panel.add(Ui.GOLD + CELL + Ui.LABEL + " safe  <dark_red>" + CELL + Ui.LABEL + " war  " + WILD + CELL + Ui.LABEL + " wild  " + Ui.CYAN + CELL + Ui.LABEL + " you");

		TextComponent.Builder out = Component.text();
		String bar = Ui.GRAD + "<strikethrough>                                                              </strikethrough></gradient>";
		out.append(Ui.mm(bar)).append(Component.newline());
		out.append(Ui.mm(" " + Ui.GRAD + "<bold>FACTION MAP</bold></gradient> " + Ui.DIM + "• " + Ui.LABEL + world)).append(Component.newline());
		for (int row = 0; row < H; row++) {
			int dz = row - H / 2;
			out.append(Component.text(" "));
			String runCol = null;
			StringBuilder run = new StringBuilder();
			for (int col = 0; col < W; col++) {
				int dx = col - W / 2;
				int x = cx + dx, z = cz + dz;
				Faction f = F.at(world, x, z);
				boolean centre = dx == 0 && dz == 0;
				boolean near = Math.abs(dx) <= CLICK_RADIUS && Math.abs(dz) <= CLICK_RADIUS;
				String color = centre ? Ui.CYAN : cellColor(f, mine);
				boolean wild = f == null || f.isWilderness();
				boolean interactive = centre || !wild || (near && mine != null);
				if (!interactive) {
					if (!color.equals(runCol)) {
						flush(out, runCol, run);
						runCol = color;
					}
					run.append(CELL);
					continue;
				}
				flush(out, runCol, run);
				runCol = null;
				Component cell = Ui.mm(color + CELL).hoverEvent(HoverEvent.showText(Ui.mm(hover(f, mine, x, z, centre, !centre && near && wild && mine != null))));
				if (!centre && near && wild && mine != null) cell = cell.clickEvent(ClickEvent.runCommand("/f mapclaim " + x + " " + z));
				else if (f != null && f.isNormal()) cell = cell.clickEvent(ClickEvent.runCommand("/f who " + f.tag()));
				out.append(cell);
			}
			flush(out, runCol, run);
			out.append(Ui.mm("  " + panel.get(row)));
			out.append(Component.newline());
		}
		out.append(Ui.mm(" " + Ui.DIM + "Hover a chunk for info • click wilderness near you to claim")).append(Component.newline());
		out.append(Ui.mm(bar));
		p.sendMessage(out.build());
	}

	private static void flush(TextComponent.Builder line, String col, StringBuilder run) {
		if (col != null && run.length() > 0) line.append(Ui.mm(col + run));
		run.setLength(0);
	}

	private String cellColor(Faction f, Faction mine) {
		if (f == null || f.isWilderness()) return WILD;
		if (f.isSafeZone()) return Ui.GOLD;
		if (f.isWarZone()) return "<dark_red>";
		if (mine != null && f.id() == mine.id()) return Ui.GREEN;
		Relation r = mine == null ? Relation.NEUTRAL : mine.relationTo(f);
		return r == Relation.ENEMY ? Ui.RED : "<white>";
	}

	private String here(Faction f, Faction mine) {
		if (f == null || f.isWilderness()) return Ui.DIM + "Wilderness";
		if (f.isSafeZone()) return Ui.GOLD + "Safe Zone";
		if (f.isWarZone()) return "<dark_red>War Zone";
		return cellColor(f, mine) + Ui.esc(f.tag());
	}

	private String hover(Faction f, Faction mine, int x, int z, boolean centre, boolean claimable) {
		String pos = Ui.DIM + "Chunk " + x + ", " + z;
		String head = centre ? Ui.CYAN + "<bold>You are here</bold>\n" : "";
		if (f == null || f.isWilderness())
			return head + Ui.LABEL + "Wilderness\n" + pos + (claimable ? "\n\n" + Ui.CYAN + "Click to claim" : "");
		if (f.isSafeZone()) return head + Ui.GOLD + "Safe Zone\n" + pos;
		if (f.isWarZone()) return head + "<dark_red>War Zone\n" + pos;
		return head + cellColor(f, mine) + "<bold>" + Ui.esc(f.tag()) + "</bold>\n" + Ui.LABEL + Who.relName(mine == null ? Relation.NEUTRAL : mine.relationTo(f))
				+ "\n" + Ui.LABEL + "Members <white>" + f.members().size() + Ui.LABEL + " • Land <white>" + f.claimCount()
				+ "\n" + plugin.shields.status(f) + "\n" + pos + "\n\n" + Ui.CYAN + "Click for their profile";
	}

	/** Compass direction the player faces. */
	private static String facing(float yaw) {
		float y = ((yaw % 360) + 360) % 360; // 0 = south, 90 = west, 180 = north, 270 = east
		String[] a = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
		return a[Math.round(y / 45f) % 8];
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
