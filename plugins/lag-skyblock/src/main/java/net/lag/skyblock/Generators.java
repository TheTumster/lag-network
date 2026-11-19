package net.lag.skyblock;

import java.io.File;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.kyori.adventure.text.Component;

/**
 * Ore generators: a cobblestone generator on your island makes ores instead of plain cobble, by your island's
 * generator tier. /gen shows the tiers and buys the next one (island-wide, any member can upgrade).
 */
final class Generators implements Listener, CommandExecutor {
	record GenTier(int number, String name, String color, Material icon, double price, LinkedHashMap<Material, Double> chances) { }

	private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 22, 31};
	private static final DecimalFormat MONEY = new DecimalFormat("#,##0");
	private final LagSkyblock plugin;
	private final List<GenTier> tiers = new ArrayList<>();
	private final Map<String, Integer> islandTier = new HashMap<>();
	private final File file;

	private static final class Menu implements InventoryHolder {
		Inventory inv;
		Object island;

		@Override
		public Inventory getInventory() {
			return inv;
		}
	}

	Generators(LagSkyblock plugin) {
		this.plugin = plugin;
		this.file = new File(plugin.getDataFolder(), "generators.yml");
		load();
	}

	void load() {
		tiers.clear();
		List<Map<?, ?>> list = plugin.getConfig().getMapList("generator-tiers");
		for (int i = 0; i < list.size(); i++) {
			Map<?, ?> m = list.get(i);
			LinkedHashMap<Material, Double> ch = new LinkedHashMap<>();
			if (m.get("ores") instanceof Map<?, ?> ores) {
				for (var e : ores.entrySet()) {
					Material mat = Material.matchMaterial(String.valueOf(e.getKey()));
					if (mat != null && mat.isBlock()) ch.put(mat, ((Number) e.getValue()).doubleValue());
				}
			}
			Material icon = Material.matchMaterial(String.valueOf(m.get("icon")));
			tiers.add(new GenTier(i + 1, String.valueOf(m.get("name")), String.valueOf(m.containsKey("color") ? m.get("color") : "#FFFFFF"),
					icon == null ? Material.COBBLESTONE : icon, m.get("price") instanceof Number n ? n.doubleValue() : 0, ch));
		}
		islandTier.clear();
		YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
		for (String k : y.getKeys(false)) islandTier.put(k, y.getInt(k));
	}

	void save() {
		YamlConfiguration y = new YamlConfiguration();
		islandTier.forEach(y::set);
		try {
			y.save(file);
		} catch (Exception e) {
			plugin.getLogger().warning("Could not save generators.yml: " + e);
		}
	}

	private int tierOf(Object island) {
		String id = Islands.id(island);
		return id == null ? 1 : Math.max(1, islandTier.getOrDefault(id, 1));
	}

	// ---------------------------------------------------------------- the generator

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onForm(BlockFormEvent e) {
		Material made = e.getNewState().getType();
		if (made != Material.COBBLESTONE && made != Material.STONE) return;
		// only lava + water generators (not concrete powder, snow, ice...)
		Material was = e.getBlock().getType();
		if (was != Material.LAVA && was != Material.WATER && was != Material.AIR) return;
		Object island = Islands.at(e.getBlock().getLocation());
		if (island == null || tiers.isEmpty()) return;
		GenTier t = tiers.get(Math.min(tiers.size(), tierOf(island)) - 1);
		double total = 0;
		for (double c : t.chances().values()) total += c;
		if (total <= 0) return;
		double r = ThreadLocalRandom.current().nextDouble() * total;
		for (var en : t.chances().entrySet()) {
			r -= en.getValue();
			if (r <= 0) {
				e.getNewState().setType(en.getKey());
				return;
			}
		}
	}

	// ---------------------------------------------------------------- /gen menu

	private static String money(double d) {
		return LagSkyblock.shards(d);
	}

	private static String pretty(Material m) {
		String s = m.name().toLowerCase().replace("_ore", "").replace('_', ' ');
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	void open(Player p) {
		// Works from anywhere: use the island you're standing on if you're a member, otherwise your own island.
		Object island = Islands.at(p.getLocation());
		if (island == null || !Islands.member(island, p.getUniqueId())) {
			org.bukkit.World w = Islands.islandWorld();
			island = w == null ? null : Islands.of(w, p.getUniqueId());
		}
		if (island == null) {
			p.sendMessage(Spawners.mm("<red>You don't have an island yet. <gray>(/is create)"));
			return;
		}
		Menu m = new Menu();
		m.island = island;
		m.inv = Bukkit.createInventory(m, 45, Spawners.mm("<gradient:#38E0FF:#7CFF6B><bold>ORE GENERATOR</bold></gradient> <dark_gray>· tier " + tierOf(island) + "/" + tiers.size()));
		draw(m);
		p.openInventory(m.inv);
		p.playSound(p.getLocation(), Sound.BLOCK_FURNACE_FIRE_CRACKLE, 0.8f, 1.2f);
	}

	private static ItemStack pane(Material mat) {
		ItemStack it = new ItemStack(mat);
		ItemMeta im = it.getItemMeta();
		im.setHideTooltip(true);
		it.setItemMeta(im);
		return it;
	}

	private void draw(Menu m) {
		int cur = tierOf(m.island);
		for (int i = 0; i < 45; i++) m.inv.setItem(i, pane(Material.BLACK_STAINED_GLASS_PANE));
		for (int i = 0; i < 9; i++) m.inv.setItem(i, pane(i % 2 == 0 ? Material.CYAN_STAINED_GLASS_PANE : Material.LIGHT_BLUE_STAINED_GLASS_PANE));
		for (int i = 0; i < tiers.size() && i < SLOTS.length; i++) {
			GenTier t = tiers.get(i);
			boolean owned = t.number() <= cur, next = t.number() == cur + 1;
			ItemStack it = new ItemStack(owned || next ? t.icon() : Material.GRAY_DYE);
			ItemMeta im = it.getItemMeta();
			String head = owned || next ? "<color:" + t.color() + "><bold>" + t.name().toUpperCase() + "</bold>" : "<dark_gray><bold>???</bold>";
			im.displayName(Spawners.mm(head + " <dark_gray>· <gray>Tier " + t.number()));
			List<Component> lore = new ArrayList<>();
			lore.add(Spawners.mm(t.number() == cur ? "<#7CFF6B>● Active" : owned ? "<#7CFF6B>✔ Owned" : next ? "<#FFB020>🔒 Next upgrade" : "<dark_gray>🔒 Locked"));
			lore.add(Component.empty());
			if (owned || next) {
				double total = t.chances().values().stream().mapToDouble(Double::doubleValue).sum();
				for (var en : t.chances().entrySet()) {
					lore.add(Spawners.mm("<gray>" + pretty(en.getKey()) + " <white>" + String.format("%.1f", en.getValue() * 100 / total) + "%"));
				}
				if (next) {
					lore.add(Component.empty());
					lore.add(Spawners.mm("<gray>Upgrade: <gold>" + money(t.price())));
					lore.add(Spawners.mm("<#7CFF6B>▶ Click to upgrade"));
				}
			} else {
				lore.add(Spawners.mm("<dark_gray>Upgrade the tier before this first."));
			}
			im.lore(lore);
			if (t.number() == cur) im.setEnchantmentGlintOverride(true);
			it.setItemMeta(im);
			m.inv.setItem(SLOTS[i], it);
		}
		ItemStack info = new ItemStack(Material.BOOK);
		ItemMeta im = info.getItemMeta();
		im.displayName(Spawners.mm("<#38E0FF><bold>HOW IT WORKS"));
		im.lore(List.of(Spawners.mm("<gray>Build a cobblestone generator"), Spawners.mm("<gray>(lava next to water) on your island."),
				Spawners.mm(""), Spawners.mm("<gray>Instead of cobble it makes <white>ores</white>,"), Spawners.mm("<gray>better ones every tier."),
				Spawners.mm(""), Spawners.mm("<dark_gray>Upgrades are island-wide.")));
		info.setItemMeta(im);
		m.inv.setItem(40, info);
	}

	@EventHandler
	public void onClick(InventoryClickEvent e) {
		if (!(e.getInventory().getHolder() instanceof Menu m)) return;
		e.setCancelled(true);
		if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
		for (int i = 0; i < tiers.size() && i < SLOTS.length; i++) {
			if (SLOTS[i] != e.getRawSlot()) continue;
			GenTier t = tiers.get(i);
			int cur = tierOf(m.island);
			if (t.number() != cur + 1) {
				p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.7f);
				return;
			}
			if (plugin.eco() == null || !plugin.eco().has(p, t.price())) {
				p.sendMessage(Spawners.mm("<red>You need <white>" + money(t.price()) + "</white> for the " + t.name() + " generator."));
				p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.7f);
				return;
			}
			plugin.eco().withdrawPlayer(p, t.price());
			islandTier.put(Islands.id(m.island), t.number());
			save();
			p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.6f, 1.3f);
			p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.4f);
			p.showTitle(net.kyori.adventure.title.Title.title(Spawners.mm("<color:" + t.color() + "><bold>" + t.name().toUpperCase() + " GENERATOR"),
					Spawners.mm("<gray>Your island's generator is now tier " + t.number())));
			draw(m);
			return;
		}
	}

	@EventHandler
	public void onDrag(InventoryDragEvent e) {
		if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
	}

	@Override
	public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
		if (s instanceof Player p) open(p);
		return true;
	}
}
