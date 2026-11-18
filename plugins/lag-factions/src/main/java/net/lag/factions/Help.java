package net.lag.factions;

import org.bukkit.Material;
import org.bukkit.entity.Player;

/** /f help: every command as a menu item (click to run the ones that need no arguments). */
final class Help {
	private final LagFactions plugin;

	Help(LagFactions plugin) {
		this.plugin = plugin;
	}

	private static final Object[][] CMDS = {
			{Material.WHITE_BANNER, "/f create <name>", "Start a faction (max 8 players)", null},
			{Material.PAPER, "/f invite <player>", "Invite someone to your faction", null},
			{Material.OAK_DOOR, "/f join <faction>", "Join a faction that invited you", null},
			{Material.GRASS_BLOCK, "/f claim", "Claim the chunk you're standing in", "f claim"},
			{Material.FILLED_MAP, "/f map", "Map of the land around you, click to claim", "f map"},
			{Material.SHIELD, "/f shield", "Pick your 6h raid window (EST)", "f shield"},
			{Material.RED_BED, "/f sethome • /f home", "Set and go to your faction home", "f home"},
			{Material.ENDER_PEARL, "/f setwarp <name> • /f warp", "Faction warps", "f warp"},
			{Material.CHEST, "/f chest", "Shared faction chest", "f chest"},
			{Material.TNT, "/f tnt deposit <n> • /tntfill <r>", "TNT bank and filling dispensers", null},
			{Material.GOLD_INGOT, "/f deposit <amount>", "Put money in the faction bank", null},
			{Material.ANVIL, "/f upgrades", "Spawner/farm speed, chest, TNT, warps", "f upgrades"},
			{Material.EMERALD, "/f shop", "Spawners and tray/trench picks", "f shop"},
			{Material.NETHER_STAR, "/f top", "F-top by spawner value", "f top"},
			{Material.WRITABLE_BOOK, "/f perms", "What each role can do", "f perms"},
			{Material.ARMOR_STAND, "/f alt invite <player>", "AFK-only alts (don't count toward 8)", "f alts"},
			{Material.BEACON, "/f outpost", "Raiding outpost: capture it for a boost", "f outpost"},
			{Material.BELL, "/koth", "Is a KOTH running?", "koth"},
			{Material.SPYGLASS, "/f who [faction]", "A faction's profile", "f who"},
			{Material.IRON_SWORD, "/f enemy <faction>", "Mark a faction as an enemy", null},
			{Material.NAME_TAG, "/f role <player> <role>", "recruit / member / mod / coleader", null},
			{Material.BARRIER, "/f leave • /f disband", "Leave or delete your faction", null},
	};

	/** /f help in chat: every command, hover for what it does, click to fill it in. */
	void chat(Player p) {
		var out = net.kyori.adventure.text.Component.text();
		out.append(Ui.mm(Classic.header("<yellow>Faction Commands", "Faction Commands")));
		for (Object[] c : CMDS) {
			String cmd = (String) c[1];
			String fill = cmd.contains(" <") ? cmd.substring(0, cmd.indexOf(" <") + 1) : cmd.split(" •")[0];
			out.append(net.kyori.adventure.text.Component.newline());
			out.append(Ui.mm("<yellow>" + Ui.esc(cmd) + " <gray>- " + Ui.esc((String) c[2]))
					.hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Ui.mm("<gray>Click to type it")))
					.clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand(fill)));
		}
		out.append(net.kyori.adventure.text.Component.newline()).append(Ui.mm("<gray>Everything else is in <yellow>/f</yellow>."));
		p.sendMessage(out.build());
	}

	void open(Player p) {
		Gui g = new Gui(5, Ui.GRAD + "<bold>FACTION COMMANDS</bold></gradient>");
		g.frame();
		int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38};
		for (int i = 0; i < CMDS.length && i < slots.length; i++) {
			Object[] c = CMDS[i];
			String run = (String) c[3];
			g.set(slots[i], Ui.item((Material) c[0], "<white><bold>" + c[1], Ui.LABEL + c[2], "", run != null ? Ui.CYAN + "Click to run" : Ui.DIM + "Type it in chat"), cl -> {
				if (run == null) return;
				cl.player().closeInventory();
				cl.player().performCommand(run);
			});
		}
		g.set(40, Ui.item(Material.ARROW, Ui.LABEL + "Back to /f menu"), c -> plugin.menu.open(c.player()));
		g.open(p);
	}
}
