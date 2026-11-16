package net.lag.cannons;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Gives every player Sakura's client-lag tools (op-only by default):
 *   /fps            menu to hide TNT, sand, explosion particles, spawners and pistons for yourself
 *   /tnttoggle      hide/show TNT        (/tntvisibility)
 *   /sandtoggle     hide/show sand       (/sandvisibility)
 * Hidden entities are simply never sent to that player's client, so it can't lag from them. The server
 * still simulates everything, so cannons behave exactly the same. Choices are saved in the player's data.
 */
final class FpsMenu implements Listener {
	private static final List<String> PERMS = List.of("bukkit.command.fps", "bukkit.command.tntvisibility", "bukkit.command.sandvisibility");
	private final JavaPlugin plugin;

	FpsMenu(JavaPlugin plugin) {
		this.plugin = plugin;
	}

	private boolean available() {
		return plugin.getConfig().getBoolean("fps-menu.enabled", true) && Bukkit.getCommandMap().getCommand("fps") != null;
	}

	@EventHandler
	public void onJoin(PlayerJoinEvent e) {
		if (!available()) return;
		Player p = e.getPlayer();
		PermissionAttachment a = p.addAttachment(plugin);
		for (String perm : PERMS) a.setPermission(perm, true);
		p.updateCommands(); // so /fps tab-completes
	}
}
