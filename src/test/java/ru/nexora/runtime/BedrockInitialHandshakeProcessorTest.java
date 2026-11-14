package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BedrockInitialHandshakeProcessorTest {
    private static final BedrockNetworkSettings SETTINGS = new BedrockNetworkSettings(
            1, BedrockNetworkSettings.CompressionAlgorithm.ZLIB, false, 0, 0.0f
    );

    @Test
    void buildsNetworkSettingsResponseThenAcceptsCompressedLoginFrame() {
        BedrockInitialHandshakeProcessor processor =
                new BedrockInitialHandshakeProcessor(SETTINGS);

        ByteBuffer requestFrame = encodePacket(
                BedrockRequestNetworkSettingsPacketCodec.PACKET_ID,
                BedrockRequestNetworkSettingsPacketCodec.encodePayload(
                        new BedrockRequestNetworkSettingsPacket(2225)),
                false
        );
        BedrockHandshakeFrameResult requestResult = processor.receiveFrame(requestFrame);

        assertEquals(BedrockHandshakeFrameStatus.NETWORK_SETTINGS_RESPONSE_READY,
                requestResult.status());
        assertEquals(BedrockHandshakeState.NETWORK_SETTINGS_PENDING, requestResult.state());
        ByteBuffer responseFrame = requestResult.outboundFrame().orElseThrow();
        List<BedrockGamePacket> responsePackets = BedrockPacketBatchCodec.decodeUncompressed(
                BedrockBatchCompressionCodec.decodeBeforeNetworkSettings(responseFrame, 1024)
        );
        assertEquals(1, responsePackets.size());
        assertEquals(BedrockNetworkSettingsPacketCodec.PACKET_ID,
                responsePackets.get(0).header().packetId());
        assertEquals(SETTINGS, BedrockNetworkSettingsPacketCodec.decodePayload(
                responsePackets.get(0).payloadBuffer()));

        assertEquals(BedrockHandshakeResult.ACCEPTED, processor.markNetworkSettingsSent());
        assertEquals(BedrockHandshakeState.AWAITING_LOGIN, processor.state());

        ByteBuffer loginFrame = encodePacket(
                BedrockLoginPacketCodec.PACKET_ID,
                BedrockLoginPacketCodec.encodePayload(new BedrockLoginPacket(2225, "opaque-login")),
                true
        );
        BedrockHandshakeFrameResult loginResult = processor.receiveFrame(loginFrame);
        assertEquals(BedrockHandshakeFrameStatus.AUTHENTICATION_REQUIRED, loginResult.status());
        assertEquals(BedrockHandshakeState.AUTHENTICATION_PENDING, loginResult.state());
        assertTrue(loginResult.outboundFrame().isEmpty());

        assertEquals(BedrockHandshakeResult.ACCEPTED, processor.markAuthenticated());
        assertEquals(BedrockHandshakeState.PLAY, processor.state());
    }

    @Test
    void rejectsUnsupportedInitialProtocolWithoutCreatingResponse() {
        BedrockInitialHandshakeProcessor processor =
                new BedrockInitialHandshakeProcessor(SETTINGS);

        BedrockHandshakeFrameResult result = processor.receiveFrame(encodePacket(
                BedrockRequestNetworkSettingsPacketCodec.PACKET_ID,
                BedrockRequestNetworkSettingsPacketCodec.encodePayload(
                        new BedrockRequestNetworkSettingsPacket(2224)),
                false
        ));

        assertEquals(BedrockHandshakeFrameStatus.REJECTED_PACKET, result.status());
        assertEquals(BedrockHandshakeResult.UNSUPPORTED_PROTOCOL, result.transition());
        assertEquals(BedrockHandshakeState.CLOSED, result.state());
        assertTrue(result.outboundFrame().isEmpty());
    }

    @Test
    void closesOnUnexpectedPacketOrMultiplePacketsInInitialBatch() {
        BedrockInitialHandshakeProcessor unexpected =
                new BedrockInitialHandshakeProcessor(SETTINGS);
        assertEquals(BedrockHandshakeFrameStatus.REJECTED_PACKET,
                unexpected.receiveFrame(encodePacket(2, ByteBuffer.allocate(0), false)).status());
        assertEquals(BedrockHandshakeState.CLOSED, unexpected.state());

        BedrockInitialHandshakeProcessor multiple =
                new BedrockInitialHandshakeProcessor(SETTINGS);
        ByteBuffer first = BedrockRequestNetworkSettingsPacketCodec.encodePayload(
                new BedrockRequestNetworkSettingsPacket(2225));
        ByteBuffer combined = BedrockPacketBatchCodec.encodeUncompressed(List.of(
                packet(BedrockRequestNetworkSettingsPacketCodec.PACKET_ID, first),
                packet(BedrockRequestNetworkSettingsPacketCodec.PACKET_ID, first)
        ));
        assertEquals(BedrockHandshakeFrameStatus.REJECTED_PACKET,
                multiple.receiveFrame(combined).status());
        assertEquals(BedrockHandshakeState.CLOSED, multiple.state());
    }

    @Test
    void rejectsSnappyNegotiationAtConstruction() {
        BedrockNetworkSettings snappy = new BedrockNetworkSettings(
                1, BedrockNetworkSettings.CompressionAlgorithm.SNAPPY, false, 0, 0.0f
        );
        assertThrows(IllegalArgumentException.class,
                () -> new BedrockInitialHandshakeProcessor(snappy));
    }

    @Test
    void closesOnLoginProtocolMismatch() {
        BedrockInitialHandshakeProcessor processor =
                new BedrockInitialHandshakeProcessor(SETTINGS);
        assertEquals(BedrockHandshakeFrameStatus.NETWORK_SETTINGS_RESPONSE_READY,
                processor.receiveFrame(encodePacket(
                        BedrockRequestNetworkSettingsPacketCodec.PACKET_ID,
                        BedrockRequestNetworkSettingsPacketCodec.encodePayload(
                                new BedrockRequestNetworkSettingsPacket(2225)),
                        false
                )).status());
        assertEquals(BedrockHandshakeResult.ACCEPTED, processor.markNetworkSettingsSent());

        BedrockHandshakeFrameResult result = processor.receiveFrame(encodePacket(
                BedrockLoginPacketCodec.PACKET_ID,
                BedrockLoginPacketCodec.encodePayload(new BedrockLoginPacket(2224, "opaque-login")),
                true
        ));
        assertEquals(BedrockHandshakeFrameStatus.REJECTED_PACKET, result.status());
        assertEquals(BedrockHandshakeResult.LOGIN_PROTOCOL_MISMATCH, result.transition());
        assertEquals(BedrockHandshakeState.CLOSED, result.state());
    }

    @Test
    void releasesDecodedLoginRequestAfterSuccessfulAuthentication() {
        BedrockInitialHandshakeProcessor processor =
                new BedrockInitialHandshakeProcessor(SETTINGS);
        processor.receiveFrame(encodePacket(
                BedrockRequestNetworkSettingsPacketCodec.PACKET_ID,
                BedrockRequestNetworkSettingsPacketCodec.encodePayload(
                        new BedrockRequestNetworkSettingsPacket(2225)),
                false));
        assertEquals(BedrockHandshakeResult.ACCEPTED, processor.markNetworkSettingsSent());
        processor.receiveFrame(encodePacket(
                BedrockLoginPacketCodec.PACKET_ID,
                BedrockLoginPacketCodec.encodePayload(new BedrockLoginPacket(2225, "sensitive-request")),
                true));

        assertTrue(processor.pendingLogin().isPresent());
        assertEquals(BedrockHandshakeResult.ACCEPTED, processor.markAuthenticated());
        assertTrue(processor.pendingLogin().isEmpty());
    }

    private static ByteBuffer encodePacket(int packetId, ByteBuffer payload, boolean compressed) {
        ByteBuffer batch = BedrockPacketBatchCodec.encodeUncompressed(
                List.of(packet(packetId, payload))
        );
        return compressed
                ? BedrockBatchCompressionCodec.encodeAfterNetworkSettings(batch, SETTINGS)
                : BedrockBatchCompressionCodec.encodeBeforeNetworkSettings(batch);
    }

    private static BedrockGamePacket packet(int packetId, ByteBuffer payload) {
        byte[] bytes = new byte[payload.remaining()];
        payload.get(bytes);
        return new BedrockGamePacket(new BedrockPacketHeader(packetId, 0, 0), bytes);
    }
}
