package ru.nexora.world;

/** Internal block identifiers. Network runtime IDs are resolved by a protocol-specific palette. */
public enum BlockType {
    AIR(0),
    STONE(1),
    DIRT(2),
    GRASS_BLOCK(3),
    BEDROCK(4),
    COBBLESTONE(5),
    GRAVEL(6),
    GLASS(7),
    OBSIDIAN(8),
    NETHERRACK(9),
    END_STONE(10),
    COAL_BLOCK(11),
    IRON_BLOCK(12),
    GOLD_BLOCK(13),
    DIAMOND_BLOCK(14),
    EMERALD_BLOCK(15),
    CHEST(16);

    private final int internalId;

    BlockType(int internalId) {
        this.internalId = internalId;
    }

    public int internalId() {
        return internalId;
    }

    public static BlockType byInternalId(int id) {
        for (BlockType type : values()) {
            if (type.internalId == id) return type;
        }
        throw new IllegalArgumentException("Unknown internal block ID: " + id);
    }
}
