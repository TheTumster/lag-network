package net.lag.core;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import net.kyori.adventure.text.Component;

/**
 * Fresh chat on every server switch: no join/quit spam, the chat is wiped half a second after you arrive
 * (after other plugins' join messages), then a clean realm banner (the hub has its own cinematic one).
 */
final class Fresh implements Listener {
	private final LagCore plugin;

	Fresh(LagCore plugin) {
		this.plugin = plugin;
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onJoin(PlayerJoinEvent e) {
		e.joinMessage(null);
		Player p = e.getPlayer();
		if (!plugin.getConfig().getBoolean("fresh-chat.enabled", true)) return;
		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			if (!p.isOnline()) return;
			clear(p);
			if (!plugin.isHub()) banner(p);
		}, plugin.getConfig().getLong("fresh-chat.delay-ticks", 10L));
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onQuit(PlayerQuitEvent e) {
		e.quitMessage(null);
	}

	static void clear(Player p) {
		for (int i = 0; i < 100; i++) p.sendMessage(Component.empty());
	}

	private void banner(Player p) {
		String role = plugin.getConfig().getString("role", "hub").toLowerCase();
		List<String> lines = plugin.getConfig().getStringList("fresh-chat.banner." + role);
		if (lines.isEmpty()) return;
		boolean art = plugin.pack != null && plugin.pack.has(p);
		String arrow = art ? Pack.glyph(Pack.I_ARROW) : "<#38E0FF>▸";
		String logo = art ? Pack.glyph(Pack.LOGO_LINE) : "<gradient:#38E0FF:#B040FF><bold>" + plugin.networkName().toUpperCase() + "</bold></gradient>";
		String bar = "<gradient:#38E0FF:#B040FF><strikethrough>                                                  </strikethrough></gradient>";
		for (String l : lines) {
			p.sendMessage(LagCore.mm(l.replace("{bar}", bar).replace("{logo}", logo).replace("{arrow}", arrow)
					.replace("{player}", p.getName()).replace("{online}", String.valueOf(plugin.count("ALL")))));
		}
	}
}
