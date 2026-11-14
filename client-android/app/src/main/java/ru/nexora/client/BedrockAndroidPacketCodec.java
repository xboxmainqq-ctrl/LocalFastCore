package ru.nexora.client;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;

/** Pure-Java Bedrock 2225 packet helpers shared by the Android transport and unit tests. */
public final class BedrockAndroidPacketCodec {
    public static final int PROTOCOL_VERSION = 2225;
    public static final int REQUEST_NETWORK_SETTINGS_PACKET_ID = 193;
    public static final int NETWORK_SETTINGS_PACKET_ID = 143;
    public static final int LOGIN_PACKET_ID = 1;
    public static final int MAX_CONNECTION_REQUEST_BYTES = 1024 * 1024;

    private BedrockAndroidPacketCodec() {
    }

    /** Encodes the uncompressed packet-batch body sent before NetworkSettings. */
    public static byte[] encodeRequestNetworkSettings() {
        ByteBuffer batch = ByteBuffer.allocate(16);
        writeVarUInt32(batch, 6);
        writeVarUInt32(batch, REQUEST_NETWORK_SETTINGS_PACKET_ID);
        batch.putInt(PROTOCOL_VERSION);
        batch.flip();
        return bytes(batch);
    }

    /** Decodes the single, uncompressed NetworkSettings response packet. */
    public static NetherNetAndroidClient.NetworkSettings decodeNetworkSettings(byte[] batchBytes) {
        if (batchBytes == null || batchBytes.length == 0 || batchBytes.length > 1024) {
            throw new IllegalArgumentException("NetworkSettings batch size is invalid");
        }
        ByteBuffer batch = ByteBuffer.wrap(batchBytes);
        int packetLength = readVarUInt32(batch);
        if (packetLength <= 0 || packetLength != batch.remaining()) {
            throw new IllegalArgumentException("NetworkSettings batch must contain one complete packet");
        }
        int packetStart = batch.position();
        int header = readVarUInt32(batch);
        if ((header & 0x3ff) != NETWORK_SETTINGS_PACKET_ID || (header & 0x3c00) != 0
                || (header & ~0x3fff) != 0) {
            throw new IllegalArgumentException("Expected NetworkSettings packet ID 143");
        }
        int headerBytes = batch.position() - packetStart;
        if (packetLength - headerBytes != 10) {
            throw new IllegalArgumentException("NetworkSettings payload must be 10 bytes");
        }
        batch.order(ByteOrder.LITTLE_ENDIAN);
        int threshold = Short.toUnsignedInt(batch.getShort());
        int algorithm = Short.toUnsignedInt(batch.getShort());
        boolean throttle = batch.get() != 0;
        int throttleThreshold = Byte.toUnsignedInt(batch.get());
        float throttleScalar = batch.getFloat();
        if (algorithm != 0 && algorithm != 1 && algorithm != 0xffff) {
            throw new IllegalArgumentException("Unsupported compression algorithm " + algorithm);
        }
        if (!Float.isFinite(throttleScalar)) {
            throw new IllegalArgumentException("Invalid client throttle scalar");
        }
        return new NetherNetAndroidClient.NetworkSettings(
                threshold, algorithm, throttle, throttleThreshold, throttleScalar);
    }

    /** Encodes LoginPacket, packet-batch framing, and negotiated compression envelope. */
    public static byte[] encodeLoginBatch(String connectionRequestJson,
                                          NetherNetAndroidClient.NetworkSettings settings) {
        if (connectionRequestJson == null || settings == null) {
            throw new IllegalArgumentException("Connection Request and NetworkSettings are required");
        }
        byte[] request = connectionRequestJson.getBytes(StandardCharsets.UTF_8);
        if (request.length == 0 || request.length > MAX_CONNECTION_REQUEST_BYTES) {
            throw new IllegalArgumentException("Bedrock Connection Request exceeds the configured limit");
        }

        ByteBuffer payload = ByteBuffer.allocate(4 + varUInt32Size(request.length) + request.length);
        payload.putInt(PROTOCOL_VERSION);
        writeVarUInt32(payload, request.length);
        payload.put(request);
        payload.flip();

        int packetLength = varUInt32Size(LOGIN_PACKET_ID) + payload.remaining();
        ByteBuffer batch = ByteBuffer.allocate(varUInt32Size(packetLength) + packetLength);
        writeVarUInt32(batch, packetLength);
        writeVarUInt32(batch, LOGIN_PACKET_ID);
        batch.put(payload);
        batch.flip();

        return bytes(encodeAfterNetworkSettings(batch, settings));
    }

    private static ByteBuffer encodeAfterNetworkSettings(
            ByteBuffer batch, NetherNetAndroidClient.NetworkSettings settings) {
        boolean disabled = settings.compressionThreshold() == 0;
        boolean belowThreshold = batch.remaining() < settings.compressionThreshold();
        if (settings.compressionAlgorithm() == 0xffff || disabled || belowThreshold) {
            ByteBuffer output = ByteBuffer.allocate(batch.remaining() + 1);
            output.put((byte) 0xff).put(batch.duplicate()).flip();
            return output.asReadOnlyBuffer();
        }
        if (settings.compressionAlgorithm() == 1) {
            throw new UnsupportedOperationException(
                    "Snappy compression is not implemented in the Android client");
        }
        if (settings.compressionAlgorithm() != 0) {
            throw new IllegalArgumentException("Unknown Bedrock compression algorithm");
        }

        byte[] source = bytes(batch);
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try {
            deflater.setInput(source);
            deflater.finish();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                int written = deflater.deflate(buffer);
                if (written <= 0) {
                    throw new IllegalStateException("Raw DEFLATE encoder made no progress");
                }
                output.write(buffer, 0, written);
            }
            byte[] compressed = output.toByteArray();
            ByteBuffer result = ByteBuffer.allocate(compressed.length + 1);
            result.put((byte) 0x00).put(compressed).flip();
            return result.asReadOnlyBuffer();
        } finally {
            deflater.end();
        }
    }

    private static int readVarUInt32(ByteBuffer input) {
        int result = 0;
        for (int index = 0; index < 5; index++) {
            if (!input.hasRemaining()) {
                throw new IllegalArgumentException("Truncated Bedrock varuint32");
            }
            int current = Byte.toUnsignedInt(input.get());
            if (index == 4 && (current & 0xf0) != 0) {
                throw new IllegalArgumentException("Bedrock varuint32 exceeds 32 bits");
            }
            result |= (current & 0x7f) << (index * 7);
            if ((current & 0x80) == 0) {
                return result;
            }
        }
        throw new IllegalArgumentException("Bedrock varuint32 is too long");
    }

    private static void writeVarUInt32(ByteBuffer output, int value) {
        while ((value & 0xffffff80) != 0) {
            output.put((byte) ((value & 0x7f) | 0x80));
            value >>>= 7;
        }
        output.put((byte) value);
    }

    private static int varUInt32Size(int value) {
        int size = 1;
        while ((value & 0xffffff80) != 0) {
            value >>>= 7;
            size++;
        }
        return size;
    }

    private static byte[] bytes(ByteBuffer buffer) {
        byte[] result = new byte[buffer.remaining()];
        buffer.duplicate().get(result);
        return result;
    }
}
