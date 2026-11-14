package net.lag.lab;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Dispenser;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** 1.8 cannon lab: /tntfill [radius] [amount] fills every dispenser near you (creative test server, no cost). */
public class LabTools extends JavaPlugin {
	private Sandbots sandbots;

	@Override
	public void onEnable() {
		sandbots = new Sandbots(this);
		getServer().getPluginManager().registerEvents(sandbots, this);
		getServer().getPluginManager().registerEvents(new WaterProof(), this);
		getServer().getPluginManager().registerEvents(new CannonProtect(this), this);
	}

	@Override
	public void onDisable() {
		if (sandbots != null) sandbots.shutdown();
	}

	@Override
	public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
		if (cmd.getName().equalsIgnoreCase("/upload")) {
			Upload.run(this, sender, args);
			return true;
		}
		if (!(sender instanceof Player)) return true;
		Player p = (Player) sender;
		if (cmd.getName().equalsIgnoreCase("sandbot")) return sandbots.command(p, args);
		int r = 32, amount = 576;
		try {
			if (args.length > 0) r = Math.max(1, Math.min(128, Integer.parseInt(args[0])));
			if (args.length > 1) amount = Math.max(1, Math.min(576, Integer.parseInt(args[1])));
		} catch (NumberFormatException e) {
			p.sendMessage(ChatColor.RED + "Usage: /tntfill [radius] [amount per dispenser]");
			return true;
		}
		Location c = p.getLocation();
		int n = 0;
		for (int x = c.getBlockX() - r; x <= c.getBlockX() + r; x++)
			for (int y = Math.max(0, c.getBlockY() - r); y <= Math.min(255, c.getBlockY() + r); y++)
				for (int z = c.getBlockZ() - r; z <= c.getBlockZ() + r; z++) {
					Block b = c.getWorld().getBlockAt(x, y, z);
					if (b.getType() != Material.DISPENSER) continue;
					BlockState st = b.getState();
					if (!(st instanceof Dispenser)) continue;
					((Dispenser) st).getInventory().clear();
					int left = amount;
					for (int i = 0; i < 9 && left > 0; i++) {
						int take = Math.min(64, left);
						((Dispenser) st).getInventory().setItem(i, new ItemStack(Material.TNT, take));
						left -= take;
					}
					n++;
				}
		p.sendMessage(ChatColor.LIGHT_PURPLE + "[laG Lab] " + ChatColor.GRAY + "Filled " + ChatColor.WHITE + n + ChatColor.GRAY
				+ " dispensers with " + amount + " TNT each (radius " + r + ").");
		return true;
	}
}
