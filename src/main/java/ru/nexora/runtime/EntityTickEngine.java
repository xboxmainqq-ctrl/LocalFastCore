package ru.nexora.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-authoritative, deterministic simulation for passive mobs and dropped items.
 *
 * <p>The engine is advanced by the world game loop at 20 ticks per second.
 * It deliberately does not own a thread or wall clock.</p>
 */
public final class EntityTickEngine {
    public static final int DEFAULT_ITEM_DESPAWN_TICKS = 6_000;
    private static final double ITEM_GRAVITY = 0.04;
    private static final double MAX_ITEM_FALL_SPEED = 0.4;
    private static final double MOB_SPEED_PER_TICK = 0.08;
    private static final int MOB_DECISION_INTERVAL = 60;
    private static final int MOB_WANDER_RADIUS = 4;

    private final EntityRegistry entities;
    private final World world;
    private final int itemDespawnTicks;
    private final MobAiController mobAi;
    private final Map<UUID, ItemState> itemStates = new HashMap<>();
    private final Map<UUID, MobState> mobStates = new HashMap<>();
    private long currentTick;

    public EntityTickEngine(EntityRegistry entities, World world) {
        this(entities, world, DEFAULT_ITEM_DESPAWN_TICKS);
    }

    public EntityTickEngine(EntityRegistry entities, World world, int itemDespawnTicks) {
        this(entities, world, itemDespawnTicks, new MobAiController());
    }

    public EntityTickEngine(EntityRegistry entities, World world, int itemDespawnTicks, MobAiController mobAi) {
        this.entities = Objects.requireNonNull(entities, "entities");
        this.world = Objects.requireNonNull(world, "world");
        this.mobAi = Objects.requireNonNull(mobAi, "mobAi");
        if (itemDespawnTicks < 1) {
            throw new IllegalArgumentException("Item despawn ticks must be at least 1");
        }
        this.itemDespawnTicks = itemDespawnTicks;
    }

    /** Advances one simulation tick and returns the work performed. */
    public synchronized TickResult tick() {
        currentTick = Math.incrementExact(currentTick);
        int movedMobs = 0;
        int movedItems = 0;
        int despawnedItems = 0;

        for (WorldEntity entity : entities.snapshot()) {
            if (entity.type() == WorldEntity.Type.ITEM) {
                ItemTickResult result = tickItem(entity);
                if (result.moved()) {
                    movedItems++;
                }
                if (result.despawned()) {
                    despawnedItems++;
                }
            } else if (entity.type() == WorldEntity.Type.MOB && tickMob(entity)) {
                movedMobs++;
            }
        }

        itemStates.keySet().removeIf(id -> entities.find(id).isEmpty());
        mobStates.keySet().removeIf(id -> entities.find(id).isEmpty());
        mobAi.retainEntities(entities);
        return new TickResult(currentTick, movedMobs, movedItems, despawnedItems);
    }

    public synchronized long currentTick() {
        return currentTick;
    }

    /** Returns the profile-based AI controller used by this authoritative simulation. */
    public MobAiController mobAi() {
        return mobAi;
    }

    private ItemTickResult tickItem(WorldEntity item) {
        ItemState state = itemStates.computeIfAbsent(item.id(), ignored -> new ItemState());
        state.ageTicks++;
        if (state.ageTicks >= itemDespawnTicks) {
            entities.remove(item.id());
            itemStates.remove(item.id());
            return new ItemTickResult(false, true);
        }

        if (!isChunkLoaded(item.x(), item.z())) {
            return new ItemTickResult(false, false);
        }

        double nextVelocityY = Math.max(-MAX_ITEM_FALL_SPEED, state.velocityY - ITEM_GRAVITY);
        double nextY = item.y() + nextVelocityY;
        WorldHeight height = world.height();
        if (nextY <= height.minY()) {
            nextY = height.minY();
            nextVelocityY = 0;
        } else {
            int blockX = floorToInt(item.x());
            int blockY = floorToInt(nextY);
            int blockZ = floorToInt(item.z());
            if (height.contains(blockY) && !world.blockAt(new BlockPosition(blockX, blockY, blockZ)).isAir()) {
                nextY = blockY + 1.0;
                nextVelocityY = 0;
            } else if (blockY > height.minY()
                    && height.contains(blockY - 1)
                    && !world.blockAt(new BlockPosition(blockX, blockY - 1, blockZ)).isAir()
                    && nextVelocityY <= 0) {
                nextY = blockY;
                nextVelocityY = 0;
            }
        }

        state.velocityY = nextVelocityY;
        boolean moved = Math.abs(nextY - item.y()) > 0.000001;
        if (moved) {
            entities.updatePosition(item.id(), item.x(), nextY, item.z(), item.yaw());
        }
        return new ItemTickResult(moved, false);
    }

    private boolean tickMob(WorldEntity mob) {
        if (!isChunkLoaded(mob.x(), mob.z())) {
            return false;
        }
        if (mobAi.hasBehavior(mob.id())) {
            return mobAi.tick(mob, currentTick, entities, world).moved();
        }
        MobState state = mobStates.computeIfAbsent(mob.id(), ignored -> new MobState());
        if (currentTick >= state.nextDecisionTick) {
            long seed = mob.id().getMostSignificantBits()
                    ^ mob.id().getLeastSignificantBits()
                    ^ (currentTick * 0x9E3779B97F4A7C15L);
            int dx = (int) Math.floorMod(mix(seed), MOB_WANDER_RADIUS * 2 + 1) - MOB_WANDER_RADIUS;
            int dz = (int) Math.floorMod(mix(seed + 31), MOB_WANDER_RADIUS * 2 + 1) - MOB_WANDER_RADIUS;
            state.targetX = Math.floor(mob.x()) + dx + 0.5;
            state.targetZ = Math.floor(mob.z()) + dz + 0.5;
            state.nextDecisionTick = currentTick + MOB_DECISION_INTERVAL;
        }

        double dx = state.targetX - mob.x();
        double dz = state.targetZ - mob.z();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < MOB_SPEED_PER_TICK) {
            return false;
        }

        double stepX = dx / distance * MOB_SPEED_PER_TICK;
        double stepZ = dz / distance * MOB_SPEED_PER_TICK;
        double nextX = mob.x() + stepX;
        double nextZ = mob.z() + stepZ;
        if (!hasSafeMobFooting(nextX, mob.y(), nextZ)) {
            state.nextDecisionTick = currentTick;
            return false;
        }

        double yaw = Math.toDegrees(Math.atan2(-stepX, stepZ));
        entities.updatePosition(mob.id(), nextX, mob.y(), nextZ, yaw);
        return true;
    }

    private boolean hasSafeMobFooting(double x, double y, double z) {
        int blockX = floorToInt(x);
        int feetY = floorToInt(y);
        int blockZ = floorToInt(z);
        WorldHeight height = world.height();
        if (!height.contains(feetY) || !height.contains(feetY + 1)
                || !height.contains(feetY - 1)
                || !world.isChunkLoaded(new ChunkPosition(Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16)))) {
            return false;
        }
        return world.blockAt(new BlockPosition(blockX, feetY, blockZ)).isAir()
                && world.blockAt(new BlockPosition(blockX, feetY + 1, blockZ)).isAir()
                && !world.blockAt(new BlockPosition(blockX, feetY - 1, blockZ)).isAir();
    }

    private boolean isChunkLoaded(double x, double z) {
        int blockX = floorToInt(x);
        int blockZ = floorToInt(z);
        return world.isChunkLoaded(new ChunkPosition(Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16)));
    }

    private static int floorToInt(double value) {
        return (int) Math.floor(value);
    }

    private static long mix(long value) {
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private record ItemTickResult(boolean moved, boolean despawned) {
    }

    private static final class ItemState {
        private int ageTicks;
        private double velocityY;
    }

    private static final class MobState {
        private double targetX;
        private double targetZ;
        private long nextDecisionTick;
    }

    public record TickResult(long tick, int movedMobs, int movedItems, int despawnedItems) {
        public TickResult {
            if (tick < 1 || movedMobs < 0 || movedItems < 0 || despawnedItems < 0) {
                throw new IllegalArgumentException("Invalid entity tick result");
            }
        }
    }
}
