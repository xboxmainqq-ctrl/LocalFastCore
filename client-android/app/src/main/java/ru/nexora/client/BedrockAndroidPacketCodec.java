package ru.nexora.client;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Pure-Java Bedrock 2225 packet helpers shared by the Android transport and unit tests. */
public final class BedrockAndroidPacketCodec {
    public static final int PROTOCOL_VERSION = 2225;
    public static final int REQUEST_NETWORK_SETTINGS_PACKET_ID = 193;
    public static final int NETWORK_SETTINGS_PACKET_ID = 143;
    public static final int LOGIN_PACKET_ID = 1;
    public static final int MAX_CONNECTION_REQUEST_BYTES = 1024 * 1024;
    public static final int MAX_CLIENT_DATA_JWT_BYTES = 1024 * 1024;
    public static final int MAX_LOGIN_JWT_BLOCK_BYTES = 2 * 1024 * 1024 - 32;
    public static final int MAX_BATCH_BYTES = 8 * 1024 * 1024;
    public static final int MAX_PACKET_BYTES = 2 * 1024 * 1024;
    public static final int MAX_PACKETS_PER_BATCH = 1024;

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
    public static BedrockAndroidNetworkSettings decodeNetworkSettings(byte[] batchBytes) {
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
        return new BedrockAndroidNetworkSettings(
                threshold, algorithm, throttle, throttleThreshold, throttleScalar);
    }

    /** Encodes LoginPacket, packet-batch framing, and negotiated compression envelope. */
    public static byte[] encodeLoginBatch(String authenticationPayloadJson,
                                          String clientDataJwt,
                                          BedrockAndroidNetworkSettings settings) {
        if (authenticationPayloadJson == null || clientDataJwt == null || settings == null) {
            throw new IllegalArgumentException("Authentication payload, client-data JWT, and NetworkSettings are required");
        }
        byte[] auth = authenticationPayloadJson.getBytes(StandardCharsets.UTF_8);
        byte[] clientData = clientDataJwt.getBytes(StandardCharsets.UTF_8);
        if (auth.length == 0 || auth.length > MAX_CONNECTION_REQUEST_BYTES) {
            throw new IllegalArgumentException("Bedrock authentication payload exceeds the configured limit");
        }
        if (clientData.length > MAX_CLIENT_DATA_JWT_BYTES) {
            throw new IllegalArgumentException("Bedrock client-data JWT exceeds the configured limit");
        }

        int jwtBlockLength = 2 * Integer.BYTES + auth.length + clientData.length;
        if (jwtBlockLength > MAX_LOGIN_JWT_BLOCK_BYTES) {
            throw new IllegalArgumentException("Bedrock Login JWT block exceeds the packet limit");
        }
        ByteBuffer payload = ByteBuffer.allocate(
                4 + varUInt32Size(jwtBlockLength) + jwtBlockLength);
        payload.putInt(PROTOCOL_VERSION);
        writeVarUInt32(payload, jwtBlockLength);
        payload.order(ByteOrder.LITTLE_ENDIAN);
        payload.putInt(auth.length).put(auth);
        payload.putInt(clientData.length).put(clientData);
        payload.flip();

        int packetLength = varUInt32Size(LOGIN_PACKET_ID) + payload.remaining();
        ByteBuffer batch = ByteBuffer.allocate(varUInt32Size(packetLength) + packetLength);
        writeVarUInt32(batch, packetLength);
        writeVarUInt32(batch, LOGIN_PACKET_ID);
        batch.put(payload);
        batch.flip();

        return bytes(encodeAfterNetworkSettings(batch, settings));
    }

    /** Decompresses and decodes one post-NetworkSettings server batch. */
    public static List<BedrockAndroidServerPacket> decodeServerBatch(
            byte[] envelope, BedrockAndroidNetworkSettings settings) {
        if (envelope == null || settings == null || envelope.length < 2
                || envelope.length > MAX_BATCH_BYTES) {
            throw new IllegalArgumentException("Bedrock server batch size is invalid");
        }
        int algorithm = Byte.toUnsignedInt(envelope[0]);
        byte[] batch;
        if (algorithm == 0xff) {
            batch = java.util.Arrays.copyOfRange(envelope, 1, envelope.length);
        } else if (algorithm == 0x00) {
            if (settings.compressionAlgorithm() != 0 || settings.compressionThreshold() == 0) {
                throw new IllegalArgumentException("Server used unnegotiated ZLIB compression");
            }
            batch = inflateRaw(envelope, 1, envelope.length - 1);
        } else if (algorithm == 0x01) {
            throw new UnsupportedOperationException("Snappy decompression is not implemented in Android client");
        } else {
            throw new IllegalArgumentException("Unknown Bedrock batch compression algorithm");
        }
        if (batch.length == 0 || batch.length > MAX_BATCH_BYTES) {
            throw new IllegalArgumentException("Decompressed Bedrock batch size is invalid");
        }

        ByteBuffer input = ByteBuffer.wrap(batch);
        List<BedrockAndroidServerPacket> packets = new ArrayList<>();
        while (input.hasRemaining()) {
            if (packets.size() >= MAX_PACKETS_PER_BATCH) {
                throw new IllegalArgumentException("Bedrock batch contains too many packets");
            }
            int packetLength = readVarUInt32(input);
            if (packetLength <= 0 || packetLength > MAX_PACKET_BYTES
                    || packetLength > input.remaining()) {
                throw new IllegalArgumentException("Bedrock packet length is invalid");
            }
            ByteBuffer packet = input.slice();
            packet.limit(packetLength);
            int header = readVarUInt32(packet);
            if ((header & ~0x3fff) != 0) {
                throw new IllegalArgumentException("Bedrock packet header contains reserved bits");
            }
            int packetId = header & 0x3ff;
            int senderSubClientId = (header >>> 10) & 0x3;
            int targetSubClientId = (header >>> 12) & 0x3;
            byte[] payload = new byte[packet.remaining()];
            packet.get(payload);
            packets.add(new BedrockAndroidServerPacket(
                    packetId, senderSubClientId, targetSubClientId, payload));
            input.position(input.position() + packetLength);
        }
        return List.copyOf(packets);
    }

    public static BedrockAndroidPlayStatus decodePlayStatus(BedrockAndroidServerPacket packet) {
        if (packet == null || packet.packetId() != 2
                || packet.senderSubClientId() != 0 || packet.targetSubClientId() != 0) {
            throw new IllegalArgumentException("Expected un-routed Bedrock PlayStatus packet ID 2");
        }
        byte[] payload = packet.payload();
        if (payload.length != Integer.BYTES) {
            throw new IllegalArgumentException("Bedrock PlayStatus payload must be exactly 4 bytes");
        }
        return BedrockAndroidPlayStatus.fromWireValue(ByteBuffer.wrap(payload).getInt());
    }

    private static byte[] inflateRaw(byte[] input, int offset, int length) {
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(input, offset, length);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count > 0) {
                    if (output.size() + count > MAX_BATCH_BYTES) {
                        throw new IllegalArgumentException("Inflated Bedrock batch exceeds the configured limit");
                    }
                    output.write(buffer, 0, count);
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    throw new IllegalArgumentException("Truncated or invalid raw DEFLATE batch");
                } else {
                    throw new IllegalArgumentException("Raw DEFLATE decoder made no progress");
                }
            }
            if (inflater.getRemaining() != 0) {
                throw new IllegalArgumentException("Trailing bytes after compressed Bedrock batch");
            }
            return output.toByteArray();
        } catch (java.util.zip.DataFormatException failure) {
            throw new IllegalArgumentException("Invalid raw DEFLATE Bedrock batch", failure);
        } finally {
            inflater.end();
        }
    }

    private static ByteBuffer encodeAfterNetworkSettings(
            ByteBuffer batch, BedrockAndroidNetworkSettings settings) {
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
