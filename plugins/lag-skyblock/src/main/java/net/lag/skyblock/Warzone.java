package net.lag.skyblock;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * The Warzone: the shattered arena under spawn (built by laGCore), the only PvP on Skyblock. Islands stay safe.
 * You get there by jumping into the Rift (/warzone takes you to its edge). Fighting tags you for warzone.tag-seconds:
 * no commands except chat ones, no flying, no pearling out, and logging out kills you.
 * Envoys: every few minutes loot chests land across the arena (common / rare / legendary). KOTH: hold the hill alone
 * for capture-seconds to win; runs on a timer or /lsb koth start.
 */
final class Warzone implements Listener, CommandExecutor {
	private final LagSkyblock plugin;
	private final NamespacedKey holoKey;
	private final Map<UUID, Long> tagged = new HashMap<>();
	private final Map<UUID, Integer> streak = new HashMap<>();
	private final List<Location> floor = new ArrayList<>();
	private final Map<Block, String> envoys = new HashMap<>();
	private final Map<Block, TextDisplay> envoyLabels = new HashMap<>();
	private long nextEnvoy, nextKoth;
	// KOTH
	private boolean kothLive;
	private UUID capper;
	private int progress;
	private BossBar bar;
	private TextDisplay kothLabel;

	Warzone(LagSkyblock plugin) {
		this.plugin = plugin;
		this.holoKey = new NamespacedKey(plugin, "warzone_holo");
		long now = System.currentTimeMillis();
		nextEnvoy = now + minutes("warzone.envoy.interval-minutes", 30);
		nextKoth = now + minutes("warzone.koth.interval-minutes", 120);
		Bukkit.getScheduler().runTaskLater(plugin, this::start, 80L);
		Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 100L, 10L);
		Bukkit.getScheduler().runTaskTimer(plugin, this::second, 100L, 20L);
	}

	private long minutes(String path, int def) {
		return Math.max(1, plugin.getConfig().getLong(path, def)) * 60_000L;
	}

	World world() {
		return Bukkit.getWorlds().get(0);
	}

	private double[] vec(String path, double... def) {
		List<?> l = plugin.getConfig().getList(path);
		if (l == null || l.size() < def.length) return def;
		double[] out = new double[l.size()];
		for (int i = 0; i < out.length; i++) out[i] = ((Number) l.get(i)).doubleValue();
		return out;
	}

	Location origin() {
		double[] o = vec("spawn.origin", 0, 100, 0);
		return new Location(world(), o[0], o[1], o[2]);
	}

	/** Inside the Warzone (any height in its band). */
	boolean in(Location l) {
		if (l == null || l.getWorld() == null || !l.getWorld().equals(world())) return false;
		Location o = origin();
		double[] c = vec("warzone.centre", 0, -44, 20), yr = vec("warzone.y-range", -60, -24);
		double r = plugin.getConfig().getDouble("warzone.radius", 44);
		double dx = l.getX() - (o.getX() + c[0] + 0.5), dz = l.getZ() - (o.getZ() + c[2] + 0.5), y = l.getY() - o.getY();
		return dx * dx + dz * dz <= r * r && y >= yr[0] && y <= yr[1];
	}

	boolean isTagged(Player p) {
		Long t = tagged.get(p.getUniqueId());
		return t != null && t > System.currentTimeMillis();
	}

	private void start() {
		World w = world();
		// PvP has to be on in this world for the Warzone; everywhere outside it we cancel player damage ourselves
		try {
			Object rule = GameRule.class.getField("PVP").get(null);
			World.class.getMethod("setGameRule", GameRule.class, Object.class).invoke(w, rule, Boolean.TRUE);
		} catch (Throwable ignored) { }
		try {
			World.class.getMethod("setPVP", boolean.class).invoke(w, true);
		} catch (Throwable ignored) { }
		removeOldPit();
		scanFloor();
		for (Entity e : w.getEntities()) if (e.getPersistentDataContainer().has(holoKey)) e.remove();
		double[] k = vec("warzone.koth.point", 0, -39, 18);
		kothLabel = label(origin().add(k[0] + 0.5, k[1] + 4.2, k[2] + 0.5), "", 1.4f);
		plugin.getLogger().info("Warzone ready: " + floor.size() + " envoy spots");
	}

	/** v2's Pit arena (z 64..98 south of spawn) is gone in v3: clear it once. */
	private void removeOldPit() {
		File marker = new File(plugin.getDataFolder(), "pit-removed");
		if (marker.exists()) return;
		World w = world();
		int cx = 0, cy = 100, cz = 80, r = 19, n = 0;
		for (int x = cx - r; x <= cx + r; x++)
			for (int z = cz - r; z <= cz + r; z++) {
				if ((x - cx) * (x - cx) + (z - cz) * (z - cz) > r * r || z <= 60) continue;
				for (int y = cy - 7; y <= cy + 17; y++) {
					Block b = w.getBlockAt(x, y, z);
					if (!b.getType().isAir()) {
						b.setType(Material.AIR, false);
						n++;
					}
				}
			}
		try {
			marker.createNewFile();
		} catch (Exception ignored) { }
		if (n > 0) plugin.getLogger().info("Removed the old Pit arena (" + n + " blocks)");
	}

	/** Every standable spot in the arena: an envoy can land there. */
	private void scanFloor() {
		floor.clear();
		World w = world();
		Location o = origin();
		double[] c = vec("warzone.centre", 0, -44, 20), yr = vec("warzone.y-range", -60, -24);
		int r = (int) plugin.getConfig().getDouble("warzone.radius", 44);
		int ox = o.getBlockX() + (int) c[0], oz = o.getBlockZ() + (int) c[2];
		for (int x = ox - r; x <= ox + r; x += 2)
			for (int z = oz - r; z <= oz + r; z += 2) {
				if ((x - ox) * (x - ox) + (z - oz) * (z - oz) > r * r) continue;
				for (int y = o.getBlockY() + (int) yr[1] - 4; y >= o.getBlockY() + (int) yr[0]; y--) {
					Block b = w.getBlockAt(x, y, z);
					if (b.getType().isSolid() && b.getType() != Material.MAGMA_BLOCK && b.getRelative(0, 1, 0).getType().isAir()
							&& b.getRelative(0, 2, 0).getType().isAir() && b.getType() != Material.WATER) {
						floor.add(b.getRelative(0, 1, 0).getLocation());
						break;
					}
				}
			}
	}

	private TextDisplay label(Location at, String text, float scale) {
		return at.getWorld().spawn(at, TextDisplay.class, t -> {
			t.getPersistentDataContainer().set(holoKey, PersistentDataType.BYTE, (byte) 1);
			t.setPersistent(false);
			t.text(Spawners.mm(text));
			t.setBillboard(Display.Billboard.CENTER);
			t.setShadowed(true);
			t.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
			t.setBrightness(new Display.Brightness(15, 15));
			t.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
		});
	}

	void clear() {
		for (Block b : envoys.keySet()) b.setType(Material.AIR, false);
		envoys.clear();
		envoyLabels.values().forEach(Entity::remove);
		envoyLabels.clear();
		if (kothLabel != null) kothLabel.remove();
		if (bar != null) bar.removeAll();
	}

	// ---------------------------------------------------------------- every half second: tag countdown, no flying

	private void tick() {
		long now = System.currentTimeMillis();
		for (Player p : Bukkit.getOnlinePlayers()) {
			boolean free = p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR;
			if (!free && in(p.getLocation()) && (p.getAllowFlight() || p.isFlying())) {
				p.setFlying(false);
				p.setAllowFlight(false);
				p.sendActionBar(Spawners.mm("<red>No flying in the Warzone"));
			}
			Long t = tagged.get(p.getUniqueId());
			if (t == null) continue;
			if (t <= now) {
				tagged.remove(p.getUniqueId());
				p.sendActionBar(Spawners.mm("<#7CFF6B>You're out of combat"));
			} else if (!kothLive || !in(p.getLocation())) {
				p.sendActionBar(Spawners.mm("<red><bold>IN COMBAT</bold> <gray>" + ((t - now) / 1000 + 1) + "s <dark_gray>· don't log out"));
			}
		}
	}

	// ---------------------------------------------------------------- every second: envoys, KOTH

	private void second() {
		long now = System.currentTimeMillis();
		int online = Bukkit.getOnlinePlayers().size();
		if (now >= nextEnvoy) {
			nextEnvoy = now + minutes("warzone.envoy.interval-minutes", 30);
			if (online >= plugin.getConfig().getInt("warzone.envoy.min-players", 1)) dropEnvoys();
		}
		if (!kothLive && now >= nextKoth) {
			nextKoth = now + minutes("warzone.koth.interval-minutes", 120);
			if (online >= plugin.getConfig().getInt("warzone.koth.min-players", 2)) startKoth();
		}
		// envoy beams so they can be found
		for (Block b : envoys.keySet()) {
			Location l = b.getLocation().add(0.5, 1.2, 0.5);
			l.getWorld().spawnParticle(Particle.END_ROD, l, 6, 0.1, 2.5, 0.1, 0.01);
		}
		if (kothLive) kothTick();
		else if (kothLabel != null && kothLabel.isValid()) {
			long m = Math.max(0, (nextKoth - now) / 60000);
			kothLabel.text(Spawners.mm("<gradient:#FF4D6D:#B040FF><bold>KOTH</bold></gradient>\n<gray>Next hill in <white>" + (m >= 60 ? (m / 60) + "h " + (m % 60) + "m" : m + "m")
					+ "\n<dark_gray>Hold it alone to win"));
		}
	}

	// ---------------------------------------------------------------- envoys

	void envoy(CommandSender s) {
		dropEnvoys();
		s.sendMessage(Spawners.mm("<#7CFF6B>Envoys dropped."));
	}

	private void dropEnvoys() {
		if (floor.isEmpty()) scanFloor();
		if (floor.isEmpty()) return;
		int n = plugin.getConfig().getInt("warzone.envoy.chests", 8);
		var rnd = ThreadLocalRandom.current();
		Set<Integer> used = new HashSet<>();
		int placed = 0;
		for (int tries = 0; tries < n * 6 && placed < n; tries++) {
			int i = rnd.nextInt(floor.size());
			if (!used.add(i)) continue;
			Block b = floor.get(i).getBlock();
			if (!b.getType().isAir()) continue;
			String rarity = roll();
			b.setType(rarity.equals("legendary") ? Material.ENDER_CHEST : rarity.equals("rare") ? Material.TRAPPED_CHEST : Material.CHEST, false);
			envoys.put(b, rarity);
			String name = switch (rarity) {
				case "legendary" -> "<gradient:#FFD84D:#FF8A3D><bold>LEGENDARY ENVOY</bold></gradient>";
				case "rare" -> "<gradient:#FF5C5C:#FFA19A><bold>RARE ENVOY</bold></gradient>";
				default -> "<white><bold>ENVOY</bold>";
			};
			envoyLabels.put(b, label(b.getLocation().add(0.5, 1.6, 0.5), name, 1.1f));
			b.getWorld().strikeLightningEffect(b.getLocation());
			placed++;
		}
		Bukkit.broadcast(Spawners.mm("<dark_gray>» <gradient:#FF4D6D:#B040FF><bold>ENVOYS</bold></gradient> <gray>" + placed
				+ " loot drops just hit the <white>Warzone<gray>. Jump in the Rift: <white>/warzone"));
		for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.3f, 1.4f);
		long despawn = minutes("warzone.envoy.despawn-minutes", 10) / 50;
		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			for (Block b : new ArrayList<>(envoys.keySet())) removeEnvoy(b);
		}, despawn);
	}

	private String roll() {
		ConfigurationSection s = plugin.getConfig().getConfigurationSection("warzone.envoy");
		double c = s == null ? 70 : s.getDouble("common.weight", 70), r = s == null ? 25 : s.getDouble("rare.weight", 25),
				l = s == null ? 5 : s.getDouble("legendary.weight", 5);
		double x = ThreadLocalRandom.current().nextDouble(c + r + l);
		return x < l ? "legendary" : x < l + r ? "rare" : "common";
	}

	private void removeEnvoy(Block b) {
		envoys.remove(b);
		b.setType(Material.AIR, false);
		TextDisplay t = envoyLabels.remove(b);
		if (t != null) t.remove();
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onOpen(PlayerInteractEvent e) {
		if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
		String rarity = envoys.get(e.getClickedBlock());
		if (rarity == null) return;
		e.setCancelled(true);
		Player p = e.getPlayer();
		removeEnvoy(e.getClickedBlock());
		reward(p, "warzone.envoy." + rarity);
		if (plugin.crates != null && !rarity.equals("common")) plugin.crates.give(p.getUniqueId(), rarity.equals("legendary") ? "relic" : "shard", 1);
		p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
		if (!rarity.equals("common")) {
			Bukkit.broadcast(Spawners.mm("<dark_gray>» <white>" + p.getName() + " <gray>looted a " + (rarity.equals("legendary")
					? "<gradient:#FFD84D:#FF8A3D><bold>LEGENDARY</bold></gradient>" : "<gradient:#FF5C5C:#FFA19A><bold>RARE</bold></gradient>") + " <gray>envoy"));
		}
	}

	/** Pays a reward section: shards [min, max], items {MATERIAL: n}, commands ({player}). */
	private void reward(Player p, String path) {
		ConfigurationSection s = plugin.getConfig().getConfigurationSection(path);
		if (s == null) return;
		List<String> got = new ArrayList<>();
		List<Integer> sh = s.getIntegerList("shards");
		double shards = sh.size() >= 2 ? ThreadLocalRandom.current().nextInt(sh.get(0), sh.get(1) + 1) : s.getDouble("shards", 0);
		if (shards > 0 && plugin.eco() != null) {
			plugin.eco().depositPlayer(p, shards);
			got.add("<gold>" + LagSkyblock.shards(shards));
		}
		ConfigurationSection items = s.getConfigurationSection("items");
		if (items != null) {
			for (String k : items.getKeys(false)) {
				Material m = Material.matchMaterial(k);
				if (m == null) continue;
				int n = items.getInt(k);
				for (ItemStack left : p.getInventory().addItem(new ItemStack(m, n)).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
				got.add("<white>" + n + "× " + m.name().toLowerCase().replace('_', ' '));
			}
		}
		for (String c : s.getStringList("commands")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("{player}", p.getName()));
		if (!got.isEmpty()) p.sendMessage(Spawners.mm("<dark_gray>» <gray>You got " + String.join("<dark_gray>, ", got)));
	}

	// ---------------------------------------------------------------- KOTH

	void kothAdmin(CommandSender s, String[] a) {
		String sub = a.length > 1 ? a[1].toLowerCase() : "";
		if (sub.equals("start")) {
			startKoth();
			s.sendMessage(Spawners.mm("<#7CFF6B>KOTH started."));
		} else if (sub.equals("stop")) {
			endKoth(null);
			s.sendMessage(Spawners.mm("<#7CFF6B>KOTH stopped."));
		} else s.sendMessage(Spawners.mm("<gray>/lsb koth start | stop"));
	}

	private void startKoth() {
		if (kothLive) return;
		kothLive = true;
		capper = null;
		progress = 0;
		if (bar == null) bar = Bukkit.createBossBar("KOTH", BarColor.PURPLE, BarStyle.SEGMENTED_10);
		bar.setProgress(0);
		for (Player p : Bukkit.getOnlinePlayers()) bar.addPlayer(p);
		Bukkit.broadcast(Spawners.mm("<dark_gray>» <gradient:#FF4D6D:#B040FF><bold>KOTH IS LIVE</bold></gradient> <gray>Hold the hill in the <white>Warzone<gray> alone for <white>"
				+ plugin.getConfig().getInt("warzone.koth.capture-seconds", 120) + "s<gray>. <white>/warzone"));
		for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 0.5f, 1.0f);
	}

	private void kothTick() {
		double[] k = vec("warzone.koth.point", 0, -39, 18);
		Location c = origin().add(k[0] + 0.5, k[1], k[2] + 0.5);
		double r = plugin.getConfig().getDouble("warzone.koth.radius", 2.5);
		int need = plugin.getConfig().getInt("warzone.koth.capture-seconds", 120);
		List<Player> on = new ArrayList<>();
		for (Player p : c.getWorld().getPlayers()) {
			Location l = p.getLocation();
			if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR) continue;
			if (Math.hypot(l.getX() - c.getX(), l.getZ() - c.getZ()) <= r && Math.abs(l.getY() - c.getY()) <= 3) on.add(p);
		}
		Player cap = capper == null ? null : Bukkit.getPlayer(capper);
		String state;
		if (on.isEmpty()) {
			if (capper != null) Bukkit.broadcast(Spawners.mm("<dark_gray>» <gray>The hill is open again."));
			capper = null;
			progress = 0;
			state = "<gray>Nobody's on the hill";
		} else if (on.size() > 1) {
			state = "<red>Contested <gray>(" + on.size() + " on the hill)";
		} else {
			Player p = on.get(0);
			if (cap == null || !cap.equals(p)) {
				capper = p.getUniqueId();
				progress = 0;
				Bukkit.broadcast(Spawners.mm("<dark_gray>» <white>" + p.getName() + " <gray>is capping the <gradient:#FF4D6D:#B040FF><bold>KOTH</bold></gradient>"));
			}
			progress++;
			state = "<white>" + p.getName() + " <gray>" + progress + "/" + need + "s";
			if (progress >= need) {
				endKoth(p);
				return;
			}
		}
		if (bar != null) {
			bar.setTitle(org.bukkit.ChatColor.LIGHT_PURPLE + "" + org.bukkit.ChatColor.BOLD + "KOTH " + org.bukkit.ChatColor.GRAY + "· "
					+ net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(Spawners.mm(state)));
			bar.setProgress(Math.min(1.0, progress / (double) need));
			for (Player p : Bukkit.getOnlinePlayers()) if (!bar.getPlayers().contains(p)) bar.addPlayer(p);
		}
		if (kothLabel != null && kothLabel.isValid()) kothLabel.text(Spawners.mm("<gradient:#FF4D6D:#B040FF><bold>KOTH LIVE</bold></gradient>\n" + state));
	}

	private void endKoth(Player winner) {
		kothLive = false;
		capper = null;
		progress = 0;
		if (bar != null) bar.removeAll();
		if (winner == null) return;
		double s = plugin.getConfig().getDouble("warzone.koth.shards", 750000);
		if (s > 0 && plugin.eco() != null) plugin.eco().depositPlayer(winner, s);
		if (plugin.crates != null) plugin.crates.give(winner.getUniqueId(), "relic", 1);
		for (String c : plugin.getConfig().getStringList("warzone.koth.commands")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("{player}", winner.getName()));
		Bukkit.broadcast(Spawners.mm("<dark_gray>» <white>" + winner.getName() + " <gray>captured the <gradient:#FF4D6D:#B040FF><bold>KOTH</bold></gradient>"
				+ (s > 0 ? " <gray>and took <gold>" + LagSkyblock.shards(s) : "")));
		for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.0f);
	}

	// ---------------------------------------------------------------- combat

	private void tag(Player p) {
		long until = System.currentTimeMillis() + plugin.getConfig().getLong("warzone.tag-seconds", 15) * 1000L;
		if (!isTagged(p)) p.sendMessage(Spawners.mm("<red>You're in combat. <gray>Logging out now kills you."));
		tagged.put(p.getUniqueId(), until);
		if ((p.isFlying() || p.getAllowFlight()) && p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR) {
			p.setFlying(false);
			p.setAllowFlight(false);
		}
	}

	private static Player attacker(Entity damager) {
		if (damager instanceof Player p) return p;
		if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
		if (damager instanceof org.bukkit.entity.TNTPrimed t && t.getSource() instanceof Player p) return p;
		if (damager instanceof org.bukkit.entity.AreaEffectCloud c && c.getSource() instanceof Player p) return p;
		return null;
	}

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
		if (plugin.getConfig().getBoolean("warzone.keep-inventory", false)) {
			e.setKeepInventory(true);
			e.setKeepLevel(true);
			e.getDrops().clear();
			e.setDroppedExp(0);
		}
		Player killer = dead.getKiller();
		if (killer == null || killer.equals(dead)) return;
		int s = streak.merge(killer.getUniqueId(), 1, Integer::sum);
		double reward = plugin.getConfig().getDouble("warzone.kill-reward", 0);
		if (reward > 0 && plugin.eco() != null) plugin.eco().depositPlayer(killer, reward);
		e.deathMessage(Spawners.mm("<dark_red>☠ <white>" + killer.getName() + " <gray>killed <white>" + dead.getName() + " <gray>in the <red><bold>WARZONE"
				+ (had >= 3 ? " <gray>ending their <red>" + had + "</red> streak" : "")));
		killer.playSound(killer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.8f);
		if (s >= 3 && s % 2 == 1) {
			Bukkit.broadcast(Spawners.mm("<dark_gray>» <white>" + killer.getName() + " <gray>is on a <red><bold>" + s + " KILL STREAK</bold> <gray>in the Warzone <dark_gray>(/warzone)"));
		}
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onCommand(PlayerCommandPreprocessEvent e) {
		Player p = e.getPlayer();
		if (!isTagged(p) || p.hasPermission("lag.skyblock.admin")) return;
		String root = e.getMessage().substring(1).split(" ")[0].toLowerCase();
		if (root.contains(":")) root = root.substring(root.indexOf(':') + 1);
		List<String> ok = plugin.getConfig().getStringList("warzone.allowed-commands");
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
			e.getPlayer().sendMessage(Spawners.mm("<red>You can't leave the Warzone in combat."));
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

	// ---------------------------------------------------------------- /warzone

	@Override
	public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
		if (!(s instanceof Player p)) return true;
		if (in(p.getLocation())) {
			p.sendMessage(Spawners.mm("<gray>You're already in the Warzone. <dark_gray>Leave with /spawn (not in combat)."));
			return true;
		}
		Location rift = origin().add(0.5, 1, 40.5);
		rift.setYaw(0);
		p.teleport(rift);
		p.showTitle(net.kyori.adventure.title.Title.title(Spawners.mm("<red><bold>THE RIFT"),
				Spawners.mm("<gray>Jump in: the <white>Warzone<gray> is below. PvP is on down there.")));
		p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.2f, 1.6f);
		return true;
	}
}
