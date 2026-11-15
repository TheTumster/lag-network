package net.lag.cannons;

import java.util.Collection;
import java.util.UUID;

import org.bukkit.World;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

/**
 * WorldGuard regions mirroring the plots, so FastAsyncWorldEdit only lets players paste/edit inside plots they own or
 * were added to (FAWE's region restrictions use WorldGuard membership). Only loaded when WorldGuard is installed.
 */
final class PlotRegions {
	private PlotRegions() {}

	private static RegionManager rm(World w) {
		return WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(w));
	}

	/** Roads and unclaimed land: nobody builds unless they're a member of a plot region there. */
	static void setupWorld(World w) {
		RegionManager m = rm(w);
		if (m == null) return;
		ProtectedRegion g = m.getRegion("__global__");
		if (g == null) {
			g = new GlobalProtectedRegion("__global__");
			m.addRegion(g);
		}
		g.setFlag(Flags.BUILD, StateFlag.State.DENY);
		g.setFlag(Flags.PASSTHROUGH, StateFlag.State.DENY);
		g.setFlag(Flags.MOB_SPAWNING, StateFlag.State.DENY);
	}

	static void set(World w, String id, int x1, int z1, int x2, int z2, UUID owner, Collection<UUID> trusted) {
		RegionManager m = rm(w);
		if (m == null) return;
		String rid = "plot_" + id.replace(';', '_').replace('-', 'm');
		ProtectedCuboidRegion r = new ProtectedCuboidRegion(rid, BlockVector3.at(x1, w.getMinHeight(), z1), BlockVector3.at(x2, w.getMaxHeight() - 1, z2));
		r.setPriority(10);
		r.getOwners().addPlayer(owner);
		for (UUID t : trusted) r.getMembers().addPlayer(t);
		r.setFlag(Flags.TNT, StateFlag.State.ALLOW);
		r.setFlag(Flags.OTHER_EXPLOSION, StateFlag.State.ALLOW);
		m.addRegion(r);
	}

	static void remove(World w, String id) {
		RegionManager m = rm(w);
		if (m == null) return;
		m.removeRegion("plot_" + id.replace(';', '_').replace('-', 'm'));
	}
}
