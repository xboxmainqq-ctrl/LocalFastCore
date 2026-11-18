package ru.nexora.network;

import java.io.IOException;

/** Encode/decode contract for authenticated cluster message envelopes. */
public interface ClusterMessageCodec {
    byte[] encode(ClusterMessage message) throws IOException;
    ClusterMessage decode(byte[] envelope, String expectedTargetNode, long nowEpochMillis) throws IOException;
}
