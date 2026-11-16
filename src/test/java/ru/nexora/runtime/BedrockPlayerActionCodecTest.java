package ru.nexora.runtime;

import io.netty.buffer.Unpooled;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.codec.v2225.Bedrock_v2225;
import org.cloudburstmc.protocol.bedrock.data.PacketRecipient;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BedrockPlayerActionCodecTest {
    @Test
    void roundTripsStartAndStopBreakActionsUsingProtocol2225Codec() throws Exception {
        for (PlayerActionType action : new PlayerActionType[]{
                PlayerActionType.START_BREAK, PlayerActionType.STOP_BREAK,
                PlayerActionType.ABORT_BREAK}) {
            PlayerActionPacket packet = new PlayerActionPacket();
            packet.setRuntimeEntityId(42L);
            packet.setAction(action);
            packet.setBlockPosition(Vector3i.from(12, 64, -7));
            packet.setResultPosition(Vector3i.from(12, 64, -7));
            packet.setFace(1);

            var encoded = Unpooled.buffer();
            try {
                Bedrock_v2225.CODEC.tryEncode(Bedrock_v2225.CODEC.createHelper(), encoded, packet);
                int packetId = Bedrock_v2225.CODEC.getPacketDefinition(PlayerActionPacket.class).id();
                PlayerActionPacket decoded = (PlayerActionPacket) Bedrock_v2225.CODEC.tryDecode(
                        Bedrock_v2225.CODEC.createHelper(), encoded, packetId, PacketRecipient.SERVER);

                assertEquals(action, decoded.getAction());
                assertEquals(42L, decoded.getRuntimeEntityId());
                assertEquals(12, decoded.getBlockPosition().getX());
                assertEquals(64, decoded.getBlockPosition().getY());
                assertEquals(-7, decoded.getBlockPosition().getZ());
                assertEquals(1, decoded.getFace());
                assertEquals(0, encoded.readableBytes());
            } finally {
                encoded.release();
            }
        }
    }
}
