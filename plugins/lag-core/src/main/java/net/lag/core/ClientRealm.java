package net.lag.core;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRegisterChannelEvent;

/**
 * Tells the RelapsedMC Client which realm this server is (laGCore's "role": hub, factions, skyblock, smp, cannons),
 * so it can switch to that realm's mode. Sent when the client registers the channel, which also happens again
 * after every /server switch through the proxy.
 * Wire format (must match ServerLink.RealmPayload): VarInt-prefixed UTF-8 string.
 */
final class ClientRealm implements Listener {
	static final String CHANNEL = "relapsed:realm";
	private final LagCore plugin;

	ClientRealm(LagCore plugin) {
		this.plugin = plugin;
		Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
	}

	@EventHandler
	public void onRegister(PlayerRegisterChannelEvent e) {
		if (!CHANNEL.equals(e.getChannel())) return;
		Player p = e.getPlayer();
		Bukkit.getScheduler().runTask(plugin, () -> {
			if (p.isOnline()) p.sendPluginMessage(plugin, CHANNEL, packet(plugin.getConfig().getString("role", "hub")));
		});
	}

	private static byte[] packet(String realm) {
		byte[] b = realm.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8);
		ByteArrayOutputStream out = new ByteArrayOutputStream(b.length + 5);
		int v = b.length;
		while ((v & ~0x7F) != 0) {
			out.write((v & 0x7F) | 0x80);
			v >>>= 7;
		}
		out.write(v);
		out.writeBytes(b);
		return out.toByteArray();
	}
}
