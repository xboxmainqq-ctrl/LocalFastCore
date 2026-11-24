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
    CHEST(16),
    OAK_LOG(17),
    OAK_PLANKS(18),
    CRAFTING_TABLE(19),
    FURNACE(20),
    BLAST_FURNACE(21),
    SMOKER(22),
    SPAWNER(23),
    OAK_LEAVES(24);

    private static final BlockType[] BY_INTERNAL_ID = createLookup();

    private final int internalId;

    BlockType(int internalId) {
        this.internalId = internalId;
    }

    public int internalId() {
        return internalId;
    }

    public static BlockType byInternalId(int id) {
        if (id < 0 || id >= BY_INTERNAL_ID.length || BY_INTERNAL_ID[id] == null) {
            throw new IllegalArgumentException("Unknown internal block ID: " + id);
        }
        return BY_INTERNAL_ID[id];
    }

    private static BlockType[] createLookup() {
        BlockType[] types = values();
        int maximumId = 0;
        for (BlockType type : types) maximumId = Math.max(maximumId, type.internalId);
        BlockType[] lookup = new BlockType[maximumId + 1];
        for (BlockType type : types) lookup[type.internalId] = type;
        return lookup;
    }
}
