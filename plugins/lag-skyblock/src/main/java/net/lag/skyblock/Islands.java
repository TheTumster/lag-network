package net.lag.skyblock;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;

/** Tiny BentoBox bridge (reflection, so we don't need BentoBox at compile time). */
final class Islands {
	private static Object manager;
	private static Method protectedAt, uniqueId, members;
	private static boolean failed;

	private Islands() { }

	private static boolean init() {
		if (manager != null) return true;
		if (failed) return false;
		try {
			Class<?> bb = Class.forName("world.bentobox.bentobox.BentoBox");
			Object inst = bb.getMethod("getInstance").invoke(null);
			if (inst == null) return false;
			manager = inst.getClass().getMethod("getIslands").invoke(inst);
			protectedAt = manager.getClass().getMethod("getProtectedIslandAt", Location.class);
			Class<?> island = Class.forName("world.bentobox.bentobox.database.objects.Island");
			uniqueId = island.getMethod("getUniqueId");
			members = island.getMethod("getMemberSet");
			return true;
		} catch (Throwable t) {
			failed = true;
			return false;
		}
	}

	/** The island whose protected area holds this location, or null. */
	static Object at(Location l) {
		if (l == null || !init()) return null;
		try {
			Optional<?> o = (Optional<?>) protectedAt.invoke(manager, l);
			return o.orElse(null);
		} catch (Throwable t) {
			return null;
		}
	}

	static String id(Object island) {
		try {
			return island == null ? null : String.valueOf(uniqueId.invoke(island));
		} catch (Throwable t) {
			return null;
		}
	}

	static UUID owner(Object island) {
		return (UUID) call(island, "getOwner");
	}

	/** The island's name, or null if it was never named. */
	static String name(Object island) {
		Object n = call(island, "getName");
		return n == null || String.valueOf(n).isBlank() ? null : String.valueOf(n);
	}

	@SuppressWarnings("unchecked")
	static Set<UUID> members(Object island) {
		try {
			return island == null || !init() ? Set.of() : (Set<UUID>) members.invoke(island);
		} catch (Throwable t) {
			return Set.of();
		}
	}

	static Location center(Object island) {
		Object o = call(island, "getProtectionCenter");
		return o instanceof Location l ? l : (Location) call(island, "getCenter");
	}

	/** The island this player belongs to in the given world (BentoBox's primary island), or null. */
	static Object of(org.bukkit.World world, UUID player) {
		if (world == null || !init()) return null;
		try {
			return manager.getClass().getMethod("getIsland", org.bukkit.World.class, UUID.class).invoke(manager, world, player);
		} catch (Throwable t) {
			return null;
		}
	}

	/** BSkyBlock's overworld (where islands live), or null. */
	static org.bukkit.World islandWorld() {
		return org.bukkit.Bukkit.getWorld("bskyblock_world");
	}

	private static Object call(Object o, String method) {
		if (o == null) return null;
		try {
			return o.getClass().getMethod(method).invoke(o);
		} catch (Throwable t) {
			return null;
		}
	}

	/** True if this player is a member (or owner) of the island. */
	static boolean member(Object island, UUID player) {
		try {
			return island != null && ((Set<?>) members.invoke(island)).contains(player);
		} catch (Throwable t) {
			return false;
		}
	}
}
