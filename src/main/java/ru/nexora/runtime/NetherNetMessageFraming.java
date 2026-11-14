package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Encodes and decodes the one-byte NetherNet data-channel message framing.
 *
 * <p>Reliable messages may be split into countdown fragments. Unreliable
 * messages must fit into one data-channel message and always use header zero.</p>
 */
public final class NetherNetMessageFraming {
    private NetherNetMessageFraming() {
    }

    /**
     * Splits a reliable application payload into framed data-channel messages.
     * The maximum frame size includes the one-byte NetherNet header.
     *
     * @param payload application payload
     * @param maxFrameBytes maximum SCTP data-channel message size, including header
     * @param maxFragments configured fragment-count limit (1..255)
     */
    public static List<ByteBuffer> fragmentReliable(ByteBuffer payload,
                                                     int maxFrameBytes,
                                                     int maxFragments) {
        Objects.requireNonNull(payload, "payload");
        if (maxFrameBytes < 2) {
            throw new IllegalArgumentException("maxFrameBytes must be at least 2");
        }
        if (maxFragments < 1 || maxFragments > 255) {
            throw new IllegalArgumentException("maxFragments must be between 1 and 255");
        }

        ByteBuffer source = payload.slice();
        int maxPayloadPerFrame = maxFrameBytes - 1;
        int payloadBytes = source.remaining();
        int fragmentCount = Math.max(1,
                (int) (((long) payloadBytes + maxPayloadPerFrame - 1) / maxPayloadPerFrame));
        if (fragmentCount > maxFragments || fragmentCount > 255) {
            throw new IllegalArgumentException("Payload requires too many NetherNet fragments");
        }

        List<ByteBuffer> frames = new ArrayList<>(fragmentCount);
        for (int index = 0; index < fragmentCount; index++) {
            int count = Math.min(maxPayloadPerFrame, source.remaining());
            ByteBuffer frame = ByteBuffer.allocate(count + 1);
            frame.put((byte) (fragmentCount - index - 1));
            ByteBuffer part = source.slice();
            part.limit(count);
            frame.put(part);
            source.position(source.position() + count);
            frame.flip();
            frames.add(frame.asReadOnlyBuffer());
        }
        return List.copyOf(frames);
    }

    /**
     * Prefixes a single unreliable application message with the mandatory zero header.
     * Unreliable messages are never fragmented.
     */
    public static ByteBuffer frameUnreliable(ByteBuffer payload, int maxFrameBytes) {
        Objects.requireNonNull(payload, "payload");
        if (maxFrameBytes < 1) {
            throw new IllegalArgumentException("maxFrameBytes must be positive");
        }
        ByteBuffer source = payload.slice();
        if (source.remaining() + 1 > maxFrameBytes) {
            throw new IllegalArgumentException("Unreliable message exceeds single-frame limit");
        }
        ByteBuffer frame = ByteBuffer.allocate(source.remaining() + 1);
        frame.put((byte) 0);
        frame.put(source);
        frame.flip();
        return frame.asReadOnlyBuffer();
    }

    /**
     * Removes and validates the NetherNet header on an unreliable channel.
     * Fragmentation is forbidden on that channel, so the header must be zero.
     * The returned read-only view shares the input storage.
     */
    public static ByteBuffer decodeUnreliable(ByteBuffer framedMessage,
                                               int maxFrameBytes) {
        Objects.requireNonNull(framedMessage, "framedMessage");
        if (maxFrameBytes < 1) {
            throw new IllegalArgumentException("maxFrameBytes must be positive");
        }
        ByteBuffer frame = framedMessage.slice();
        if (!frame.hasRemaining()) {
            throw new IllegalArgumentException("Missing NetherNet fragment header");
        }
        if (frame.remaining() > maxFrameBytes) {
            throw new IllegalArgumentException("Unreliable frame exceeds configured byte limit");
        }
        int header = Byte.toUnsignedInt(frame.get());
        if (header != 0) {
            throw new IllegalArgumentException("Unreliable channel does not support fragmentation");
        }
        return frame.slice().asReadOnlyBuffer();
    }
}
