package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BedrockBatchCodecTest {
    @Test
    void roundTripsUncompressedBatchWithSubClientHeaderFields() {
        List<BedrockPacket> packets = List.of(
                new BedrockPacket(1, 0, 0, new byte[] {10, 20}),
                new BedrockPacket(0x123, 2, 1, new byte[] {30, 40, 50})
        );

        byte[] encoded = BedrockBatchCodec.encode(packets, false, 256);
        List<BedrockPacket> decoded = BedrockBatchCodec.decode(encoded, false);

        assertEquals(2, decoded.size());
        assertEquals(1, decoded.get(0).packetId());
        assertArrayEquals(new byte[] {10, 20}, decoded.get(0).payload());
        assertEquals(0x123, decoded.get(1).packetId());
        assertEquals(2, decoded.get(1).senderSubClientId());
        assertEquals(1, decoded.get(1).targetSubClientId());
        assertArrayEquals(new byte[] {30, 40, 50}, decoded.get(1).payload());
    }

    @Test
    void decodesFirstRequestNetworkSettingsWithoutCompressionPrefix() {
        byte[] protocol = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2225).array();
        BedrockPacket request = new BedrockPacket(
                BedrockBatchCodec.REQUEST_NETWORK_SETTINGS_PACKET_ID, 0, 0, protocol);

        byte[] encoded = BedrockBatchCodec.encode(List.of(request), false, 256);
        List<BedrockPacket> decoded = BedrockBatchCodec.decode(encoded, false);

        assertEquals(2225, BedrockBatchCodec.readRequestedProtocol(decoded.getFirst()));
    }

    @Test
    void roundTripsRawDeflateCompressionAfterNegotiation() {
        byte[] payload = new byte[32_000];
        java.util.Arrays.fill(payload, (byte) 7);
        List<BedrockPacket> packets = List.of(new BedrockPacket(1, 0, 0, payload));

        byte[] encoded = BedrockBatchCodec.encode(packets, true, 1);
        assertEquals(0, Byte.toUnsignedInt(encoded[0]));

        List<BedrockPacket> decoded = BedrockBatchCodec.decode(encoded, true);
        assertArrayEquals(payload, decoded.getFirst().payload());
    }

    @Test
    void usesExplicitUncompressedMarkerForSmallNegotiatedBatch() {
        List<BedrockPacket> packets = List.of(new BedrockPacket(1, 0, 0, new byte[] {1}));
        byte[] encoded = BedrockBatchCodec.encode(packets, true, 256);

        assertEquals(0xff, Byte.toUnsignedInt(encoded[0]));
        assertEquals(1, BedrockBatchCodec.decode(encoded, true).getFirst().payload()[0]);
    }

    @Test
    void networkSettingsUsesLittleEndianFieldsAndZlibSelection() {
        BedrockPacket settings = BedrockBatchCodec.createNetworkSettings(256, BedrockBatchCodec.COMPRESSION_ZLIB);
        assertEquals(BedrockBatchCodec.NETWORK_SETTINGS_PACKET_ID, settings.packetId());
        byte[] body = settings.payload();

        assertEquals(10, body.length);
        assertArrayEquals(new byte[] {0, 1, 0, 0, 0, 0, 0, 0, 0, 0}, body);
    }

    @Test
    void buildsEmptyResourcePackPackets() {
        BedrockPacket info = BedrockBatchCodec.createResourcePacksInfo();
        BedrockPacket stack = BedrockBatchCodec.createResourcePackStack("1.26.62");

        assertEquals(BedrockBatchCodec.RESOURCE_PACKS_INFO_PACKET_ID, info.packetId());
        assertEquals(22, info.payload().length);
        assertEquals(BedrockBatchCodec.RESOURCE_PACK_STACK_PACKET_ID, stack.packetId());
        assertEquals(16, stack.payload().length);
    }

    @Test
    void parsesProtocolStringResourcePackResponse() {
        byte[] body = new byte[] {
                3, 19,
                'D', 'o', 'w', 'n', 'l', 'o', 'a', 'd', 'i', 'n', 'g',
                'F', 'i', 'n', 'i', 's', 'h', 'e', 'd'
        };
        BedrockPacket packet = new BedrockPacket(
                BedrockBatchCodec.RESOURCE_PACK_CLIENT_RESPONSE_PACKET_ID, 0, 0, body);

        BedrockBatchCodec.ResourcePackClientResponseData response =
                BedrockBatchCodec.parseResourcePackClientResponse(packet);

        assertEquals(3, response.response());
        assertEquals("DownloadingFinished", response.name());
    }


    @Test
    void parsesLowerCamelCaseResourcePackResponseFromBedrockClient() {
        byte[] body = new byte[] {
                2, 19,
                'd', 'o', 'w', 'n', 'l', 'o', 'a', 'd', 'i', 'n', 'g',
                'F', 'i', 'n', 'i', 's', 'h', 'e', 'd'
        };
        BedrockPacket packet = new BedrockPacket(
                BedrockBatchCodec.RESOURCE_PACK_CLIENT_RESPONSE_PACKET_ID, 0, 0, body);

        BedrockBatchCodec.ResourcePackClientResponseData response =
                BedrockBatchCodec.parseResourcePackClientResponse(packet);

        assertEquals(3, response.response());
        assertEquals("downloadingFinished", response.name());
    }

    @Test
    void rejectsRakNetBatchMarkerOnNetherNet() {
        assertThrows(IllegalArgumentException.class,
                () -> BedrockBatchCodec.decode(new byte[] {(byte) 0xfe}, false));
    }

    @Test
    void rejectsHeaderVarIntThatCrossesPacketBoundary() {
        byte[] malformed = new byte[] {1, (byte) 0x80, 1, 0x01};
        assertThrows(IllegalArgumentException.class,
                () -> BedrockBatchCodec.decode(malformed, false));
    }

    @Test
    void rejectsSnappyUntilNegotiatedImplementationExists() {
        byte[] framed = new byte[] {1, 1, 1};
        assertThrows(UnsupportedOperationException.class,
                () -> BedrockBatchCodec.decode(framed, true));
    }

    @Test
    void enforcesDecompressedBatchLimit() {
        byte[] payload = new byte[10_000];
        java.util.Arrays.fill(payload, (byte) 1);
        byte[] encoded = BedrockBatchCodec.encode(
                List.of(new BedrockPacket(1, 0, 0, payload)), true, 1);
        assertThrows(IllegalArgumentException.class,
                () -> BedrockBatchCodec.decode(encoded, true, 100));
    }

    @Test
    void rejectsTrailingBytesAfterCompressedBatchWithoutAcceptingPartialPayload() {
        byte[] payload = new byte[4_096];
        java.util.Arrays.fill(payload, (byte) 3);
        byte[] encoded = BedrockBatchCodec.encode(
                List.of(new BedrockPacket(1, 0, 0, payload)), true, 1);
        byte[] withTrailingByte = java.util.Arrays.copyOf(encoded, encoded.length + 1);
        withTrailingByte[withTrailingByte.length - 1] = 42;

        assertThrows(IllegalArgumentException.class,
                () -> BedrockBatchCodec.decode(withTrailingByte, true));
    }

    @Test
    void rejectsCompressedInputWhenEncodedFrameExceedsConfiguredExpansionAllowance() {
        byte[] oversized = new byte[BedrockBatchCodec.DEFAULT_MAX_BATCH_BYTES + 65_538];
        oversized[0] = 0;

        assertThrows(IllegalArgumentException.class,
                () -> BedrockBatchCodec.decode(oversized, true));
    }


    @Test
    void preflightsExactEncodedBatchSizeBeforeAllocatingOutput() {
        List<BedrockPacket> packets = List.of(new BedrockPacket(1, 0, 0, new byte[] {7}));
        byte[] encoded = BedrockBatchCodec.encode(packets, false, 256, 3);

        assertEquals(3, encoded.length);
        assertThrows(IllegalArgumentException.class,
                () -> BedrockBatchCodec.encode(packets, false, 256, 2));
        assertArrayEquals(new byte[] {7}, BedrockBatchCodec.decode(encoded, false).getFirst().payload());
    }

}
