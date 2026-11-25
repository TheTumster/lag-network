package net.lag.core;

import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/**
 * The same everyday commands on every realm: /help, /discord, /website, /store, /server [realm].
 * laGCore is on every server, so these look and work identically everywhere. Other plugins (Essentials' /help,
 * the vanilla one) are overridden once everything has loaded; theirs stay reachable as /essentials:help etc.
 * Text lives in config.yml under help: (shared lines + one block per realm role).
 */
final class NetCommands implements TabExecutor {
	private final LagCore plugin;
	static final String[] OWNED = {"help", "discord", "website", "store", "server"};

	NetCommands(LagCore plugin) {
		this.plugin = plugin;
		for (String c : OWNED) {
			PluginCommand pc = plugin.getCommand(c);
			if (pc != null) {
				pc.setExecutor(this);
				pc.setTabCompleter(this);
			}
		}
		// after every plugin has registered its commands, make ours the plain names (and their aliases)
		Bukkit.getScheduler().runTaskLater(plugin, this::claim, 1L);
	}

	private void claim() {
		var known = Bukkit.getCommandMap().getKnownCommands();
		for (String c : OWNED) {
			PluginCommand pc = plugin.getCommand(c);
			if (pc == null) continue;
			known.put(c, pc);
			for (String a : pc.getAliases()) known.put(a.toLowerCase(Locale.ROOT), pc);
		}
	}

	private String role() {
		return plugin.getConfig().getString("role", "hub").toLowerCase(Locale.ROOT);
	}

	@Override
	public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
		switch (cmd.getName().toLowerCase(Locale.ROOT)) {
			case "help" -> help(s);
			case "discord" -> link(s, "discord", "Discord", "join the community, events and support");
			case "website" -> link(s, "website", "Website", "news, rules and updates");
			case "store" -> link(s, "store", "Store", "cosmetic ranks that support the server");
			case "server" -> {
				if (!(s instanceof Player p)) return true;
				if (args.length == 0) {
					Selector.open(plugin, p);
					return true;
				}
				String want = args[0].toLowerCase(Locale.ROOT);
				var realms = plugin.section("realms");
				String target = null;
				if (want.equals("hub") || want.equals("lobby")) target = "hub";
				else if (realms != null) {
					for (String k : realms.getKeys(false)) {
						String srv = realms.getString(k + ".server", k);
						if (k.equalsIgnoreCase(want) || srv.equalsIgnoreCase(want)) target = srv;
					}
				}
				if (target == null) {
					p.sendMessage(LagCore.mm("<#FF6B6B>▌ <#9AA3B8>No realm called <white><bold>" + LagCore.esc(args[0])
							+ "</bold><#9AA3B8>. <#5E6478>/server</#5E6478> opens the menu."));
				} else if (target.equalsIgnoreCase(role())) {
					p.sendMessage(LagCore.mm("<#FF6B6B>▌ <#9AA3B8>You're already here."));
				} else {
					plugin.send(p, target);
				}
			}
			default -> { }
		}
		return true;
	}

	private void link(CommandSender s, String key, String title, String blurb) {
		String url = plugin.getConfig().getString("links." + key, "");
		s.sendMessage(net.kyori.adventure.text.Component.empty());
		if (url == null || url.isBlank()) {
			s.sendMessage(LagCore.mm("<#FF6B6B>▌ <white><bold><u>" + title + "</u></bold>  <#9AA3B8>coming soon."));
		} else {
			String shown = url.replaceFirst("^https?://", "").replaceFirst("(?i)^relapsedmc\\.com", "RelapsedMC.com");
			s.sendMessage(LagCore.mm("<#FF6B6B>▌ <white><bold><u>" + title + "</u></bold>  <#9AA3B8>" + blurb));
			s.sendMessage(LagCore.mm("   <click:open_url:'" + url + "'><hover:show_text:'<#9AA3B8>Open " + LagCore.esc(shown)
					+ "'><#FF8A80><u>" + LagCore.esc(shown) + "</u></hover></click>  <#5E6478>(click)"));
		}
		s.sendMessage(net.kyori.adventure.text.Component.empty());
	}

	private void help(CommandSender s) {
		var cfg = plugin.getConfig();
		String role = role();
		String realm = role.substring(0, 1).toUpperCase(Locale.ROOT) + role.substring(1);
		String accent = cfg.getString("help.accent." + role, "#FF6B6B");
		s.sendMessage(net.kyori.adventure.text.Component.empty());
		s.sendMessage(LagCore.mm("<gradient:#FF5C5C:#FFA19A><bold>RelapsedMC</bold></gradient> <#2B3048>│ <#9AA3B8>help <#5E6478>on <"
				+ accent + "><bold>" + realm.toUpperCase(Locale.ROOT)));
		section(s, "#FF6B6B", "Everywhere", cfg.getStringList("help.network"));
		List<String> mine = cfg.getStringList("help.realm." + role);
		if (!mine.isEmpty()) section(s, accent, realm, mine);
		s.sendMessage(LagCore.mm("<#5E6478>Click a command to run it • hover for details"));
		s.sendMessage(net.kyori.adventure.text.Component.empty());
	}

	/** Lines are "/command|what it does". The command part is clickable (suggests it in chat). */
	private void section(CommandSender s, String accent, String title, List<String> lines) {
		s.sendMessage(LagCore.mm("<" + accent + ">▌ <white><bold><u>" + title + "</u></bold>"));
		for (String l : lines) {
			String[] p = l.split("\\|", 2);
			String c = p[0].trim(), d = p.length > 1 ? p[1].trim() : "";
			String run = c.split(" <| \\[")[0];
			s.sendMessage(LagCore.mm("  <click:suggest_command:'" + run.replace("'", "") + " '><hover:show_text:'<#9AA3B8>" + LagCore.esc(d)
					+ "'><white><bold>" + LagCore.esc(c) + "</bold></white></hover></click>  <#9AA3B8>" + LagCore.esc(d)));
		}
	}

	@Override
	public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] args) {
		if (cmd.getName().equalsIgnoreCase("server") && args.length == 1) {
			var out = new java.util.ArrayList<String>();
			out.add("hub");
			var realms = plugin.section("realms");
			if (realms != null) out.addAll(realms.getKeys(false));
			out.removeIf(x -> !x.startsWith(args[0].toLowerCase(Locale.ROOT)));
			return out;
		}
		return List.of();
	}
}
