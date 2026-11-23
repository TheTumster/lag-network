package net.lag.cannons;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Relapse gear rules (config relapse.enchant-cap / relapse.koth-only):
 *  - Standard gear caps at Protection 4 / Unbreaking 3. Anything higher (anvils, tables, books, villagers, loot,
 *    items from other servers or creative) is clamped down.
 *  - Netherite gear only comes from KOTH: the smithing upgrade is blocked, it's stripped from loot, untagged netherite
 *    armor can't be worn and untagged netherite weapons/tools don't hit or mine.
 *  - KOTH reward gear carries a PDC tag (lagcannons:koth_gear) and is never clamped. Staff make it with
 *    /lc kothgear [protection level] while holding the item.
 */
final class GearCap implements Listener {
	private final JavaPlugin plugin;
	final NamespacedKey kothKey;
	private final Map<Enchantment, Integer> caps = new HashMap<>();
	private final Set<Material> kothOnly = new HashSet<>();

	GearCap(JavaPlugin plugin) {
		this.plugin = plugin;
		this.kothKey = new NamespacedKey(plugin, "koth_gear");
		reload();
	}

	void reload() {
		caps.clear();
		kothOnly.clear();
		var sec = plugin.getConfig().getConfigurationSection("relapse.enchant-cap");
		if (sec != null) {
			for (String k : sec.getKeys(false)) {
				Enchantment en = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(k.toLowerCase(Locale.ROOT)));
				if (en == null) plugin.getLogger().warning("relapse.enchant-cap: unknown enchantment " + k);
				else caps.put(en, Math.max(0, sec.getInt(k)));
			}
		}
		for (String m : plugin.getConfig().getStringList("relapse.koth-only")) {
			Material mat = Material.matchMaterial(m);
			if (mat == null) plugin.getLogger().warning("relapse.koth-only: unknown item " + m);
			else kothOnly.add(mat);
		}
	}

	private boolean on() {
		return plugin.getConfig().getBoolean("relapse.gear-rules", true);
	}

	boolean isKoth(ItemStack it) {
		return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(kothKey, PersistentDataType.BYTE);
	}

	private boolean lockedNetherite(ItemStack it) {
		return it != null && kothOnly.contains(it.getType()) && !isKoth(it);
	}

	/** Clamps enchant levels in place. Returns true if anything changed. */
	boolean clamp(ItemStack it) {
		if (it == null || caps.isEmpty() || !it.hasItemMeta() || isKoth(it)) return false;
		ItemMeta meta = it.getItemMeta();
		boolean changed = false;
		for (var e : caps.entrySet()) {
			int lvl = meta.getEnchantLevel(e.getKey());
			if (lvl > e.getValue()) {
				meta.removeEnchant(e.getKey());
				if (e.getValue() > 0) meta.addEnchant(e.getKey(), e.getValue(), true);
				changed = true;
			}
			if (meta instanceof EnchantmentStorageMeta book && book.getStoredEnchantLevel(e.getKey()) > e.getValue()) {
				book.removeStoredEnchant(e.getKey());
				if (e.getValue() > 0) book.addStoredEnchant(e.getKey(), e.getValue(), true);
				changed = true;
			}
		}
		if (changed) it.setItemMeta(meta);
		return changed;
	}

	private void sweep(Player p) {
		PlayerInventory inv = p.getInventory();
		boolean any = false;
		for (ItemStack it : inv.getContents()) any |= clamp(it); // contents include armor + offhand
		if (any) p.sendMessage(MiniMessage.miniMessage().deserialize(
				"<gradient:#FF5C5C:#FFA19A>[RelapsedMC]</gradient> <gray>Some gear was over the cap (Protection 4, Unbreaking 3) and got lowered."));
		unequipLocked(p);
	}

	private void unequipLocked(Player p) {
		PlayerInventory inv = p.getInventory();
		ItemStack[] armor = inv.getArmorContents();
		List<ItemStack> off = new ArrayList<>();
		for (int i = 0; i < armor.length; i++) {
			if (lockedNetherite(armor[i])) { off.add(armor[i]); armor[i] = null; }
		}
		if (off.isEmpty()) return;
		inv.setArmorContents(armor);
		for (ItemStack left : inv.addItem(off.toArray(new ItemStack[0])).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
		p.sendMessage(MiniMessage.miniMessage().deserialize(
				"<gradient:#FF5C5C:#FFA19A>[RelapsedMC]</gradient> <red>Netherite only works if it's a KOTH reward."));
	}

	// ---------------------------------------------------------------- sources of over-cap gear

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onAnvil(PrepareAnvilEvent e) {
		if (!on()) return;
		ItemStack r = e.getResult();
		if (r == null) return;
		ItemStack c = r.clone();
		if (clamp(c)) e.setResult(c);
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onEnchant(EnchantItemEvent e) {
		if (!on()) return;
		for (var cap : caps.entrySet()) {
			Integer lvl = e.getEnchantsToAdd().get(cap.getKey());
			if (lvl != null && lvl > cap.getValue()) {
				if (cap.getValue() > 0) e.getEnchantsToAdd().put(cap.getKey(), cap.getValue());
				else e.getEnchantsToAdd().remove(cap.getKey());
			}
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onTrade(VillagerAcquireTradeEvent e) {
		if (!on()) return;
		MerchantRecipe old = e.getRecipe();
		ItemStack res = old.getResult().clone();
		if (!clamp(res)) return;
		MerchantRecipe r = new MerchantRecipe(res, old.getUses(), old.getMaxUses(), old.hasExperienceReward(),
				old.getVillagerExperience(), old.getPriceMultiplier(), old.getDemand(), old.getSpecialPrice());
		r.setIngredients(old.getIngredients());
		e.setRecipe(r);
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onSmith(PrepareSmithingEvent e) {
		if (on() && lockedNetherite(e.getResult())) e.setResult(null);
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onLoot(LootGenerateEvent e) {
		if (!on()) return;
		List<ItemStack> loot = e.getLoot();
		loot.removeIf(this::lockedNetherite);
		for (ItemStack it : loot) clamp(it);
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onPickup(EntityPickupItemEvent e) {
		if (!on() || !(e.getEntity() instanceof Player)) return;
		ItemStack it = e.getItem().getItemStack();
		if (clamp(it)) e.getItem().setItemStack(it);
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onClick(InventoryClickEvent e) {
		if (!on()) return;
		ItemStack cur = e.getCurrentItem();
		if (cur != null && clamp(cur)) e.setCurrentItem(cur);
	}

	@EventHandler
	public void onJoin(PlayerJoinEvent e) {
		if (on()) sweep(e.getPlayer());
	}

	/** Untagged netherite weapons/tools don't work (only creative/admin can still have them). */
	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onHit(EntityDamageByEntityEvent e) {
		if (on() && e.getDamager() instanceof Player p && lockedNetherite(p.getInventory().getItemInMainHand())) {
			e.setCancelled(true);
			kothOnlyMsg(p);
		}
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onBreak(BlockBreakEvent e) {
		if (on() && lockedNetherite(e.getPlayer().getInventory().getItemInMainHand())) {
			e.setCancelled(true);
			kothOnlyMsg(e.getPlayer());
		}
	}

	private static void kothOnlyMsg(Player p) {
		p.sendActionBar(MiniMessage.miniMessage().deserialize("<red>Netherite only works if it's a KOTH reward."));
	}

	@EventHandler
	public void onArmor(PlayerArmorChangeEvent e) {
		if (!on() || !lockedNetherite(e.getNewItem())) return;
		Player p = e.getPlayer();
		Bukkit.getScheduler().runTask(plugin, () -> unequipLocked(p)); // can't change armor inside the event
	}

	// ---------------------------------------------------------------- /lc kothgear

	/** Tags the held item as KOTH gear (never clamped, netherite allowed). Optional protection level, e.g. 5. */
	boolean command(CommandSender sender, String[] args) {
		if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
		ItemStack it = p.getInventory().getItemInMainHand();
		if (it.getType() == Material.AIR) { p.sendMessage("§cHold the item you want to make a KOTH reward."); return true; }
		boolean already = isKoth(it);
		ItemMeta meta = it.getItemMeta();
		meta.getPersistentDataContainer().set(kothKey, PersistentDataType.BYTE, (byte) 1);
		if (args.length > 1) {
			try {
				int lvl = Integer.parseInt(args[1]);
				meta.removeEnchant(Enchantment.PROTECTION);
				if (lvl > 0) meta.addEnchant(Enchantment.PROTECTION, lvl, true);
			} catch (NumberFormatException ex) {
				p.sendMessage("§cUsage: /lc kothgear [protection level]");
				return true;
			}
		}
		if (!already) {
			List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
			lore.add(MiniMessage.miniMessage().deserialize("<gradient:#FF5C5C:#FFA19A>KOTH Reward</gradient>").decoration(TextDecoration.ITALIC, false));
			meta.lore(lore);
		}
		it.setItemMeta(meta);
		p.sendMessage(MiniMessage.miniMessage().deserialize("<gradient:#FF5C5C:#FFA19A>[RelapsedMC]</gradient> <gray>Tagged as KOTH gear."));
		return true;
	}
}
