package ru.nexora.runtime;

import java.util.Objects;
import java.util.UUID;

/** Server-authoritative pickup of dropped item entities into player inventories. */
public final class ItemPickupService {
    public static final double DEFAULT_PICKUP_RADIUS = 1.5;

    private final EntityRegistry entities;
    private final World world;
    private final double pickupRadius;

    public ItemPickupService(EntityRegistry entities, World world) {
        this(entities, world, DEFAULT_PICKUP_RADIUS);
    }

    public ItemPickupService(EntityRegistry entities, World world, double pickupRadius) {
        this.entities = Objects.requireNonNull(entities, "entities");
        this.world = Objects.requireNonNull(world, "world");
        if (!Double.isFinite(pickupRadius) || pickupRadius <= 0.0 || pickupRadius > 8.0) {
            throw new IllegalArgumentException("Pickup radius must be greater than 0 and at most 8");
        }
        this.pickupRadius = pickupRadius;
    }

    /**
     * Collects nearby item entities for the owning player.
     * Each item entity currently represents one item; full inventories leave
     * remaining entities untouched on the ground.
     */
    public synchronized PickupResult pickup(UUID playerId, double x, double y, double z,
                                            PlayerInventory inventory) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(inventory, "inventory");
        if (!playerId.equals(inventory.ownerId())) {
            throw new IllegalArgumentException("Inventory does not belong to this player");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 30_000_000d || Math.abs(y) > 30_000_000d
                || Math.abs(z) > 30_000_000d) {
            throw new IllegalArgumentException("Player position is outside world bounds");
        }

        int collected = 0;
        for (WorldEntity entity : entities.within(x, y, z, pickupRadius)) {
            if (entity.type() != WorldEntity.Type.ITEM || !isChunkLoaded(entity)) {
                continue;
            }
            if (!isItemIdentifier(entity.name())) {
                continue;
            }
            if (inventory.isFull()) {
                break;
            }
            if (entities.remove(entity.id()).isEmpty()) {
                continue;
            }
            if (inventory.addOne(entity.name())) {
                collected++;
            } else {
                entities.spawn(entity);
                break;
            }
        }
        return new PickupResult(collected, inventory.occupiedSlots(), inventory.isFull());
    }

    private boolean isChunkLoaded(WorldEntity entity) {
        int blockX = (int) Math.floor(entity.x());
        int blockZ = (int) Math.floor(entity.z());
        return world.isChunkLoaded(new ChunkPosition(Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16)));
    }

    private static boolean isItemIdentifier(String value) {
        try {
            new ItemStack(value, 1);
            return true;
        } catch (IllegalArgumentException invalidItemId) {
            return false;
        }
    }

    public record PickupResult(int collected, int occupiedSlots, boolean inventoryFull) {
        public PickupResult {
            if (collected < 0 || occupiedSlots < 0) {
                throw new IllegalArgumentException("Invalid pickup result");
            }
        }
    }
}
