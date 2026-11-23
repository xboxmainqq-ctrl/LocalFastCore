package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BedrockStartGameCodecTest {
    @Test
    void encodesProtocol2225StartGameHeaderAndSpawnPosition() {
        BedrockPacket packet = BedrockStartGameCodec.create(
                7, "NEXORA World", "1.26.62", "1.26.62", 0.5f, 65.0f, 0.5f);

        assertEquals(0x0b, packet.packetId());
        byte[] payload = packet.payload();
        assertTrue(payload.length > 180);

        assertEquals(14, Byte.toUnsignedInt(payload[0])); // signed VarInt64(7)
        assertEquals(7, Byte.toUnsignedInt(payload[1]));  // VarUInt64(7)
        assertEquals(0, Byte.toUnsignedInt(payload[2]));  // survival

        ByteBuffer position = ByteBuffer.wrap(payload, 3, 12).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0.5f, position.getFloat());
        assertEquals(65.0f, position.getFloat());
        assertEquals(0.5f, position.getFloat());

        assertTrue(contains(payload, "NEXORA World".getBytes(StandardCharsets.UTF_8)));
        assertTrue(contains(payload, "1.26.62".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void disablesItemStackNetManagerUntilServerSupportsItsRequests() {
        byte[] payload = BedrockStartGameCodec.create(
                7, "NEXORA World", "1.26.62", "1.26.62", 0.5f, 65.0f, 0.5f).payload();
        byte[] serverVersion = "1.26.62".getBytes(StandardCharsets.UTF_8);
        int versionOffset = lastIndexOf(payload, serverVersion);

        assertTrue(versionOffset >= 0);
        assertTrue(versionOffset >= 2);
        assertEquals(0, Byte.toUnsignedInt(payload[versionOffset - 2]));
    }

    @Test
    void encodesEmptyPlayerPropertyDataAsNetworkNbtCompound() {
        byte[] payload = BedrockStartGameCodec.create(
                7, "NEXORA World", "1.26.62", "1.26.62", 0.5f, 65.0f, 0.5f).payload();
        byte[] serverVersion = "1.26.62".getBytes(StandardCharsets.UTF_8);
        int versionOffset = lastIndexOf(payload, serverVersion);
        int propertyOffset = versionOffset + serverVersion.length;

        assertTrue(versionOffset >= 0);
        assertTrue(propertyOffset + 2 < payload.length);
        // Network NBT uses varint string lengths: root compound, empty name, end tag.
        assertArrayEquals(new byte[] {0x0a, 0, 0},
                java.util.Arrays.copyOfRange(payload, propertyOffset, propertyOffset + 3));
        // The next field is the fixed-width server block type registry checksum.
        assertEquals(0, Byte.toUnsignedInt(payload[propertyOffset + 3]));
    }

    @Test
    void encodesProtocol2225NetworkPermissionsAndOptionalServerConfigTail() {
        byte[] payload = BedrockStartGameCodec.create(
                7, "NEXORA World", "1.26.60", "1.26.60", 0.5f, 65.0f, 0.5f).payload();

        // checksum (8), template UUID (16), client generation, block hash flag,
        // NetworkPermissions, absent optional server_config, and four empty telemetry strings.
        assertTrue(payload.length >= 32);
        int tail = payload.length - 32;
        for (int i = 0; i < 32; i++) {
            int expected = i == 25 ? 1 : 0; // BlockNetworkIdsAreHashes is enabled.
            assertEquals(expected, Byte.toUnsignedInt(payload[tail + i]),
                    "Unexpected StartGame tail byte at offset " + i);
        }
    }

    @Test
    void buildsInitialWorldMetadataInProtocolOrder() {
        List<BedrockPacket> packets = BedrockStartGameCodec.createInitialWorldPackets(
                9, "NEXORA World", "1.26.62", "1.26.62", 0.5f, 65.0f, 0.5f);

        assertEquals(List.of(0x0a, 0x139, 0x151, 0x0b, 0x2b, 0x3c, 0x3b, 0x3e, 0x79),
                packets.stream().map(BedrockPacket::packetId).toList());
        // Protocol 2225 requires a network-little-endian NBT compound containing
        // the four empty compound lists used by the reference server.
        assertArrayEquals(new byte[] {
                0x0a, 0x00,
                0x09, 0x0a, 'p', 'r', 'o', 'c', 'e', 's', 's', 'o', 'r', 's', 0x0a, 0x00,
                0x09, 0x0e, 't', 'e', 'm', 'p', 'l', 'a', 't', 'e', '_', 'p', 'o', 'o', 'l', 's', 0x0a, 0x00,
                0x09, 0x07, 'j', 'i', 'g', 's', 'a', 'w', 's', 0x0a, 0x00,
                0x09, 0x0e, 's', 't', 'r', 'u', 'c', 't', 'u', 'r', 'e', '_', 's', 'e', 't', 's', 0x0a, 0x00,
                0x00
        }, packets.get(1).payload());
        assertArrayEquals(new byte[4], packets.get(2).payload());
        assertArrayEquals(new byte[] {0, (byte) 0x82, 1, 0, 0x30, 0, 0, 0, 0},
                packets.get(8).payload());
    }

    @Test
    void scalesChunkPublisherRadiusToTheNegotiatedChunkDiameter() {
        BedrockPacket packet = BedrockStartGameCodec.createChunkPublisherUpdate(0, 65, 0, 2);

        assertEquals(0x79, packet.packetId());
        assertArrayEquals(new byte[] {0, (byte) 0x82, 1, 0, 0x50, 0, 0, 0, 0}, packet.payload());
        assertThrows(IllegalArgumentException.class,
                () -> BedrockStartGameCodec.createChunkPublisherUpdate(0, 65, 0, 0));
    }

    @Test
    void rejectsInvalidEntityIdsAndBlankWorldMetadata() {
        assertThrows(IllegalArgumentException.class,
                () -> BedrockStartGameCodec.create(0, "NEXORA", "1.26.62", "1.26.62", 0, 65, 0));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockStartGameCodec.create(1, " ", "1.26.62", "1.26.62", 0, 65, 0));
    }

    private static int lastIndexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = haystack.length - needle.length; i >= 0; i--) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
