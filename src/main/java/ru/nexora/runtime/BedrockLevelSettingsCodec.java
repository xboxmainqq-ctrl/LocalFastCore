package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.util.Objects;

/** Protocol-2225 serializer for all 51 fields of a minimal flat-world LevelSettings object. */
public final class BedrockLevelSettingsCodec {
    private static final int MAX_BASE_GAME_VERSION_BYTES = 64;

    public record Settings(long seed, int dimension,
                           BedrockStartGamePrimitivesCodec.BlockPosition defaultSpawn,
                           int gameDifficulty, int dayCycleStopTime,
                           int serverChunkTickRange, String baseGameVersion) {
        public Settings {
            Objects.requireNonNull(defaultSpawn, "defaultSpawn");
            Objects.requireNonNull(baseGameVersion, "baseGameVersion");
            if (dimension < -1 || dimension > 2) throw new IllegalArgumentException("Unsupported Bedrock dimension");
            if (gameDifficulty < 0 || gameDifficulty > 3) throw new IllegalArgumentException("Game difficulty must be in [0, 3]");
            if (serverChunkTickRange < 0) throw new IllegalArgumentException("Server chunk tick range must not be negative");
            if (baseGameVersion.isBlank() || baseGameVersion.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BASE_GAME_VERSION_BYTES) {
                throw new IllegalArgumentException("Invalid base game version");
            }
        }
    }

    private BedrockLevelSettingsCodec() { }

    /** Encodes all LevelSettings fields in protocol-2225 schema order. */
    public static ByteBuffer encodeMinimalSurvivalFlat(Settings settings) {
        Objects.requireNonNull(settings, "settings");
        ByteBuffer out = ByteBuffer.allocate(1024);

        // 0 Seed; 1 SpawnSettings (default biome, empty custom name, dimension).
        BedrockBinary.writeLittleEndianInt64(out, settings.seed());
        writeInt16LE(out, 0);
        BedrockBinary.writeString(out, "", 256);
        BedrockBinary.writeVarInt32(out, settings.dimension());

        // 2 GeneratorType.Flat; 3 GameType.Survival; 4 Hardcore; 5 Difficulty.
        BedrockBinary.writeVarInt32(out, 2);
        BedrockBinary.writeVarInt32(out, 0);
        bool(out, false);
        BedrockBinary.writeVarInt32(out, settings.gameDifficulty());

        // 6 Spawn; 7 Achievements; 8-11 editor metadata.
        BedrockStartGamePrimitivesCodec.writeBlockPosition(out, settings.defaultSpawn());
        bool(out, true);
        BedrockBinary.writeVarInt32(out, 0);
        bool(out, false);
        bool(out, false);
        out.put((byte) 0);

        // 12-18 time, education, weather and platform-lock flags.
        BedrockBinary.writeVarInt32(out, settings.dayCycleStopTime());
        BedrockBinary.writeVarUInt32(out, 0);
        bool(out, false);
        BedrockBinary.writeString(out, "", 256);
        BedrockBinary.writeLittleEndianFloat(out, 0.0f);
        BedrockBinary.writeLittleEndianFloat(out, 0.0f);
        bool(out, false);

        // 19-24 multiplayer/publication/command/resource-pack settings.
        bool(out, true);
        bool(out, false);
        BedrockBinary.writeVarInt32(out, 0);
        BedrockBinary.writeVarInt32(out, 0);
        bool(out, true);
        bool(out, false);

        // 25 Empty GameRules array; 26 Empty Experiments (fixed uint32 count).
        BedrockBinary.writeVarUInt32(out, 0);
        BedrockBinary.writeLittleEndianInt32(out, 0);
        bool(out, false);

        // 27-40 bonus/map/permissions/locks/player cosmetics.
        bool(out, false);
        bool(out, false);
        out.put((byte) 1);
        BedrockBinary.writeLittleEndianInt32(out, settings.serverChunkTickRange());
        for (int field = 31; field <= 40; field++) bool(out, false);

        // 41-45 version, world limits, nether and education URI.
        BedrockBinary.writeString(out, settings.baseGameVersion(), MAX_BASE_GAME_VERSION_BYTES);
        BedrockBinary.writeLittleEndianInt32(out, 0);
        BedrockBinary.writeLittleEndianInt32(out, 0);
        bool(out, false);
        BedrockBinary.writeString(out, "", 256);
        BedrockBinary.writeString(out, "", 2048);

        // 46 Optional override absent; 47-50 final policy flags/enums.
        bool(out, false);
        out.put((byte) 0);
        bool(out, false);
        BedrockBinary.writeVarInt32(out, 0);
        bool(out, false);

        out.flip();
        return out.asReadOnlyBuffer();
    }

    private static void bool(ByteBuffer out, boolean value) { out.put((byte) (value ? 1 : 0)); }

    private static void writeInt16LE(ByteBuffer out, int value) {
        if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) throw new IllegalArgumentException("int16 out of range");
        out.put((byte) value);
        out.put((byte) (value >>> 8));
    }
}
