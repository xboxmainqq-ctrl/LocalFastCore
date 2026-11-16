package ru.nexora.runtime;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemVersion;
import org.cloudburstmc.protocol.bedrock.packet.ItemRegistryPacket;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Version-pinned vanilla Bedrock item definitions for protocol 2225.
 *
 * <p>The runtime IDs are captured from a 1.26.60.6 dedicated server. They are
 * signed 16-bit values; do not replace them with Java Edition IDs or IDs from
 * another Bedrock release.</p>
 */
public final class BedrockItemRegistry {
    public static final String RESOURCE = "/bedrock/1.26.60/required_item_list.json";
    private static final JsonFactory JSON = new JsonFactory();

    private final Map<String, ItemDefinition> definitions;
    private final List<ItemDefinition> orderedDefinitions;

    private BedrockItemRegistry(Map<String, ItemDefinition> definitions) {
        this.definitions = Map.copyOf(definitions);
        this.orderedDefinitions = List.copyOf(definitions.values());
    }

    public static BedrockItemRegistry loadBundled() {
        try (InputStream input = BedrockItemRegistry.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing bundled Bedrock 1.26.60 item registry: " + RESOURCE);
            }
            return load(input);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not load Bedrock 1.26.60 item registry", failure);
        }
    }

    static BedrockItemRegistry load(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        Map<String, ItemDefinition> definitions = new LinkedHashMap<>();
        boolean[] runtimeIds = new boolean[Short.MAX_VALUE - Short.MIN_VALUE + 1];
        try (JsonParser parser = JSON.createParser(input)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Item registry root must be an object");
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) {
                    throw new IOException("Expected item identifier in registry");
                }
                String identifier = parser.currentName();
                if (parser.nextToken() != JsonToken.START_OBJECT) {
                    throw new IOException("Expected object for item " + identifier);
                }
                Integer runtimeId = null;
                Boolean componentBased = null;
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.FIELD_NAME) {
                        throw new IOException("Expected item property for " + identifier);
                    }
                    String property = parser.currentName();
                    JsonToken value = parser.nextToken();
                    if ("runtime_id".equals(property) && value == JsonToken.VALUE_NUMBER_INT) {
                        runtimeId = parser.getIntValue();
                    } else if ("component_based".equals(property)
                            && (value == JsonToken.VALUE_TRUE || value == JsonToken.VALUE_FALSE)) {
                        componentBased = parser.getBooleanValue();
                    } else {
                        parser.skipChildren();
                    }
                }
                if (runtimeId == null || componentBased == null
                        || runtimeId < Short.MIN_VALUE || runtimeId > Short.MAX_VALUE) {
                    throw new IOException("Invalid runtime ID or component flag for " + identifier);
                }
                int index = runtimeId - Short.MIN_VALUE;
                if (runtimeIds[index]) {
                    throw new IOException("Duplicate Bedrock item runtime ID " + runtimeId);
                }
                runtimeIds[index] = true;
                ItemVersion version = componentBased ? ItemVersion.DATA_DRIVEN : ItemVersion.LEGACY;
                ItemDefinition definition = new SimpleItemDefinition(
                        identifier, runtimeId, version, componentBased, NbtMap.EMPTY);
                if (definitions.putIfAbsent(identifier, definition) != null) {
                    throw new IOException("Duplicate Bedrock item identifier " + identifier);
                }
            }
        }
        if (definitions.size() < 1000) {
            throw new IOException("Bedrock item registry is unexpectedly incomplete: " + definitions.size());
        }
        return new BedrockItemRegistry(definitions);
    }

    public ItemDefinition definition(String identifier) {
        Objects.requireNonNull(identifier, "identifier");
        ItemDefinition definition = definitions.get(identifier);
        if (definition == null) {
            throw new IllegalArgumentException("No Bedrock 1.26.60 item definition for " + identifier);
        }
        return definition;
    }

    public ItemData itemData(String identifier, int count) {
        return ItemData.builder().definition(definition(identifier)).count(count).build();
    }

    public ItemRegistryPacket createPacket() {
        ItemRegistryPacket packet = new ItemRegistryPacket();
        packet.getItems().addAll(orderedDefinitions);
        return packet;
    }

    public int size() {
        return definitions.size();
    }
}
