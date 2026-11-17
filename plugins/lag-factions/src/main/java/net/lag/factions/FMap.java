package net.lag.factions;

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
 * /f map: a clean chunk grid in chat, north up. Your faction green, enemies red, neutrals white, safe zone gold,
 * war zone dark red, wilderness dark. You're the cyan arrow. Hover a chunk for who owns it and its shield;
 * click wilderness near you to claim it (goes through FactionsUUID's normal claim rules).
 */
final class FMap {
	static final int W = 31, H = 13, CLICK_RADIUS = 6;
	private final LagFactions plugin;

	FMap(LagFactions plugin) {
		this.plugin = plugin;
	}

	private static final String CELL = "■";

	void show(Player p) {
		FPlayer me = F.fp(p);
		Faction mine = me != null && me.hasFaction() ? me.faction() : null;
		String world = p.getWorld().getName();
		int cx = p.getLocation().getBlockX() >> 4, cz = p.getLocation().getBlockZ() >> 4;
		String facing = facing(p.getLocation().getYaw());

		String bar = Ui.GRAD + "<strikethrough>                                                              </strikethrough></gradient>";
		p.sendMessage(Component.empty());
		p.sendMessage(Ui.mm(bar));
		p.sendMessage(Ui.mm(" " + Ui.GRAD + "<bold>MAP</bold></gradient> " + Ui.DIM + "• " + Ui.LABEL + world + " " + Ui.DIM + "(" + "<white>" + cx + Ui.DIM + ", <white>" + cz + Ui.DIM + ")"
				+ "   " + Ui.LABEL + "facing <white>" + facing + "   " + Ui.LABEL + "here: " + here(F.at(world, cx, cz), mine)));
		for (int row = 0; row < H; row++) {
			int dz = row - H / 2;
			TextComponent.Builder line = Component.text().append(Component.text(" "));
			// merge runs of identical, non-interactive cells to keep the packet small
			String runCol = null;
			StringBuilder run = new StringBuilder();
			for (int col = 0; col < W; col++) {
				int dx = col - W / 2;
				int x = cx + dx, z = cz + dz;
				Faction f = F.at(world, x, z);
				boolean centre = dx == 0 && dz == 0;
				boolean near = Math.abs(dx) <= CLICK_RADIUS && Math.abs(dz) <= CLICK_RADIUS;
				String color;
				if (centre) color = Ui.CYAN;
				else color = cellColor(f, mine);
				boolean interactive = centre || near || (f != null && !f.isWilderness());
				if (!interactive) {
					if (!color.equals(runCol)) {
						flush(line, runCol, run);
						runCol = color;
					}
					run.append(CELL);
					continue;
				}
				flush(line, runCol, run);
				runCol = null;
				Component cell = Ui.mm(color + CELL).hoverEvent(HoverEvent.showText(Ui.mm(hover(f, mine, x, z, near && f != null && f.isWilderness() && mine != null))));
				if (!centre && near && mine != null && (f == null || f.isWilderness()))
					cell = cell.clickEvent(ClickEvent.runCommand("/f mapclaim " + x + " " + z));
				else if (f != null && f.isNormal())
					cell = cell.clickEvent(ClickEvent.runCommand("/f who " + f.tag()));
				line.append(cell);
			}
			flush(line, runCol, run);
			// compass on the right of the top rows
			if (row == 0) line.append(Ui.mm("   " + Ui.DIM + "N"));
			if (row == 1) line.append(Ui.mm("  " + Ui.DIM + "W<white>+" + Ui.DIM + "E"));
			if (row == 2) line.append(Ui.mm("   " + Ui.DIM + "S"));
			p.sendMessage(line.build());
		}
		p.sendMessage(Ui.mm(" " + Ui.GREEN + CELL + Ui.LABEL + " you  " + Ui.RED + CELL + Ui.LABEL + " enemy  <white>" + CELL + Ui.LABEL + " neutral  "
				+ Ui.GOLD + CELL + Ui.LABEL + " safe  <dark_red>" + CELL + Ui.LABEL + " war  " + Ui.DIM + CELL + Ui.LABEL + " wild  " + Ui.CYAN + CELL + Ui.LABEL + " you"));
		p.sendMessage(Ui.mm(" " + Ui.DIM + "Hover a chunk for info • click wilderness near you to claim"));
		p.sendMessage(Ui.mm(bar));
	}

	private static void flush(TextComponent.Builder line, String col, StringBuilder run) {
		if (col != null && run.length() > 0) line.append(Ui.mm(col + run));
		run.setLength(0);
	}

	private String cellColor(Faction f, Faction mine) {
		if (f == null || f.isWilderness()) return "<#2A2D36>";
		if (f.isSafeZone()) return Ui.GOLD;
		if (f.isWarZone()) return "<dark_red>";
		if (mine != null && f.id() == mine.id()) return Ui.GREEN;
		Relation r = mine == null ? Relation.NEUTRAL : mine.relationTo(f);
		if (r == Relation.ENEMY) return Ui.RED;
		return "<white>";
	}

	private String here(Faction f, Faction mine) {
		if (f == null || f.isWilderness()) return Ui.DIM + "Wilderness";
		if (f.isSafeZone()) return Ui.GOLD + "Safe Zone";
		if (f.isWarZone()) return "<dark_red>War Zone";
		return cellColor(f, mine) + Ui.esc(f.tag());
	}

	private String hover(Faction f, Faction mine, int x, int z, boolean claimable) {
		String pos = Ui.DIM + "Chunk " + x + ", " + z;
		if (f == null || f.isWilderness())
			return Ui.LABEL + "Wilderness\n" + pos + (claimable ? "\n\n" + Ui.CYAN + "Click to claim" : "");
		if (f.isSafeZone()) return Ui.GOLD + "Safe Zone\n" + pos;
		if (f.isWarZone()) return "<dark_red>War Zone\n" + pos;
		String s = cellColor(f, mine) + "<bold>" + Ui.esc(f.tag()) + "</bold>\n" + Ui.LABEL + Who.relName(mine == null ? Relation.NEUTRAL : mine.relationTo(f))
				+ "\n" + Ui.LABEL + "Members <white>" + f.members().size() + Ui.LABEL + " • Land <white>" + f.claimCount();
		if (plugin.shields != null) s += "\n" + plugin.shields.status(f);
		return s + "\n" + pos + "\n\n" + Ui.CYAN + "Click for /f who";
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
