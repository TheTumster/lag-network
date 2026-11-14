package net.lag.lab;

import java.util.HashSet;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;

/** Water/lava can't wash away redstone on the lab (same list as the factions server). */
final class WaterProof implements Listener {
	private static final Set<Material> PROOF = new HashSet<Material>();

	static {
		for (Material m : Material.values()) {
			String n = m.name();
			if (n.equals("REDSTONE_WIRE") || n.startsWith("DIODE_BLOCK") || n.startsWith("REDSTONE_COMPARATOR") || n.startsWith("REDSTONE_TORCH")
					|| n.equals("LEVER") || n.equals("TRIPWIRE") || n.equals("TRIPWIRE_HOOK") || n.endsWith("_BUTTON") || n.endsWith("_PLATE")
					|| n.equals("RAILS") || n.equals("POWERED_RAIL") || n.equals("DETECTOR_RAIL") || n.equals("ACTIVATOR_RAIL"))
				PROOF.add(m);
		}
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onFlow(BlockFromToEvent e) {
		if (PROOF.contains(e.getToBlock().getType())) e.setCancelled(true);
	}
}
