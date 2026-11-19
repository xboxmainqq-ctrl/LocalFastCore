package ru.nexora.network;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * Bounded HMAC-SHA-256 envelope codec for node-to-node messages.
 * It authenticates bytes and recipient/time metadata; replay deduplication,
 * key rotation and peer authorization must be enforced by the transport layer.
 */
public final class AuthenticatedClusterMessageCodec implements ClusterMessageCodec {
    private static final int MAGIC = 0x4e58434d; // NXCM
    private static final int VERSION = 1;
    private static final int MAC_BYTES = 32;
    private final byte[] secret;
    private final long maximumClockSkewMillis;

    public AuthenticatedClusterMessageCodec(byte[] sharedSecret, long maximumClockSkewMillis) {
        if (sharedSecret == null || sharedSecret.length < 32)
            throw new IllegalArgumentException("Cluster shared secret must contain at least 32 bytes");
        if (maximumClockSkewMillis < 0) throw new IllegalArgumentException("Negative clock skew");
        this.secret = sharedSecret.clone();
        this.maximumClockSkewMillis = maximumClockSkewMillis;
    }

    public byte[] encode(ClusterMessage message) throws IOException {
        if (message == null) throw new NullPointerException("message");
        ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream(message.payload().length + 160);
        try (DataOutputStream output = new DataOutputStream(bodyBytes)) {
            output.writeInt(MAGIC);
            output.writeByte(VERSION);
            output.writeLong(message.messageId().getMostSignificantBits());
            output.writeLong(message.messageId().getLeastSignificantBits());
            writeString(output, message.sourceNode(), 64);
            writeString(output, message.targetNode(), 64);
            writeString(output, message.type(), 64);
            output.writeLong(message.createdAtEpochMillis());
            byte[] payload = message.payload();
            output.writeInt(payload.length);
            output.write(payload);
        }
        byte[] body = bodyBytes.toByteArray();
        byte[] mac = hmac(body);
        ByteArrayOutputStream envelope = new ByteArrayOutputStream(body.length + mac.length);
        envelope.write(body);
        envelope.write(mac);
        return envelope.toByteArray();
    }

    public ClusterMessage decode(byte[] envelope, String expectedTargetNode, long nowEpochMillis)
            throws IOException {
        if (envelope == null) throw new NullPointerException("envelope");
        if (expectedTargetNode == null || !expectedTargetNode.matches("[A-Za-z0-9._-]{1,64}"))
            throw new IllegalArgumentException("Invalid expected target node");
        if (nowEpochMillis < 0) throw new IllegalArgumentException("Negative current time");
        if (envelope.length < 4 + 1 + 16 + 6 + 8 + 4 + MAC_BYTES
                || envelope.length > ClusterMessage.MAX_PAYLOAD_BYTES + 512) {
            throw new IOException("Invalid cluster envelope size");
        }
        int bodyLength = envelope.length - MAC_BYTES;
        byte[] body = java.util.Arrays.copyOf(envelope, bodyLength);
        byte[] suppliedMac = java.util.Arrays.copyOfRange(envelope, bodyLength, envelope.length);
        if (!MessageDigest.isEqual(suppliedMac, hmac(body)))
            throw new IOException("Cluster message authentication failed");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
            if (input.readInt() != MAGIC) throw new IOException("Invalid cluster message magic");
            if (input.readUnsignedByte() != VERSION) throw new IOException("Unsupported cluster message version");
            UUID id = new UUID(input.readLong(), input.readLong());
            String source = readString(input, 64);
            String target = readString(input, 64);
            String type = readString(input, 64);
            long created = input.readLong();
            int payloadLength = input.readInt();
            if (payloadLength < 0 || payloadLength > ClusterMessage.MAX_PAYLOAD_BYTES
                    || payloadLength != input.available()) {
                throw new IOException("Invalid cluster message payload length");
            }
            byte[] payload = new byte[payloadLength];
            input.readFully(payload);
            if (!target.equals(expectedTargetNode)) throw new IOException("Cluster message addressed to another node");
            long lowerBound = nowEpochMillis < maximumClockSkewMillis
                    ? 0 : nowEpochMillis - maximumClockSkewMillis;
            long upperBound;
            try { upperBound = Math.addExact(nowEpochMillis, maximumClockSkewMillis); }
            catch (ArithmeticException overflow) { upperBound = Long.MAX_VALUE; }
            if (created < lowerBound || created > upperBound)
                throw new IOException("Cluster message timestamp outside accepted clock skew");
            try {
                return new ClusterMessage(id, source, target, type, created, payload);
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid cluster message metadata", invalid);
            }
        } catch (EOFException truncated) {
            throw new IOException("Truncated cluster message", truncated);
        }
    }

    private byte[] hmac(byte[] body) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(body);
        } catch (GeneralSecurityException impossible) {
            throw new IOException("HMAC-SHA-256 is unavailable", impossible);
        }
    }

    private static void writeString(DataOutputStream output, String value, int maxBytes) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 1 || bytes.length > maxBytes) throw new IOException("Cluster string exceeds limit");
        output.writeByte(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input, int maxBytes) throws IOException {
        int length = input.readUnsignedByte();
        if (length < 1 || length > maxBytes) throw new IOException("Invalid cluster string length");
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new IOException("Malformed cluster message UTF-8", invalid);
        }
    }
}
