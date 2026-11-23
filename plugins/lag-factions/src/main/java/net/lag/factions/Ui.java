package net.lag.factions;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

/** RelapsedMC look: brand prefix, MiniMessage, menu items, sounds. */
final class Ui {
	static final ZoneId EST = ZoneId.of("America/New_York");
	static final String GRAD = "<gradient:#FF5C5C:#FFA19A>";
	static final String RED = "<#FF4D6D>", PURPLE = "<#B040FF>", CYAN = "<#38E0FF>", GREEN = "<#7CFF6B>", GOLD = "<#FFD24D>",
			LABEL = "<#8A8FA3>", DIM = "<#4A4E5C>";
	static final String PREFIX = GRAD + "<bold>F</bold></gradient> <dark_gray>»</dark_gray> ";

	private Ui() { }

	static Component mm(String s) {
		return MiniMessage.miniMessage().deserialize(s);
	}

	static String esc(String s) {
		return MiniMessage.miniMessage().escapeTags(s == null ? "" : s);
	}

	static void msg(CommandSender to, String mm) {
		to.sendMessage(mm(PREFIX + mm));
	}

	static void err(Player p, String mm) {
		p.sendMessage(mm(PREFIX + RED + mm));
		p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.7f);
	}

	static void ok(Player p, String mm) {
		p.sendMessage(mm(PREFIX + mm));
		p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.4f);
	}

	static void click(Player p) {
		p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.6f);
	}

	/** A menu item: name + lore lines, no italics, no vanilla tooltips. */
	static ItemStack item(Material m, String name, String... lore) {
		return item(m, false, name, lore);
	}

	static ItemStack item(Material m, boolean glow, String name, String... lore) {
		ItemStack it = new ItemStack(m);
		ItemMeta meta = it.getItemMeta();
		meta.displayName(mm(name).decoration(TextDecoration.ITALIC, false));
		List<Component> l = new ArrayList<>();
		for (String s : lore) l.add(mm(s).decoration(TextDecoration.ITALIC, false));
		meta.lore(l);
		meta.addItemFlags(ItemFlag.values());
		if (glow) meta.setEnchantmentGlintOverride(true);
		it.setItemMeta(meta);
		return it;
	}

	/** 8 PM, 12 AM ... */
	static String hour(int h) {
		h = ((h % 24) + 24) % 24;
		int t = h % 12 == 0 ? 12 : h % 12;
		return t + (h < 12 ? " AM" : " PM");
	}

	static String duration(long ms) {
		long m = Math.max(0, ms / 60000);
		long d = m / 1440, hh = (m / 60) % 24, mm = m % 60;
		if (d > 0) return d + "d " + hh + "h";
		if (hh > 0) return hh + "h " + mm + "m";
		return Math.max(1, mm) + "m";
	}

	static String money(double v) {
		if (v >= 1_000_000_000) return String.format("$%.2fB", v / 1e9);
		if (v >= 1_000_000) return String.format("$%.2fM", v / 1e6);
		if (v >= 10_000) return String.format("$%.1fK", v / 1e3);
		return String.format("$%,.0f", v);
	}
}
