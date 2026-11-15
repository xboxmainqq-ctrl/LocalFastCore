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

    /** Decodes Bedrock ResourcePacksInfo, including the advertised resource-pack metadata. */
    public static BedrockAndroidResourcePackInfo decodeResourcePacksInfo(byte[] payloadBytes) {
        ByteBuffer payload = ByteBuffer.wrap(payloadBytes);
        boolean required = readBoolean(payload);
        boolean hasAddonPacks = readBoolean(payload);
        boolean hasScripts = readBoolean(payload);
        boolean forceDisableVibrantVisuals = readBoolean(payload);
        if (payload.remaining() < 16) {
            throw new IllegalArgumentException("Truncated ResourcePacksInfo world-template UUID");
        }
        readLittleEndianInt64(payload);
        readLittleEndianInt64(payload);
        readUtf8String(payload, 32); // world-template version
        int packCount = readVarUInt32(payload);
        if (packCount < 0 || packCount > 64) {
            throw new IllegalArgumentException("ResourcePacksInfo pack count is outside the allowed range");
        }
        java.util.ArrayList<BedrockAndroidResourcePackInfo.Pack> packs =
                new java.util.ArrayList<>(packCount);
        java.util.HashSet<String> identities = new java.util.HashSet<>();
        for (int i = 0; i < packCount; i++) {
            java.util.UUID uuid = new java.util.UUID(
                    readLittleEndianInt64(payload), readLittleEndianInt64(payload));
            String version = readUtf8String(payload, 64);
            long size = readLittleEndianInt64(payload);
            String contentKey = readUtf8String(payload, 256);
            String subpack = readUtf8String(payload, 256);
            String identity = readUtf8String(payload, 256);
            boolean scripts = readBoolean(payload);
            boolean addon = readBoolean(payload);
            boolean rayTracing = readBoolean(payload);
            String cdnUrl = readUtf8String(payload, 2048);
            BedrockAndroidResourcePackInfo.Pack pack =
                    new BedrockAndroidResourcePackInfo.Pack(uuid, version, size,
                            contentKey, subpack, identity, scripts, addon, rayTracing, cdnUrl);
            if (!identities.add(pack.resourceName())) {
                throw new IllegalArgumentException("Duplicate Bedrock resource-pack identity");
            }
            packs.add(pack);
        }
        if (payload.hasRemaining()) {
            throw new IllegalArgumentException("Trailing ResourcePacksInfo payload bytes");
        }
        return new BedrockAndroidResourcePackInfo(required, hasAddonPacks,
                hasScripts, forceDisableVibrantVisuals, packs);
    }

    /** Validates the empty-pack ResourcePacksInfo variant used by older UI paths. */
    public static void decodeEmptyResourcePacksInfo(byte[] payloadBytes) {
        BedrockAndroidResourcePackInfo info = decodeResourcePacksInfo(payloadBytes);
        if (info.required() || info.hasAddonPacks() || info.hasScripts()
                || info.packs().size() != 0) {
            throw new UnsupportedOperationException("Non-empty or forced resource packs require the pack downloader");
        }
    }

    /** Decodes ResourcePackDataInfo for one unencrypted resource archive. */
    public static BedrockAndroidResourcePackDataInfo decodeResourcePackDataInfo(byte[] payloadBytes) {
        ByteBuffer payload = ByteBuffer.wrap(payloadBytes);
        String resourceName = readUtf8String(payload, 128);
        int chunkSize = readLittleEndianInt32(payload);
        int chunkCount = readLittleEndianInt32(payload);
        long fileSize = readLittleEndianInt64(payload);
        String hash = readUtf8String(payload, 64);
        boolean premium = readBoolean(payload);
        if (!payload.hasRemaining()) throw new IllegalArgumentException("Missing resource-pack type");
        int packType = Byte.toUnsignedInt(payload.get());
        if (payload.hasRemaining() || resourceName.isBlank()
                || chunkSize < 1 || chunkSize > 1024 * 1024
                || chunkCount < 1 || chunkCount > 64
                || fileSize < 1 || fileSize > 64L * 1024L * 1024L
                || chunkCount != (fileSize + chunkSize - 1) / chunkSize
                || !hash.matches("[0-9a-fA-F]{64}") || premium || packType != 6) {
            throw new IllegalArgumentException("Unsupported or invalid Bedrock resource-pack data info");
        }
        return new BedrockAndroidResourcePackDataInfo(resourceName, chunkSize,
                chunkCount, fileSize, hash.toLowerCase(java.util.Locale.ROOT), packType);
    }

    /** Decodes one ResourcePackChunkData payload. */
    public static BedrockAndroidResourcePackChunk decodeResourcePackChunkData(byte[] payloadBytes) {
        ByteBuffer payload = ByteBuffer.wrap(payloadBytes);
        String resourceName = readUtf8String(payload, 128);
        int chunkIndex = readLittleEndianInt32(payload);
        long offset = readLittleEndianInt64(payload);
        int length = readVarUInt32(payload);
        if (chunkIndex < 0 || offset < 0 || length < 1 || length > 1024 * 1024
                || length != payload.remaining()) {
            throw new IllegalArgumentException("Invalid Bedrock resource-pack chunk data");
        }
        byte[] data = new byte[length];
        payload.get(data);
        return new BedrockAndroidResourcePackChunk(resourceName, chunkIndex, offset, data);
    }

    /** Decodes ResourcePackStack and returns the ordered pack IDs. */
    public static BedrockAndroidResourcePackStack decodeResourcePackStack(byte[] payloadBytes) {
        ByteBuffer payload = ByteBuffer.wrap(payloadBytes);
        boolean required = readBoolean(payload);
        int packCount = readVarUInt32(payload);
        if (packCount < 0 || packCount > 64) {
            throw new IllegalArgumentException("ResourcePackStack pack count is outside the allowed range");
        }
        java.util.ArrayList<String> resourceNames = new java.util.ArrayList<>(packCount);
        for (int i = 0; i < packCount; i++) {
            String uuid = readUtf8String(payload, 64);
            String version = readUtf8String(payload, 64);
            readUtf8String(payload, 256); // subpack name
            java.util.UUID.fromString(uuid);
            if (!version.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z.-]+)?")) {
                throw new IllegalArgumentException("Invalid resource-pack stack version");
            }
            resourceNames.add(uuid + "_" + version);
        }
        String gameVersion = readUtf8String(payload, 64);
        int experimentCount = readLittleEndianInt32(payload);
        if (experimentCount < 0 || experimentCount > 1024) {
            throw new IllegalArgumentException("Bedrock experiment count is outside the allowed range");
        }
        if (experimentCount != 0) {
            throw new UnsupportedOperationException("Experiments are not implemented in the Android client");
        }
        boolean experimentsPreviouslyToggled = readBoolean(payload);
        boolean hasEditorPacks = readBoolean(payload);
        if (experimentsPreviouslyToggled || hasEditorPacks || payload.hasRemaining()) {
            throw new IllegalArgumentException("Unsupported or trailing ResourcePackStack fields");
        }
        return new BedrockAndroidResourcePackStack(required, gameVersion, resourceNames);
    }

    /** Validates the empty-pack ResourcePackStack variant used by NEXORA. */
    public static void decodeEmptyResourcePackStack(byte[] payloadBytes) {
        if (!decodeResourcePackStack(payloadBytes).resourceNames().isEmpty()) {
            throw new UnsupportedOperationException("Non-empty resource-pack stacks require the pack downloader");
        }
    }

    /** Encodes the client response with optional requested pack IDs. */
    public static byte[] encodeResourcePackClientResponse(
            BedrockAndroidResourcePackStatus status, List<String> packIds,
            BedrockAndroidNetworkSettings settings) {
        if (status == null || packIds == null || settings == null || packIds.size() > 64) {
            throw new IllegalArgumentException("Resource-pack response arguments are invalid");
        }
        String statusName = switch (status) {
            case REFUSED -> "cancel";
            case SEND_PACKS -> "downloading";
            case HAVE_ALL_PACKS -> "downloadingfinished";
            case COMPLETED -> "resourcepackstackfinished";
        };
        if (status != BedrockAndroidResourcePackStatus.SEND_PACKS && !packIds.isEmpty()) {
            throw new IllegalArgumentException("Only SEND_PACKS may include pack identifiers");
        }
        int capacity = 128;
        for (String id : packIds) capacity += id.getBytes(StandardCharsets.UTF_8).length + 5;
        ByteBuffer payload = ByteBuffer.allocate(capacity);
        writeUtf8String(payload, statusName);
        if (status == BedrockAndroidResourcePackStatus.SEND_PACKS) {
            writeVarUInt32(payload, packIds.size());
            for (String id : packIds) writeUtf8String(payload, id);
        }
        payload.flip();

        int packetLength = varUInt32Size(8) + payload.remaining();
        ByteBuffer batch = ByteBuffer.allocate(varUInt32Size(packetLength) + packetLength);
        writeVarUInt32(batch, packetLength);
        writeVarUInt32(batch, 8);
        batch.put(payload);
        batch.flip();
        return bytes(encodeAfterNetworkSettings(batch, settings));
    }

    public static byte[] encodeResourcePackClientResponse(
            BedrockAndroidResourcePackStatus status, BedrockAndroidNetworkSettings settings) {
        return encodeResourcePackClientResponse(status, List.of(), settings);
    }

    /** Encodes a serverbound ResourcePackChunkRequest packet. */
    public static byte[] encodeResourcePackChunkRequest(
            String resourceName, int chunkIndex, BedrockAndroidNetworkSettings settings) {
        if (resourceName == null || resourceName.isBlank() || chunkIndex < 0 || settings == null) {
            throw new IllegalArgumentException("Resource-pack chunk request is invalid");
        }
        ByteBuffer payload = ByteBuffer.allocate(160);
        writeUtf8String(payload, resourceName);
        payload.put((byte) chunkIndex).put((byte) (chunkIndex >>> 8))
                .put((byte) (chunkIndex >>> 16)).put((byte) (chunkIndex >>> 24));
        payload.flip();
        int packetLength = varUInt32Size(84) + payload.remaining();
        ByteBuffer batch = ByteBuffer.allocate(varUInt32Size(packetLength) + packetLength);
        writeVarUInt32(batch, packetLength);
        writeVarUInt32(batch, 84);
        batch.put(payload);
        batch.flip();
        return bytes(encodeAfterNetworkSettings(batch, settings));
    }

    /** Encodes ResourcePacksReadyForValidation (packet 203), sent after the stack is applied. */
    public static byte[] encodeResourcePacksReadyForValidation(
            BedrockAndroidNetworkSettings settings) {
        if (settings == null) throw new IllegalArgumentException("Network settings are required");
        int packetLength = varUInt32Size(340);
        ByteBuffer batch = ByteBuffer.allocate(varUInt32Size(packetLength) + packetLength);
        writeVarUInt32(batch, packetLength);
        writeVarUInt32(batch, 340);
        batch.flip();
        return bytes(encodeAfterNetworkSettings(batch, settings));
    }

    private static int readLittleEndianInt32(ByteBuffer input) {
        if (input.remaining() < Integer.BYTES) {
            throw new IllegalArgumentException("Truncated little-endian int32");
        }
        return Byte.toUnsignedInt(input.get())
                | (Byte.toUnsignedInt(input.get()) << 8)
                | (Byte.toUnsignedInt(input.get()) << 16)
                | (input.get() << 24);
    }

    private static long readLittleEndianInt64(ByteBuffer input) {
        if (input.remaining() < Long.BYTES) {
            throw new IllegalArgumentException("Truncated little-endian int64");
        }
        long value = 0L;
        for (int i = 0; i < Long.BYTES; i++) {
            value |= (long) Byte.toUnsignedInt(input.get()) << (i * 8);
        }
        return value;
    }

    private static boolean readBoolean(ByteBuffer input) {
        if (!input.hasRemaining()) {
            throw new IllegalArgumentException("Truncated Bedrock boolean");
        }
        int value = Byte.toUnsignedInt(input.get());
        if (value > 1) {
            throw new IllegalArgumentException("Bedrock boolean must be zero or one");
        }
        return value != 0;
    }

    private static String readUtf8String(ByteBuffer input, int maxBytes) {
        int length = readVarUInt32(input);
        if (length < 0 || length > maxBytes || length > input.remaining()) {
            throw new IllegalArgumentException("Bedrock string length is invalid");
        }
        ByteBuffer bytes = input.slice();
        bytes.limit(length);
        input.position(input.position() + length);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(bytes).toString();
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new IllegalArgumentException("Bedrock string is not valid UTF-8", invalid);
        }
    }

    private static void writeUtf8String(ByteBuffer output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarUInt32(output, bytes.length);
        output.put(bytes);
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
