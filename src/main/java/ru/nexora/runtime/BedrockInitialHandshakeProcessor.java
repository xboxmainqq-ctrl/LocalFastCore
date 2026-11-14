package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;

/**
 * Frame-level coordinator for the initial Bedrock 1.26.60 handshake.
 *
 * <p>It accepts already-delivered transport frames and produces the initial
 * NetworkSettings response frame. It does not open sockets, implement
 * NetherNet/WebRTC, or authenticate the Login connection request.</p>
 */
public final class BedrockInitialHandshakeProcessor {
    private final BedrockSessionHandshake handshake;
    private final BedrockNetworkSettings networkSettings;

    public BedrockInitialHandshakeProcessor(BedrockNetworkSettings networkSettings) {
        this(new BedrockSessionHandshake(), networkSettings);
    }

    public BedrockInitialHandshakeProcessor(
            BedrockSessionHandshake handshake,
            BedrockNetworkSettings networkSettings
    ) {
        this.handshake = Objects.requireNonNull(handshake, "handshake");
        this.networkSettings = Objects.requireNonNull(networkSettings, "networkSettings");
        if (networkSettings.compressionAlgorithm()
                == BedrockNetworkSettings.CompressionAlgorithm.SNAPPY) {
            throw new IllegalArgumentException(
                    "NEXORA cannot negotiate Snappy until its codec is implemented"
            );
        }
    }

    public synchronized BedrockHandshakeFrameResult receiveFrame(ByteBuffer frame) {
        Objects.requireNonNull(frame, "frame");
        BedrockHandshakeState state = handshake.state();
        if (state == BedrockHandshakeState.CLOSED) {
            return result(BedrockHandshakeFrameStatus.SESSION_CLOSED,
                    BedrockHandshakeResult.SESSION_CLOSED, null);
        }
        if (state == BedrockHandshakeState.NETWORK_SETTINGS_PENDING
                || state == BedrockHandshakeState.AUTHENTICATION_PENDING
                || state == BedrockHandshakeState.PLAY) {
            return result(BedrockHandshakeFrameStatus.INVALID_STATE,
                    BedrockHandshakeResult.INVALID_STATE, null);
        }

        try {
            ByteBuffer batch;
            if (state == BedrockHandshakeState.AWAITING_NETWORK_SETTINGS_REQUEST) {
                batch = BedrockBatchCompressionCodec.decodeBeforeNetworkSettings(
                        frame, BedrockPacketBatchCodec.DEFAULT_MAX_BATCH_BYTES);
            } else if (state == BedrockHandshakeState.AWAITING_LOGIN) {
                batch = BedrockBatchCompressionCodec.decodeAfterNetworkSettings(
                        frame, networkSettings);
            } else {
                return result(BedrockHandshakeFrameStatus.INVALID_STATE,
                        BedrockHandshakeResult.INVALID_STATE, null);
            }

            List<BedrockGamePacket> packets = BedrockPacketBatchCodec.decodeUncompressed(batch);
            if (packets.size() != 1) {
                handshake.close();
                return result(BedrockHandshakeFrameStatus.REJECTED_PACKET,
                        BedrockHandshakeResult.INVALID_STATE, null);
            }

            BedrockGamePacket packet = packets.get(0);
            if (packet.header().senderSubClientId() != 0
                    || packet.header().targetSubClientId() != 0) {
                handshake.close();
                return result(BedrockHandshakeFrameStatus.REJECTED_PACKET,
                        BedrockHandshakeResult.INVALID_STATE, null);
            }

            if (state == BedrockHandshakeState.AWAITING_NETWORK_SETTINGS_REQUEST) {
                return processNetworkSettingsRequest(packet);
            }
            return processLogin(packet);
        } catch (UnsupportedOperationException unsupported) {
            handshake.close();
            return result(BedrockHandshakeFrameStatus.UNSUPPORTED_COMPRESSION,
                    BedrockHandshakeResult.INVALID_STATE, null);
        } catch (IllegalArgumentException malformed) {
            handshake.close();
            return result(BedrockHandshakeFrameStatus.MALFORMED_FRAME,
                    BedrockHandshakeResult.INVALID_STATE, null);
        }
    }

    /** Call only after the NetworkSettings response has actually been sent. */
    public synchronized BedrockHandshakeResult markNetworkSettingsSent() {
        return handshake.markNetworkSettingsSent();
    }

    /** Call only after external identity and account authentication succeeds. */
    public synchronized BedrockHandshakeResult markAuthenticated() {
        return handshake.markAuthenticated();
    }

    public synchronized void close() {
        handshake.close();
    }

    public synchronized BedrockHandshakeState state() {
        return handshake.state();
    }

    private BedrockHandshakeFrameResult processNetworkSettingsRequest(BedrockGamePacket packet) {
        if (packet.header().packetId() != BedrockRequestNetworkSettingsPacketCodec.PACKET_ID) {
            handshake.close();
            return result(BedrockHandshakeFrameStatus.REJECTED_PACKET,
                    BedrockHandshakeResult.INVALID_STATE, null);
        }

        BedrockRequestNetworkSettingsPacket request =
                BedrockRequestNetworkSettingsPacketCodec.decodePayload(packet.payloadBuffer());
        BedrockHandshakeResult transition = handshake.receiveNetworkSettingsRequest(request);
        if (transition == BedrockHandshakeResult.UNSUPPORTED_PROTOCOL) {
            return result(BedrockHandshakeFrameStatus.REJECTED_PACKET, transition, null);
        }
        if (transition != BedrockHandshakeResult.ACCEPTED) {
            return result(BedrockHandshakeFrameStatus.INVALID_STATE, transition, null);
        }

        BedrockGamePacket response = new BedrockGamePacket(
                new BedrockPacketHeader(BedrockNetworkSettingsPacketCodec.PACKET_ID, 0, 0),
                bytes(BedrockNetworkSettingsPacketCodec.encodePayload(networkSettings))
        );
        ByteBuffer responseBatch = BedrockPacketBatchCodec.encodeUncompressed(List.of(response));
        ByteBuffer responseFrame =
                BedrockBatchCompressionCodec.encodeBeforeNetworkSettings(responseBatch);
        return result(BedrockHandshakeFrameStatus.NETWORK_SETTINGS_RESPONSE_READY,
                transition, responseFrame);
    }

    private BedrockHandshakeFrameResult processLogin(BedrockGamePacket packet) {
        if (packet.header().packetId() != BedrockLoginPacketCodec.PACKET_ID) {
            handshake.close();
            return result(BedrockHandshakeFrameStatus.REJECTED_PACKET,
                    BedrockHandshakeResult.INVALID_STATE, null);
        }

        BedrockLoginPacket login = BedrockLoginPacketCodec.decodePayload(packet.payloadBuffer());
        BedrockHandshakeResult transition = handshake.receiveLogin(login);
        if (transition != BedrockHandshakeResult.ACCEPTED) {
            return result(BedrockHandshakeFrameStatus.REJECTED_PACKET, transition, null);
        }
        return result(BedrockHandshakeFrameStatus.AUTHENTICATION_REQUIRED, transition, null);
    }

    private BedrockHandshakeFrameResult result(
            BedrockHandshakeFrameStatus status,
            BedrockHandshakeResult transition,
            ByteBuffer outbound
    ) {
        return new BedrockHandshakeFrameResult(status, transition, handshake.state(), outbound);
    }

    private static byte[] bytes(ByteBuffer source) {
        byte[] bytes = new byte[source.remaining()];
        source.get(bytes);
        return bytes;
    }
}
