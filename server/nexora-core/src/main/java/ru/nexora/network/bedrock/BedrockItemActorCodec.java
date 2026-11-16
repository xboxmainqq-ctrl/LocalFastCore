package ru.nexora.network.bedrock;

import ru.nexora.inventory.ItemStack;
import ru.nexora.world.WorldItemEntity;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Protocol-2225 item actor spawn, removal and pickup animation packets. */
public final class BedrockItemActorCodec {
    public static final int REMOVE_ACTOR_PACKET_ID = 0x0e;
    public static final int ADD_ITEM_ACTOR_PACKET_ID = 0x0f;
    public static final int TAKE_ITEM_ACTOR_PACKET_ID = 0x11;
    public static final int MOVE_ACTOR_ABSOLUTE_PACKET_ID = 0x12;

    private BedrockItemActorCodec() {
    }

    public static BedrockPacket createAdd(WorldItemEntity entity, BedrockItemRegistry registry) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(registry, "registry");
        BedrockItemRegistry.ItemDefinition definition = registry.findByIdentifier(entity.stack().itemId())
                .networkDefinition();
        ItemStack stack = entity.stack();
        if (stack.count() > definition.maxStackSize()) {
            throw new IllegalArgumentException("Dropped stack exceeds registered item maximum");
        }
        Writer out = new Writer();
        out.varInt64(entity.uniqueId());
        out.varUInt64(entity.runtimeId());
        out.int16(definition.runtimeItemId());
        out.uint16(stack.count());
        out.varUInt32(definition.auxValue());
        out.byteValue(0); // no StackNetId
        out.varUInt32(definition.blockRuntimeId());
        out.varUInt32(definition.userData().length);
        out.bytes(definition.userData());
        out.float32(entity.x()); out.float32(entity.y()); out.float32(entity.z());
        out.float32(entity.velocityX()); out.float32(entity.velocityY()); out.float32(entity.velocityZ());
        out.varUInt32(0); // empty entity metadata
        out.byteValue(0); // not spawned from fishing
        return new BedrockPacket(ADD_ITEM_ACTOR_PACKET_ID, 0, 0, out.toByteArray());
    }

    public static BedrockPacket createRemove(long uniqueId) {
        if (uniqueId == 0) throw new IllegalArgumentException("Invalid item actor unique ID");
        Writer out = new Writer();
        out.varInt64(uniqueId);
        return new BedrockPacket(REMOVE_ACTOR_PACKET_ID, 0, 0, out.toByteArray());
    }

    public static BedrockPacket createTake(long playerRuntimeId, long itemRuntimeId) {
        if (playerRuntimeId <= 0 || itemRuntimeId <= 0) {
            throw new IllegalArgumentException("Invalid take-item actor runtime ID");
        }
        Writer out = new Writer();
        out.varUInt64(playerRuntimeId);
        out.varUInt64(itemRuntimeId);
        return new BedrockPacket(TAKE_ITEM_ACTOR_PACKET_ID, 0, 0, out.toByteArray());
    }

    public static BedrockPacket createMove(WorldItemEntity entity, boolean onGround, long tick) {
        Objects.requireNonNull(entity, "entity");
        if (tick < 0) throw new IllegalArgumentException("Invalid item actor tick");
        Writer out = new Writer();
        out.varUInt64(entity.runtimeId());
        out.byteValue(onGround ? 1 : 0);
        out.float32(entity.x()); out.float32(entity.y()); out.float32(entity.z());
        out.byteValue(0); out.byteValue(0); out.byteValue(0); // pitch, yaw, head yaw
        out.varUInt64(tick);
        return new BedrockPacket(MOVE_ACTOR_ABSOLUTE_PACKET_ID, 0, 0, out.toByteArray());
    }

    private static final class Writer {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream(96);
        void byteValue(int value) { output.write(value & 0xff); }
        void int16(int value) {
            output.writeBytes(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
                    .putShort((short) value).array());
        }
        void uint16(int value) {
            if (value < 0 || value > 0xffff) throw new IllegalArgumentException("uint16 out of range");
            output.writeBytes(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
                    .putShort((short) value).array());
        }
        void float32(double value) {
            if (!Double.isFinite(value) || Math.abs(value) > Float.MAX_VALUE) {
                throw new IllegalArgumentException("Non-finite item actor position");
            }
            output.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                    .putFloat((float) value).array());
        }
        void bytes(byte[] value) { output.writeBytes(value); }
        void varUInt32(long value) {
            if (value < 0 || value > 0xffff_ffffL) throw new IllegalArgumentException("VarUInt32 out of range");
            do {
                int next = (int) (value & 0x7f);
                value >>>= 7;
                if (value != 0) next |= 0x80;
                output.write(next);
            } while (value != 0);
        }
        void varUInt64(long value) {
            if (value < 0) throw new IllegalArgumentException("VarUInt64 out of range");
            do {
                int next = (int) (value & 0x7f);
                value >>>= 7;
                if (value != 0) next |= 0x80;
                output.write(next);
            } while (value != 0);
        }
        void varInt64(long value) {
            long encoded = (value << 1) ^ (value >> 63);
            varUInt64(encoded);
        }
        byte[] toByteArray() { return output.toByteArray(); }
    }
}
