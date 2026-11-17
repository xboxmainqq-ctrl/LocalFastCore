package ru.nexora.network.bedrock;

import ru.nexora.inventory.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Strict decoder for protocol-2225 CraftingEvent packets. */
public final class BedrockCraftingEventCodec {
    public static final int CRAFTING_EVENT_PACKET_ID = 0x35;
    public static final int TYPE_SHAPELESS = 0;

    private static final int MAX_GRID_SLOTS = 9;
    private static final int MAX_OUTPUTS = 4;
    private static final int MAX_USER_DATA_BYTES = 65_536;

    private BedrockCraftingEventCodec() {
    }

    public static DecodedEvent decode(BedrockPacket packet, BedrockItemRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        if (packet == null || packet.packetId() != CRAFTING_EVENT_PACKET_ID) {
            throw new IllegalArgumentException("Expected CraftingEvent packet 0x35");
        }
        Reader in = new Reader(packet.payload());
        int windowId = in.byteValue();
        int type = in.varInt32();
        UUID recipeId = in.uuidLittleEndian();
        int inputCount = in.varUInt32();
        if (inputCount < 1 || inputCount > MAX_GRID_SLOTS) {
            throw new IllegalArgumentException("Crafting input grid must contain 1..9 slots");
        }
        List<Optional<ItemStack>> input = new ArrayList<>(inputCount);
        for (int i = 0; i < inputCount; i++) input.add(in.itemStack(registry));

        int outputCount = in.varUInt32();
        if (outputCount < 1 || outputCount > MAX_OUTPUTS) {
            throw new IllegalArgumentException("Crafting output must contain 1..4 stacks");
        }
        List<Optional<ItemStack>> output = new ArrayList<>(outputCount);
        for (int i = 0; i < outputCount; i++) output.add(in.itemStack(registry));
        if (in.remaining() != 0) throw new IllegalArgumentException("Trailing CraftingEvent data");
        return new DecodedEvent(windowId, type, recipeId, List.copyOf(input), List.copyOf(output));
    }

    public record DecodedEvent(
            int windowId, int type, UUID recipeId,
            List<Optional<ItemStack>> input, List<Optional<ItemStack>> output
    ) {
        public DecodedEvent {
            Objects.requireNonNull(recipeId, "recipeId");
            input = List.copyOf(input);
            output = List.copyOf(output);
        }
    }

    private static final class Reader {
        private final ByteArrayInputStream input;

        private Reader(byte[] payload) {
            input = new ByteArrayInputStream(Objects.requireNonNull(payload, "payload"));
        }

        int byteValue() {
            int value = input.read();
            if (value < 0) throw new IllegalArgumentException("Truncated CraftingEvent packet");
            return value;
        }

        int int16() {
            return (short) (byteValue() | (byteValue() << 8));
        }

        int uint16() {
            return byteValue() | (byteValue() << 8);
        }

        int varUInt32() {
            long value = varUInt32Long();
            if (value > Integer.MAX_VALUE) throw new IllegalArgumentException("Crafting VarUInt32 too large");
            return (int) value;
        }

        long varUInt32Long() {
            long value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                int next = byteValue();
                if (shift == 28 && (next & 0xf0) != 0) {
                    throw new IllegalArgumentException("Crafting VarUInt32 overflow");
                }
                value |= (long) (next & 0x7f) << shift;
                if ((next & 0x80) == 0) return value;
            }
            throw new IllegalArgumentException("Malformed crafting VarUInt32");
        }

        int varInt32() {
            long encoded = varUInt32Long();
            return (int) ((encoded >>> 1) ^ -(encoded & 1));
        }

        UUID uuidLittleEndian() {
            long most = longLittleEndian();
            long least = longLittleEndian();
            return new UUID(most, least);
        }

        long longLittleEndian() {
            long value = 0;
            for (int i = 0; i < 8; i++) value |= (long) byteValue() << (i * 8);
            return value;
        }

        Optional<ItemStack> itemStack(BedrockItemRegistry registry) {
            int runtimeItemId = int16();
            int count = uint16();
            long auxValue = varUInt32Long();
            int hasNetId = byteValue();
            if (hasNetId > 1) throw new IllegalArgumentException("Invalid crafting stack net ID flag");
            if (hasNetId != 0) throw new IllegalArgumentException("Crafting stack net IDs are not enabled");
            long blockRuntimeId = varUInt32Long();
            byte[] userData = byteArray(MAX_USER_DATA_BYTES);

            if (runtimeItemId == 0) {
                if (count != 0 || auxValue != 0 || blockRuntimeId != 0 || userData.length != 0) {
                    throw new IllegalArgumentException("Malformed empty crafting item stack");
                }
                return Optional.empty();
            }
            if (count < 1) throw new IllegalArgumentException("Crafting item count must be positive");
            BedrockItemRegistry.Entry entry = registry.findByRuntimeItemId(runtimeItemId);
            if (entry == null) throw new IllegalArgumentException("Unknown crafting item runtime ID " + runtimeItemId);
            BedrockInventoryContentCodec.ItemDefinition definition = entry.networkDefinition();
            if (auxValue != definition.auxValue() || blockRuntimeId != definition.blockRuntimeId()
                    || !Arrays.equals(userData, definition.userData())) {
                throw new IllegalArgumentException("Crafting item descriptor does not match the item registry");
            }
            if (count > entry.maxStackSize()) throw new IllegalArgumentException("Crafting stack exceeds max size");
            return Optional.of(new ItemStack(entry.namespacedId(), count, entry.maxStackSize()));
        }

        byte[] byteArray(int maximumLength) {
            int length = varUInt32();
            if (length > maximumLength || length > input.available()) {
                throw new IllegalArgumentException("Invalid crafting item user-data length");
            }
            byte[] result = input.readNBytes(length);
            if (result.length != length) throw new IllegalArgumentException("Truncated crafting item user data");
            return result;
        }

        int remaining() {
            return input.available();
        }
    }
}
