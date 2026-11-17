package net.lag.factions;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.TileState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * /f shop: spawners and tray/trench pickaxes, paid from your money or the faction bank.
 * Also owns how spawners behave as items: shop spawners keep their mob when placed, silk-touch mining drops the
 * spawner, explosions drop it (spawners.explosion-drop-chance). Tray/trench picks break a 3x3 or 5x5 area.
 */
final class Shop implements Listener {
	private final LagFactions plugin;
	final NamespacedKey spawnerKey, pickKey;
	private final Set<UUID> busy = new HashSet<>();

	private static final Set<Material> TRAY = EnumSet.of(Material.DIRT, Material.GRASS_BLOCK, Material.SAND, Material.RED_SAND, Material.GRAVEL,
			Material.NETHERRACK, Material.SOUL_SAND, Material.SOUL_SOIL, Material.CLAY, Material.MYCELIUM, Material.COARSE_DIRT, Material.PODZOL,
			Material.ROOTED_DIRT, Material.MUD, Material.SNOW_BLOCK, Material.END_STONE, Material.DIRT_PATH);

	private static final java.util.Map<String, Double> DEFAULT_PICKS = java.util.Map.of("trench3", 150000.0, "trench5", 500000.0, "tray3", 75000.0, "tray5", 250000.0);

	Shop(LagFactions plugin) {
		this.plugin = plugin;
		this.spawnerKey = new NamespacedKey(plugin, "spawner");
		this.pickKey = new NamespacedKey(plugin, "pick");
	}

	// ---------------------------------------------------------------- items

	static String pretty(String s) {
		StringBuilder b = new StringBuilder();
		for (String w : s.toLowerCase(Locale.ROOT).split("_")) {
			if (!b.isEmpty()) b.append(' ');
			b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
		}
		return b.toString();
	}

	ItemStack spawner(EntityType type, int amount) {
		ItemStack it = Ui.item(Material.SPAWNER, Ui.GOLD + "<bold>" + pretty(type.name()) + " Spawner",
				Ui.LABEL + "Place it to spawn " + pretty(type.name()).toLowerCase(Locale.ROOT) + "s",
				Ui.DIM + "Mine with silk touch to pick it back up");
		it.setAmount(Math.max(1, Math.min(64, amount)));
		ItemMeta m = it.getItemMeta();
		m.getPersistentDataContainer().set(spawnerKey, PersistentDataType.STRING, type.name());
		it.setItemMeta(m);
		return it;
	}

	ItemStack pick(String kind) {
		boolean tray = kind.startsWith("tray");
		int size = kind.endsWith("5") ? 5 : 3;
		ItemStack it = Ui.item(tray ? Material.DIAMOND_SHOVEL : Material.DIAMOND_PICKAXE, true,
				(tray ? Ui.CYAN : Ui.PURPLE) + "<bold>" + (tray ? "Tray" : "Trench") + " Pickaxe " + Ui.LABEL + size + "x" + size,
				tray ? Ui.LABEL + "Clears a flat " + size + "x" + size + " layer of dirt, sand," : Ui.LABEL + "Breaks a " + size + "x" + size + " area",
				tray ? Ui.LABEL + "gravel, netherrack and soul sand" : Ui.LABEL + "facing the way you mine",
				Ui.DIM + "Sneak to break one block");
		ItemMeta m = it.getItemMeta();
		m.addEnchant(Enchantment.EFFICIENCY, 5, true);
		m.addEnchant(Enchantment.UNBREAKING, 3, true);
		m.getPersistentDataContainer().set(pickKey, PersistentDataType.STRING, kind);
		it.setItemMeta(m);
		return it;
	}

	// ---------------------------------------------------------------- GUI

	void open(Player p) {
		Gui g = new Gui(6, Ui.GRAD + "<bold>FACTION SHOP</bold></gradient>");
		g.frame();
		ConfigurationSection sp = plugin.getConfig().getConfigurationSection("shop.spawners");
		int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
		int i = 0;
		if (sp != null) {
			for (String k : sp.getKeys(false)) {
				if (i >= slots.length) break;
				EntityType type;
				try {
					type = EntityType.valueOf(k.toUpperCase(Locale.ROOT));
				} catch (IllegalArgumentException e) {
					continue;
				}
				double price = sp.getDouble(k);
				ItemStack icon = spawner(type, 1);
				ItemMeta m = icon.getItemMeta();
				List<net.kyori.adventure.text.Component> lore = new ArrayList<>(m.lore());
				lore.add(net.kyori.adventure.text.Component.empty());
				lore.add(Ui.mm(Ui.LABEL + "Price <white>" + Ui.money(price)).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
				lore.add(Ui.mm(Ui.CYAN + "Click to buy").decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
				m.lore(lore);
				icon.setItemMeta(m);
				g.set(slots[i++], icon, c -> Pay.choose(c.player(), Ui.GOLD + pretty(type.name()) + " Spawner", price, Material.SPAWNER, () -> open(c.player()),
						payer -> give(payer, spawner(type, 1))));
			}
		}
		String[] picks = {"trench3", "trench5", "tray3", "tray5"};
		int[] pslots = {38, 39, 41, 42};
		for (int k = 0; k < picks.length; k++) {
			String kind = picks[k];
			double cfg = plugin.getConfig().getDouble("shop.picks." + kind, DEFAULT_PICKS.getOrDefault(kind, 0.0));
			final double price = cfg > 0 ? cfg : DEFAULT_PICKS.getOrDefault(kind, 0.0);
			ItemStack icon;
			try {
				icon = pick(kind);
			} catch (Exception ex) {
				plugin.getLogger().warning("Shop: couldn't make " + kind + ": " + ex);
				continue;
			}
			ItemMeta m = icon.getItemMeta();
			List<net.kyori.adventure.text.Component> lore = new ArrayList<>(m.lore());
			lore.add(net.kyori.adventure.text.Component.empty());
			lore.add(Ui.mm(Ui.LABEL + "Price <white>" + Ui.money(price)).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
			lore.add(Ui.mm(Ui.CYAN + "Click to buy").decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
			m.lore(lore);
			icon.setItemMeta(m);
			String label = (kind.startsWith("tray") ? Ui.CYAN + "Tray Pickaxe " : Ui.PURPLE + "Trench Pickaxe ") + (kind.endsWith("5") ? "5x5" : "3x3");
			g.set(pslots[k], icon, c -> Pay.choose(c.player(), label, price, kind.startsWith("tray") ? Material.DIAMOND_SHOVEL : Material.DIAMOND_PICKAXE,
					() -> open(c.player()), payer -> give(payer, pick(kind))));
		}
		g.set(4, Ui.item(Material.EMERALD, true, "<white><bold>SHOP", Ui.LABEL + "Pay with your money or the", Ui.LABEL + "faction bank (mods and up)."));
		g.set(9, Ui.item(Material.SPAWNER, Ui.GOLD + "<bold>SPAWNERS", Ui.LABEL + "Keep their mob when placed.", Ui.LABEL + "Silk touch picks them up."));
		g.set(36, Ui.item(Material.DIAMOND_PICKAXE, Ui.PURPLE + "<bold>TOOLS", Ui.LABEL + "Tray + trench pickaxes.", Ui.LABEL + "Sneak to break one block."));
		g.set(49, Ui.item(Material.ARROW, Ui.LABEL + "Back to /f menu"), c -> plugin.menu.open(c.player()));
		g.open(p);
	}

	private void give(Player p, ItemStack it) {
		p.getInventory().addItem(it).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
		Ui.ok(p, "Bought " + net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().serialize(it.getItemMeta().displayName()) + Ui.LABEL + ".");
	}

	// ---------------------------------------------------------------- spawner items

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPlace(BlockPlaceEvent e) {
		ItemStack it = e.getItemInHand();
		if (it.getType() != Material.SPAWNER || !it.hasItemMeta()) return;
		String t = it.getItemMeta().getPersistentDataContainer().get(spawnerKey, PersistentDataType.STRING);
		if (t == null) return;
		Block b = e.getBlockPlaced();
		Bukkit.getScheduler().runTask(plugin, () -> {
			if (b.getState() instanceof CreatureSpawner cs) {
				try {
					cs.setSpawnedType(EntityType.valueOf(t));
					cs.update(true, false);
				} catch (IllegalArgumentException ignored) { }
			}
		});
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onBreakSpawner(BlockBreakEvent e) {
		Block b = e.getBlock();
		if (b.getType() != Material.SPAWNER) return;
		ItemStack tool = e.getPlayer().getInventory().getItemInMainHand();
		if (!tool.containsEnchantment(Enchantment.SILK_TOUCH) || !(b.getState() instanceof CreatureSpawner cs) || cs.getSpawnedType() == null) return;
		e.setExpToDrop(0);
		e.setDropItems(false);
		b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), spawner(cs.getSpawnedType(), 1));
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onExplode(EntityExplodeEvent e) {
		double chance = plugin.getConfig().getDouble("spawners.explosion-drop-chance", 1.0);
		for (Block b : e.blockList()) {
			if (b.getType() != Material.SPAWNER || !(b.getState() instanceof CreatureSpawner cs) || cs.getSpawnedType() == null) continue;
			if (Math.random() < chance) b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), spawner(cs.getSpawnedType(), 1));
		}
	}

	// ---------------------------------------------------------------- tray / trench picks

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onBreakArea(BlockBreakEvent e) {
		Player p = e.getPlayer();
		if (busy.contains(p.getUniqueId()) || p.isSneaking()) return;
		ItemStack tool = p.getInventory().getItemInMainHand();
		if (!tool.hasItemMeta()) return;
		String kind = tool.getItemMeta().getPersistentDataContainer().get(pickKey, PersistentDataType.STRING);
		if (kind == null) return;
		boolean tray = kind.startsWith("tray");
		int r = kind.endsWith("5") ? 2 : 1;
		Block c = e.getBlock();
		if (tray && !TRAY.contains(c.getType())) return;
		float pitch = p.getLocation().getPitch();
		BlockFace face = p.getFacing();
		boolean flat = tray || Math.abs(pitch) > 50;
		List<Block> area = new ArrayList<>();
		for (int u = -r; u <= r; u++) {
			for (int v = -r; v <= r; v++) {
				if (u == 0 && v == 0) continue;
				Block b;
				if (flat) b = c.getRelative(u, 0, v);
				else if (face == BlockFace.NORTH || face == BlockFace.SOUTH) b = c.getRelative(u, v, 0);
				else b = c.getRelative(0, v, u);
				area.add(b);
			}
		}
		busy.add(p.getUniqueId());
		try {
			for (Block b : area) {
				Material m = b.getType();
				if (m.isAir() || !m.isSolid() && !TRAY.contains(m)) continue;
				if (tray ? !TRAY.contains(m) : !breakable(b)) continue;
				p.breakBlock(b); // fires BlockBreakEvent: claims and protections still apply
			}
		} finally {
			busy.remove(p.getUniqueId());
		}
	}

	private static boolean breakable(Block b) {
		Material m = b.getType();
		if (m.getHardness() < 0 || m.getHardness() > 30) return false; // bedrock, barrier, obsidian, crying obsidian...
		if (m == Material.SPAWNER || b.getState() instanceof TileState) return false; // spawners, chests, hoppers...
		return !Tag.SHULKER_BOXES.isTagged(m);
	}
}
