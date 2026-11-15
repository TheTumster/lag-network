package net.lag.skyblock;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * /fly on your own island (permission lag.skyblock.islandfly - a rank perk). Turns off when you leave the island,
 * and the fall after it is cancelled.
 */
final class IslandFly implements Listener, CommandExecutor {
	static final String PERM = "lag.skyblock.islandfly";
	private final Set<UUID> on = new HashSet<>();
	private final Set<UUID> safeFall = new HashSet<>();

	IslandFly(LagSkyblock plugin) {
		Bukkit.getScheduler().runTaskTimer(plugin, this::check, 20L, 10L);
	}

	private boolean allowedHere(Player p) {
		return Islands.member(Islands.at(p.getLocation()), p.getUniqueId());
	}

	private boolean free(Player p) {
		return p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR;
	}

	private void check() {
		for (UUID id : new HashSet<>(on)) {
			Player p = Bukkit.getPlayer(id);
			if (p == null) {
				on.remove(id);
				continue;
			}
			if (free(p)) continue;
			if (!allowedHere(p)) {
				on.remove(id);
				p.setFlying(false);
				p.setAllowFlight(false);
				safeFall.add(id);
				p.sendActionBar(Spawners.mm("<#FFB020>Island fly off <gray>- you left your island"));
				p.playSound(p.getLocation(), Sound.ITEM_ELYTRA_FLYING, 0.3f, 1.6f);
			}
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onFall(EntityDamageEvent e) {
		if (e.getCause() == EntityDamageEvent.DamageCause.FALL && e.getEntity() instanceof Player p && safeFall.remove(p.getUniqueId())) {
			e.setCancelled(true);
		}
	}

	/** /fly and /is fly go to island fly for players without Essentials' own /fly. */
	@EventHandler(ignoreCancelled = true)
	public void onCmd(org.bukkit.event.player.PlayerCommandPreprocessEvent e) {
		String m = e.getMessage().toLowerCase().trim();
		if ((m.equals("/fly") || m.equals("/is fly") || m.equals("/island fly")) && !e.getPlayer().hasPermission("essentials.fly")) {
			e.setCancelled(true);
			onCommand(e.getPlayer(), null, "islandfly", new String[0]);
		}
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent e) {
		on.remove(e.getPlayer().getUniqueId());
		safeFall.remove(e.getPlayer().getUniqueId());
	}

	@Override
	public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
		if (!(s instanceof Player p)) return true;
		if (!p.hasPermission(PERM)) {
			p.sendMessage(Spawners.mm("<red>Island fly is a rank perk."));
			return true;
		}
		if (on.remove(p.getUniqueId())) {
			p.setFlying(false);
			p.setAllowFlight(false);
			safeFall.add(p.getUniqueId());
			p.sendMessage(Spawners.mm("<gray>Island fly <red>off"));
			return true;
		}
		if (!allowedHere(p)) {
			p.sendMessage(Spawners.mm("<red>You can only fly on your own island."));
			return true;
		}
		on.add(p.getUniqueId());
		p.setAllowFlight(true);
		p.sendMessage(Spawners.mm("<gray>Island fly <#7CFF6B>on <dark_gray>(turns off if you leave your island)"));
		p.playSound(p.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 0.5f, 1.4f);
		return true;
	}
}
