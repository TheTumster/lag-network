"""laG Paper: small cannon patches applied to Paper 1.21.11's generated sources (after ./gradlew applyAllPatches).

Each change mirrors what 1.8 cannon servers (PaperSpigot fix-cannons + TacoSpigot/SulphurSpigot) did:
  1. TNT explodes from its middle (y + height/2), not y + 0.0625.
  2. Explosions aim/measure TNT and falling blocks at their middle (1.8 fix-cannons headHeight = length/2).
  3. TNT / falling blocks: move Z before X when |x| > |z| (TacoSpigot fix-east-west-cannoning).
Fails loudly (printing nearby code) if Paper's code doesn't look as expected.
"""
import pathlib, re, sys

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "paper-server/src/minecraft/java")


def find(rel):
    p = root / rel
    if not p.exists():
        hits = list(root.rglob(pathlib.Path(rel).name))
        if not hits:
            sys.exit(f"!! {rel} not found under {root}")
        p = hits[0]
    return p


def show(src, needle, n=6):
    i = src.find(needle)
    lines = src[:i].count("\n") if i >= 0 else 0
    print("\n".join(src.splitlines()[max(0, lines - n):lines + n]))


def edit(rel, pattern, repl, count=1, regex=False):
    p = find(rel)
    src = p.read_text()
    if regex:
        new, k = re.subn(pattern, repl, src, count=count)
    else:
        k = src.count(pattern)
        new = src.replace(pattern, repl, count if count else -1)
    if k == 0:
        key = pattern.split("(")[0][:40] if not regex else pattern[:40]
        print(f"!! no match in {p} for: {pattern[:120]}")
        for line in src.splitlines():
            if any(w in line for w in ("getY(0.0625)", "getEyePosition", "distanceToSqr(this.center)", "performCollisions(")):
                print("   ", line.strip())
        sys.exit(1)
    p.write_text(new)
    print(f"ok  {p.name}: {k} change(s) [{pattern[:70]}]")


MID = "(entity instanceof net.minecraft.world.entity.item.PrimedTnt || entity instanceof net.minecraft.world.entity.item.FallingBlockEntity)"

# 1) TNT explosion center: middle of the TNT (1.8 cannon servers), not 1/16 up
edit("net/minecraft/world/entity/item/PrimedTnt.java", "this.getY(0.0625)", "this.getY(0.5) /* laG: 1.8 cannon explosion center */")

# 2) knockback direction + distance from the middle of TNT / falling blocks
edit("net/minecraft/world/level/ServerExplosion.java",
     r"Vec3 vec3 = entity instanceof PrimedTnt \? entity\.position\(\) : entity\.getEyePosition\(\);",
     f"Vec3 vec3 = {MID} ? entity.position().add(0.0, entity.getBbHeight() / 2.0, 0.0) : (entity instanceof PrimedTnt ? entity.position() : entity.getEyePosition()); // laG: 1.8 cannon target",
     regex=True)
edit("net/minecraft/world/level/ServerExplosion.java",
     "Math.sqrt(entity.distanceToSqr(this.center)) / f",
     f"Math.sqrt({MID} ? entity.position().add(0.0, entity.getBbHeight() / 2.0, 0.0).distanceToSqr(this.center) : entity.distanceToSqr(this.center)) / f /* laG */")

# 3) east/west cannoning: legacy axis order for TNT and falling blocks
cu = "ca/spottedleaf/moonrise/patches/collisions/CollisionUtil.java"
edit(cu,
     "    public static boolean isCollidingWithBorder(final WorldBorder worldborder, final AABB boundingBox) {",
     """    // laG: TNT / falling blocks move Z before X when |x| > |z| (1.8 TacoSpigot "fix-east-west-cannoning")
    public static Vec3 performCollisionsCannon(final Vec3 moveVector, AABB axisalignedbb,
                                               final List<VoxelShape> voxels,
                                               final List<AABB> aabbs) {
        double x = moveVector.x;
        double y = moveVector.y;
        double z = moveVector.z;
        if (y != 0.0) {
            y = performAABBCollisionsY(axisalignedbb, y, aabbs);
            y = performVoxelCollisionsY(axisalignedbb, y, voxels);
            if (y != 0.0) axisalignedbb = offsetY(axisalignedbb, y);
        }
        final boolean zFirst = Math.abs(x) > Math.abs(z);
        if (zFirst && z != 0.0) {
            z = performAABBCollisionsZ(axisalignedbb, z, aabbs);
            z = performVoxelCollisionsZ(axisalignedbb, z, voxels);
            if (z != 0.0) axisalignedbb = offsetZ(axisalignedbb, z);
        }
        if (x != 0.0) {
            x = performAABBCollisionsX(axisalignedbb, x, aabbs);
            x = performVoxelCollisionsX(axisalignedbb, x, voxels);
            if (!zFirst && x != 0.0) axisalignedbb = offsetX(axisalignedbb, x);
        }
        if (!zFirst && z != 0.0) {
            z = performAABBCollisionsZ(axisalignedbb, z, aabbs);
            z = performVoxelCollisionsZ(axisalignedbb, z, voxels);
        }
        return new Vec3(x, y, z);
    }

    public static boolean isCollidingWithBorder(final WorldBorder worldborder, final AABB boundingBox) {""")
edit("net/minecraft/world/entity/Entity.java",
     "final Vec3 collided = ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.performCollisions(movement, currentBox, potentialCollisionsVoxel, potentialCollisionsBB);",
     "final Vec3 collided = ((Object)this instanceof net.minecraft.world.entity.item.PrimedTnt || (Object)this instanceof net.minecraft.world.entity.item.FallingBlockEntity) ? ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.performCollisionsCannon(movement, currentBox, potentialCollisionsVoxel, potentialCollisionsBB) : ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.performCollisions(movement, currentBox, potentialCollisionsVoxel, potentialCollisionsBB); // laG")
print("laG Paper patches applied")
