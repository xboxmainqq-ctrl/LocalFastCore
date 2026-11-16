package ru.nexora.network.bedrock;

import ru.nexora.inventory.ItemStack;
import ru.nexora.world.WorldItemEntity;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** Protocol-2225 AddItemActor and RemoveActor packet encoders. */
public final class BedrockAddItemActorCodec {
    public static final int ADD_ITEM_ACTOR_PACKET_ID = 0x0f;
    public static final int REMOVE_ACTOR_PACKET_ID = 0x0e;

    private BedrockAddItemActorCodec() {
    }

    public static BedrockPacket createAddItemActor(WorldItemEntity entity, BedrockItemRegistry registry) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(registry, "registry");
        Writer out = new Writer();
        out.varInt64(entity.uniqueId());
        out.varUInt64(entity.runtimeId());
        writeItemStack(out, entity.stack(), registry);
        out.float32((float) entity.x());
        out.float32((float) entity.y());
        out.float32((float) entity.z());
        out.float32((float) entity.velocityX());
        out.float32((float) entity.velocityY());
        out.float32((float) entity.velocityZ());
        out.varUInt32(0); // SynchedActorDataList is empty for a dropped item
        out.byteValue(0); // IsFromFishing
        return new BedrockPacket(ADD_ITEM_ACTOR_PACKET_ID, 0, 0, out.bytes());
    }

    public static BedrockPacket createRemoveActor(long uniqueId) {
        if (uniqueId == 0) throw new IllegalArgumentException("Actor unique ID cannot be zero");
        Writer out = new Writer();
        out.varInt64(uniqueId);
        return new BedrockPacket(REMOVE_ACTOR_PACKET_ID, 0, 0, out.bytes());
    }

    private static void writeItemStack(Writer out, ItemStack stack, BedrockItemRegistry registry) {
        BedrockItemRegistry.Entry entry = registry.findByIdentifier(stack.itemId());
        if (entry == null) throw new IllegalArgumentException("No Bedrock item definition for " + stack.itemId());
        if (stack.count() > entry.maxStackSize()) throw new IllegalArgumentException("Dropped item stack exceeds item maximum");
        BedrockInventoryContentCodec.ItemDefinition definition = entry.networkDefinition();
        out.int16(definition.runtimeItemId());
        out.uint16(stack.count());
        out.varUInt32(definition.auxValue());
        out.byteValue(0); // ItemStackNetManager disabled
        out.varUInt32(definition.blockRuntimeId());
        out.byteArray(definition.userData());
    }

    private static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream(96);
        void byteValue(int value) { out.write(value & 0xff); }
        void int16(int value) { byteValue(value); byteValue(value >>> 8); }
        void uint16(int value) {
            if (value < 0 || value > 0xffff) throw new IllegalArgumentException("uint16 out of range");
            byteValue(value); byteValue(value >>> 8);
        }
        void float32(float value) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Actor vector must be finite");
            out.writeBytes(ByteBuffer.allocate(Float.BYTES).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array());
        }
        void varUInt32(long value) {
            if (value < 0 || value > 0xffff_ffffL) throw new IllegalArgumentException("varuint32 out of range");
            do {
                int next = (int) (value & 0x7f);
                value >>>= 7;
                if (value != 0) next |= 0x80;
                byteValue(next);
            } while (value != 0);
        }
        void varUInt64(long value) {
            if (value < 0) throw new IllegalArgumentException("varuint64 out of supported range");
            do {
                int next = (int) (value & 0x7f);
                value >>>= 7;
                if (value != 0) next |= 0x80;
                byteValue(next);
            } while (value != 0);
        }
        void varInt64(long value) {
            long encoded = (value << 1) ^ (value >> 63);
            do {
                int next = (int) (encoded & 0x7f);
                encoded >>>= 7;
                if (encoded != 0) next |= 0x80;
                byteValue(next);
            } while (encoded != 0);
        }
        void byteArray(byte[] value) { varUInt32(value.length); out.writeBytes(value); }
        byte[] bytes() { return out.toByteArray(); }
    }
}
