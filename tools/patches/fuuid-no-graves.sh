#!/usr/bin/env bash
# GravesX's API jar disappeared from its Maven host, which breaks FactionsUUID's build.
# Graves is an optional integration we don't use, so stub it out.
set -e
sed -i '/libs\.graves,/d' bukkit/build.gradle.kts
cat > bukkit/src/main/java/dev/kitteh/factions/integration/Graves.java <<'JAVA'
package dev.kitteh.factions.integration;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;

/** Stubbed by laG Network: GravesX integration removed (its API is no longer downloadable). */
@ApiStatus.Internal
public class Graves {
    public static boolean init(Plugin graves) {
        return false;
    }

    public static boolean allowAnyway(org.bukkit.block.Block block) {
        return false;
    }
}
JAVA
echo "patched: graves integration removed"
