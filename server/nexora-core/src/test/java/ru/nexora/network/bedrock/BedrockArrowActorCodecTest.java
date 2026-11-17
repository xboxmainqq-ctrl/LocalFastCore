package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import ru.nexora.combat.ArrowProjectile;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class BedrockArrowActorCodecTest {
    @Test
    void spawnUsesAddActorPacketAndArrowIdentifier() {
        ArrowProjectile arrow = new ArrowProjectile(-1, 2_000_000, "mob:1",
                1, 65, 2, 1, 0.2, 0, 0);
        BedrockPacket packet = BedrockArrowActorCodec.createSpawn(arrow);

        assertEquals(0x0d, packet.packetId());
        byte[] payload = packet.payload();
        assertEquals(1, Byte.toUnsignedInt(payload[0])); // ZigZag ActorUniqueID(-1)
        int identifierOffset = 1 + 3; // runtime ID 2,000,000 is encoded in three bytes
        assertEquals("minecraft:arrow",
                new String(payload, identifierOffset + 1, 15, StandardCharsets.UTF_8));
    }

    @Test
    void movementUsesMoveActorAbsolutePacket() {
        ArrowProjectile arrow = new ArrowProjectile(-1, 2_000_000, "mob:1",
                1, 65, 2, 1, 0.2, 0, 0);
        assertEquals(0x12, BedrockArrowActorCodec.createMove(arrow, 1).packetId());
    }
}
