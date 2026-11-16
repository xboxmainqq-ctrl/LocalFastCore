package ru.nexora.runtime;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.codec.v2225.Bedrock_v2225;
import org.cloudburstmc.protocol.bedrock.packet.AddItemEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.RemoveEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.TakeItemEntityPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BedrockItemEntityPacketTest {

    @Test
    void encodesDroppedItemSpawnWithProtocol2225() throws Exception {
        BedrockItemRegistry registry = BedrockItemRegistry.loadBundled();
        AddItemEntityPacket packet = new AddItemEntityPacket();
        packet.setUniqueEntityId(42L);
        packet.setRuntimeEntityId(42L);
        packet.setItemInHand(registry.itemData("minecraft:diamond", 1));
        packet.setPosition(Vector3f.from(1.5f, 64.5f, -2.5f));
        packet.setMotion(Vector3f.from(0.0f, 0.08f, 0.0f));

        ByteBuf bytes = encode(packet);
        try {
            assertTrue(bytes.isReadable());
            assertNotNull(Bedrock_v2225.CODEC.getPacketDefinition(AddItemEntityPacket.class));
        } finally {
            bytes.release();
        }
    }

    @Test
    void encodesPickupAnimationAndEntityRemovalWithProtocol2225() throws Exception {
        TakeItemEntityPacket taken = new TakeItemEntityPacket();
        taken.setItemRuntimeEntityId(42L);
        taken.setRuntimeEntityId(7L);
        RemoveEntityPacket removed = new RemoveEntityPacket();
        removed.setUniqueEntityId(42L);

        ByteBuf takeBytes = encode(taken);
        ByteBuf removeBytes = encode(removed);
        try {
            assertTrue(takeBytes.isReadable());
            assertTrue(removeBytes.isReadable());
            assertNotNull(Bedrock_v2225.CODEC.getPacketDefinition(TakeItemEntityPacket.class));
            assertNotNull(Bedrock_v2225.CODEC.getPacketDefinition(RemoveEntityPacket.class));
        } finally {
            takeBytes.release();
            removeBytes.release();
        }
    }

    private static ByteBuf encode(org.cloudburstmc.protocol.bedrock.packet.BedrockPacket packet)
            throws Exception {
        ByteBuf payload = Unpooled.buffer();
        try {
            Bedrock_v2225.CODEC.tryEncode(
                    Bedrock_v2225.CODEC.createHelper(), payload, packet);
            return Unpooled.copiedBuffer(payload);
        } finally {
            payload.release();
        }
    }
}
