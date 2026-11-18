package net.lag.factions;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import dev.kitteh.factions.Faction;
import dev.kitteh.factions.integration.Econ;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;

/**
 * KOTHs: staff-started, named arenas. Stand alone in the zone (no enemies of yours inside) to cap; the timer runs
 * while you hold it and resets when you're knocked off. Winner gets KOTH gear (Protection 5 netherite, tagged so the
 * gear rules allow it), money to their faction bank and any configured commands. Does not add F-top value.
 *
 *   /koth                       status
 *   /koth create <name> [radius] (staff) zone centred on you
 *   /koth delete <name>
 *   /koth start <name> [minutes] (staff) cap time, default koth.cap-minutes
 *   /koth stop
 *   /koth list
 */
final class Koth {
	private final LagFactions plugin;
	private final File file;
	private final Map<String, Arena> arenas = new LinkedHashMap<>();
	private Arena active;
	private long capMs, heldMs;
	private Player capper;
	private BossBar bar;
	private int task = -1;
	private final Random rnd = new Random();

	private record Arena(String name, String world, int x, int y, int z, int r) {
		boolean inside(Location l) {
			return l.getWorld() != null && l.getWorld().getName().equals(world) && Math.abs(l.getBlockX() - x) <= r
					&& Math.abs(l.getBlockZ() - z) <= r && l.getBlockY() >= y - 2 && l.getBlockY() <= y + 6;
		}
	}

	Koth(LagFactions plugin) {
		this.plugin = plugin;
		this.file = new File(plugin.getDataFolder(), "koths.yml");
		YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
		var sec = y.getConfigurationSection("arenas");
		if (sec != null) for (String k : sec.getKeys(false))
			arenas.put(k.toLowerCase(Locale.ROOT), new Arena(sec.getString(k + ".name", k), sec.getString(k + ".world"), sec.getInt(k + ".x"),
					sec.getInt(k + ".y"), sec.getInt(k + ".z"), sec.getInt(k + ".r", 4)));
	}

	private void save() {
		YamlConfiguration y = new YamlConfiguration();
		for (var e : arenas.entrySet()) {
			Arena a = e.getValue();
			String p = "arenas." + e.getKey();
			y.set(p + ".name", a.name());
			y.set(p + ".world", a.world());
			y.set(p + ".x", a.x());
			y.set(p + ".y", a.y());
			y.set(p + ".z", a.z());
			y.set(p + ".r", a.r());
		}
		try {
			plugin.getDataFolder().mkdirs();
			y.save(file);
		} catch (IOException ignored) { }
	}

	boolean command(CommandSender s, String[] a) {
		boolean staff = s.hasPermission("lag.factions.admin");
		String op = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
		switch (op) {
			case "create" -> {
				if (!staff || !(s instanceof Player p) || a.length < 2) { Ui.msg(s, "<gray>/koth create <name> [radius]"); return true; }
				int r = a.length > 2 ? Integer.parseInt(a[2]) : 4;
				Location l = p.getLocation();
				arenas.put(a[1].toLowerCase(Locale.ROOT), new Arena(a[1], l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), r));
				save();
				Ui.msg(s, "KOTH <white>" + Ui.esc(a[1]) + "</white> set here (" + (r * 2 + 1) + "x" + (r * 2 + 1) + ").");
			}
			case "delete" -> {
				if (!staff || a.length < 2) return true;
				arenas.remove(a[1].toLowerCase(Locale.ROOT));
				save();
				Ui.msg(s, "Deleted.");
			}
			case "start" -> {
				if (!staff || a.length < 2) { Ui.msg(s, "<gray>/koth start <name> [minutes]"); return true; }
				Arena ar = arenas.get(a[1].toLowerCase(Locale.ROOT));
				if (ar == null) { Ui.msg(s, "<red>No KOTH called " + Ui.esc(a[1]) + ". /koth list"); return true; }
				double min = a.length > 2 ? Double.parseDouble(a[2]) : plugin.getConfig().getDouble("koth.cap-minutes", 10);
				start(ar, (long) (min * 60_000));
			}
			case "stop" -> {
				if (!staff) return true;
				if (active == null) { Ui.msg(s, "No KOTH running."); return true; }
				Bukkit.broadcast(Ui.mm(Ui.PREFIX + Ui.GOLD + "<bold>KOTH</bold> " + Ui.LABEL + "<white>" + Ui.esc(active.name()) + "</white> was cancelled."));
				end();
			}
			case "list" -> {
				if (arenas.isEmpty()) { Ui.msg(s, "No KOTHs yet." + (staff ? " <white>/koth create <name>" : "")); return true; }
				for (Arena ar : arenas.values())
					Ui.msg(s, Ui.GOLD + Ui.esc(ar.name()) + Ui.LABEL + " • " + ar.world() + " " + ar.x() + ", " + ar.y() + ", " + ar.z());
			}
			default -> {
				if (active == null) Ui.msg(s, Ui.LABEL + "No KOTH is running. They're announced in Discord first.");
				else Ui.msg(s, Ui.GOLD + "<bold>" + Ui.esc(active.name()) + "</bold> " + Ui.LABEL + "at <white>" + active.x() + ", " + active.z()
						+ Ui.LABEL + " (" + active.world() + ")" + Ui.LABEL + " • " + (capper == null ? "nobody capping" : "<white>" + Ui.esc(capper.getName()) + Ui.LABEL + " capping, " + Ui.duration(capMs - heldMs) + " left"));
			}
		}
		return true;
	}

	private void start(Arena ar, long capTime) {
		if (active != null) end();
		active = ar;
		capMs = capTime;
		heldMs = 0;
		capper = null;
		bar = Bukkit.createBossBar("KOTH", BarColor.YELLOW, BarStyle.SEGMENTED_10);
		for (Player p : Bukkit.getOnlinePlayers()) {
			bar.addPlayer(p);
			p.showTitle(Title.title(Ui.mm(Ui.GOLD + "<bold>KOTH: " + Ui.esc(ar.name())), Ui.mm(Ui.LABEL + "at <white>" + ar.x() + ", " + ar.z() + Ui.LABEL + " • hold it for " + Ui.duration(capTime))));
			p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 1f, 1f);
		}
		Bukkit.broadcast(Ui.mm(Ui.PREFIX + Ui.GOLD + "<bold>KOTH</bold> <white>" + Ui.esc(ar.name()) + Ui.LABEL + " has started at <white>" + ar.x() + ", " + ar.z()
				+ Ui.LABEL + " (" + ar.world() + "). Hold the zone for <white>" + Ui.duration(capTime) + Ui.LABEL + ". " + Ui.CYAN + "/koth"));
		task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L).getTaskId();
	}

	private void tick() {
		if (active == null) return;
		World w = Bukkit.getWorld(active.world());
		if (w == null) return;
		List<Player> in = new ArrayList<>();
		for (Player p : w.getPlayers()) if (!p.isDead() && p.getGameMode() != org.bukkit.GameMode.SPECTATOR && active.inside(p.getLocation())) in.add(p);
		// keep the capper while they're inside; contested if someone from another faction is in too
		if (capper != null && !in.contains(capper)) {
			Bukkit.broadcast(Ui.mm(Ui.PREFIX + Ui.GOLD + "<bold>KOTH</bold> <white>" + Ui.esc(capper.getName()) + Ui.LABEL + " was knocked off <white>" + Ui.esc(active.name())));
			capper = null;
			heldMs = 0;
		}
		if (capper == null && !in.isEmpty()) {
			capper = in.get(0);
			heldMs = 0;
			Faction f = F.of(capper);
			Bukkit.broadcast(Ui.mm(Ui.PREFIX + Ui.GOLD + "<bold>KOTH</bold> <white>" + Ui.esc(capper.getName()) + (f == null ? "" : Ui.LABEL + " of <white>" + Ui.esc(f.tag()))
					+ Ui.LABEL + " is capping <white>" + Ui.esc(active.name())));
		}
		boolean contested = false;
		if (capper != null) {
			Faction cf = F.of(capper);
			for (Player p : in) {
				if (p.equals(capper)) continue;
				Faction pf = F.of(p);
				if (cf == null || pf == null || pf.id() != cf.id()) { contested = true; break; }
			}
			if (!contested) heldMs += 1000;
		}
		// zone outline
		for (int i = 0; i < 24; i++) {
			double t = i / 24.0 * Math.PI * 2;
			w.spawnParticle(Particle.END_ROD, active.x() + 0.5 + Math.cos(t) * (active.r() + 0.5), active.y() + 0.3, active.z() + 0.5 + Math.sin(t) * (active.r() + 0.5), 1, 0, 0, 0, 0);
		}
		long left = Math.max(0, capMs - heldMs);
		bar.setProgress(Math.max(0, Math.min(1, heldMs / (double) capMs)));
		bar.setColor(contested ? BarColor.RED : capper == null ? BarColor.WHITE : BarColor.YELLOW);
		bar.setTitle("§6§lKOTH §f" + active.name() + " §8• " + (capper == null ? "§7nobody capping" : (contested ? "§cCONTESTED §7" : "§f") + capper.getName() + " §8• §f" + Ui.duration(left)));
		for (Player p : Bukkit.getOnlinePlayers()) if (!bar.getPlayers().contains(p)) bar.addPlayer(p);
		if (capper != null && heldMs >= capMs) win(capper);
	}

	private void win(Player p) {
		Arena ar = active;
		Faction f = F.of(p);
		Bukkit.broadcast(Ui.mm(Ui.PREFIX + Ui.GOLD + "<bold>KOTH</bold> <white><bold>" + Ui.esc(p.getName()) + "</bold>" + (f == null ? "" : Ui.LABEL + " of <white>" + Ui.esc(f.tag()))
				+ Ui.LABEL + " captured " + Ui.GOLD + "<bold>" + Ui.esc(ar.name()) + "</bold>" + Ui.LABEL + "!"));
		for (Player o : Bukkit.getOnlinePlayers()) {
			o.showTitle(Title.title(Ui.mm(Ui.GOLD + "<bold>" + Ui.esc(ar.name()) + " CAPTURED"), Ui.mm("<white>" + Ui.esc(p.getName()) + (f == null ? "" : Ui.LABEL + " • " + Ui.esc(f.tag())))));
			o.playSound(o.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
		}
		reward(p, f);
		end();
	}

	private void reward(Player p, Faction f) {
		var c = plugin.getConfig();
		double money = c.getDouble("koth.reward.money", 1_000_000);
		if (money > 0 && Pay.ready()) {
			if (f != null && Econ.shouldBeUsedWithBanks()) Econ.modifyMoney(f, money, null, null);
			else Econ.modifyMoney(F.fp(p), money, null, null);
			Ui.msg(p, Ui.GREEN + "+" + Ui.money(money) + Ui.LABEL + (f != null ? " to your faction bank" : ""));
		}
		int pieces = c.getInt("koth.reward.gear-pieces", 2);
		List<Material> armor = new ArrayList<>(List.of(Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS));
		java.util.Collections.shuffle(armor, rnd);
		for (int i = 0; i < Math.min(pieces, armor.size()); i++) give(p, gear(armor.get(i)));
		if (c.getBoolean("koth.reward.sword", true) && rnd.nextDouble() < c.getDouble("koth.reward.sword-chance", 0.5)) give(p, gear(Material.NETHERITE_SWORD));
		for (String cmd : c.getStringList("koth.reward.commands"))
			Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", p.getName()).replace("{faction}", f == null ? "" : f.tag()));
	}

	/** KOTH reward gear: Protection 5 (armor) / Sharpness 5 (sword), tagged so laGCannons' gear rules let it be used. */
	ItemStack gear(Material m) {
		ItemStack it = new ItemStack(m);
		ItemMeta meta = it.getItemMeta();
		if (m == Material.NETHERITE_SWORD) meta.addEnchant(Enchantment.SHARPNESS, 5, true);
		else meta.addEnchant(Enchantment.PROTECTION, plugin.getConfig().getInt("koth.reward.protection", 5), true);
		meta.addEnchant(Enchantment.UNBREAKING, 3, true);
		NamespacedKey k = NamespacedKey.fromString("lagcannons:koth_gear");
		if (k != null) meta.getPersistentDataContainer().set(k, PersistentDataType.BYTE, (byte) 1);
		meta.lore(List.of(Ui.mm(Ui.GOLD + "KOTH Reward").decoration(TextDecoration.ITALIC, false)));
		it.setItemMeta(meta);
		return it;
	}

	private void give(Player p, ItemStack it) {
		p.getInventory().addItem(it).values().forEach(l -> p.getWorld().dropItemNaturally(p.getLocation(), l));
	}

	private void end() {
		if (task != -1) Bukkit.getScheduler().cancelTask(task);
		task = -1;
		if (bar != null) bar.removeAll();
		bar = null;
		active = null;
		capper = null;
	}

	List<String> names() {
		List<String> out = new ArrayList<>();
		for (Arena a : arenas.values()) out.add(a.name());
		return out;
	}
}
