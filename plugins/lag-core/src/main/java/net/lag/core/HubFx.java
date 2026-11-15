package net.lag.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;

/**
 * Hub polish: cinematic join (typewriter title, chord, burst, chat banner), animated boss bar, particle
 * effects (beacon helix, portal rings, sparkles), launch pads, hotbar (cosmetics / selector / player
 * visibility) and particle trails.
 */
final class HubFx implements Listener {
	static final Color CYAN = Color.fromRGB(0x38E0FF);
	static final Color PURPLE = Color.fromRGB(0xB040FF);

	private final LagCore plugin;
	private final HubBuild build;
	private final NamespacedKey itemKey, trailKey;
	private final Set<UUID> hidden = new HashSet<>();
	private final Map<UUID, String> trails = new HashMap<>();
	private final Map<UUID, Long> padCooldown = new HashMap<>();
	private final BossBar bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.PURPLE, BossBar.Overlay.NOTCHED_20);
	private long tick;

	/** Trail id -> [icon, display name]. */
	static final String[][] TRAILS = {
			{"none", "BARRIER", "<red>No trail"},
			{"brand", "AMETHYST_SHARD", "<gradient:#38E0FF:#B040FF><bold>Relapsed</bold></gradient> <gray>(cyan to purple)"},
			{"endrod", "END_ROD", "<white><bold>Starlight</bold>"},
			{"soul", "SOUL_LANTERN", "<aqua><bold>Soulfire</bold>"},
			{"flame", "BLAZE_POWDER", "<gold><bold>Inferno</bold>"},
			{"cherry", "CHERRY_LEAVES", "<light_purple><bold>Sakura</bold>"},
			{"notes", "NOTE_BLOCK", "<green><bold>Melody</bold>"},
			{"hearts", "RED_DYE", "<red><bold>Hearts</bold>"},
			{"glow", "GLOW_INK_SAC", "<aqua><bold>Glow</bold>"},
	};

	HubFx(LagCore plugin, HubBuild build) {
		this.plugin = plugin;
		this.build = build;
		this.itemKey = new NamespacedKey(plugin, "hubitem");
		this.trailKey = new NamespacedKey(plugin, "trail");
	}

	void start() {
		Bukkit.getScheduler().runTaskTimer(plugin, this::loop, 1L, 1L);
	}

	private boolean inHub(Player p) {
		Location o = build.origin();
		return o != null && p.getWorld().equals(o.getWorld());
	}

	// ---------------------------------------------------------------- hotbar

	ItemStack hubItem(String id, Material mat, String name, String... lore) {
		ItemStack it = new ItemStack(mat);
		ItemMeta m = it.getItemMeta();
		m.displayName(LagCore.mm(name).decoration(TextDecoration.ITALIC, false));
		List<Component> l = new ArrayList<>();
		for (String s : lore) l.add(LagCore.mm(s).decoration(TextDecoration.ITALIC, false));
		m.lore(l);
		m.setEnchantmentGlintOverride(true);
		m.addItemFlags(ItemFlag.values());
		m.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, id);
		it.setItemMeta(m);
		return it;
	}

	void giveHotbar(Player p) {
		p.getInventory().setItem(0, hubItem("cosmetics", Material.NETHER_STAR,
				"<gradient:#B040FF:#38E0FF><bold>Cosmetics</bold></gradient> <gray>(Right Click)", "<gray>Particle trails and more"));
		p.getInventory().setItem(8, visibilityItem(hidden.contains(p.getUniqueId())));
	}

	private ItemStack visibilityItem(boolean hiding) {
		return hiding
				? hubItem("visibility", Material.GRAY_DYE, "<gray><bold>Players: Hidden</bold> <dark_gray>(Right Click)", "<gray>Click to show players")
				: hubItem("visibility", Material.LIME_DYE, "<green><bold>Players: Visible</bold> <dark_gray>(Right Click)", "<gray>Click to hide players");
	}

	@EventHandler(priority = EventPriority.LOW)
	public void onUse(PlayerInteractEvent e) {
		Player p = e.getPlayer();
		// launch pads
		if (e.getAction() == Action.PHYSICAL && e.getClickedBlock() != null
				&& e.getClickedBlock().getType() == Material.HEAVY_WEIGHTED_PRESSURE_PLATE && inHub(p)) {
			e.setCancelled(true);
			launch(p, e.getClickedBlock());
			return;
		}
		if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
		ItemStack it = e.getItem();
		if (it == null || !it.hasItemMeta()) return;
		String id = it.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
		if (id == null) return;
		e.setCancelled(true);
		switch (id) {
			case "cosmetics" -> openCosmetics(p);
			case "visibility" -> toggleVisibility(p);
			default -> { }
		}
	}

	private void toggleVisibility(Player p) {
		boolean hide = hidden.add(p.getUniqueId());
		if (!hide) hidden.remove(p.getUniqueId());
		for (Player o : Bukkit.getOnlinePlayers()) {
			if (o == p) continue;
			if (hide) p.hidePlayer(plugin, o);
			else p.showPlayer(plugin, o);
		}
		p.getInventory().setItem(8, visibilityItem(hide));
		p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, hide ? 0.8f : 1.4f);
		p.sendActionBar(LagCore.mm(hide ? "<gray>Players hidden" : "<green>Players visible"));
	}

	// ---------------------------------------------------------------- cosmetics GUI

	private static final class CosmeticsHolder implements InventoryHolder {
		Inventory inv;

		@Override
		public Inventory getInventory() {
			return inv;
		}
	}

	void openCosmetics(Player p) {
		CosmeticsHolder h = new CosmeticsHolder();
		boolean art = plugin.pack != null && plugin.pack.has(p);
		Inventory inv = Bukkit.createInventory(h, 36, LagCore.mm(art
				? plugin.pack.menuTitle(p, Pack.MENU_COSMETICS, "<white><bold>COSMETICS</bold> <gray>» Trails")
				: "<gradient:#B040FF:#38E0FF><bold>Cosmetics</bold></gradient> <dark_gray>» Trails"));
		h.inv = inv;
		if (!art) Selector.border(inv);
		String cur = trails.getOrDefault(p.getUniqueId(), "none");
		int[] slots = {10, 11, 12, 13, 14, 15, 16, 20, 22, 24};
		for (int i = 0; i < TRAILS.length && i < slots.length; i++) {
			String[] t = TRAILS[i];
			boolean on = t[0].equals(cur);
			ItemStack it = hubItem("trail:" + t[0], Material.valueOf(t[1]), t[2],
					on ? "<green>✔ Equipped" : "<gray>Click to equip");
			if (!on) {
				ItemMeta m = it.getItemMeta();
				m.setEnchantmentGlintOverride(false);
				it.setItemMeta(m);
			}
			inv.setItem(slots[i], it);
		}
		p.openInventory(inv);
		p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.8f, 1.5f);
	}

	@EventHandler
	public void onClick(InventoryClickEvent e) {
		if (!(e.getInventory().getHolder() instanceof CosmeticsHolder)) return;
		e.setCancelled(true);
		if (!(e.getWhoClicked() instanceof Player p)) return;
		ItemStack it = e.getCurrentItem();
		if (it == null || !it.hasItemMeta()) return;
		String id = it.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
		if (id == null || !id.startsWith("trail:")) return;
		String t = id.substring(6);
		if ("none".equals(t)) trails.remove(p.getUniqueId());
		else trails.put(p.getUniqueId(), t);
		p.getPersistentDataContainer().set(trailKey, PersistentDataType.STRING, t);
		p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.5f);
		openCosmetics(p);
	}

	@EventHandler
	public void onDrag(InventoryDragEvent e) {
		if (e.getInventory().getHolder() instanceof CosmeticsHolder) e.setCancelled(true);
	}

	// ---------------------------------------------------------------- join / quit

	@EventHandler(priority = EventPriority.MONITOR)
	public void onJoin(PlayerJoinEvent e) {
		Player p = e.getPlayer();
		String t = p.getPersistentDataContainer().get(trailKey, PersistentDataType.STRING);
		if (t != null && !"none".equals(t)) trails.put(p.getUniqueId(), t);
		for (UUID id : hidden) {
			Player h = Bukkit.getPlayer(id);
			if (h != null) h.hidePlayer(plugin, p);
		}
		p.showBossBar(bar);
		build.ensure();
		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			if (!p.isOnline()) return;
			giveHotbar(p);
			cinematic(p);
		}, 5L);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent e) {
		hidden.remove(e.getPlayer().getUniqueId());
		trails.remove(e.getPlayer().getUniqueId());
		padCooldown.remove(e.getPlayer().getUniqueId());
	}

	private void cinematic(Player p) {
		String name = plugin.networkName().toUpperCase();
		Title.Times fast = Title.Times.times(Duration.ZERO, Duration.ofMillis(400), Duration.ZERO);
		for (int i = 1; i <= name.length(); i++) {
			final int n = i;
			Bukkit.getScheduler().runTaskLater(plugin, () -> {
				if (!p.isOnline()) return;
				p.showTitle(Title.title(LagCore.mm("<gradient:#38E0FF:#B040FF><bold>" + name.substring(0, n) + "</bold></gradient><white>▌"),
						Component.empty(), fast));
				p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.25f, 1.6f + n * 0.03f);
			}, i * 2L);
		}
		long end = name.length() * 2L + 2;
		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			if (!p.isOnline()) return;
			p.showTitle(Title.title(LagCore.mm(plugin.pack != null && plugin.pack.has(p) ? Pack.glyph(Pack.LOGO_TITLE) : "<gradient:#38E0FF:#B040FF><bold>" + name + "</bold></gradient>"),
					LagCore.mm("<gray>Factions <dark_gray>• <gray>SMP <dark_gray>• <gray>1.8 Cannons"),
					Title.Times.times(Duration.ZERO, Duration.ofSeconds(3), Duration.ofMillis(800))));
			// chord
			p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.5f, 1.4f);
			p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, 0.94f);
			p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, 1.19f);
			p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, 1.41f);
			burst(p.getLocation().add(0, 1, 0));
			banner(p);
		}, end);
	}

	private void burst(Location l) {
		World w = l.getWorld();
		for (int i = 0; i < 48; i++) {
			double a = Math.PI * 2 * i / 48;
			Vector v = new Vector(Math.cos(a), 0.15, Math.sin(a)).multiply(0.35);
			w.spawnParticle(Particle.END_ROD, l, 0, v.getX(), v.getY(), v.getZ(), 1);
		}
		w.spawnParticle(Particle.DUST_COLOR_TRANSITION, l, 60, 0.6, 0.8, 0.6, new Particle.DustTransition(CYAN, PURPLE, 1.4f));
	}

	private void banner(Player p) {
		String line = "<gradient:#38E0FF:#B040FF><strikethrough>                                                  </strikethrough></gradient>";
		p.sendMessage(LagCore.mm(line));
		boolean art = plugin.pack != null && plugin.pack.has(p);
		p.sendMessage(LagCore.mm("            <gray>Welcome to " + (art ? Pack.glyph(Pack.LOGO_LINE) : "<gradient:#38E0FF:#B040FF><bold>" + plugin.networkName().toUpperCase() + "</bold></gradient>")));
		p.sendMessage(LagCore.mm("         <dark_gray>Factions  <gray>•  <dark_gray>SMP  <gray>•  <dark_gray>1.8 Cannons"));
		p.sendMessage(Component.empty());
		String arrow = art ? Pack.glyph(Pack.I_ARROW) : "<#38E0FF>▸";
		p.sendMessage(LagCore.mm("  " + arrow + " <gray>Walk into a portal or use your <white>compass</white> to play"));
		p.sendMessage(LagCore.mm("  " + arrow + " <gray>Grab a trail from <white>Cosmetics</white> on your hotbar"));
		p.sendMessage(LagCore.mm(line));
	}

	// ---------------------------------------------------------------- launch pads

	private void launch(Player p, Block plate) {
		long now = System.currentTimeMillis();
		Long last = padCooldown.get(p.getUniqueId());
		if (last != null && now - last < 800) return;
		padCooldown.put(p.getUniqueId(), now);
		Location o = build.origin();
		Vector dir;
		if (o != null) {
			dir = plate.getLocation().add(0.5, 0, 0.5).toVector().subtract(o.clone().add(0.5, 0, 0.5).toVector()).setY(0);
			if (dir.lengthSquared() < 0.01) dir = p.getLocation().getDirection().setY(0);
		} else {
			dir = p.getLocation().getDirection().setY(0);
		}
		Vector v = dir.normalize().multiply(plugin.getConfig().getDouble("hub.launch-pad.speed", 1.25))
				.setY(plugin.getConfig().getDouble("hub.launch-pad.lift", 0.85));
		p.setVelocity(v);
		Location l = p.getLocation();
		l.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, l, 40, 0.4, 0.1, 0.4, new Particle.DustTransition(CYAN, PURPLE, 1.6f));
		l.getWorld().spawnParticle(Particle.CLOUD, l, 10, 0.3, 0.05, 0.3, 0.05);
		p.playSound(l, Sound.ENTITY_BREEZE_WIND_BURST, 0.7f, 1.2f);
		p.playSound(l, Sound.BLOCK_BEACON_POWER_SELECT, 0.3f, 2f);
	}

	// ---------------------------------------------------------------- per-tick effects

	@EventHandler(ignoreCancelled = true)
	public void onMove(PlayerMoveEvent e) {
		String t = trails.get(e.getPlayer().getUniqueId());
		if (t == null) return;
		Location from = e.getFrom(), to = e.getTo();
		if (from.getX() == to.getX() && from.getZ() == to.getZ() && from.getY() == to.getY()) return;
		if ((tick & 1) == 1) return;
		trail(e.getPlayer(), t, to.clone().add(0, 0.15, 0));
	}

	private void trail(Player p, String t, Location l) {
		World w = l.getWorld();
		switch (t) {
			case "brand" -> w.spawnParticle(Particle.DUST_COLOR_TRANSITION, l, 3, 0.15, 0.05, 0.15,
					new Particle.DustTransition((tick / 10) % 2 == 0 ? CYAN : PURPLE, (tick / 10) % 2 == 0 ? PURPLE : CYAN, 1.1f));
			case "endrod" -> w.spawnParticle(Particle.END_ROD, l, 1, 0.1, 0.05, 0.1, 0.01);
			case "soul" -> w.spawnParticle(Particle.SOUL_FIRE_FLAME, l, 2, 0.1, 0.05, 0.1, 0.01);
			case "flame" -> w.spawnParticle(Particle.FLAME, l, 2, 0.1, 0.05, 0.1, 0.01);
			case "cherry" -> w.spawnParticle(Particle.CHERRY_LEAVES, l.clone().add(0, 1.6, 0), 2, 0.3, 0.2, 0.3, 0);
			case "notes" -> w.spawnParticle(Particle.NOTE, l.clone().add(0, 0.2, 0), 1, 0.2, 0.1, 0.2, 1);
			case "hearts" -> { if (tick % 6 == 0) w.spawnParticle(Particle.HEART, l.clone().add(0, 0.3, 0), 1, 0.2, 0.1, 0.2, 0); }
			case "glow" -> w.spawnParticle(Particle.GLOW, l, 2, 0.15, 0.05, 0.15, 0.01);
			default -> { }
		}
	}

	private void loop() {
		tick++;
		Location o = build.origin();
		if (o == null) return;
		World w = o.getWorld();
		if (w.getPlayers().isEmpty()) return;

		// beacon double helix (cyan + purple) around the spawn
		if (tick % 2 == 0) {
			for (int strand = 0; strand < 2; strand++) {
				double a = tick * 0.18 + strand * Math.PI;
				double y = (tick % 60) / 60.0 * 4.5;
				Location l = o.clone().add(0.5 + Math.cos(a) * 1.6, 1.2 + y, 0.5 + Math.sin(a) * 1.6);
				w.spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0, new Particle.DustOptions(strand == 0 ? CYAN : PURPLE, 1.2f));
			}
		}
		// portal rings
		if (tick % 3 == 0) {
			var portals = plugin.getConfig().getConfigurationSection("hub.portals");
			if (portals != null) {
				for (String realm : portals.getKeys(false)) {
					List<?> off = portals.getList(realm);
					if (off == null || off.size() < 3) continue;
					Location c = o.clone().add(0.5, 4.5, ((Number) off.get(2)).doubleValue() + 0.5);
					for (int k = 0; k < 3; k++) {
						double a = tick * 0.12 + k * (Math.PI * 2 / 3);
						Location l = c.clone().add(Math.cos(a) * 3.3, Math.sin(a) * 4.3, 0);
						Color from = "smp".equals(realm) ? Color.fromRGB(0x7CFF6B) : Color.fromRGB(0xFF4D6D);
						w.spawnParticle(Particle.DUST_COLOR_TRANSITION, l, 1, 0, 0, 0, 0, new Particle.DustTransition(from, PURPLE, 1.3f));
					}
				}
			}
		}
		// drifting sparkles over the plaza
		if (tick % 5 == 0) {
			double a = Math.random() * Math.PI * 2, r = Math.random() * 30;
			w.spawnParticle(Particle.END_ROD, o.clone().add(Math.cos(a) * r, 2 + Math.random() * 6, Math.sin(a) * r), 0, 0, 0.02, 0, 1);
			w.spawnParticle(Particle.WAX_OFF, o.clone().add(Math.cos(a + 1) * r, 1 + Math.random() * 4, Math.sin(a + 1) * r), 1, 0, 0, 0, 0);
		}
		// boss bar
		if (tick % 2 == 0) updateBar();
	}

	private void updateBar() {
		List<String> msgs = plugin.getConfig().getStringList("hub.bossbar");
		if (msgs.isEmpty()) msgs = List.of("<gray>Welcome to {gradient}<bold>{network}</bold></gradient>");
		int period = 160; // 8s per message
		int i = (int) ((tick / period) % msgs.size());
		float prog = 1f - (tick % period) / (float) period;
		double phase = ((tick * 0.04) % 2.0) - 1.0;
		String m = msgs.get(i)
				.replace("{gradient}", "<gradient:#38E0FF:#B040FF:#38E0FF:" + String.format(java.util.Locale.ROOT, "%.2f", phase) + ">")
				.replace("{network}", plugin.networkName().toUpperCase())
				.replace("{online}", String.valueOf(plugin.count("ALL")));
		bar.name(LagCore.mm(m));
		bar.progress(Math.max(0f, Math.min(1f, prog)));
	}
}
