package net.lag.cannons;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * //upload <link> [name]
 * Downloads a .schem/.schematic from a link (Discord attachment links work) into FAWE's (or WorldEdit's)
 * schematics folder so you can //schem load it right away.
 */
final class SchemUpload {
	private static final long MAX_BYTES = 30L * 1024 * 1024;
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	private SchemUpload() { }

	private static void say(CommandSender s, String mm) {
		s.sendMessage(MiniMessage.miniMessage().deserialize(mm));
	}

	static boolean run(Plugin plugin, CommandSender sender, String[] args) {
		if (args.length < 1) {
			say(sender, "<gray>Usage: <white>//upload <link> [name]");
			say(sender, "<gray>Tip: drop the .schem in Discord, right-click it > Copy Link, then paste that here.");
			return true;
		}
		String url = args[0];
		if (!url.startsWith("http://") && !url.startsWith("https://")) {
			say(sender, "<red>That doesn't look like a link.");
			return true;
		}

		File folder = schematicsFolder();
		if (folder == null) {
			say(sender, "<red>FastAsyncWorldEdit / WorldEdit isn't installed on this server.");
			return true;
		}

		String fromUrl = fileNameFrom(url);
		String ext = fromUrl.toLowerCase(Locale.ROOT).endsWith(".schematic") ? ".schematic" : ".schem";
		String name = args.length > 1 ? args[1] : fromUrl.replaceAll("(?i)\\.(schem|schematic)$", "");
		name = name.replaceAll("[^A-Za-z0-9_\\-]", "_");
		if (name.isBlank()) name = "upload_" + (System.currentTimeMillis() / 1000);
		File out = new File(folder, name + ext);
		final String finalName = name;

		say(sender, "<gray>Downloading <white>" + finalName + ext + "<gray>...");
		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			String result;
			try {
				HttpRequest req = HttpRequest.newBuilder(URI.create(url))
						.timeout(Duration.ofSeconds(60))
						.header("User-Agent", "laGCannons/1.0")
						.GET().build();
				HttpResponse<InputStream> res = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
				if (res.statusCode() != 200) {
					result = "<red>Download failed (HTTP " + res.statusCode() + "). Discord links expire after a while; copy a fresh one.";
				} else {
					byte[] data;
					try (InputStream in = res.body()) {
						data = in.readNBytes((int) MAX_BYTES + 1);
					}
					if (data.length > MAX_BYTES) {
						result = "<red>That file is over 30 MB.";
					} else if (data.length < 2 || (data[0] & 0xFF) != 0x1F || (data[1] & 0xFF) != 0x8B) {
						// .schem/.schematic files are gzip-compressed NBT; anything else is the wrong file (or a web page)
						result = "<red>That link isn't a schematic file. Make sure it's a direct download link to a .schem.";
					} else {
						folder.mkdirs();
						Files.write(out.toPath(), data);
						result = "<gradient:#FF5C5C:#FFA19A>Uploaded!</gradient> <gray>Now run <white>//schem load " + finalName
								+ "<gray> then <white>//paste";
					}
				}
			} catch (Exception e) {
				result = "<red>Download failed: " + e.getMessage();
			}
			final String msg = result;
			Bukkit.getScheduler().runTask(plugin, () -> say(sender, msg));
		});
		return true;
	}

	private static File schematicsFolder() {
		for (String name : new String[] {"FastAsyncWorldEdit", "WorldEdit"}) {
			Plugin p = Bukkit.getPluginManager().getPlugin(name);
			if (p != null) return new File(p.getDataFolder(), "schematics");
		}
		return null;
	}

	private static String fileNameFrom(String url) {
		try {
			String path = URI.create(url).getPath();
			String last = path.substring(path.lastIndexOf('/') + 1);
			return URLDecoder.decode(last, StandardCharsets.UTF_8);
		} catch (Exception e) {
			return "upload.schem";
		}
	}
}
