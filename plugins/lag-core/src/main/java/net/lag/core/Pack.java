package net.lag.core;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

/**
 * The RelapsedMC resource pack (logo, icons, menu panels; tools/pack/genpack.py). Downloaded once at startup to
 * get its SHA-1, then offered to everyone on join. Glyphs are only used for players who loaded it.
 */
final class Pack implements Listener {
	static final UUID ID = UUID.nameUUIDFromBytes("relapsedmc-pack".getBytes());
	static final String FONT = "relapsed:ui";

	// glyphs (font relapsed:ui)
	// logo glyph strings (tiled, made by tools/pack/genpack.py -> resources/glyphs.json)
	static final String LOGO_TAB, LOGO_LINE, LOGO_TITLE, LOGO_HOLO;
	static {
		java.util.Map<String, String> g = new java.util.HashMap<>();
		try (var in = Pack.class.getResourceAsStream("/glyphs.json")) {
			if (in != null) {
				var json = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
				for (var e : json.entrySet()) g.put(e.getKey(), e.getValue().getAsString());
			}
		} catch (Exception ignored) { }
		LOGO_TAB = g.getOrDefault("logo_tab", "");
		LOGO_LINE = g.getOrDefault("logo_line", "");
		LOGO_TITLE = g.getOrDefault("logo_title", "");
		LOGO_HOLO = g.getOrDefault("logo_holo", "");
	}
	static final String I_ARROW = "\uE010", I_PLAYERS = "\uE011", I_PING = "\uE012", I_TNT = "\uE013",
			I_SWORD = "\uE014", I_STAR = "\uE015", I_DOT = "\uE016", I_BOLT = "\uE017";
	static final String MENU_SELECTOR = "\uE100", MENU_COSMETICS = "\uE101";
	static final String NEG_8 = "\uF808", BACK_TO_TITLE = "\uF8B1";

	private final LagCore plugin;
	private final Set<UUID> loaded = new HashSet<>();
	private volatile byte[] hash;
	private volatile boolean ready;

	Pack(LagCore plugin) {
		this.plugin = plugin;
	}

	String url() {
		return plugin.getConfig().getString("resource-pack.url",
				"https://github.com/TheTumster/lag-network/releases/latest/download/RelapsedMC-pack.zip");
	}

	boolean enabled() {
		return plugin.getConfig().getBoolean("resource-pack.enabled", true);
	}

	void start() {
		if (!enabled()) return;
		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			try {
				HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).connectTimeout(Duration.ofSeconds(10)).build();
				byte[] body = c.send(HttpRequest.newBuilder(URI.create(url())).timeout(Duration.ofSeconds(30)).build(),
						HttpResponse.BodyHandlers.ofByteArray()).body();
				hash = MessageDigest.getInstance("SHA-1").digest(body);
				plugin.getLogger().info("Resource pack ready (" + body.length / 1024 + " KB, sha1 " + HexFormat.of().formatHex(hash) + ")");
			} catch (Exception e) {
				plugin.getLogger().warning("Couldn't fetch the resource pack (" + e.getMessage() + "); sending it without a hash");
			}
			ready = true;
			Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getOnlinePlayers().forEach(this::send));
		});
	}

	void send(Player p) {
		if (!enabled() || !ready) return;
		p.setResourcePack(ID, url(), hash, LagCore.mm("<gradient:#38E0FF:#B040FF><bold>RELAPSEDMC</bold></gradient>\n<gray>Our pack adds the logo, icons and menus.\n<dark_gray>(about 1 MB, one time)"),
				plugin.getConfig().getBoolean("resource-pack.required", false));
	}

	boolean has(Player p) {
		return p != null && loaded.contains(p.getUniqueId());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onJoin(PlayerJoinEvent e) {
		Player p = e.getPlayer();
		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			if (p.isOnline()) send(p);
		}, 2L);
	}

	@EventHandler
	public void onStatus(PlayerResourcePackStatusEvent e) {
		if (e.getID() != null && !ID.equals(e.getID())) return;
		switch (e.getStatus()) {
			case SUCCESSFULLY_LOADED -> loaded.add(e.getPlayer().getUniqueId());
			case DECLINED, FAILED_DOWNLOAD, FAILED_RELOAD, INVALID_URL, DISCARDED -> loaded.remove(e.getPlayer().getUniqueId());
			default -> { }
		}
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent e) {
		loaded.remove(e.getPlayer().getUniqueId());
	}

	// ---------------------------------------------------------------- MiniMessage helpers

	/** A glyph in the pack font, white so the texture shows its real colours. */
	static String glyph(String chars) {
		return "<white><font:" + FONT + ">" + chars + "</font></white>";
	}

	/** The logo (if the player has the pack) or the gradient text fallback. */
	String logo(Player p, String size) {
		if (has(p)) return glyph(size);
		return "<gradient:#38E0FF:#B040FF><bold>" + plugin.networkName().toUpperCase() + "</bold></gradient>";
	}

	String icon(Player p, String icon, String fallback) {
		return has(p) ? glyph(icon) : fallback;
	}

	/** Container title with a textured panel behind it (pack) or plain gradient text. */
	String menuTitle(Player p, String panel, String text) {
		if (has(p)) return glyph(NEG_8 + panel + BACK_TO_TITLE) + text;
		return text;
	}
}
