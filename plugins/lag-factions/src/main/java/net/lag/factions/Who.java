package net.lag.factions;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import dev.kitteh.factions.FPlayer;
import dev.kitteh.factions.Faction;
import dev.kitteh.factions.permissible.Relation;
import dev.kitteh.factions.permissible.Role;

/** /f who: a faction profile menu (stats up top, members as heads) instead of FactionsUUID's chat wall. */
final class Who {
	private static final String DEFAULT_DESC = "Default faction description :(";
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

	private static Material banner(Relation r) {
		if (r == null) return Material.WHITE_BANNER;
		return switch (r) {
			case MEMBER -> Material.LIME_BANNER;
			case ENEMY -> Material.RED_BANNER;
			default -> Material.WHITE_BANNER;
		};
	}

	/** The profile page inside the /f menu. */
	void profile(Player viewer, Faction f) {
		FPlayer me = F.fp(viewer);
		Relation rel = me != null && me.hasFaction() ? me.faction().relationTo(f) : Relation.NEUTRAL;
		String rc = relColor(rel);
		List<FPlayer> members = new ArrayList<>(f.members());
		members.sort(Comparator.comparingInt((FPlayer m) -> m.role() == null ? 99 : m.role().ordinal())
				.thenComparing((FPlayer m) -> !m.isOnline()).thenComparing(FPlayer::name, String.CASE_INSENSITIVE_ORDER));
		long online = members.stream().filter(FPlayer::isOnline).count();
		String desc = f.description() == null || f.description().isBlank() || f.description().equals(DEFAULT_DESC) ? "" : f.description();

		Gui g = new Gui(6, rc + "<bold>" + Ui.esc(f.tag()) + "</bold> <dark_gray>• " + Ui.LABEL + relName(rel));
		g.frame();
		String founded = "";
		try {
			founded = DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.of("America/New_York")).format(f.founded());
		} catch (Exception ignored) { }
		g.set(4, Ui.item(banner(rel), true, rc + "<bold>" + Ui.esc(f.tag()),
				Ui.LABEL + relName(rel),
				desc.isEmpty() ? "" : Ui.DIM + "<italic>" + Ui.esc(desc),
				"", Ui.LABEL + "Founded <white>" + founded));

		// stats row
		g.set(19, Ui.item(Material.PLAYER_HEAD, "<white><bold>MEMBERS", "<white>" + members.size() + Ui.DIM + "/" + "<white>" + f.memberLimit(),
				Ui.GREEN + online + Ui.LABEL + " online"));
		g.set(20, Ui.item(Material.REDSTONE, "<white><bold>POWER", "<white>" + f.power() + Ui.DIM + " / <white>" + f.powerMax()));
		g.set(21, Ui.item(Material.GRASS_BLOCK, "<white><bold>LAND", "<white>" + f.claimCount() + Ui.LABEL + " chunks"));
		boolean raid = plugin.shields.raidable(f);
		g.set(22, Ui.item(raid ? Material.TNT : Material.SHIELD, raid, "<white><bold>SHIELD", plugin.shields.status(f),
				Ui.LABEL + "Window <white>" + plugin.shields.windowText(plugin.shields.startHour(f))));
		int rank = plugin.top.rank(f);
		g.set(23, Ui.item(Material.GOLD_INGOT, "<white><bold>F-TOP", rank > 0 ? Ui.GOLD + "#" + rank : Ui.LABEL + "Unranked",
				Ui.LABEL + "Value " + Ui.GREEN + Ui.money(plugin.top.value(f))));
		g.set(24, Ui.item(Material.TNT_MINECART, "<white><bold>TNT BANK", "<white>" + String.format("%,d", f.tntBank())));
		g.set(25, Ui.item(Material.ANVIL, "<white><bold>UPGRADES",
				Ui.LABEL + "Spawners <white>Lv " + plugin.upgrades.level(f, Upgrades.Type.SPAWNER) + Ui.LABEL + " • Farms <white>Lv " + plugin.upgrades.level(f, Upgrades.Type.FARM),
				Ui.LABEL + "Chest <white>Lv " + plugin.upgrades.level(f, Upgrades.Type.CHEST) + Ui.LABEL + " • TNT <white>Lv " + plugin.upgrades.level(f, Upgrades.Type.TNT),
				Ui.LABEL + "Warps <white>Lv " + plugin.upgrades.level(f, Upgrades.Type.WARPS)));

		// members as heads
		int[] slots = {28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
		for (int i = 0; i < Math.min(slots.length, members.size()); i++) {
			FPlayer m = members.get(i);
			ItemStack head = Ui.item(Material.PLAYER_HEAD, (m.isOnline() ? Ui.GREEN : "<gray>") + "<bold>" + Ui.esc(m.name()),
					roleColor(m.role()) + F.roleName(m.role()),
					Ui.LABEL + "Power <white>" + (int) m.power() + Ui.DIM + "/" + "<white>" + (int) m.powerMax(),
					m.isOnline() ? Ui.GREEN + "● Online" : Ui.DIM + "● Offline");
			if (head.getItemMeta() instanceof SkullMeta sm) {
				sm.setOwningPlayer(Bukkit.getOfflinePlayer(m.uniqueId()));
				head.setItemMeta(sm);
			}
			g.set(slots[i], head);
		}
		if (members.isEmpty()) g.set(31, Ui.item(Material.BARRIER, Ui.LABEL + "No members"));

		boolean mine = me != null && me.hasFaction() && me.faction().id() == f.id();
		if (mine) g.set(49, Ui.item(Material.ARROW, Ui.LABEL + "Back to /f menu"), c -> plugin.menu.open(c.player()));
		else g.set(49, Ui.item(Material.BARRIER, Ui.LABEL + "Close"), c -> c.player().closeInventory());
		g.open(viewer);
	}

	/** /f who: classic chat profile. */
	void show(Player viewer, Faction f) {
		FPlayer me = F.fp(viewer);
		Relation rel = me != null && me.hasFaction() ? me.faction().relationTo(f) : Relation.NEUTRAL;
		String rc = relColor(rel);
		List<FPlayer> members = new ArrayList<>(f.members());
		members.sort(Comparator.comparingInt((FPlayer m) -> m.role() == null ? 99 : m.role().ordinal())
				.thenComparing(FPlayer::name, String.CASE_INSENSITIVE_ORDER));
		String desc = f.description() == null || f.description().isBlank() || f.description().equals(DEFAULT_DESC) ? "" : f.description();
		String founded = "";
		try {
			founded = DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.of("America/New_York")).format(f.founded());
		} catch (Exception ignored) { }
		var out = net.kyori.adventure.text.Component.text();
		out.append(Ui.mm(Classic.header(rc + Ui.esc(f.tag()), f.tag()))).append(net.kyori.adventure.text.Component.newline());
		if (!desc.isEmpty()) out.append(Ui.mm(Classic.line("Description", Ui.esc(desc)))).append(net.kyori.adventure.text.Component.newline());
		out.append(Ui.mm(Classic.line("Land / Power / Max Power", f.claimCount() + " / " + f.power() + " / " + f.powerMax()))).append(net.kyori.adventure.text.Component.newline());
		out.append(Ui.mm("<gold>Shield: </gold>" + plugin.shields.status(f) + " <gray>(" + plugin.shields.windowText(plugin.shields.startHour(f)) + ")"))
				.append(net.kyori.adventure.text.Component.newline());
		int rank = plugin.top.rank(f);
		out.append(Ui.mm(Classic.line("F-Top", (rank > 0 ? "#" + rank + " " : "") + Ui.money(plugin.top.value(f))))).append(net.kyori.adventure.text.Component.newline());
		out.append(Ui.mm(Classic.line("TNT Bank", String.format("%,d", f.tntBank())))).append(net.kyori.adventure.text.Component.newline());
		if (!founded.isEmpty()) out.append(Ui.mm(Classic.line("Founded", founded))).append(net.kyori.adventure.text.Component.newline());
		long on = members.stream().filter(FPlayer::isOnline).count();
		out.append(roster("Online", members.stream().filter(FPlayer::isOnline).toList(), on, f.memberLimit())).append(net.kyori.adventure.text.Component.newline());
		out.append(roster("Offline", members.stream().filter(m -> !m.isOnline()).toList(), members.size() - on, -1));
		viewer.sendMessage(out.build());
	}

	private net.kyori.adventure.text.Component roster(String label, List<FPlayer> list, long n, int limit) {
		var b = net.kyori.adventure.text.Component.text();
		b.append(Ui.mm("<gold>" + label + " (" + n + (limit > 0 ? "/" + limit : "") + "): </gold>"));
		boolean first = true;
		for (FPlayer m : list) {
			if (!first) b.append(Ui.mm("<yellow>, "));
			first = false;
			b.append(Ui.mm((m.isOnline() ? "<green>" : "<gray>") + F.roleStars(m.role()) + Ui.esc(m.name()))
					.hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Ui.mm("<gold>" + F.roleName(m.role()) + "\n<gold>Power: <yellow>" + (int) m.power() + "/" + (int) m.powerMax()))));
		}
		if (list.isEmpty()) b.append(Ui.mm("<gray>none"));
		return b.build();
	}

	private static String roleColor(Role r) {
		if (r == null) return Ui.LABEL;
		return switch (r) {
			case ADMIN -> Ui.GOLD + "★ ";
			case COLEADER -> Ui.PURPLE + "✦ ";
			case MODERATOR -> Ui.CYAN + "◆ ";
			default -> Ui.LABEL;
		};
	}
}
