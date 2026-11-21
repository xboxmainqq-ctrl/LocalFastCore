package ru.nexora.network.bedrock;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, validated mapping between NEXORA item identifiers and the exact
 * protocol-specific fields needed by Bedrock network item stack descriptors.
 *
 * Item registry metadata is kept separate from the stack descriptor. Component
 * data is supplied as an already encoded, protocol-specific network-NBT value;
 * this registry never fabricates component payloads.
 */
public final class BedrockItemRegistry implements BedrockInventoryContentCodec.ItemDefinitionResolver {
    private final Map<String, Entry> byIdentifier;
    private final Map<Integer, Entry> byRuntimeId;
    private final List<Entry> entries;

    public BedrockItemRegistry(Collection<Entry> entries) {
        Objects.requireNonNull(entries, "entries");
        Map<String, Entry> identifiers = new LinkedHashMap<>();
        Map<Integer, Entry> runtimeIds = new LinkedHashMap<>();
        List<Entry> ordered = new ArrayList<>(entries.size());

        for (Entry entry : entries) {
            Objects.requireNonNull(entry, "entry");
            if (identifiers.putIfAbsent(entry.namespacedId(), entry) != null) {
                throw new IllegalArgumentException("Duplicate item identifier: " + entry.namespacedId());
            }
            int runtimeId = entry.networkDefinition().runtimeItemId();
            if (runtimeIds.putIfAbsent(runtimeId, entry) != null) {
                throw new IllegalArgumentException("Duplicate Bedrock runtime item ID: " + runtimeId);
            }
            ordered.add(entry);
        }

        this.byIdentifier = Collections.unmodifiableMap(identifiers);
        this.byRuntimeId = Collections.unmodifiableMap(runtimeIds);
        this.entries = List.copyOf(ordered);
    }

    public static BedrockItemRegistry empty() {
        return new BedrockItemRegistry(List.of());
    }

    @Override
    public BedrockInventoryContentCodec.ItemDefinition resolve(String namespacedItemId) {
        Entry entry = byIdentifier.get(Objects.requireNonNull(namespacedItemId, "namespacedItemId"));
        return entry == null ? null : entry.networkDefinition();
    }

    public Entry findByIdentifier(String namespacedItemId) {
        return byIdentifier.get(Objects.requireNonNull(namespacedItemId, "namespacedItemId"));
    }

    public Entry findByRuntimeItemId(int runtimeItemId) {
        return byRuntimeId.get(runtimeItemId);
    }

    /** Entries retain registration order, which is useful for deterministic packet construction. */
    public List<Entry> entries() {
        return entries;
    }

    public int size() {
        return entries.size();
    }

    public record Entry(
            String namespacedId,
            BedrockInventoryContentCodec.ItemDefinition networkDefinition,
            int maxStackSize,
            boolean componentBased,
            int itemVersion,
            byte[] componentData
    ) {
        private static final byte[] EMPTY_COMPOUND_NBT = new byte[] {10, 0, 0};

        public Entry(String namespacedId, BedrockInventoryContentCodec.ItemDefinition networkDefinition) {
            this(namespacedId, networkDefinition, 64, false, 2, EMPTY_COMPOUND_NBT);
        }

        public Entry(String namespacedId, BedrockInventoryContentCodec.ItemDefinition networkDefinition,
                     int maxStackSize) {
            this(namespacedId, networkDefinition, maxStackSize, false, 2, EMPTY_COMPOUND_NBT);
        }

        public Entry {
            Objects.requireNonNull(namespacedId, "namespacedId");
            if (!namespacedId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                throw new IllegalArgumentException("namespacedId must be a namespaced identifier");
            }
            Objects.requireNonNull(networkDefinition, "networkDefinition");
            if (maxStackSize < 1 || maxStackSize > 99) {
                throw new IllegalArgumentException("maxStackSize must be 1..99");
            }
            if (itemVersion < 0 || itemVersion > 2) {
                throw new IllegalArgumentException("itemVersion must be one of 0 (Legacy), 1 (DataDriven), or 2 (None)");
            }
            componentData = Objects.requireNonNull(componentData, "componentData").clone();
            if (componentData.length < 3 || componentData.length > 1_048_576) {
                throw new IllegalArgumentException("componentData must be a complete bounded network-NBT tag");
            }
        }

        @Override
        public byte[] componentData() {
            return componentData.clone();
        }
    }
}
