package ru.nexora.runtime;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.codec.v2225.Bedrock_v2225;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Protocol-2225 StartGame packet serializer for the minimal standalone NEXORA world.
 *
 * <p>The serializer emits the protocol-2225 StartGame fields in schema order.
 * The current server sends an empty player-property NBT compound, empty block
 * properties. Item definitions are omitted by the pinned protocol serializer. Non-empty player properties are rejected
 * until their NBT schema is implemented.</p>
 */
public final class BedrockStartGamePacketCodec {
    public static final int PACKET_ID = 11;
    private static final int MAX_STRING_BYTES = 4096;
    private static final int MAX_PROPERTY_ENTRIES = 4096;

    public record IntProperty(int index, int value) {
        public IntProperty {
            if (index < 0) throw new IllegalArgumentException("Property index must not be negative");
        }
    }

    public record FloatProperty(int index, float value) {
        public FloatProperty {
            if (index < 0) throw new IllegalArgumentException("Property index must not be negative");
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Property value must be finite");
        }
    }

    /** In-memory player properties; only the empty protocol-2225 NBT form is currently serialized. */
    public record PlayerPropertyData(List<IntProperty> integers, List<FloatProperty> floats) {
        public PlayerPropertyData {
            integers = List.copyOf(Objects.requireNonNull(integers, "integers"));
            floats = List.copyOf(Objects.requireNonNull(floats, "floats"));
            if (integers.size() > MAX_PROPERTY_ENTRIES || floats.size() > MAX_PROPERTY_ENTRIES) {
                throw new IllegalArgumentException("Too many player property entries");
            }
            integers.forEach(value -> Objects.requireNonNull(value, "integer property"));
            floats.forEach(value -> Objects.requireNonNull(value, "float property"));
        }

        public static PlayerPropertyData empty() {
            return new PlayerPropertyData(List.of(), List.of());
        }
    }

    public record Telemetry(String serverId, String scenarioId, String worldId, String ownerId) {
        public Telemetry {
            requireString(serverId, "serverId", MAX_STRING_BYTES);
            requireString(scenarioId, "scenarioId", MAX_STRING_BYTES);
            requireString(worldId, "worldId", MAX_STRING_BYTES);
            requireString(ownerId, "ownerId", MAX_STRING_BYTES);
        }

        public static Telemetry empty() {
            return new Telemetry("", "", "", "");
        }
    }

    public record Settings(
            long entityUniqueId,
            long runtimeEntityId,
            int gameType,
            BedrockStartGamePrimitivesCodec.Vec3 position,
            BedrockStartGamePrimitivesCodec.Vec2 rotation,
            BedrockLevelSettingsCodec.Settings levelSettings,
            String levelId,
            String levelName,
            String templateContentIdentity,
            boolean trial,
            BedrockStartGamePrimitivesCodec.SyncedPlayerMovementSettings movementSettings,
            long levelCurrentTime,
            int enchantmentSeed,
            String multiplayerCorrelationId,
            boolean itemStackNetManagerEnabled,
            String serverVersion,
            PlayerPropertyData playerPropertyData,
            long serverBlockTypeRegistryChecksum,
            long worldTemplateMostSignificantBits,
            long worldTemplateLeastSignificantBits,
            boolean clientSideGenerationEnabled,
            boolean blockNetworkIdsAreHashes,
            boolean serverAuthSoundEnabled,
            Telemetry telemetry
    ) {
        public Settings {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(rotation, "rotation");
            Objects.requireNonNull(levelSettings, "levelSettings");
            requireString(levelId, "levelId", MAX_STRING_BYTES);
            requireString(levelName, "levelName", MAX_STRING_BYTES);
            requireString(templateContentIdentity, "templateContentIdentity", MAX_STRING_BYTES);
            Objects.requireNonNull(movementSettings, "movementSettings");
            requireString(multiplayerCorrelationId, "multiplayerCorrelationId", 128);
            requireString(serverVersion, "serverVersion", 128);
            Objects.requireNonNull(playerPropertyData, "playerPropertyData");
            Objects.requireNonNull(telemetry, "telemetry");
            if (gameType < -1 || gameType > 6) {
                throw new IllegalArgumentException("Unsupported GameType value");
            }
        }
    }

    private BedrockStartGamePacketCodec() {
    }

    /** Encodes the StartGame payload, excluding the packet header and batch length. */
    public static ByteBuffer encodePayload(Settings settings) {
        Objects.requireNonNull(settings, "settings");
        ByteBuffer out = ByteBuffer.allocate(128 * 1024);

        // 0-5: identity, game mode, transform, and the complete nested LevelSettings.
        BedrockBinary.writeVarInt64(out, settings.entityUniqueId());
        BedrockBinary.writeVarUInt64(out, settings.runtimeEntityId());
        BedrockBinary.writeVarInt32(out, settings.gameType());
        BedrockStartGamePrimitivesCodec.writeVec3(out, settings.position());
        BedrockStartGamePrimitivesCodec.writeVec2(out, settings.rotation());
        ByteBuffer levelSettings = BedrockLevelSettingsCodec.encodeMinimalSurvivalFlat(
                settings.levelSettings());
        out.put(levelSettings);

        // 6-10: world identity and movement configuration.
        BedrockBinary.writeString(out, settings.levelId(), MAX_STRING_BYTES);
        BedrockBinary.writeString(out, settings.levelName(), MAX_STRING_BYTES);
        BedrockBinary.writeString(out, settings.templateContentIdentity(), MAX_STRING_BYTES);
        writeBoolean(out, settings.trial());
        BedrockStartGamePrimitivesCodec.writeMovementSettings(out, settings.movementSettings());

        // 11-16: clock, enchantment seed, empty block properties, and server metadata.
        BedrockBinary.writeLittleEndianInt64(out, settings.levelCurrentTime());
        BedrockBinary.writeVarInt32(out, settings.enchantmentSeed());
        BedrockBinary.writeVarUInt32(out, 0); // BlockPropertyData[]
        BedrockBinary.writeString(out, settings.multiplayerCorrelationId(), 128);
        writeBoolean(out, settings.itemStackNetManagerEnabled());
        BedrockBinary.writeString(out, settings.serverVersion(), 128);

        // 17: PlayerPropertyData is an NBT compound, not a pair of varint arrays.
        writePlayerPropertyData(out, settings.playerPropertyData());

        // 18-22: registry/template identifiers and network permissions.
        BedrockBinary.writeLittleEndianInt64(out, settings.serverBlockTypeRegistryChecksum());
        BedrockBinary.writeLittleEndianInt64(out, settings.worldTemplateMostSignificantBits());
        BedrockBinary.writeLittleEndianInt64(out, settings.worldTemplateLeastSignificantBits());
        writeBoolean(out, settings.clientSideGenerationEnabled());
        writeBoolean(out, settings.blockNetworkIdsAreHashes());
        writeBoolean(out, false); // tickDeathSystemsEnabled
        writeBoolean(out, settings.serverAuthSoundEnabled()); // NetworkPermissions.serverAuthSounds

        // Optional server-join information is intentionally absent for this minimal server.
        writeBoolean(out, false);

        // 24: protocol-2225 ServerTelemetryData (server, scenario, world, owner IDs).
        BedrockBinary.writeString(out, settings.telemetry().serverId(), MAX_STRING_BYTES);
        BedrockBinary.writeString(out, settings.telemetry().scenarioId(), MAX_STRING_BYTES);
        BedrockBinary.writeString(out, settings.telemetry().worldId(), MAX_STRING_BYTES);
        BedrockBinary.writeString(out, settings.telemetry().ownerId(), MAX_STRING_BYTES);

        out.flip();
        return out.asReadOnlyBuffer();
    }

    /** Builds packet 11 for insertion into the normal Bedrock packet batch. */
    public static BedrockGamePacket encodePacket(Settings settings) {
        ByteBuffer payload = encodePayload(settings);
        byte[] bytes = new byte[payload.remaining()];
        payload.get(bytes);
        return new BedrockGamePacket(new BedrockPacketHeader(PACKET_ID, 0, 0), bytes);
    }

    private static void writePlayerPropertyData(ByteBuffer out, PlayerPropertyData data) {
        if (!data.integers().isEmpty() || !data.floats().isEmpty()) {
            throw new IllegalArgumentException(
                    "Non-empty Bedrock 1.26.60 player-property NBT is not supported yet");
        }
        ByteBuf nbt = Unpooled.buffer();
        try {
            Bedrock_v2225.CODEC.createHelper().writeTag(nbt, NbtMap.EMPTY);
            byte[] encoded = new byte[nbt.readableBytes()];
            nbt.readBytes(encoded);
            out.put(encoded);
        } finally {
            nbt.release();
        }
    }

    private static void writeBoolean(ByteBuffer out, boolean value) {
        out.put((byte) (value ? 1 : 0));
    }

    private static void requireString(String value, String name, int maxBytes) {
        Objects.requireNonNull(value, name);
        if (value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(name + " exceeds the byte limit");
        }
    }
}
