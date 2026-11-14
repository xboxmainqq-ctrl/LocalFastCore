package ru.nexora.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Wire codec matching the server's private NEXORA protocol v1. */
final class NexoraAndroidProtocolCodec {
    static final int MAJOR_VERSION = 1;
    static final int MINOR_VERSION = 0;
    static final int MAX_MESSAGE_BYTES = 8 * 1024 * 1024;
    private static final byte[] MAGIC = {'N', 'X', 'R', 'A'};
    private static final int TYPE_WORLD_INFO = 4;
    private static final int TYPE_CHUNK_DATA = 6;
    private static final int TYPE_CLIENT_MOVE = 7;
    private static final int TYPE_SERVER_POSITION = 8;

    private NexoraAndroidProtocolCodec() {
    }

    static byte[] encodeClientHello(String build) {
        if (build == null) {
            throw new IllegalArgumentException("Client build is required");
        }
        byte[] text = build.getBytes(StandardCharsets.UTF_8);
        if (text.length < 1 || text.length > 64) {
            throw new IllegalArgumentException("Client build must be 1..64 UTF-8 bytes");
        }
        ByteBuffer output = ByteBuffer.allocate(8 + text.length).order(ByteOrder.BIG_ENDIAN);
        output.put(MAGIC).put((byte) MAJOR_VERSION).put((byte) MINOR_VERSION)
                .put((byte) 1).put((byte) text.length).put(text);
        return output.array();
    }

    static ServerResponse decodeServerResponse(byte[] bytes) {
        if (bytes == null || bytes.length < 7 || bytes.length > MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("Invalid NEXORA message size");
        }
        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        for (byte expected : MAGIC) {
            if (input.get() != expected) {
                throw new IllegalArgumentException("Invalid NEXORA protocol magic");
            }
        }
        int major = Byte.toUnsignedInt(input.get());
        int minor = Byte.toUnsignedInt(input.get());
        int type = Byte.toUnsignedInt(input.get());
        if (major != MAJOR_VERSION || minor > MINOR_VERSION) {
            throw new IllegalArgumentException("Unsupported NEXORA protocol version "
                    + major + "." + minor);
        }
        if (type == 2) {
            if (input.remaining() != 20) {
                throw new IllegalArgumentException("Malformed NEXORA ServerHello");
            }
            UUID serverId = new UUID(input.getLong(), input.getLong());
            int maxMessageBytes = input.getInt();
            if (maxMessageBytes < 1 || maxMessageBytes > 8 * 1024 * 1024) {
                throw new IllegalArgumentException("Invalid NEXORA server message limit");
            }
            return new ServerResponse(true, serverId.toString(), maxMessageBytes, 0, "");
        }
        if (type == 3) {
            if (input.remaining() < 2) {
                throw new IllegalArgumentException("Malformed NEXORA rejection");
            }
            int code = Byte.toUnsignedInt(input.get());
            int length = Byte.toUnsignedInt(input.get());
            if (code == 0 || length < 1 || length > 160 || input.remaining() != length) {
                throw new IllegalArgumentException("Malformed NEXORA rejection details");
            }
            byte[] text = new byte[length];
            input.get(text);
            return new ServerResponse(false, "", 0, code, decodeUtf8(text));
        }
        throw new IllegalArgumentException("Unexpected NEXORA server message type");
    }

    static WorldInfo decodeWorldInfo(byte[] bytes) {
        ByteBuffer input = checkedHeader(bytes, TYPE_WORLD_INFO);
        int idLength = Byte.toUnsignedInt(input.get());
        if (idLength < 1 || idLength > 64 || input.remaining() != idLength + 28) {
            throw new IllegalArgumentException("Malformed NEXORA WorldInfo");
        }
        byte[] idBytes = new byte[idLength];
        input.get(idBytes);
        String worldId = decodeUtf8(idBytes);
        int minY = input.getInt();
        int maxYExclusive = input.getInt();
        int spawnX = input.getInt();
        int spawnY = input.getInt();
        int spawnZ = input.getInt();
        int tickRate = input.getInt();
        int loadedChunks = input.getInt();
        if (worldId.isBlank() || maxYExclusive <= minY || spawnY < minY
                || spawnY >= maxYExclusive || tickRate < 1 || tickRate > 120 || loadedChunks < 0) {
            throw new IllegalArgumentException("Invalid NEXORA world information");
        }
        return new WorldInfo(worldId, minY, maxYExclusive, spawnX, spawnY, spawnZ,
                tickRate, loadedChunks);
    }


    static byte[] encodePlayerMove(double x, double y, double z) {
        return encodePosition(TYPE_CLIENT_MOVE, x, y, z);
    }

    static PlayerPosition decodeServerPosition(byte[] bytes) {
        ByteBuffer input = checkedHeader(bytes, TYPE_SERVER_POSITION);
        if (input.remaining() != 24) {
            throw new IllegalArgumentException("Malformed NEXORA server position");
        }
        double x = input.getDouble();
        double y = input.getDouble();
        double z = input.getDouble();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 30_000_000d || Math.abs(y) > 30_000_000d
                || Math.abs(z) > 30_000_000d) {
            throw new IllegalArgumentException("Invalid NEXORA server position");
        }
        return new PlayerPosition(x, y, z);
    }

    private static byte[] encodePosition(int type, double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 30_000_000d || Math.abs(y) > 30_000_000d
                || Math.abs(z) > 30_000_000d) {
            throw new IllegalArgumentException("Invalid NEXORA movement coordinates");
        }
        ByteBuffer output = ByteBuffer.allocate(31).order(ByteOrder.BIG_ENDIAN);
        output.put(MAGIC).put((byte) MAJOR_VERSION).put((byte) MINOR_VERSION)
                .put((byte) type).putDouble(x).putDouble(y).putDouble(z);
        return output.array();
    }

    static byte[] encodeChunkRequest(int chunkX, int chunkZ) {
        ByteBuffer output = ByteBuffer.allocate(15).order(ByteOrder.BIG_ENDIAN);
        output.put(MAGIC).put((byte) MAJOR_VERSION).put((byte) MINOR_VERSION)
                .put((byte) 5).putInt(chunkX).putInt(chunkZ);
        return output.array();
    }

    static ChunkData decodeChunkData(byte[] bytes) {
        ByteBuffer input = checkedHeader(bytes, TYPE_CHUNK_DATA);
        if (input.remaining() < 12) {
            throw new IllegalArgumentException("Truncated NEXORA chunk data");
        }
        int chunkX = input.getInt();
        int chunkZ = input.getInt();
        int count = input.getInt();
        if (count < 0 || count > 32768) {
            throw new IllegalArgumentException("NEXORA chunk contains an invalid block count");
        }
        java.util.ArrayList<ChunkBlock> blocks = new java.util.ArrayList<>(count);
        java.util.HashSet<Long> positions = new java.util.HashSet<>();
        for (int i = 0; i < count; i++) {
            if (input.remaining() < 7) {
                throw new IllegalArgumentException("Truncated NEXORA chunk block");
            }
            int localX = Byte.toUnsignedInt(input.get());
            int y = input.getInt();
            int localZ = Byte.toUnsignedInt(input.get());
            int length = Byte.toUnsignedInt(input.get());
            if (localX > 15 || localZ > 15 || length < 1 || length > 128
                    || input.remaining() < length) {
                throw new IllegalArgumentException("Invalid NEXORA chunk block");
            }
            long position = ((long) y << 8) | (localZ << 4) | localX;
            if (!positions.add(position)) {
                throw new IllegalArgumentException("Duplicate NEXORA chunk block");
            }
            byte[] identifier = new byte[length];
            input.get(identifier);
            String blockId = decodeUtf8(identifier);
            if (blockId.isBlank()) {
                throw new IllegalArgumentException("Empty NEXORA block identifier");
            }
            blocks.add(new ChunkBlock(localX, y, localZ, blockId));
        }
        if (input.hasRemaining()) {
            throw new IllegalArgumentException("Trailing bytes in NEXORA chunk data");
        }
        return new ChunkData(chunkX, chunkZ, java.util.Collections.unmodifiableList(blocks));
    }

    private static ByteBuffer checkedHeader(byte[] bytes, int expectedType) {
        if (bytes == null || bytes.length < 7 || bytes.length > MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("Invalid NEXORA message size");
        }
        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        for (byte expected : MAGIC) {
            if (input.get() != expected) {
                throw new IllegalArgumentException("Invalid NEXORA protocol magic");
            }
        }
        int major = Byte.toUnsignedInt(input.get());
        int minor = Byte.toUnsignedInt(input.get());
        int type = Byte.toUnsignedInt(input.get());
        if (major != MAJOR_VERSION || minor > MINOR_VERSION) {
            throw new IllegalArgumentException("Unsupported NEXORA protocol version "
                    + major + "." + minor);
        }
        if (type != expectedType) {
            throw new IllegalArgumentException("Unexpected NEXORA message type");
        }
        return input;
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("NEXORA text is not valid UTF-8", invalid);
        }
    }

    record ServerResponse(boolean accepted, String serverId, int maxMessageBytes,
                          int rejectionCode, String rejectionReason) {
    }

    record ChunkBlock(int localX, int y, int localZ, String blockId) {
    }

    record ChunkData(int chunkX, int chunkZ, java.util.List<ChunkBlock> blocks) {
    }

    record PlayerPosition(double x, double y, double z) {\n    }\n\n    record WorldInfo(String worldId, int minY, int maxYExclusive,
                     int spawnX, int spawnY, int spawnZ,
                     int tickRate, int loadedChunks) {
    }
}
