package net.lag.factions;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import dev.kitteh.factions.FPlayer;
import dev.kitteh.factions.Faction;

/** /f menu (also plain /f): one GUI hub for everything a faction does. */
final class Menu {
	private final LagFactions plugin;

	Menu(LagFactions plugin) {
		this.plugin = plugin;
	}

	void open(Player p) {
		FPlayer me = F.fp(p);
		Faction f = me != null && me.hasFaction() ? me.faction() : null;
		Gui g = new Gui(5, Ui.GRAD + "<bold>FACTIONS</bold></gradient>" + (f == null ? "" : " <dark_gray>• " + Ui.esc(f.tag())));
		g.frame();
		if (f == null) {
			g.set(13, Ui.item(Material.WHITE_BANNER, true, Ui.RED + "<bold>NO FACTION", "", Ui.LABEL + "Make one: <white>/f create <name>",
					Ui.LABEL + "Or join one you were invited to:", "<white>/f join <name>", "", Ui.DIM + "Max 8 players • no allies"));
			g.set(29, Ui.item(Material.FILLED_MAP, Ui.CYAN + "<bold>MAP", Ui.LABEL + "See who owns the land around you"), c -> { c.player().closeInventory(); plugin.map.show(c.player()); });
			g.set(31, Ui.item(Material.GOLD_INGOT, Ui.GOLD + "<bold>TOP FACTIONS", Ui.LABEL + "Who's winning this map"), c -> plugin.top.open(c.player()));
			g.set(33, Ui.item(Material.ARMOR_STAND, Ui.CYAN + "<bold>ALT INVITES", Ui.LABEL + "Accept an alt invite: <white>/f alt accept"), c -> { c.player().closeInventory(); c.player().performCommand("f alt"); });
			g.open(p);
			return;
		}
		boolean raid = plugin.shields.raidable(f);
		g.set(4, Ui.item(Material.RED_BANNER, true, "<white><bold>" + Ui.esc(f.tag()).toUpperCase(),
				Ui.LABEL + "Members <white>" + f.members().size() + "/" + f.memberLimit() + Ui.LABEL + " • Online <white>" + f.membersOnline(true).size(),
				Ui.LABEL + "Power <white>" + f.power() + "/" + f.powerMax() + Ui.LABEL + " • Land <white>" + f.claimCount(),
				Ui.LABEL + "Shield " + plugin.shields.status(f),
				"", Ui.CYAN + "Click for the full card"), c -> { c.player().closeInventory(); plugin.who.show(c.player(), f); });

		g.set(19, Ui.item(raid ? Material.TNT : Material.SHIELD, "<white><bold>SHIELD",
				plugin.shields.status(f), Ui.LABEL + "Window <white>" + plugin.shields.windowText(plugin.shields.startHour(f)),
				"", Ui.CYAN + "Pick your raid window"), c -> plugin.shields.open(c.player()));
		g.set(20, Ui.item(Material.FILLED_MAP, "<white><bold>MAP", Ui.LABEL + "Your land, enemies, wilderness", Ui.LABEL + "Click a chunk to claim it"),
				c -> { c.player().closeInventory(); plugin.map.show(c.player()); });
		g.set(21, Ui.item(Material.ANVIL, "<white><bold>UPGRADES", Ui.LABEL + "Spawner + farm speed, chest,", Ui.LABEL + "TNT bank, warps", "", Ui.CYAN + "Click to open"),
				c -> plugin.upgrades.open(c.player()));
		g.set(22, Ui.item(Material.EMERALD, "<white><bold>SHOP", Ui.LABEL + "Spawners, tray + trench picks", "", Ui.CYAN + "Click to open"),
				c -> plugin.shop.open(c.player()));
		g.set(23, Ui.item(Material.GOLD_INGOT, "<white><bold>TOP FACTIONS", Ui.LABEL + "Ranked by spawner value"),
				c -> plugin.top.open(c.player()));
		g.set(24, Ui.item(Material.TNT_MINECART, "<white><bold>TNT BANK", Ui.LABEL + "Stored <white>" + String.format("%,d", f.tntBank()) + Ui.DIM + " / " + String.format("%,d", f.tntBankMax()),
				"", Ui.LABEL + "Deposit: <white>/f tnt deposit <amount>", Ui.LABEL + "Fill dispensers: <white>/tntfill <radius>"));
		g.set(25, Ui.item(Material.ARMOR_STAND, "<white><bold>ALTS", Ui.LABEL + "AFK-only accounts for your", Ui.LABEL + "spawners and farms", "", Ui.CYAN + "Click to list them"),
				c -> { c.player().closeInventory(); c.player().performCommand("f alts"); });

		g.set(29, Ui.item(Material.RED_BED, "<white><bold>HOME", Ui.LABEL + (f.hasHome() ? "Teleport to your faction home" : "No home yet: <white>/f sethome")),
				c -> { c.player().closeInventory(); c.player().performCommand("f home"); });
		g.set(30, Ui.item(Material.ENDER_PEARL, "<white><bold>WARPS", Ui.LABEL + "Warps <white>" + f.warps().size() + "/" + (int) plugin.upgrades.value(f, Upgrades.Type.WARPS), "", Ui.CYAN + "Click to open"),
				c -> { c.player().closeInventory(); c.player().performCommand("f warp"); });
		g.set(31, Ui.item(Material.CHEST, "<white><bold>FACTION CHEST", Ui.LABEL + "Shared storage <white>" + (int) plugin.upgrades.value(f, Upgrades.Type.CHEST) + " rows"),
				c -> plugin.chest.open(c.player()));
		g.set(32, Ui.item(Material.WRITABLE_BOOK, "<white><bold>PERMISSIONS", Ui.LABEL + "What each role can do", "", Ui.CYAN + "Click to edit"),
				c -> plugin.perms.open(c.player()));
		g.set(40, Ui.item(Material.BEACON, true, Ui.PURPLE + "<bold>OUTPOST", Ui.LABEL + "Held by <white>" + (plugin.outpost.owner() == null ? "nobody" : Ui.esc(plugin.outpost.owner().tag())),
				Ui.LABEL + "Owners get faster spawners + farms", "", Ui.CYAN + "Click to open"), c -> plugin.outpost.open(c.player()));
		g.set(33, Ui.item(Material.PLAYER_HEAD, "<white><bold>MEMBERS", Ui.LABEL + "Invite: <white>/f invite <player>", Ui.LABEL + "Promote: <white>/f role <player> <role>"),
				c -> { c.player().closeInventory(); plugin.who.show(c.player(), f); });
		g.open(p);
	}
}
