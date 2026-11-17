package net.lag.factions;

import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import dev.kitteh.factions.Faction;

/**
 * RelapsedMC factions layer on top of FactionsUUID. FactionsUUID keeps the data (claims, power, members); every
 * screen players see for these commands is ours:
 *   /f, /f menu      GUI hub
 *   /f shield        raid window picker (EST), shield status
 *   /f who [name]    clean faction card (FactionsUUID's /f show)
 *   /f map           clickable chunk map, click to claim
 *   /f upgrades /f shop /f chest /f top /f perms /f outpost
 */
public final class LagFactions extends JavaPlugin implements Listener {
	private static final Set<String> ROOTS = Set.of("f", "fac", "faction", "factions");
	Shields shields;
	Menu menu;
	Who who;
	FMap map;
	Upgrades upgrades;
	FChest chest;
	Shop shop;
	FTop top;
	Perms perms;
	Outpost outpost;

	@Override
	public void onDisable() {
		if (chest != null) chest.saveAll();
	}

	@Override
	public void onEnable() {
		saveDefaultConfig();
		getConfig().options().copyDefaults(true);
		saveConfig();
		shields = new Shields(this);
		menu = new Menu(this);
		who = new Who(this);
		map = new FMap(this);
		upgrades = new Upgrades(this);
		chest = new FChest(this);
		shop = new Shop(this);
		top = new FTop(this);
		perms = new Perms(this);
		outpost = new Outpost(this);
		var pm = getServer().getPluginManager();
		pm.registerEvents(this, this);
		pm.registerEvents(shields, this);
		pm.registerEvents(upgrades, this);
		pm.registerEvents(chest, this);
		pm.registerEvents(shop, this);
		pm.registerEvents(top, this);
		pm.registerEvents(outpost, this);
		pm.registerEvents(new Gui.Events(), this);
		// scoreboard line for laGCore ("shield" service: player -> MiniMessage status, "" if factionless)
		Function<Player, String> shieldLine = p -> {
			Faction f = F.of(p);
			return f == null ? "" : shields.status(f);
		};
		Bukkit.getServicesManager().register(Function.class, shieldLine, this, ServicePriority.Normal);
		getLogger().info("laGFactions ready: /f menu, /f shield, /f who, /f map");
	}

	/** Runs after ClassicFactions (LOWEST) has turned /f who into /f show etc. */
	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onCommand(PlayerCommandPreprocessEvent e) {
		String[] a = e.getMessage().substring(1).trim().split("\\s+");
		if (a.length == 0 || !ROOTS.contains(a[0].toLowerCase(Locale.ROOT))) return;
		Player p = e.getPlayer();
		String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
		switch (sub) {
			case "", "menu", "gui", "m" -> { e.setCancelled(true); menu.open(p); }
			case "shield", "shields", "raidwindow" -> { e.setCancelled(true); shields.open(p); }
			case "show", "who" -> {
				Faction f = a.length > 2 ? resolve(a[2]) : F.of(p);
				if (f == null && a.length > 2) return; // let FactionsUUID say "not found"
				e.setCancelled(true);
				if (f == null) { Ui.err(p, "You're not in a faction. <white>/f who <faction></white> to look one up."); return; }
				who.show(p, f);
			}
			case "map" -> {
				if (a.length > 2) return; // /f map on|off stays FactionsUUID's auto-map
				e.setCancelled(true);
				map.show(p);
			}
			case "upgrades", "upgrade", "u" -> { e.setCancelled(true); upgrades.open(p); }
			case "shop", "store" -> { e.setCancelled(true); shop.open(p); }
			case "chest", "pv" -> { e.setCancelled(true); chest.open(p); }
			case "top", "ftop" -> { if (a.length > 2) return; e.setCancelled(true); top.open(p); }
			case "perms", "perm", "permissions" -> { e.setCancelled(true); perms.open(p); }
			case "outpost", "op" -> { e.setCancelled(true); outpost.open(p); }
			case "set" -> {
				// ClassicFactions turns /f perms into /f set perm; with no further arguments that's our menu
				if (a.length == 3 && (a[2].equalsIgnoreCase("perm") || a[2].equalsIgnoreCase("perms"))) { e.setCancelled(true); perms.open(p); }
			}
			case "mapclaim" -> {
				e.setCancelled(true);
				try {
					map.claim(p, Integer.parseInt(a[2]), Integer.parseInt(a[3]));
				} catch (Exception ex) {
					Ui.err(p, "Click a chunk on <white>/f map</white> to claim it.");
				}
			}
			default -> { }
		}
	}

	@SuppressWarnings("deprecation")
	private Faction resolve(String name) {
		Faction f = F.byTag(name);
		if (f != null) return f;
		OfflinePlayer o = Bukkit.getPlayerExact(name);
		if (o == null) o = Bukkit.getOfflinePlayerIfCached(name);
		return o == null ? null : F.of(o);
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length > 1 && args[0].equalsIgnoreCase("outpost") && args[1].equalsIgnoreCase("rebuild")) {
			outpost.startRebuild();
			Ui.msg(sender, "Outpost rebuilding.");
			return true;
		}
		if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
			reloadConfig();
			Ui.msg(sender, "Reloaded.");
			return true;
		}
		// /lf give <player> spawner <type> [amount] | /lf give <player> pick <trench3|trench5|tray3|tray5>
		if (args.length >= 4 && args[0].equalsIgnoreCase("give")) {
			Player t = Bukkit.getPlayerExact(args[1]);
			if (t == null) { Ui.msg(sender, "<red>Player not online."); return true; }
			org.bukkit.inventory.ItemStack it;
			try {
				it = args[2].equalsIgnoreCase("spawner")
						? shop.spawner(org.bukkit.entity.EntityType.valueOf(args[3].toUpperCase(Locale.ROOT)), args.length > 4 ? Integer.parseInt(args[4]) : 1)
						: shop.pick(args[3].toLowerCase(Locale.ROOT));
			} catch (Exception ex) {
				Ui.msg(sender, "<red>Unknown item: " + args[3]);
				return true;
			}
			t.getInventory().addItem(it).values().forEach(l -> t.getWorld().dropItemNaturally(t.getLocation(), l));
			Ui.msg(sender, "Given.");
			return true;
		}
		Ui.msg(sender, "<gray>/lf reload <dark_gray>• <gray>/lf give <player> spawner <type> [amount] <dark_gray>• <gray>/lf give <player> pick <trench3|trench5|tray3|tray5>");
		return true;
	}
}
