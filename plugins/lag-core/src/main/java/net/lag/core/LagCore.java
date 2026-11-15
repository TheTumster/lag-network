package net.lag.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * laG Core: shared plumbing for every server in the network.
 * - /hub everywhere, /server opens the selector
 * - talks to the Velocity proxy over the standard BungeeCord plugin channel (Connect, PlayerCount)
 * - on the hub: selector item, protection, double jump, scoreboard
 */
public final class LagCore extends JavaPlugin implements PluginMessageListener {
	public static final String CHANNEL = "BungeeCord";
	private static LagCore instance;
	private final Map<String, Integer> counts = new HashMap<>();
	private Hub hub;
	HubBuild hubBuild;
	Pack pack;
	Ranks ranks;
	HubFx hubFx;
	Parkour parkour;

	public static LagCore get() {
		return instance;
	}

	public static Component mm(String s) {
		return MiniMessage.miniMessage().deserialize(s);
	}

	public String networkName() {
		return getConfig().getString("network-name", "RelapsedMC");
	}

	/**
	 * A config section, falling back to the plugin's built-in one when the server's config.yml doesn't have it.
	 * (Bukkit's getConfigurationSection returns an EMPTY section in that case instead of the default.)
	 */
	public org.bukkit.configuration.ConfigurationSection section(String path) {
		var c = getConfig();
		var s = c.isConfigurationSection(path) ? c.getConfigurationSection(path) : null;
		if (s != null && !s.getKeys(false).isEmpty()) return s;
		var d = c.getDefaults();
		var ds = d == null ? null : d.getConfigurationSection(path);
		return ds != null ? ds : s;
	}

	public boolean isHub() {
		return "hub".equalsIgnoreCase(getConfig().getString("role", "hub"));
	}

	@Override
	public void onEnable() {
		instance = this;
		saveDefaultConfig();
		getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
		getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);

		pack = new Pack(this);
		getServer().getPluginManager().registerEvents(pack, this);
		pack.start();
		getServer().getPluginManager().registerEvents(new Fresh(this), this);
		ranks = new Ranks(this);
		getServer().getPluginManager().registerEvents(ranks, this);
		Selector selector = new Selector(this);
		getServer().getPluginManager().registerEvents(selector, this);
		if (isHub()) {
			hub = new Hub(this, selector);
			getServer().getPluginManager().registerEvents(hub, this);
			hubBuild = new HubBuild(this);
			getServer().getPluginManager().registerEvents(hubBuild, this);
			Bukkit.getScheduler().runTask(this, hubBuild::start);
			hubFx = new HubFx(this, hubBuild);
			getServer().getPluginManager().registerEvents(hubFx, this);
			Bukkit.getScheduler().runTask(this, hubFx::start);
			parkour = new Parkour(this, hubBuild, hubFx);
			getServer().getPluginManager().registerEvents(parkour, this);
		}
		if (getConfig().getBoolean("scoreboard.enabled", true)) {
			Board board = new Board(this);
			getServer().getPluginManager().registerEvents(board, this);
			Bukkit.getScheduler().runTaskTimer(this, board::refreshAll, 20L, 20L);
		}
		// Ask the proxy for player counts every 5s (needs at least one player online to send through).
		Bukkit.getScheduler().runTaskTimer(this, this::requestCounts, 40L, 100L);
		getLogger().info(networkName() + " core enabled as " + getConfig().getString("role"));
	}

	// ---------------------------------------------------------------- proxy messaging

	public void send(Player p, String server) {
		try {
			ByteArrayOutputStream b = new ByteArrayOutputStream();
			DataOutputStream out = new DataOutputStream(b);
			out.writeUTF("Connect");
			out.writeUTF(server);
			p.sendPluginMessage(this, CHANNEL, b.toByteArray());
		} catch (IOException e) {
			p.sendMessage(mm("<red>Couldn't reach the proxy."));
		}
	}

	private void requestCounts() {
		Player any = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
		if (any == null) return;
		java.util.List<String> servers = new java.util.ArrayList<>();
		servers.add("ALL");
		var realms = section("realms");
		if (realms != null) for (String k : realms.getKeys(false)) servers.add(realms.getString(k + ".server", k));
		for (String s : servers) {
			try {
				ByteArrayOutputStream b = new ByteArrayOutputStream();
				DataOutputStream out = new DataOutputStream(b);
				out.writeUTF("PlayerCount");
				out.writeUTF(s);
				any.sendPluginMessage(this, CHANNEL, b.toByteArray());
			} catch (IOException ignored) { }
		}
	}

	@Override
	public void onPluginMessageReceived(String channel, Player player, byte[] message) {
		if (!CHANNEL.equals(channel)) return;
		try {
			DataInputStream in = new DataInputStream(new ByteArrayInputStream(message));
			if ("PlayerCount".equals(in.readUTF())) {
				String server = in.readUTF();
				counts.put(server, in.readInt());
			}
		} catch (IOException ignored) { }
	}

	public int count(String server) {
		Integer c = counts.get(server);
		if (c != null) return c;
		return "ALL".equals(server) ? Bukkit.getOnlinePlayers().size() : 0;
	}

	// ---------------------------------------------------------------- commands

	@Override
	public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
		switch (cmd.getName().toLowerCase()) {
			case "hub" -> {
				if (!(sender instanceof Player p)) return true;
				if (isHub()) {
					if (hub != null) hub.toSpawn(p);
				} else {
					p.sendMessage(mm("<gray>Sending you to the hub..."));
					send(p, "hub");
				}
			}
			case "server" -> {
				if (sender instanceof Player p) Selector.open(this, p);
			}
			case "setspawn" -> {
				if (!(sender instanceof Player p)) return true;
				Location l = p.getLocation();
				getConfig().set("hub.spawn", l.getWorld().getName() + "," + l.getX() + "," + l.getY() + "," + l.getZ() + "," + l.getYaw() + "," + l.getPitch());
				saveConfig();
				p.getWorld().setSpawnLocation(l);
				p.sendMessage(mm("<#38E0FF>Spawn set."));
			}
			case "lagcore" -> {
				if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
					reloadConfig();
					if (hubBuild != null) hubBuild.holograms();
					sender.sendMessage(mm("<#38E0FF>RelapsedMC core reloaded."));
				} else if (args.length > 0 && args[0].equalsIgnoreCase("rebuildhub") && hubBuild != null) {
					// re-place the spawn island (same spot), e.g. after an update or if someone broke it
					var o = hubBuild.origin();
					var w = o != null ? o.getWorld() : Bukkit.getWorlds().get(0);
					int n = hubBuild.build(w, o != null ? o.getBlockX() : 0, o != null ? o.getBlockY() : 100, o != null ? o.getBlockZ() : 0);
					hubBuild.atmosphere(w);
					hubBuild.holograms();
					sender.sendMessage(mm("<#38E0FF>Hub spawn rebuilt <gray>(" + n + " blocks)."));
				} else if (args.length > 0 && args[0].equalsIgnoreCase("holograms") && hubBuild != null) {
					hubBuild.holograms();
					sender.sendMessage(mm("<#38E0FF>Holograms respawned."));
				} else {
					sender.sendMessage(mm("<gray>/lagcore reload | rebuildhub | holograms"));
				}
			}
			default -> { }
		}
		return true;
	}

	public Location spawn() {
		String s = getConfig().getString("hub.spawn", "");
		if (s != null && !s.isEmpty()) {
			String[] p = s.split(",");
			var w = Bukkit.getWorld(p[0]);
			if (w != null) {
				return new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
						Float.parseFloat(p[4]), Float.parseFloat(p[5]));
			}
		}
		return Bukkit.getWorlds().get(0).getSpawnLocation().add(0.5, 0, 0.5);
	}
}
