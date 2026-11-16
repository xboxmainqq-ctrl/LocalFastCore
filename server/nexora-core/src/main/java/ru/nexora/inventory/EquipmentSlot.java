package ru.nexora.inventory;

/**
 * Stable logical slots reserved for future NEXORA magical equipment.
 *
 * These are server-side equipment slots, not Bedrock container IDs. A client
 * UI/protocol mapping can be added later without changing saved player data.
 */
public enum EquipmentSlot {
    MAGIC_STAFF,
    RING_LEFT,
    RING_RIGHT,
    AMULET,
    TALISMAN,
    ARTIFACT_LEFT,
    ARTIFACT_RIGHT
}
