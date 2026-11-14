package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.util.Optional;

/** Immutable outcome of processing one inbound Bedrock handshake frame. */
public final class BedrockHandshakeFrameResult {
    private final BedrockHandshakeFrameStatus status;
    private final BedrockHandshakeResult transition;
    private final BedrockHandshakeState state;
    private final ByteBuffer outboundFrame;

    BedrockHandshakeFrameResult(
            BedrockHandshakeFrameStatus status,
            BedrockHandshakeResult transition,
            BedrockHandshakeState state,
            ByteBuffer outboundFrame
    ) {
        this.status = status;
        this.transition = transition;
        this.state = state;
        this.outboundFrame = outboundFrame == null ? null : readOnlyCopy(outboundFrame);
    }

    public BedrockHandshakeFrameStatus status() {
        return status;
    }

    public BedrockHandshakeResult transition() {
        return transition;
    }

    public BedrockHandshakeState state() {
        return state;
    }

    /** Present only when the server must send the initial NetworkSettings response. */
    public Optional<ByteBuffer> outboundFrame() {
        return outboundFrame == null
                ? Optional.empty()
                : Optional.of(outboundFrame.asReadOnlyBuffer());
    }

    private static ByteBuffer readOnlyCopy(ByteBuffer source) {
        ByteBuffer copy = ByteBuffer.allocate(source.remaining());
        copy.put(source.duplicate()).flip();
        return copy.asReadOnlyBuffer();
    }
}
