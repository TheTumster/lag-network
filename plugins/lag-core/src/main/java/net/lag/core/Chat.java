package net.lag.core;

import java.util.List;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Premium chat layout helpers: pixel-accurate centering (default Minecraft font), the big pack wordmark, and
 * click buttons. Used by the hub welcome and every realm's join banner.
 *
 * Banner lines (config fresh-chat.banner.<role>) understand:
 *   {logo}      the big RELAPSEDMC wordmark (pack) or a spaced gradient title (no pack), centered, 3 lines tall
 *   {c}...      center the rest of the line
 *   {player} {online} {realm}
 *   [[label|command|hover]]  a clickable button (command starts with / ; or a https:// link opens a URL)
 */
final class Chat {
	static final int CHAT_WIDTH = 320;

	private Chat() { }

	/** Pixel advance of a character in the default font (glyph width + 1px spacing). */
	static int advance(int c) {
		if (c == ' ') return 4;
		if ("i!.,:;|'".indexOf(c) >= 0) return 2;
		if ("l`".indexOf(c) >= 0) return 3;
		if ("It[]".indexOf(c) >= 0) return 4;
		if ("fk<>(){}\"*".indexOf(c) >= 0) return 5;
		if (c == '@' || c == '~') return 7;
		if (c == '•' || c == '·') return 3;
		if (c >= 0xE000 && c <= 0xF8FF) return 0; // pack glyphs: measured separately
		return 6;
	}

	/** Width in pixels of a rendered component (bold adds 1px per character). */
	static int width(Component c) {
		return width(c, false);
	}

	private static int width(Component c, boolean parentBold) {
		TextDecoration.State st = c.style().decoration(TextDecoration.BOLD);
		boolean bold = st == TextDecoration.State.TRUE || (st == TextDecoration.State.NOT_SET && parentBold);
		int w = 0;
		if (c instanceof TextComponent t) {
			String s = t.content();
			for (int i = 0; i < s.length(); ) {
				int cp = s.codePointAt(i);
				int a = advance(cp);
				w += a + (bold && a > 0 ? 1 : 0);
				i += Character.charCount(cp);
			}
		}
		for (Component ch : c.children()) w += width(ch, bold);
		return w;
	}

	/** Left padding (spaces) to center a component in chat. */
	static Component center(Component c) {
		return center(c, width(c));
	}

	static Component center(Component c, int px) {
		int pad = Math.max(0, (CHAT_WIDTH - px) / 2);
		return Component.text(" ".repeat(pad / 4)).append(c);
	}

	/** The big wordmark: two blank lines (it draws upward over them) then the centered glyph. */
	static void logo(Player p, LagCore plugin) {
		boolean art = plugin.pack != null && plugin.pack.has(p) && !Pack.LOGO_CHAT.isEmpty();
		if (art) {
			p.sendMessage(Component.empty());
			p.sendMessage(Component.empty());
			p.sendMessage(center(LagCore.mm(Pack.glyph(Pack.LOGO_CHAT)), Pack.LOGO_CHAT_WIDTH));
		} else {
			String name = plugin.networkName().toUpperCase();
			StringBuilder spaced = new StringBuilder();
			for (char ch : name.toCharArray()) spaced.append(ch).append(' ');
			p.sendMessage(center(LagCore.mm("<gradient:#38E0FF:#B040FF><bold>" + spaced.toString().trim() + "</bold></gradient>")));
		}
	}

	/** Expand [[label|/command|hover]] buttons into MiniMessage click/hover tags. */
	static String buttons(String line) {
		StringBuilder out = new StringBuilder();
		int i = 0;
		while (true) {
			int a = line.indexOf("[[", i);
			if (a < 0) break;
			int b = line.indexOf("]]", a);
			if (b < 0) break;
			out.append(line, i, a);
			String[] parts = line.substring(a + 2, b).split("\\|", 3);
			String label = parts[0], target = parts.length > 1 ? parts[1].trim() : "", hover = parts.length > 2 ? parts[2] : "";
			String click;
			if (target.startsWith("http")) click = "<click:open_url:'" + target + "'>";
			else if (target.startsWith("/")) click = "<click:run_command:'" + target + "'>";
			else click = "";
			String hov = hover.isEmpty() ? "" : "<hover:show_text:'" + hover.replace("'", "\\'") + "'>";
			out.append(click).append(hov).append("<dark_gray>[</dark_gray>").append(label).append("<dark_gray>]</dark_gray>")
					.append(hover.isEmpty() ? "" : "</hover>").append(click.isEmpty() ? "" : "</click>");
			i = b + 2;
		}
		out.append(line.substring(i));
		return out.toString();
	}

	/** Send a configured banner (see class doc). */
	static void banner(Player p, LagCore plugin, List<String> lines, String realm) {
		for (String raw : lines) {
			String l = raw.replace("{player}", LagCore.esc(p.getName())).replace("{online}", String.valueOf(plugin.count("ALL")))
					.replace("{realm}", realm);
			if (l.trim().equals("{logo}")) {
				logo(p, plugin);
				continue;
			}
			boolean c = l.startsWith("{c}");
			if (c) l = l.substring(3);
			l = buttons(l.replace("{discord}", plugin.getConfig().getString("links.discord", ""))
					.replace("{store}", plugin.getConfig().getString("links.store", "")));
			Component comp = LagCore.mm(l);
			p.sendMessage(c ? center(comp) : comp);
		}
	}
}
