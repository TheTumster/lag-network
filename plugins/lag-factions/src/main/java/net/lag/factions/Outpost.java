package net.lag.factions;

import dev.kitteh.factions.Faction;

/** Raiding outpost (own world, fortress + core). Filled in by the outpost update; boost is 0 until then. */
final class Outpost {
	private final LagFactions plugin;

	Outpost(LagFactions plugin) {
		this.plugin = plugin;
	}

	/** Spawner/farm speed boost (percent) for the faction holding the outpost. */
	double boost(Faction f) {
		return 0;
	}
}
