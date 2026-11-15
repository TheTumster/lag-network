package net.lag.skyblock;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * Monthly seasons. State lives in plugins/laGSkyblock/season.yml (number, end date, reset flag) so config edits never
 * touch it.
 *
 *   /season                       season number, time left, top 3, your place
 *   /lsb season ends <date time>  set the end shown in /season (yyyy-MM-dd HH:mm, server time)
 *   /lsb season end confirm       final island top -> seasons/season-N.yml, champion titles, everyone to the hub,
 *                                 server stops. The NEXT start wipes islands, spawners, money and inventories.
 *
 * Titles go in a file every server reads (season.titles-file, shared by laGCore), so they show network-wide.
 */
final class Season implements CommandExecutor {
	static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
	private final LagSkyblock plugin;
	private final File file;
	private int number;
	private String ends;

	Season(LagSkyblock plugin) {
		this.plugin = plugin;
		this.file = new File(plugin.getDataFolder(), "season.yml");
		YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
		number = Math.max(1, y.getInt("number", 1));
		ends = y.getString("ends", "");
	}

	int number() {
		return number;
	}

	private void save(boolean resetPending) {
		YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
		y.set("number", number);
		y.set("ends", ends);
		y.set("reset-pending", resetPending);
		try {
			y.save(file);
		} catch (Exception e) {
			plugin.getLogger().warning("Could not save season.yml: " + e);
		}
	}

	private String timeLeft() {
		if (ends == null || ends.isBlank()) return null;
		try {
			Duration d = Duration.between(LocalDateTime.now(), LocalDateTime.parse(ends, WHEN));
			if (d.isNegative()) return "ending any moment";
			long days = d.toDays(), hours = d.toHoursPart(), mins = d.toMinutesPart();
			return days > 0 ? days + "d " + hours + "h" : hours > 0 ? hours + "h " + mins + "m" : mins + "m";
		} catch (Exception e) {
			return null;
		}
	}

	// ---------------------------------------------------------------- /season

	@Override
	public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
		String left = timeLeft();
		s.sendMessage(Spawners.mm("<dark_gray><strikethrough>                                        "));
		s.sendMessage(Spawners.mm("  <gradient:#FFD84D:#FF8A3D><bold>SEASON " + number + "</bold></gradient>"
				+ (left != null ? " <dark_gray>· <gray>ends in <white>" + left : "")));
		s.sendMessage(Spawners.mm("  <dark_gray><italic>Every month the void takes the islands back."));
		s.sendMessage(Spawners.mm(""));
		List<IslandTop.Entry> r = plugin.top.ranking();
		if (r.isEmpty()) s.sendMessage(Spawners.mm("  <gray>No islands ranked yet. Place spawners to get on the board."));
		for (int i = 0; i < Math.min(3, r.size()); i++) {
			s.sendMessage(Spawners.mm("  " + (i == 0 ? "<gradient:#FFD84D:#FF8A3D>" : i == 1 ? "<#D9E2EC>" : "<#E0915A>") + "<bold>#" + (i + 1)
					+ "</bold> <white>" + r.get(i).display().replace("<", "\\<") + " <dark_gray>· <gold>" + IslandTop.money(r.get(i).value())));
		}
		if (s instanceof Player p) {
			int rank = plugin.top.rankOf(p.getUniqueId());
			if (rank > 3) s.sendMessage(Spawners.mm("  <gray>Your island: <white>#" + rank + " <dark_gray>· <gold>" + IslandTop.money(r.get(rank - 1).value())));
		}
		s.sendMessage(Spawners.mm(""));
		s.sendMessage(Spawners.mm("  <gray>Top islands win <gold>store credit</gold><gray>. #1 gets the permanent"));
		s.sendMessage(Spawners.mm("  " + title(1).replace("{n}", String.valueOf(number)) + " <gray>title. <white>/is top"));
		s.sendMessage(Spawners.mm("<dark_gray><strikethrough>                                        "));
		return true;
	}

	private String title(int place) {
		ConfigurationSection t = plugin.getConfig().getConfigurationSection("season.titles");
		String v = t == null ? null : t.getString(String.valueOf(place));
		if (v == null && place == 1) v = "<gradient:#FFD84D:#FF8A3D><bold>S{n} CHAMPION</bold></gradient>";
		return v == null ? "" : v;
	}

	// ---------------------------------------------------------------- admin: /lsb season ...

	void admin(CommandSender s, String[] args) {
		String sub = args.length > 1 ? args[1].toLowerCase() : "";
		switch (sub) {
			case "ends" -> {
				if (args.length < 4) {
					s.sendMessage(Spawners.mm("<gray>/lsb season ends <yyyy-MM-dd> <HH:mm> <dark_gray>(server time)"));
					return;
				}
				try {
					LocalDateTime.parse(args[2] + " " + args[3], WHEN);
				} catch (Exception e) {
					s.sendMessage(Spawners.mm("<red>Use yyyy-MM-dd HH:mm, e.g. 2026-12-31 20:00"));
					return;
				}
				ends = args[2] + " " + args[3];
				save(false);
				s.sendMessage(Spawners.mm("<#7CFF6B>Season " + number + " ends " + ends));
			}
			case "end" -> {
				if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
					s.sendMessage(Spawners.mm("<#FFB020>This ends Season " + number + ": saves the final island top, gives the champion titles,"));
					s.sendMessage(Spawners.mm("<#FFB020>sends everyone to the hub and stops this server. The next start WIPES islands, spawners,"));
					s.sendMessage(Spawners.mm("<#FFB020>money and inventories. Type <white>/lsb season end confirm</white> to do it."));
					return;
				}
				end(s);
			}
			default -> s.sendMessage(Spawners.mm("<gray>Season " + number + ". <white>/lsb season ends <date> <time> <dark_gray>| <white>/lsb season end"));
		}
	}

	private void end(CommandSender s) {
		plugin.top.refresh();
		List<IslandTop.Entry> r = plugin.top.ranking();
		int n = number;
		// 1. snapshot
		YamlConfiguration snap = new YamlConfiguration();
		snap.set("season", n);
		snap.set("ended", LocalDateTime.now().format(WHEN));
		ConfigurationSection credit = plugin.getConfig().getConfigurationSection("season.store-credit");
		for (int i = 0; i < Math.min(plugin.getConfig().getInt("season.keep-top", 25), r.size()); i++) {
			IslandTop.Entry e = r.get(i);
			String k = "top." + (i + 1);
			snap.set(k + ".island", e.display());
			snap.set(k + ".value", e.value());
			snap.set(k + ".spawners", e.spawners());
			snap.set(k + ".owner", String.valueOf(e.owner()));
			List<String> names = new ArrayList<>();
			for (UUID u : e.members()) names.add(Bukkit.getOfflinePlayer(u).getName() + " (" + u + ")");
			snap.set(k + ".members", names);
			if (credit != null && credit.contains(String.valueOf(i + 1))) snap.set(k + ".store-credit", credit.get(String.valueOf(i + 1)));
		}
		File dir = new File(plugin.getDataFolder(), "seasons");
		dir.mkdirs();
		try {
			snap.save(new File(dir, "season-" + n + ".yml"));
		} catch (Exception e) {
			s.sendMessage(Spawners.mm("<red>Couldn't save the season snapshot, not ending: " + e.getMessage()));
			return;
		}
		// 2. titles
		File tf = titlesFile();
		YamlConfiguration titles = YamlConfiguration.loadConfiguration(tf);
		for (int place = 1; place <= Math.min(10, r.size()); place++) {
			String t = title(place);
			if (t.isEmpty()) continue;
			t = t.replace("{n}", String.valueOf(n));
			for (UUID u : r.get(place - 1).members()) {
				List<String> list = new ArrayList<>(titles.getStringList("players." + u + ".titles"));
				if (!list.contains(t)) list.add(t);
				titles.set("players." + u + ".titles", list);
				titles.set("players." + u + ".name", Bukkit.getOfflinePlayer(u).getName());
			}
		}
		try {
			tf.getParentFile().mkdirs();
			titles.save(tf);
		} catch (Exception e) {
			plugin.getLogger().warning("Couldn't save titles to " + tf + ": " + e);
		}
		// 3. tell everyone
		Bukkit.broadcast(Spawners.mm("<dark_gray><strikethrough>                                        "));
		Bukkit.broadcast(Spawners.mm("  <gradient:#FFD84D:#FF8A3D><bold>SEASON " + n + " IS OVER</bold></gradient>"));
		Bukkit.broadcast(Spawners.mm("  <gray><italic>The void takes the islands back."));
		for (int i = 0; i < Math.min(3, r.size()); i++) {
			Bukkit.broadcast(Spawners.mm("  <white><bold>#" + (i + 1) + "</bold> " + r.get(i).display().replace("<", "\\<")
					+ " <dark_gray>· <gold>" + IslandTop.money(r.get(i).value())));
		}
		Bukkit.broadcast(Spawners.mm("<dark_gray><strikethrough>                                        "));
		plugin.getLogger().info("Season " + n + " ended. Results + store credit to pay: plugins/laGSkyblock/seasons/season-" + n + ".yml");
		// 4. next season starts on the next boot, after the wipe
		number = n + 1;
		ends = "";
		save(true);
		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			for (Player p : Bukkit.getOnlinePlayers()) toHub(p);
		}, 100L);
		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			for (Player p : Bukkit.getOnlinePlayers()) p.kick(Spawners.mm("<gradient:#FFD84D:#FF8A3D><bold>SEASON " + n + " IS OVER</bold></gradient>\n<gray>Season " + (n + 1) + " opens soon."));
			Bukkit.shutdown();
		}, 200L);
	}

	private void toHub(Player p) {
		try {
			ByteArrayOutputStream b = new ByteArrayOutputStream();
			DataOutputStream out = new DataOutputStream(b);
			out.writeUTF("Connect");
			out.writeUTF("hub");
			p.sendPluginMessage(plugin, "BungeeCord", b.toByteArray());
		} catch (Exception ignored) { }
	}

	File titlesFile() {
		return new File(plugin.getConfig().getString("season.titles-file", "../shared/titles.yml"));
	}

	// ---------------------------------------------------------------- the wipe (runs in onLoad, before any world loads)

	/** If the last season was ended, wipe everything season-bound. Called from onLoad. */
	static void wipeIfPending(File dataFolder, org.bukkit.configuration.file.FileConfiguration config, Logger log) {
		File f = new File(dataFolder, "season.yml");
		YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
		if (!y.getBoolean("reset-pending", false)) return;
		log.warning("== Season reset: wiping islands for Season " + y.getInt("number") + " ==");
		File root = Bukkit.getWorldContainer();
		File plugins = dataFolder.getParentFile();
		String level = "world";
		try (FileInputStream in = new FileInputStream("server.properties")) {
			Properties p = new Properties();
			p.load(in);
			level = p.getProperty("level-name", "world");
		} catch (Exception ignored) { }
		List<File> gone = new ArrayList<>(List.of(
				new File(root, "bskyblock_world"), new File(root, "bskyblock_world_nether"), new File(root, "bskyblock_world_the_end"),
				new File(plugins, "BentoBox/database"),
				new File(plugins, "SmartSpawner/spawners.db"), new File(plugins, "SmartSpawner/spawners.db-wal"),
				new File(plugins, "SmartSpawner/spawners.db-shm"), new File(plugins, "SmartSpawner/spawners_data.yml"),
				new File(dataFolder, "generators.yml"), new File(dataFolder, "unlocks.yml")));
		// inventories, ender chests, positions
		File pd = new File(new File(root, level), "playerdata");
		File[] dats = pd.listFiles((d, n) -> n.endsWith(".dat") || n.endsWith(".dat_old"));
		if (dats != null && config.getBoolean("season.wipe-inventories", true)) gone.addAll(List.of(dats));
		for (File g : gone) {
			if (!g.exists()) continue;
			delete(g);
			log.info("  wiped " + g.getPath());
		}
		// money back to the starting balance, homes on the old islands gone
		if (config.getBoolean("season.wipe-economy", true)) {
			double start = YamlConfiguration.loadConfiguration(new File(plugins, "Essentials/config.yml")).getDouble("starting-balance", 0);
			File[] users = new File(plugins, "Essentials/userdata").listFiles((d, n) -> n.endsWith(".yml"));
			int n = 0;
			if (users != null) {
				for (File u : users) {
					YamlConfiguration uy = YamlConfiguration.loadConfiguration(u);
					uy.set("money", String.valueOf(start));
					uy.set("homes", null);
					uy.set("lastlocation", null);
					uy.set("logoutlocation", null);
					try {
						uy.save(u);
						n++;
					} catch (Exception ignored) { }
				}
			}
			log.info("  reset " + n + " balances to " + start);
		}
		y.set("reset-pending", false);
		try {
			y.save(f);
		} catch (Exception e) {
			log.severe("Could not clear the season reset flag: " + e);
		}
		log.warning("== Season " + y.getInt("number") + " starts now ==");
	}

	private static void delete(File f) {
		if (f.isDirectory()) {
			File[] kids = f.listFiles();
			if (kids != null) for (File k : kids) delete(k);
		}
		try {
			Files.deleteIfExists(f.toPath());
		} catch (Exception ignored) { }
	}
}
