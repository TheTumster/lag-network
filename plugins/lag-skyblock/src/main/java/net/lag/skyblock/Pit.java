package net.lag.skyblock;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

/**
 * The Pit: the only PvP on Skyblock. A walled arena floating south of spawn in the main (spawn) world; islands stay
 * safe. Hitting or getting hit in the Pit tags you for pit.tag-seconds: no commands (except chat ones), no flying,
 * no pearling out, and logging out kills you. /pit takes you there.
 */
final class Pit implements Listener, CommandExecutor {
	private final LagSkyblock plugin;
	private final Map<UUID, Long> tagged = new HashMap<>();
	private final Map<UUID, Integer> streak = new HashMap<>();
	private final File state;

	Pit(LagSkyblock plugin) {
		this.plugin = plugin;
		this.state = new File(plugin.getDataFolder(), "pit.yml");
		Bukkit.getScheduler().runTask(plugin, this::start);
		Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 10L);
	}

	boolean enabled() {
		return plugin.getConfig().getBoolean("pit.enabled", true);
	}

	World world() {
		return Bukkit.getWorlds().get(0);
	}

	Location center() {
		List<Integer> c = plugin.getConfig().getIntegerList("pit.center");
		if (c.size() < 3) c = List.of(0, 100, 80);
		return new Location(world(), c.get(0) + 0.5, c.get(1), c.get(2) + 0.5);
	}

	int radius() {
		return Math.max(6, plugin.getConfig().getInt("pit.radius", 16));
	}

	/** Inside the arena (on the floor, inside the walls). */
	boolean in(Location l) {
		if (!enabled() || l == null || l.getWorld() == null || !l.getWorld().equals(world())) return false;
		Location c = center();
		double dx = l.getX() - c.getX(), dz = l.getZ() - c.getZ();
		return dx * dx + dz * dz <= (radius() + 0.7) * (radius() + 0.7) && l.getY() >= c.getY() - 3 && l.getY() <= c.getY() + 16;
	}

	/** The arena plus its wall and a margin: nobody builds here. */
	private boolean area(Location l) {
		if (!enabled() || l == null || l.getWorld() == null || !l.getWorld().equals(world())) return false;
		Location c = center();
		double dx = l.getX() - c.getX(), dz = l.getZ() - c.getZ(), m = radius() + 4;
		return dx * dx + dz * dz <= m * m && l.getY() >= c.getY() - 8 && l.getY() <= c.getY() + 20;
	}

	boolean isTagged(Player p) {
		Long t = tagged.get(p.getUniqueId());
		return t != null && t > System.currentTimeMillis();
	}

	private void start() {
		if (!enabled()) return;
		World w = world();
		// PvP has to be on in this world for the arena; everywhere outside it we cancel player damage ourselves
		// (1.21.9+ made pvp a gamerule; older API has World#setPVP. Reflection so either compiles.)
		try {
			Object rule = GameRule.class.getField("PVP").get(null);
			World.class.getMethod("setGameRule", GameRule.class, Object.class).invoke(w, rule, Boolean.TRUE);
		} catch (Throwable ignored) { }
		try {
			World.class.getMethod("setPVP", boolean.class).invoke(w, true);
		} catch (Throwable ignored) { }
		holograms();
		YamlConfiguration y = YamlConfiguration.loadConfiguration(state);
		if (!y.getBoolean("built", false)) {
			int n = build();
			y.set("built", true);
			try {
				y.save(state);
			} catch (Exception ignored) { }
			plugin.getLogger().info("The Pit built (" + n + " blocks)");
		}
	}

	// ---------------------------------------------------------------- the arena

	private final org.bukkit.NamespacedKey holoKey = new org.bukkit.NamespacedKey("lagskyblock", "pit_holo");

	/** Floating "THE PIT" sign over the arena (not saved; re-made each start). Keeps the arena chunks loaded. */
	void holograms() {
		World w = world();
		Location c = center();
		int r = radius() + 2;
		for (int x = (c.getBlockX() - r) >> 4; x <= (c.getBlockX() + r) >> 4; x++)
			for (int z = (c.getBlockZ() - r) >> 4; z <= (c.getBlockZ() + r) >> 4; z++) w.getChunkAt(x, z).addPluginChunkTicket(plugin);
		for (org.bukkit.entity.Entity e : w.getEntities()) if (e.getPersistentDataContainer().has(holoKey)) e.remove();
		text(c.clone().add(0, 9.5, 0), "<gradient:#FF4D6D:#B040FF><bold>THE PIT</bold></gradient>", 3f);
		text(c.clone().add(0, 8.3, 0), "<gray>Fight in the ring <dark_gray>• <gray>" + plugin.getConfig().getLong("pit.tag-seconds", 15)
				+ "s combat tag <dark_gray>• <gray>leave: <white>/spawn", 1f);
	}

	private void text(Location at, String mm, float scale) {
		at.getWorld().spawn(at, org.bukkit.entity.TextDisplay.class, t -> {
			t.getPersistentDataContainer().set(holoKey, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
			t.setPersistent(false);
			t.text(Spawners.mm(mm));
			t.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
			t.setShadowed(true);
			t.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
			t.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
			t.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.AxisAngle4f(),
					new org.joml.Vector3f(scale, scale, scale), new org.joml.AxisAngle4f()));
		});
	}

	private static void set(World w, int x, int y, int z, Material m) {
		w.getBlockAt(x, y, z).setType(m, false);
	}

	/** Places the arena. Returns the number of blocks set. */
	int build() {
		World w = world();
		Location c = center();
		int cx = c.getBlockX(), cy = c.getBlockY(), cz = c.getBlockZ(), r = radius();
		int n = 0;
		for (int dx = -r - 2; dx <= r + 2; dx++) {
			for (int dz = -r - 2; dz <= r + 2; dz++) {
				double d = Math.sqrt(dx * dx + dz * dz);
				int x = cx + dx, z = cz + dz;
				// clear the space first (re-builds)
				if (d <= r + 1.5) for (int y = cy + 1; y <= cy + 16; y++) set(w, x, y, z, Material.AIR);
				if (d <= r + 0.5) {
					Material floor;
					if (d <= 2.2) floor = Material.CRYING_OBSIDIAN;
					else if (d <= 3.4) floor = Material.PURPUR_BLOCK;
					else if (d > 7.6 && d <= 8.6) floor = Material.DARK_PRISMARINE;
					else if (d > r - 1.5) floor = Material.POLISHED_BLACKSTONE_BRICKS;
					else floor = ((dx + dz) & 1) == 0 ? Material.POLISHED_DEEPSLATE : Material.DEEPSLATE_TILES;
					// lights set into the floor, 8 around the middle ring
					if (d > 11.5 && d <= 12.5 && Math.abs(Math.atan2(dz, dx) % (Math.PI / 4)) < 0.06) floor = Material.SEA_LANTERN;
					set(w, x, cy, z, floor);
					n++;
					// the rock under it, tapering down
					set(w, x, cy - 1, z, Material.DEEPSLATE);
					if (d <= r - 2) set(w, x, cy - 2, z, Material.COBBLED_DEEPSLATE);
					if (d <= r - 5) set(w, x, cy - 3, z, Material.TUFF);
					if (d <= r - 9) set(w, x, cy - 4, z, Material.TUFF);
					if (d <= 3) set(w, x, cy - 5, z, Material.AMETHYST_BLOCK);
					n += 2;
				} else if (d <= r + 1.5) {
					// wall: 4 high, purple/cyan glass band, invisible barrier above so nobody jumps out
					set(w, x, cy, z, Material.DEEPSLATE_BRICKS);
					set(w, x, cy - 1, z, Material.DEEPSLATE);
					boolean pillar = Math.abs(Math.atan2(dz, dx) % (Math.PI / 6)) < 0.07;
					for (int y = 1; y <= 4; y++) {
						Material m = pillar ? Material.POLISHED_BLACKSTONE : y == 3 ? (((dx + dz) & 1) == 0 ? Material.PURPLE_STAINED_GLASS : Material.CYAN_STAINED_GLASS) : Material.DEEPSLATE_BRICKS;
						set(w, x, cy + y, z, m);
					}
					set(w, x, cy + 5, z, pillar ? Material.END_ROD : Material.POLISHED_BLACKSTONE_BRICK_SLAB);
					for (int y = 6; y <= 16; y++) set(w, x, cy + y, z, Material.BARRIER);
					n += 16;
				}
			}
		}
		return n;
	}

	/** A spot on the arena's edge facing the middle. */
	private Location drop() {
		Location c = center();
		double a = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
		double d = radius() - 3;
		Location l = c.clone().add(Math.cos(a) * d, 1, Math.sin(a) * d);
		Vector look = c.toVector().subtract(l.toVector());
		l.setDirection(look);
		l.setPitch(0);
		return l;
	}

	// ---------------------------------------------------------------- tick: tag countdown, no flying in the Pit

	private void tick() {
		long now = System.currentTimeMillis();
		for (Player p : Bukkit.getOnlinePlayers()) {
			boolean free = p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR;
			if (!free && in(p.getLocation()) && (p.getAllowFlight() || p.isFlying())) {
				p.setFlying(false);
				p.setAllowFlight(false);
				p.sendActionBar(Spawners.mm("<red>No flying in the Pit"));
			}
			Long t = tagged.get(p.getUniqueId());
			if (t == null) continue;
			if (t <= now) {
				tagged.remove(p.getUniqueId());
				p.sendActionBar(Spawners.mm("<#7CFF6B>You're out of combat"));
			} else {
				p.sendActionBar(Spawners.mm("<red><bold>IN COMBAT</bold> <gray>" + ((t - now) / 1000 + 1) + "s <dark_gray>· don't log out"));
			}
		}
	}

	private void tag(Player p) {
		long until = System.currentTimeMillis() + plugin.getConfig().getLong("pit.tag-seconds", 15) * 1000L;
		if (!isTagged(p)) p.sendMessage(Spawners.mm("<red>You're in combat. <gray>Logging out now kills you."));
		tagged.put(p.getUniqueId(), until);
		if (p.isFlying() || p.getAllowFlight()) {
			if (p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR) {
				p.setFlying(false);
				p.setAllowFlight(false);
			}
		}
	}

	private static Player attacker(Entity damager) {
		if (damager instanceof Player p) return p;
		if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
		if (damager instanceof org.bukkit.entity.TNTPrimed t && t.getSource() instanceof Player p) return p;
		if (damager instanceof org.bukkit.entity.AreaEffectCloud c && c.getSource() instanceof Player p) return p;
		return null;
	}

	// ---------------------------------------------------------------- PvP only inside the Pit

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onDamage(EntityDamageByEntityEvent e) {
		if (!(e.getEntity() instanceof Player victim) || !victim.getWorld().equals(world())) return;
		Player hitter = attacker(e.getDamager());
		if (hitter == null || hitter.equals(victim)) return;
		if (!in(victim.getLocation()) || !in(hitter.getLocation())) {
			e.setCancelled(true);
			return;
		}
		tag(victim);
		tag(hitter);
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onSplash(PotionSplashEvent e) {
		if (!(e.getPotion().getShooter() instanceof Player thrower) || !thrower.getWorld().equals(world())) return;
		boolean ok = in(thrower.getLocation());
		for (LivingEntity le : e.getAffectedEntities()) {
			if (le instanceof Player p && !p.equals(thrower) && (!ok || !in(p.getLocation()))) e.setIntensity(le, 0);
		}
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void onDeath(PlayerDeathEvent e) {
		Player dead = e.getEntity();
		boolean wasTagged = tagged.remove(dead.getUniqueId()) != null;
		int had = streak.getOrDefault(dead.getUniqueId(), 0);
		streak.remove(dead.getUniqueId());
		if (!in(dead.getLocation()) && !wasTagged) return;
		if (plugin.getConfig().getBoolean("pit.keep-inventory", false)) {
			e.setKeepInventory(true);
			e.setKeepLevel(true);
			e.getDrops().clear();
			e.setDroppedExp(0);
		}
		Player killer = dead.getKiller();
		if (killer == null || killer.equals(dead)) return;
		int s = streak.merge(killer.getUniqueId(), 1, Integer::sum);
		double reward = plugin.getConfig().getDouble("pit.kill-reward", 0);
		if (reward > 0 && plugin.eco() != null) plugin.eco().depositPlayer(killer, reward);
		e.deathMessage(Spawners.mm("<dark_red>☠ <white>" + killer.getName() + " <gray>killed <white>" + dead.getName() + " <gray>in <red><bold>THE PIT"
				+ (had >= 3 ? " <gray>ending their <red>" + had + "</red> streak" : "")));
		killer.playSound(killer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.8f);
		if (s >= 3 && s % 2 == 1) {
			Bukkit.broadcast(Spawners.mm("<dark_gray>» <white>" + killer.getName() + " <gray>is on a <red><bold>" + s + " KILL STREAK</bold> <gray>in the Pit <dark_gray>(/pit)"));
		}
	}

	// ---------------------------------------------------------------- no escaping a fight

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onCommand(PlayerCommandPreprocessEvent e) {
		Player p = e.getPlayer();
		if (!isTagged(p) || p.hasPermission("lag.skyblock.admin")) return;
		String root = e.getMessage().substring(1).split(" ")[0].toLowerCase();
		if (root.contains(":")) root = root.substring(root.indexOf(':') + 1);
		List<String> ok = plugin.getConfig().getStringList("pit.allowed-commands");
		if (ok.isEmpty()) ok = List.of("msg", "tell", "w", "r", "reply", "m", "whisper");
		if (ok.contains(root)) return;
		e.setCancelled(true);
		p.sendMessage(Spawners.mm("<red>You can't use that in combat. <gray>(" + (Math.max(0, tagged.get(p.getUniqueId()) - System.currentTimeMillis()) / 1000 + 1) + "s)"));
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onTeleport(PlayerTeleportEvent e) {
		if (!isTagged(e.getPlayer()) || in(e.getTo())) return;
		var cause = e.getCause();
		if (cause == PlayerTeleportEvent.TeleportCause.ENDER_PEARL || cause == PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT
				|| cause == PlayerTeleportEvent.TeleportCause.COMMAND || cause == PlayerTeleportEvent.TeleportCause.PLUGIN) {
			e.setCancelled(true);
			e.getPlayer().sendMessage(Spawners.mm("<red>You can't leave the Pit in combat."));
		}
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent e) {
		Player p = e.getPlayer();
		if (isTagged(p) && !p.isDead()) {
			p.setHealth(0);
			Bukkit.broadcast(Spawners.mm("<dark_gray>» <white>" + p.getName() + " <gray>logged out in combat and <red>died"));
		}
		tagged.remove(p.getUniqueId());
		streak.remove(p.getUniqueId());
	}

	// ---------------------------------------------------------------- nobody builds in the Pit

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onBreak(BlockBreakEvent e) {
		if (!e.getPlayer().hasPermission("lag.build") && area(e.getBlock().getLocation())) e.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onPlace(BlockPlaceEvent e) {
		if (!e.getPlayer().hasPermission("lag.build") && area(e.getBlock().getLocation())) e.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onBucket(PlayerBucketEmptyEvent e) {
		if (!e.getPlayer().hasPermission("lag.build") && area(e.getBlock().getLocation())) e.setCancelled(true);
	}

	// ---------------------------------------------------------------- /pit

	@Override
	public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
		if (!(s instanceof Player p)) return true;
		if (!enabled()) {
			p.sendMessage(Spawners.mm("<red>The Pit is closed."));
			return true;
		}
		if (in(p.getLocation())) {
			p.sendMessage(Spawners.mm("<gray>You're already in the Pit."));
			return true;
		}
		p.teleport(drop());
		p.setFallDistance(0);
		p.showTitle(net.kyori.adventure.title.Title.title(Spawners.mm("<red><bold>THE PIT"),
				Spawners.mm("<gray>PvP is on. <white>/spawn <gray>to leave")));
		p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.25f, 1.6f);
		return true;
	}
}
