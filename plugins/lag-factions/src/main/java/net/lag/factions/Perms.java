package net.lag.factions;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import dev.kitteh.factions.Faction;
import dev.kitteh.factions.permissible.PermSelector;
import dev.kitteh.factions.permissible.PermState;
import dev.kitteh.factions.permissible.PermissibleActions;
import dev.kitteh.factions.permissible.Relation;
import dev.kitteh.factions.permissible.Role;
import dev.kitteh.factions.permissible.selector.RelationSingleSelector;
import dev.kitteh.factions.permissible.selector.RoleSingleSelector;

/**
 * /f perms: pick who (a role or outsiders), then click actions to allow / deny / reset. Writes FactionsUUID's own
 * permission selectors (moved to the top of the list so they win), so every check in the engine follows it.
 */
final class Perms {
	private final LagFactions plugin;

	private record Target(String name, Material icon, PermSelector selector) { }

	private static final Object[][] ACTIONS = {
			{PermissibleActions.BUILD, Material.BRICKS, "Build"},
			{PermissibleActions.DESTROY, Material.IRON_PICKAXE, "Break"},
			{PermissibleActions.CONTAINER, Material.CHEST, "Open containers"},
			{PermissibleActions.DOOR, Material.OAK_DOOR, "Use doors"},
			{PermissibleActions.BUTTON, Material.STONE_BUTTON, "Press buttons"},
			{PermissibleActions.LEVER, Material.LEVER, "Flip levers"},
			{PermissibleActions.PLATE, Material.STONE_PRESSURE_PLATE, "Pressure plates"},
			{PermissibleActions.ITEM, Material.FLINT_AND_STEEL, "Use items"},
			{PermissibleActions.HOME, Material.RED_BED, "/f home"},
			{PermissibleActions.SETHOME, Material.WHITE_BED, "Set home"},
			{PermissibleActions.WARP, Material.ENDER_PEARL, "Use warps"},
			{PermissibleActions.SETWARP, Material.ENDER_EYE, "Set warps"},
			{PermissibleActions.TNTDEPOSIT, Material.TNT, "TNT deposit"},
			{PermissibleActions.TNTWITHDRAW, Material.TNT_MINECART, "TNT withdraw"},
			{PermissibleActions.TERRITORY, Material.GRASS_BLOCK, "Claim / unclaim"},
			{PermissibleActions.INVITE, Material.PAPER, "Invite"},
			{PermissibleActions.KICK, Material.IRON_BOOTS, "Kick"},
			{PermissibleActions.ECONOMY, Material.GOLD_INGOT, "Use the bank"},
			{PermissibleActions.FLY, Material.FEATHER, "Fly"},
	};

	Perms(LagFactions plugin) {
		this.plugin = plugin;
	}

	private static Target[] targets() {
		return new Target[] {
				new Target("Recruits", Material.LEATHER_HELMET, new RoleSingleSelector(Role.RECRUIT)),
				new Target("Members", Material.IRON_HELMET, new RoleSingleSelector(Role.NORMAL)),
				new Target("Mods", Material.GOLDEN_HELMET, new RoleSingleSelector(Role.MODERATOR)),
				new Target("Co-Leaders", Material.DIAMOND_HELMET, new RoleSingleSelector(Role.COLEADER)),
				new Target("Neutral players", Material.WHITE_WOOL, new RelationSingleSelector(Relation.NEUTRAL)),
				new Target("Enemies", Material.RED_WOOL, new RelationSingleSelector(Relation.ENEMY)),
		};
	}

	void open(Player p) {
		Faction f = F.of(p);
		if (f == null) { Ui.err(p, "You need a faction."); return; }
		Gui g = new Gui(4, Ui.GRAD + "<bold>PERMISSIONS</bold></gradient> <dark_gray>• " + Ui.esc(f.tag()));
		g.frame();
		Target[] ts = targets();
		int[] slots = {10, 11, 12, 13, 15, 16};
		for (int i = 0; i < ts.length; i++) {
			Target t = ts[i];
			g.set(slots[i], Ui.item(t.icon(), "<white><bold>" + t.name().toUpperCase(), Ui.LABEL + "What " + t.name().toLowerCase() + " can do", Ui.LABEL + "in your claims", "",
					Ui.CYAN + "Click to edit"), c -> page(c.player(), t));
		}
		g.set(4, Ui.item(Material.WRITABLE_BOOK, true, "<white><bold>PERMISSIONS", Ui.LABEL + "Leader and co-leaders can edit.", Ui.LABEL + "Leaders can always do everything."));
		g.set(31, Ui.item(Material.ARROW, Ui.LABEL + "Back to /f menu"), c -> plugin.menu.open(c.player()));
		g.open(p);
	}

	private void page(Player p, Target t) {
		Faction f = F.of(p);
		if (f == null) return;
		boolean canEdit = F.atLeast(p, Role.COLEADER);
		var perms = f.permissions();
		Gui g = new Gui(5, Ui.GRAD + "<bold>" + t.name().toUpperCase() + "</bold></gradient> <dark_gray>• permissions");
		g.frame();
		int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
		for (int i = 0; i < ACTIONS.length && i < slots.length; i++) {
			PermissibleActions a = (PermissibleActions) ACTIONS[i][0];
			PermState st = perms.has(t.selector()) ? perms.get(t.selector()).get(a) : PermState.UNSET;
			String state = st == PermState.ALLOW ? Ui.GREEN + "<bold>ALLOWED" : st == PermState.DENY ? Ui.RED + "<bold>DENIED" : Ui.LABEL + "Default";
			g.set(slots[i], Ui.item((Material) ACTIONS[i][1], st == PermState.ALLOW, "<white><bold>" + ACTIONS[i][2], state, "",
					canEdit ? Ui.CYAN + "Click: allow → deny → default" : Ui.DIM + "Leader / co-leader only"), c -> {
				if (!F.atLeast(c.player(), Role.COLEADER)) return;
				Faction ff = F.of(c.player());
				if (ff == null) return;
				var pp = ff.permissions();
				var sp = pp.has(t.selector()) ? pp.get(t.selector()) : pp.add(t.selector());
				PermState cur = sp.get(a);
				sp.set(a, cur == PermState.ALLOW ? PermState.DENY : cur == PermState.DENY ? PermState.UNSET : PermState.ALLOW);
				// our single-role/relation rules go first so they win over broader defaults
				for (int k = 0; k < 64 && pp.selectors().indexOf(t.selector()) > 0; k++) pp.moveSelectorUp(t.selector());
				page(c.player(), t);
			});
		}
		g.set(40, Ui.item(Material.ARROW, Ui.LABEL + "Back"), c -> open(c.player()));
		g.open(p);
	}
}
