package net.lag.cannons;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.DoubleChest;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.Event;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;

import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Relapse rules on top of FactionsUUID:
 *  - No allies or truces (/f ally, /f truce, /f relation ally|truce are refused). FactionsUUID's maxRelations is
 *    also set to 0 by tools/bake.py, this just gives a clean message.
 *  - Alts: /f alt invite|accept|leave|kick|list. An alt is linked to one faction but is NOT a FactionsUUID member, so
 *    it never counts toward the 8-man cap. Alts can stand in claims and AFK spawners/farms, nothing else: no damage,
 *    no placing/breaking, no containers, no item pickup/drop, no right-clicking blocks except doors/gates, no
 *    plates/tripwires (so alts can't fire cannons).
 */
final class Alts implements Listener {
	private static final String P = "<gradient:#FF5C5C:#FFA19A>[RELAPSEDMC]</gradient> ";
	private static final long INVITE_MS = 5 * 60_000L;
	private static final Set<String> F_ROOTS = Set.of("f", "fac", "faction", "factions");

	private final JavaPlugin plugin;
	private final File file;
	/** alt uuid -> FactionsUUID faction id */
	private final Map<UUID, Integer> alts = new HashMap<>();
	private final Map<UUID, Invite> invites = new HashMap<>();

	private record Invite(int faction, long at) { }

	Alts(JavaPlugin plugin) {
		this.plugin = plugin;
		this.file = new File(plugin.getDataFolder(), "alts.yml");
		load();
	}

	private boolean altsOn() {
		return plugin.getConfig().getBoolean("relapse.alts.enabled", true) && Fuuid.present();
	}

	private boolean noAllies() {
		return plugin.getConfig().getBoolean("relapse.no-allies", true);
	}

	private static void send(org.bukkit.command.CommandSender to, String mm) {
		to.sendMessage(MiniMessage.miniMessage().deserialize(P + mm));
	}

	// ---------------------------------------------------------------- storage

	private void load() {
		alts.clear();
		if (!file.exists()) return;
		YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
		var sec = y.getConfigurationSection("alts");
		if (sec == null) return;
		for (String k : sec.getKeys(false)) {
			try {
				alts.put(UUID.fromString(k), sec.getInt(k));
			} catch (IllegalArgumentException ignored) { }
		}
	}

	private void save() {
		YamlConfiguration y = new YamlConfiguration();
		for (var e : alts.entrySet()) y.set("alts." + e.getKey(), e.getValue());
		try {
			plugin.getDataFolder().mkdirs();
			y.save(file);
		} catch (IOException ex) {
			plugin.getLogger().warning("Couldn't save alts.yml: " + ex.getMessage());
		}
	}

	/** The faction this player is an alt for, or null. Drops the link if that faction was disbanded. */
	Object altFaction(OfflinePlayer p) {
		if (!altsOn()) return null;
		Integer id = alts.get(p.getUniqueId());
		if (id == null) return null;
		Object f = Fuuid.byId(id);
		if (f == null) {
			alts.remove(p.getUniqueId());
			save();
		}
		return f;
	}

	boolean isAlt(Player p) {
		return altFaction(p) != null;
	}

	// ---------------------------------------------------------------- commands

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true) // after ClassicFactions (LOWEST) rewrote aliases
	public void onCommand(PlayerCommandPreprocessEvent e) {
		String[] parts = e.getMessage().substring(1).trim().split("\\s+");
		if (parts.length < 2 || !F_ROOTS.contains(parts[0].toLowerCase(Locale.ROOT))) return;
		String sub = parts[1].toLowerCase(Locale.ROOT);
		Player p = e.getPlayer();

		if (noAllies() && sub.equals("relation") && parts.length > 2) {
			String rel = parts[2].toLowerCase(Locale.ROOT);
			if (rel.equals("ally") || rel.equals("truce")) {
				e.setCancelled(true);
				send(p, "<red>No allies or truces on RELAPSEDMC. It's you and your 8 against everyone.");
				return;
			}
		}

		if (!altsOn()) return;

		if (sub.equals("alt") || sub.equals("alts")) {
			e.setCancelled(true);
			String[] a = java.util.Arrays.copyOfRange(parts, 2, parts.length);
			if (sub.equals("alts") && a.length == 0) a = new String[] {"list"};
			altCommand(p, a);
			return;
		}

		Object alt = altFaction(p);
		if (alt != null && (sub.equals("join") || sub.equals("create"))) {
			e.setCancelled(true);
			send(p, "<red>You're an alt for <white>" + Fuuid.tag(alt) + "<red>. Run <white>/f alt leave</white> first.");
		}
	}

	private void altCommand(Player p, String[] a) {
		String op = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "help";
		switch (op) {
			case "invite", "add" -> invite(p, a);
			case "accept", "join" -> accept(p, a);
			case "leave" -> leave(p);
			case "kick", "remove", "revoke" -> kick(p, a);
			case "list" -> list(p, a);
			default -> {
				send(p, "<gray>Alts can stand in your claim and AFK spawners and farms. They can't fight, build, break, open containers, press buttons or pick up items, and they don't count toward your 8.");
				send(p, "<white>/f alt invite <player> <dark_gray>•</dark_gray> /f alt kick <player> <dark_gray>•</dark_gray> /f alt list [faction] <dark_gray>•</dark_gray> /f alt accept [faction] <dark_gray>•</dark_gray> /f alt leave");
			}
		}
	}

	private void invite(Player p, String[] a) {
		if (a.length < 2) { send(p, "<red>Usage: /f alt invite <player>"); return; }
		Object fac = Fuuid.faction(p);
		if (fac == null) { send(p, "<red>You need a faction to invite alts."); return; }
		if (!Fuuid.isModOrAbove(p)) { send(p, "<red>Only mods and above can invite alts."); return; }
		Player t = Bukkit.getPlayerExact(a[1]);
		if (t == null) { send(p, "<red>" + a[1] + " isn't online."); return; }
		if (t.equals(p)) { send(p, "<red>You can't be your own alt."); return; }
		if (Fuuid.hasFaction(t)) { send(p, "<red>" + t.getName() + " is in a faction. Alts have to be factionless."); return; }
		Object cur = altFaction(t);
		if (cur != null) { send(p, "<red>" + t.getName() + " is already an alt for " + Fuuid.tag(cur) + "."); return; }
		invites.put(t.getUniqueId(), new Invite(Fuuid.id(fac), System.currentTimeMillis()));
		String tag = Fuuid.tag(fac);
		send(p, "<gray>Invited <white>" + t.getName() + "</white> as an alt. Expires in 5 minutes.");
		send(t, "<white>" + p.getName() + "</white> <gray>invited you to be an alt for <white>" + tag
				+ "</white>. <click:run_command:'/f alt accept " + tag + "'><hover:show_text:'Become an alt for " + tag
				+ "'><#7CFF6B><u>Click to accept</u></hover></click> <gray>or /f alt accept " + tag);
	}

	private void accept(Player p, String[] a) {
		Invite inv = invites.get(p.getUniqueId());
		if (inv == null || System.currentTimeMillis() - inv.at() > INVITE_MS) {
			invites.remove(p.getUniqueId());
			send(p, "<red>You don't have an alt invite (they expire after 5 minutes).");
			return;
		}
		Object fac = Fuuid.byId(inv.faction());
		if (fac == null) { invites.remove(p.getUniqueId()); send(p, "<red>That faction doesn't exist anymore."); return; }
		if (a.length > 1 && !Fuuid.tag(fac).equalsIgnoreCase(a[1])) { send(p, "<red>You don't have an alt invite from " + a[1] + "."); return; }
		if (Fuuid.hasFaction(p)) { send(p, "<red>Leave your faction first (/f leave). Alts have to be factionless."); return; }
		invites.remove(p.getUniqueId());
		alts.put(p.getUniqueId(), inv.faction());
		save();
		send(p, "<gray>You're now an alt for <white>" + Fuuid.tag(fac) + "</white>. You can AFK in their claim; you can't fight, build or open containers.");
		notifyFaction(fac, "<white>" + p.getName() + "</white> <gray>joined as an alt.");
	}

	private void leave(Player p) {
		Object fac = altFaction(p);
		if (fac == null) { send(p, "<red>You're not an alt."); return; }
		alts.remove(p.getUniqueId());
		save();
		send(p, "<gray>You're no longer an alt for <white>" + Fuuid.tag(fac) + "</white>.");
		notifyFaction(fac, "<white>" + p.getName() + "</white> <gray>stopped being an alt.");
	}

	@SuppressWarnings("deprecation")
	private void kick(Player p, String[] a) {
		if (a.length < 2) { send(p, "<red>Usage: /f alt kick <player>"); return; }
		Object fac = Fuuid.faction(p);
		if (fac == null || !Fuuid.isModOrAbove(p)) { send(p, "<red>Only mods and above can kick alts."); return; }
		int id = Fuuid.id(fac);
		OfflinePlayer t = Bukkit.getOfflinePlayer(a[1]);
		Invite inv = invites.get(t.getUniqueId());
		if (inv != null && inv.faction() == id) {
			invites.remove(t.getUniqueId());
			send(p, "<gray>Cancelled the alt invite for <white>" + a[1] + "</white>.");
			return;
		}
		Integer cur = alts.get(t.getUniqueId());
		if (cur == null || cur != id) { send(p, "<red>" + a[1] + " isn't one of your alts."); return; }
		alts.remove(t.getUniqueId());
		save();
		notifyFaction(fac, "<white>" + a[1] + "</white> <gray>was removed as an alt by <white>" + p.getName() + "</white>.");
		if (t.getPlayer() != null) send(t.getPlayer(), "<gray>You were removed as an alt for <white>" + Fuuid.tag(fac) + "</white>.");
	}

	private void list(Player p, String[] a) {
		Object fac = a.length > 1 ? Fuuid.byTag(a[1]) : Fuuid.faction(p);
		if (fac == null) fac = altFaction(p);
		if (fac == null) { send(p, "<red>" + (a.length > 1 ? "No faction called " + a[1] + "." : "You're not in a faction.")); return; }
		int id = Fuuid.id(fac);
		List<String> names = new ArrayList<>();
		for (var e : alts.entrySet()) {
			if (e.getValue() != id) continue;
			OfflinePlayer o = Bukkit.getOfflinePlayer(e.getKey());
			String n = o.getName() == null ? e.getKey().toString().substring(0, 8) : o.getName();
			names.add((o.isOnline() ? "<#7CFF6B>" : "<gray>") + n);
		}
		send(p, "<white>" + Fuuid.tag(fac) + "</white> <gray>alts (" + names.size() + "): " + (names.isEmpty() ? "<dark_gray>none" : String.join("<dark_gray>, ", names)));
	}

	private void notifyFaction(Object fac, String mm) {
		int id = Fuuid.id(fac);
		for (Player o : Bukkit.getOnlinePlayers()) {
			Object of = Fuuid.faction(o);
			if (of != null && Fuuid.id(of) == id) send(o, mm);
		}
	}

	@EventHandler
	public void onJoin(PlayerJoinEvent e) {
		Object fac = altFaction(e.getPlayer());
		if (fac != null) send(e.getPlayer(), "<gray>You're logged in as an alt for <white>" + Fuuid.tag(fac) + "</white>.");
	}

	// ---------------------------------------------------------------- what alts can't do

	private boolean deny(Player p) {
		if (!isAlt(p)) return false;
		p.sendActionBar(MiniMessage.miniMessage().deserialize("<red>Alts can only AFK. (/f alt leave)"));
		return true;
	}

	private static Player shooter(Entity damager) {
		if (damager instanceof Player p) return p;
		if (damager instanceof Projectile pr) {
			ProjectileSource s = pr.getShooter();
			if (s instanceof Player p) return p;
		}
		return null;
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onDamage(EntityDamageByEntityEvent e) {
		Player p = shooter(e.getDamager());
		if (p != null && deny(p)) e.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onLaunch(ProjectileLaunchEvent e) {
		if (e.getEntity() instanceof EnderPearl) return;
		if (e.getEntity().getShooter() instanceof Player p && deny(p)) e.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onPlace(BlockPlaceEvent e) {
		if (deny(e.getPlayer())) e.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onBreak(BlockBreakEvent e) {
		if (deny(e.getPlayer())) e.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onBucket(PlayerBucketEmptyEvent e) {
		if (deny(e.getPlayer())) e.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onBucket(PlayerBucketFillEvent e) {
		if (deny(e.getPlayer())) e.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onHangingPlace(HangingPlaceEvent e) {
		if (e.getPlayer() != null && deny(e.getPlayer())) e.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onHangingBreak(HangingBreakByEntityEvent e) {
		Player p = shooter(e.getRemover());
		if (p != null && deny(p)) e.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onOpen(InventoryOpenEvent e) {
		if (!(e.getPlayer() instanceof Player p)) return;
		InventoryHolder h = e.getInventory().getHolder(false);
		boolean container = h instanceof org.bukkit.block.Container || h instanceof DoubleChest
				|| (h instanceof Entity && !(h instanceof Player));
		if (container && deny(p)) e.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onEntityInteract(PlayerInteractEntityEvent e) {
		if (deny(e.getPlayer())) e.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onStand(PlayerArmorStandManipulateEvent e) {
		if (deny(e.getPlayer())) e.setCancelled(true);
	}

	/**
	 * Alts only AFK: every block they right-click is locked except doors, trapdoors and gates (so they can reach their
	 * AFK spot), and they can't trigger pressure plates or tripwires (no firing cannons).
	 */
	@EventHandler(priority = EventPriority.LOW)
	public void onInteract(PlayerInteractEvent e) {
		Block b = e.getClickedBlock();
		if (b == null || (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.PHYSICAL)) return;
		Material m = b.getType();
		if (e.getAction() == Action.RIGHT_CLICK_BLOCK && (Tag.DOORS.isTagged(m) || Tag.TRAPDOORS.isTagged(m) || Tag.FENCE_GATES.isTagged(m))) return;
		if (!isAlt(e.getPlayer())) return;
		e.setUseInteractedBlock(Event.Result.DENY);
		e.setUseItemInHand(Event.Result.DENY);
		if (e.getAction() == Action.RIGHT_CLICK_BLOCK) deny(e.getPlayer());
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onPickup(EntityPickupItemEvent e) {
		if (e.getEntity() instanceof Player p && isAlt(p)) e.setCancelled(true); // silent: they'd get spammed standing on farms
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onDrop(PlayerDropItemEvent e) {
		if (deny(e.getPlayer())) e.setCancelled(true);
	}
}
