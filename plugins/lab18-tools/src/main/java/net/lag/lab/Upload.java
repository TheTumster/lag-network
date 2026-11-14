package net.lag.lab;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

/** //upload <link> [name] on the 1.8 lab: saves a schematic from a link (Discord links work) into WorldEdit's folder. */
final class Upload {
	private static final int MAX = 30 * 1024 * 1024;

	static void run(final Plugin plugin, final CommandSender s, String[] args) {
		if (args.length < 1 || !(args[0].startsWith("http://") || args[0].startsWith("https://"))) {
			s.sendMessage(ChatColor.GRAY + "Usage: " + ChatColor.WHITE + "//upload <link> [name]");
			s.sendMessage(ChatColor.GRAY + "Tip: drop the .schematic in Discord, right-click it > Copy Link, paste it here.");
			return;
		}
		final String url = args[0];
		Plugin we = Bukkit.getPluginManager().getPlugin("WorldEdit");
		if (we == null) {
			s.sendMessage(ChatColor.RED + "WorldEdit isn't installed.");
			return;
		}
		final File folder = new File(we.getDataFolder(), "schematics");
		String fromUrl = "upload";
		try {
			String path = new URL(url).getPath();
			fromUrl = URLDecoder.decode(path.substring(path.lastIndexOf('/') + 1), "UTF-8");
		} catch (Exception ignored) { }
		final boolean sponge = fromUrl.toLowerCase().endsWith(".schem");
		String name = args.length > 1 ? args[1] : fromUrl.replaceAll("(?i)\\.(schem|schematic)$", "");
		name = name.replaceAll("[^A-Za-z0-9_\\-]", "_");
		if (name.isEmpty()) name = "upload_" + (System.currentTimeMillis() / 1000);
		final String fname = name;
		s.sendMessage(ChatColor.GRAY + "Downloading " + ChatColor.WHITE + fname + ChatColor.GRAY + "...");
		Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
			public void run() {
				String msg;
				try {
					HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
					c.setInstanceFollowRedirects(true);
					c.setConnectTimeout(10000);
					c.setReadTimeout(60000);
					c.setRequestProperty("User-Agent", "laGLab/1.0");
					if (c.getResponseCode() != 200) {
						msg = ChatColor.RED + "Download failed (HTTP " + c.getResponseCode() + "). Discord links expire; copy a fresh one.";
					} else {
						ByteArrayOutputStream bo = new ByteArrayOutputStream();
						InputStream in = c.getInputStream();
						byte[] buf = new byte[65536];
						int n;
						while ((n = in.read(buf)) > 0) {
							bo.write(buf, 0, n);
							if (bo.size() > MAX) break;
						}
						in.close();
						byte[] d = bo.toByteArray();
						if (d.length > MAX) msg = ChatColor.RED + "That file is over 30 MB.";
						else if (d.length < 2 || (d[0] & 0xFF) != 0x1F || (d[1] & 0xFF) != 0x8B)
							msg = ChatColor.RED + "That link isn't a schematic file (needs a direct download link).";
						else {
							folder.mkdirs();
							FileOutputStream fo = new FileOutputStream(new File(folder, fname + ".schematic"));
							fo.write(d);
							fo.close();
							msg = ChatColor.LIGHT_PURPLE + "Uploaded! " + ChatColor.GRAY + "Now run " + ChatColor.WHITE + "//schem load " + fname
									+ ChatColor.GRAY + " then " + ChatColor.WHITE + "//paste"
									+ (sponge ? ChatColor.YELLOW + " (note: that's a 1.13+ .schem; 1.8 WorldEdit can only load old .schematic files)" : "");
						}
					}
				} catch (Exception e) {
					msg = ChatColor.RED + "Download failed: " + e.getMessage();
				}
				final String m = msg;
				Bukkit.getScheduler().runTask(plugin, new Runnable() {
					public void run() {
						s.sendMessage(m);
					}
				});
			}
		});
	}
}
