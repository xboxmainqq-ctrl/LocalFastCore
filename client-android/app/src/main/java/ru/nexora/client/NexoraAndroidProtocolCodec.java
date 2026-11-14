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
    static final int MINOR_VERSION = 2;
    static final int MAX_MESSAGE_BYTES = 8 * 1024 * 1024;
    private static final byte[] MAGIC = {'N', 'X', 'R', 'A'};
    private static final int TYPE_WORLD_INFO = 4;
    private static final int TYPE_CHUNK_DATA = 6;
    private static final int TYPE_CLIENT_MOVE = 7;
    private static final int TYPE_SERVER_POSITION = 8;
    private static final int TYPE_REMOTE_PLAYER_STATE = 9;
    private static final int TYPE_REMOTE_PLAYER_LEFT = 10;
    private static final int TYPE_INVENTORY = 11;
    private static final int TYPE_INVENTORY_ACTION = 12;
    private static final int TYPE_INVENTORY_ACTION_RESULT = 13;

    private NexoraAndroidProtocolCodec() {
    }

    static byte[] encodeClientHello(String build) {
        return encodeClientHello(build, "Игрок", 0);
    }

    static byte[] encodeClientHello(String build, String playerName) {
        return encodeClientHello(build, playerName, 0);
    }

    static byte[] encodeClientHello(String build, String playerName, int skinIndex) {
        if (build == null || playerName == null || playerName.isBlank() || playerName.length() > 16 || skinIndex < 0 || skinIndex > 4) throw new IllegalArgumentException("Client build and player name are required");
        byte[] text = build.getBytes(StandardCharsets.UTF_8);
        byte[] name = playerName.getBytes(StandardCharsets.UTF_8);
        if (text.length < 1 || text.length > 64 || name.length < 1 || name.length > 48) throw new IllegalArgumentException("Invalid NEXORA hello text length");
        ByteBuffer output = ByteBuffer.allocate(10 + text.length + name.length).order(ByteOrder.BIG_ENDIAN);
        output.put(MAGIC).put((byte) MAJOR_VERSION).put((byte) MINOR_VERSION)
                .put((byte) 1).put((byte) text.length).put(text).put((byte) name.length).put(name).put((byte) skinIndex);
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
        if (major != MAJOR_VERSION || minor != MINOR_VERSION) {
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
        double yaw = input.getDouble();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 30_000_000d || Math.abs(y) > 30_000_000d
                || Math.abs(z) > 30_000_000d) {
            throw new IllegalArgumentException("Invalid NEXORA server position");
        }
        return new PlayerPosition(x, y, z);
    }

    static RemotePlayerState decodeRemotePlayerState(byte[] bytes) {
        ByteBuffer input = checkedHeader(bytes, TYPE_REMOTE_PLAYER_STATE);
        if (input.remaining() < 50) throw new IllegalArgumentException("Malformed NEXORA remote player state");
        UUID playerId = new UUID(input.getLong(), input.getLong());
        int nameLength = Byte.toUnsignedInt(input.get());
        if (nameLength < 1 || nameLength > 48 || input.remaining() != nameLength + 33) throw new IllegalArgumentException("Invalid remote player name length");
        byte[] nameBytes = new byte[nameLength];
        input.get(nameBytes);
        String playerName = decodeUtf8(nameBytes);
        int skinIndex = Byte.toUnsignedInt(input.get());
        if (skinIndex > 4) throw new IllegalArgumentException("Invalid remote player skin");
        if (playerName.isBlank() || playerName.length() > 16) throw new IllegalArgumentException("Invalid remote player name");
        double x = input.getDouble();
        double y = input.getDouble();
        double z = input.getDouble();
        double yaw = input.getDouble();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 30_000_000d || Math.abs(y) > 30_000_000d
                || Math.abs(z) > 30_000_000d || !Double.isFinite(yaw) || yaw < -180.0 || yaw > 180.0) {
            throw new IllegalArgumentException("Invalid NEXORA remote player coordinates or yaw");
        }
        return new RemotePlayerState(playerId, playerName, skinIndex, x, y, z, yaw);
    }

    static UUID decodeRemotePlayerLeft(byte[] bytes) {
        ByteBuffer input = checkedHeader(bytes, TYPE_REMOTE_PLAYER_LEFT);
        if (input.remaining() != 16) {
            throw new IllegalArgumentException("Malformed NEXORA remote player leave");
        }
        return new UUID(input.getLong(), input.getLong());
    }

    static InventoryState decodeInventory(byte[] bytes) {
        ByteBuffer input = checkedHeader(bytes, TYPE_INVENTORY);
        if (input.remaining() < 17) {
            throw new IllegalArgumentException("Truncated NEXORA inventory snapshot");
        }
        UUID owner = new UUID(input.getLong(), input.getLong());
        int count = Byte.toUnsignedInt(input.get());
        if (count > 36) throw new IllegalArgumentException("Too many NEXORA inventory stacks");
        java.util.ArrayList<InventoryStack> stacks = new java.util.ArrayList<>(count);
        boolean[] seenSlots = new boolean[36];
        int total = 0;
        for (int i = 0; i < count; i++) {
            if (input.remaining() < 2) throw new IllegalArgumentException("Truncated NEXORA inventory stack");
            int slot = Byte.toUnsignedInt(input.get());
            int length = Byte.toUnsignedInt(input.get());
            if (slot >= 36 || seenSlots[slot] || length < 1 || length > 96 || input.remaining() < length + 1) {
                throw new IllegalArgumentException("Invalid NEXORA item identifier length");
            }
            byte[] id = new byte[length];
            input.get(id);
            String itemId = decodeUtf8(id);
            if (!itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                throw new IllegalArgumentException("Invalid NEXORA item identifier");
            }
            int amount = Byte.toUnsignedInt(input.get());
            if (amount < 1 || amount > 64) {
                throw new IllegalArgumentException("Invalid NEXORA item stack size");
            }
            total += amount;
            seenSlots[slot] = true;
            stacks.add(new InventoryStack(slot, itemId, amount));
        }
        if (input.hasRemaining()) throw new IllegalArgumentException("Trailing NEXORA inventory bytes");
        return new InventoryState(owner, java.util.Collections.unmodifiableList(stacks), total);
    }

    static byte[] encodeInventoryAction(InventoryAction action, int sourceSlot, int targetSlot) {
        if (action == null || sourceSlot < 0 || sourceSlot >= 36
                || targetSlot < 0 || targetSlot >= 36) {
            throw new IllegalArgumentException("Invalid NEXORA inventory action");
        }
        ByteBuffer output = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN);
        output.put(MAGIC).put((byte) MAJOR_VERSION).put((byte) MINOR_VERSION)
                .put((byte) TYPE_INVENTORY_ACTION).put((byte) action.wireId)
                .put((byte) sourceSlot).put((byte) targetSlot);
        return output.array();
    }

    static InventoryActionResult decodeInventoryActionResult(byte[] bytes) {
        ByteBuffer input = checkedHeader(bytes, TYPE_INVENTORY_ACTION_RESULT);
        if (input.remaining() != 2) throw new IllegalArgumentException("Malformed inventory action result");
        int action = Byte.toUnsignedInt(input.get());
        int result = Byte.toUnsignedInt(input.get());
        return new InventoryActionResult(InventoryAction.fromWire(action),
                InventoryActionStatus.fromWire(result));
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
        if (major != MAJOR_VERSION || minor != MINOR_VERSION) {
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

    record RemotePlayerState(UUID playerId, String playerName, int skinIndex, double x, double y, double z, double yaw) {
    }

    enum InventoryAction {
        MOVE_OR_SWAP(1), DROP_ONE(2), DROP_STACK(3), USE(4);
        final int wireId;
        InventoryAction(int wireId) { this.wireId = wireId; }
        static InventoryAction fromWire(int value) {
            for (InventoryAction action : values()) if (action.wireId == value) return action;
            throw new IllegalArgumentException("Unknown inventory action");
        }
    }

    enum InventoryActionStatus {
        OK(0), INVALID_SLOT(1), EMPTY_SLOT(2), NOT_USABLE(3);
        final int wireId;
        InventoryActionStatus(int wireId) { this.wireId = wireId; }
        static InventoryActionStatus fromWire(int value) {
            for (InventoryActionStatus status : values()) if (status.wireId == value) return status;
            throw new IllegalArgumentException("Unknown inventory action status");
        }
    }

    record InventoryActionResult(InventoryAction action, InventoryActionStatus status) {
    }

    record InventoryStack(int slotIndex, String itemId, int count) {
    }

    record InventoryState(UUID playerId, java.util.List<InventoryStack> stacks, int totalItems) {
    }

    record PlayerPosition(double x, double y, double z) {
    }

    record WorldInfo(String worldId, int minY, int maxYExclusive,
                     int spawnX, int spawnY, int spawnZ,
                     int tickRate, int loadedChunks) {
    }
}
