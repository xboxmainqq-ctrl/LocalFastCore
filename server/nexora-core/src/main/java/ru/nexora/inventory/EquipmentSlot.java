package ru.nexora.inventory;

/**
 * Stable logical slots reserved for magical equipment.
 * These identifiers are persisted; Bedrock UI/container IDs are mapped separately.
 */
public enum EquipmentSlot {
    MAGIC_STAFF("Посох"),
    RING_LEFT("Кольцо (левая рука)"),
    RING_RIGHT("Кольцо (правая рука)"),
    AMULET("Амулет"),
    TALISMAN("Талисман"),
    ARTIFACT_LEFT("Артефакт I"),
    ARTIFACT_RIGHT("Артефакт II");

    private final String displayName;

    EquipmentSlot(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
