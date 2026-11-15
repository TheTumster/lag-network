package net.lag.cannons;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.plugin.java.JavaPlugin;

import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Cannon-safe ClearLag: on a timer (with countdown warnings) removes only dropped items, stuck arrows and XP orbs.
 * Never touches primed TNT, falling sand, minecarts, armor stands, item frames, mobs or named items.
 * Config: clearlag.*  Command: /clearlag [now|status]
 */
final class ClearLag implements CommandExecutor {
	private final JavaPlugin plugin;
	private int secondsLeft;

	ClearLag(JavaPlugin plugin) {
		this.plugin = plugin;
		secondsLeft = interval();
		Bukkit.getScheduler().runTaskTimer(plugin, this::second, 20L, 20L);
	}

	private int interval() {
		return Math.max(30, plugin.getConfig().getInt("clearlag.interval-seconds", 300));
	}

	private void second() {
		if (!plugin.getConfig().getBoolean("clearlag.enabled", true)) return;
		secondsLeft--;
		List<Integer> warn = plugin.getConfig().getIntegerList("clearlag.warn-at");
		if (warn.isEmpty()) warn = List.of(60, 30, 10, 5, 4, 3, 2, 1);
		if (warn.contains(secondsLeft)) {
			broadcast("<dark_gray>[<gradient:#38E0FF:#B040FF>ClearLag</gradient><dark_gray>] <gray>Dropped items clear in <white>"
					+ secondsLeft + "s");
		}
		if (secondsLeft <= 0) {
			int n = clear();
			broadcast("<dark_gray>[<gradient:#38E0FF:#B040FF>ClearLag</gradient><dark_gray>] <gray>Cleared <white>" + n + "<gray> entities.");
			secondsLeft = interval();
		}
	}

	private void broadcast(String msg) {
		Bukkit.broadcast(MiniMessage.miniMessage().deserialize(msg));
	}

	int clear() {
		Set<Material> keep = new HashSet<>();
		for (String s : plugin.getConfig().getStringList("clearlag.keep-items")) {
			Material m = Material.matchMaterial(s.toUpperCase(Locale.ROOT));
			if (m != null) keep.add(m);
		}
		boolean items = plugin.getConfig().getBoolean("clearlag.clear.items", true);
		boolean arrows = plugin.getConfig().getBoolean("clearlag.clear.arrows", true);
		boolean xp = plugin.getConfig().getBoolean("clearlag.clear.xp-orbs", false);
		List<String> worlds = plugin.getConfig().getStringList("clearlag.worlds");
		int n = 0;
		for (World w : Bukkit.getWorlds()) {
			if (!worlds.isEmpty() && !worlds.contains(w.getName())) continue;
			for (Entity e : w.getEntities()) {
				if (e.customName() != null) continue;
				if (items && e instanceof Item it && !keep.contains(it.getItemStack().getType())) {
					e.remove();
					n++;
				} else if (arrows && e instanceof AbstractArrow a && (a.isInBlock() || a.isOnGround())) {
					e.remove();
					n++;
				} else if (xp && e instanceof ExperienceOrb) {
					e.remove();
					n++;
				}
			}
		}
		return n;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		var mm = MiniMessage.miniMessage();
		if (args.length > 0 && args[0].equalsIgnoreCase("now")) {
			if (!sender.hasPermission("lag.cannons.clearlag.now")) {
				sender.sendMessage(mm.deserialize("<red>No permission."));
				return true;
			}
			int n = clear();
			broadcast("<dark_gray>[<gradient:#38E0FF:#B040FF>ClearLag</gradient><dark_gray>] <gray>Cleared <white>" + n + "<gray> entities.");
			secondsLeft = interval();
			return true;
		}
		sender.sendMessage(mm.deserialize("<gray>Next clear in <white>" + secondsLeft + "s <dark_gray>(every " + interval()
				+ "s; items" + (plugin.getConfig().getBoolean("clearlag.clear.arrows", true) ? ", arrows" : "")
				+ (plugin.getConfig().getBoolean("clearlag.clear.xp-orbs", false) ? ", xp" : "") + "; never TNT or sand)"));
		return true;
	}
}
