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

# 2b) explosion exposure for TNT / falling blocks: 1.8's World.a(Vec3D, AABB) (float sample stepping, rays stop at
#     block outlines - cobwebs, ladders, carpets shield like 1.8) instead of 1.21 collision-shape rays
edit("net/minecraft/world/level/ServerExplosion.java",
     "    private float getBlockDensity(Vec3 vec3d, Entity entity) {",
     """    private float getBlockDensity(Vec3 vec3d, Entity entity) {
        if (entity instanceof net.minecraft.world.entity.item.PrimedTnt || entity instanceof net.minecraft.world.entity.item.FallingBlockEntity) return this.lagLegacyDensity(vec3d, entity); // laG""")
edit("net/minecraft/world/level/ServerExplosion.java",
     "    // Paper start - Optimize explosions\n    private float getBlockDensity(",
     """    // laG: 1.8 explosion exposure (World.a(Vec3D, AxisAlignedBB)) for cannon entities
    private float lagLegacyDensity(Vec3 center, Entity entity) {
        net.minecraft.world.phys.AABB bb = entity.getBoundingBox();
        double d0 = 1.0D / ((bb.maxX - bb.minX) * 2.0D + 1.0D);
        double d1 = 1.0D / ((bb.maxY - bb.minY) * 2.0D + 1.0D);
        double d2 = 1.0D / ((bb.maxZ - bb.minZ) * 2.0D + 1.0D);
        double d3 = (1.0D - Math.floor(1.0D / d0) * d0) / 2.0D;
        double d4 = (1.0D - Math.floor(1.0D / d2) * d2) / 2.0D;
        if (d0 < 0.0D || d1 < 0.0D || d2 < 0.0D) return 0.0F;
        int seen = 0, total = 0;
        for (float f = 0.0F; f <= 1.0F; f = (float) (f + d0)) {
            for (float f1 = 0.0F; f1 <= 1.0F; f1 = (float) (f1 + d1)) {
                for (float f2 = 0.0F; f2 <= 1.0F; f2 = (float) (f2 + d2)) {
                    double x = bb.minX + (bb.maxX - bb.minX) * (double) f;
                    double y = bb.minY + (bb.maxY - bb.minY) * (double) f1;
                    double z = bb.minZ + (bb.maxZ - bb.minZ) * (double) f2;
                    Vec3 from = new Vec3(x + d3, y, z + d4);
                    if (this.level.clip(new net.minecraft.world.level.ClipContext(from, center, net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, entity)).getType() == net.minecraft.world.phys.HitResult.Type.MISS) seen++;
                    total++;
                }
            }
        }
        return total == 0 ? 0.0F : (float) seen / (float) total;
    }

    // Paper start - Optimize explosions
    private float getBlockDensity(""")

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
# 4) pistons push TNT / falling blocks like 1.8: a fixed 0.5625 per tick (progress step + 1/16), a final 0.25 nudge when
#    the piston finishes, and no 0.51-per-tick piston movement cap (1.8 had none)
CANNON = "(entity instanceof net.minecraft.world.entity.item.PrimedTnt || entity instanceof net.minecraft.world.entity.item.FallingBlockEntity)"
pm = "net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java"
edit(pm, "d4 = Math.min(d4, d) + 0.01;", f"d4 = {CANNON} ? d + 0.0625 : Math.min(d4, d) + 0.01; // laG: 1.8 piston push")
edit(pm, "    public static void tick(Level level, BlockPos pos, BlockState state, PistonMovingBlockEntity blockEntity) {",
     """    // laG: 1.8 TileEntityPiston pushes entities in the finished block's box once more by 0.25 when it completes
    private static void lagFinalPush(Level level, BlockPos pos, PistonMovingBlockEntity piston) {
        VoxelShape shape = piston.getCollisionRelatedBlockState().getCollisionShape(level, pos);
        if (shape.isEmpty()) return;
        AABB box = moveByPositionAndProgress(pos, shape.bounds(), piston);
        Direction dir = piston.getMovementDirection();
        for (Entity entity : level.getEntities((Entity) null, box)) {
            if (""" + CANNON + """) moveEntityByPiston(dir, entity, 0.25, dir);
        }
    }

    public static void tick(Level level, BlockPos pos, BlockState state, PistonMovingBlockEntity blockEntity) {""")
edit(pm, "        if (blockEntity.progressO >= 1.0F) {\n",
     "        if (blockEntity.progressO >= 1.0F) {\n            if (!level.isClientSide()) lagFinalPush(level, pos, blockEntity); // laG\n")
edit("net/minecraft/world/entity/Entity.java", r"(\w+) = this\.limitPistonMovement\((\w+)\);",
     r"if (!((Object) this instanceof net.minecraft.world.entity.item.PrimedTnt || (Object) this instanceof net.minecraft.world.entity.item.FallingBlockEntity)) \1 = this.limitPistonMovement(\2); // laG: 1.8 had no piston cap",
     regex=True)
print("laG Paper patches applied")
