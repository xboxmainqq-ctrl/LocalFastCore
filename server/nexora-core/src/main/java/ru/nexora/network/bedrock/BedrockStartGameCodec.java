package ru.nexora.network.bedrock;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.List;
import java.util.UUID;

/**
 * Minimal vanilla StartGame packet encoder for the Bedrock protocol 2225 layout.
 *
 * This intentionally sends no custom blocks, game rules, experiments, NBT actor
 * properties or template metadata. Those systems are added by later world stages.
 */
public final class BedrockStartGameCodec {
    public static final int START_GAME_PACKET_ID = 0x0b;

    private BedrockStartGameCodec() {
    }

    public static BedrockPacket create(
            long entityId, String worldName, String gameVersion, String baseGameVersion,
            float spawnX, float spawnY, float spawnZ
    ) {
        return create(entityId, worldName, gameVersion, baseGameVersion,
                0L, 0, 64, 0, spawnX, spawnY, spawnZ);
    }

    public static BedrockPacket create(
            long entityId,
            String worldName,
            String gameVersion,
            String baseGameVersion,
            long worldSeed,
            int worldSpawnX,
            int worldSpawnY,
            int worldSpawnZ,
            float spawnX,
            float spawnY,
            float spawnZ
    ) {
        if (entityId < 1) throw new IllegalArgumentException("entityId must be positive");
        requireText(worldName, "worldName");
        requireText(gameVersion, "gameVersion");
        requireText(baseGameVersion, "baseGameVersion");

        Writer out = new Writer();
        out.varInt64(entityId);             // EntityUniqueID
        out.varUInt64(entityId);            // EntityRuntimeID
        out.varInt32(0);                    // PlayerGameMode: survival
        out.float32(spawnX);
        out.float32(spawnY);
        out.float32(spawnZ);
        out.float32(0);                     // Pitch
        out.float32(0);                     // Yaw
        out.int64(worldSeed);              // WorldSeed
        out.int16(0);                       // SpawnBiomeType: default
        out.string("");                     // UserDefinedBiomeName
        out.varInt32(0);                    // Dimension: overworld
        out.varInt32(1);                    // Generator: infinite
        out.varInt32(0);                    // WorldGameMode: survival
        out.bool(false);                    // Hardcore
        out.varInt32(1);                    // Difficulty: easy
        out.varInt32(worldSpawnX);
        out.varInt32(worldSpawnY);
        out.varInt32(worldSpawnZ);
        out.bool(false);                    // AchievementsDisabled
        out.varInt32(0);                    // EditorWorldType
        out.bool(false);                    // CreatedInEditor
        out.bool(false);                    // ExportedFromEditor
        out.byteValue(0);                  // EditorLevelMigrationVersion
        out.varInt32(0);                    // DayCycleLockTime
        out.varUInt32(0);                   // EducationEditionOffer
        out.bool(false);                    // EducationFeaturesEnabled
        out.string("");                     // EducationProductID
        out.float32(0);                     // RainLevel
        out.float32(0);                     // LightningLevel
        out.bool(false);                    // ConfirmedPlatformLockedContent
        out.bool(true);                     // MultiPlayerGame
        out.bool(false);                    // LANBroadcastEnabled
        out.varInt32(0);                    // XBLBroadcastMode
        out.varInt32(0);                    // PlatformBroadcastMode
        out.bool(true);                     // CommandsEnabled
        out.bool(false);                    // TexturePackRequired
        out.varUInt32(0);                   // GameRules: empty
        out.uint32(0);                      // Experiments: empty, fixed uint32 count
        out.bool(false);                    // ExperimentsPreviouslyToggled
        out.bool(false);                    // BonusChestEnabled
        out.bool(false);                    // StartWithMapEnabled
        out.byteValue(1);                  // PlayerPermissions: member
        out.int32(4);                       // ServerChunkTickRadius
        out.bool(false);                    // HasLockedBehaviourPack
        out.bool(false);                    // HasLockedTexturePack
        out.bool(false);                    // FromLockedWorldTemplate
        out.bool(false);                    // MSAGamerTagsOnly
        out.bool(false);                    // FromWorldTemplate
        out.bool(true);                     // WorldTemplateSettingsLocked
        out.bool(false);                    // OnlySpawnV1Villagers
        out.bool(false);                    // PersonaDisabled
        out.bool(false);                    // CustomSkinsDisabled
        out.bool(false);                    // EmoteChatMuted
        out.string(baseGameVersion);
        out.int32(0);                       // LimitedWorldWidth
        out.int32(0);                       // LimitedWorldDepth
        out.bool(false);                    // NewNether
        out.string("");                     // EducationSharedResourceURI.ButtonName
        out.string("");                     // EducationSharedResourceURI.LinkURI
        out.bool(false);                    // ForceExperimentalGameplay absent
        out.byteValue(0);                  // ChatRestrictionLevel: none
        out.bool(false);                    // DisablePlayerInteractions
        out.varInt32(0);                    // ServerEditorConnectionPolicy
        out.bool(false);                    // AllowAnonymousBlockDropsInEditorWorlds
        out.string("NEXORA");               // LevelID
        out.string(worldName);              // WorldName
        out.string("");                     // TemplateContentIdentity
        out.bool(false);                    // Trial
        out.varInt32(0);                    // PlayerMovementSettings.RewindHistorySize
        out.bool(false);                    // ServerAuthoritativeBlockBreaking
        out.int64(0);                       // Time
        out.varInt32(0);                    // EnchantmentSeed
        out.varUInt32(0);                   // Custom blocks: empty
        out.string(UUID.randomUUID().toString()); // MultiPlayerCorrelationID
        out.bool(true);                     // ServerAuthoritativeInventory
        out.string(gameVersion);
        out.bytes(new byte[] {0x0a, 0, 0, 0}); // Empty network-little-endian NBT compound
        out.uint64(0);                      // ServerBlockStateChecksum
        out.bytes(new byte[16]);            // Empty WorldTemplateID
        out.bool(false);                    // ClientSideGeneration
        out.bool(true);                     // UseBlockNetworkIDHashes: block IDs are FNV-1a state hashes
        out.bool(false);                    // ServerAuthoritativeSound
        out.bool(false);                    // ServerJoinInformation absent
        out.string("");                     // ServerID
        out.string("");                     // ScenarioID
        out.string("");                     // WorldID
        out.string("");                     // OwnerID

        return new BedrockPacket(START_GAME_PACKET_ID, 0, 0, out.toByteArray());
    }

    /** Builds initial world metadata after StartGame; chunk data is sent by a later stage. */
    public static List<BedrockPacket> createInitialWorldPackets(
            long entityId, String worldName, String gameVersion, String baseGameVersion,
            float spawnX, float spawnY, float spawnZ
    ) {
        return createInitialWorldPackets(entityId, worldName, gameVersion, baseGameVersion,
                0L, 0, 64, 0, spawnX, spawnY, spawnZ);
    }

    public static List<BedrockPacket> createInitialWorldPackets(
            long entityId, String worldName, String gameVersion, String baseGameVersion,
            long worldSeed, int spawnBlockX, int spawnBlockY, int spawnBlockZ,
            float spawnX, float spawnY, float spawnZ
    ) {
        return List.of(
                create(entityId, worldName, gameVersion, baseGameVersion, worldSeed,
                        spawnBlockX, spawnBlockY, spawnBlockZ, spawnX, spawnY, spawnZ),
                packet(0x0a, varIntPayload(0)),
                packet(0x3c, varUIntPayload(1)),
                packet(0x3b, new byte[] {1}),
                packet(0x2b, setSpawnPositionPayload(spawnBlockX, spawnBlockY, spawnBlockZ)),
                packet(0x3e, varIntPayload(0)),
                packet(0x79, chunkPublisherPayload(spawnBlockX, (int) Math.floor(spawnY), spawnBlockZ))
        );
    }

    private static BedrockPacket packet(int id, byte[] payload) {
        return new BedrockPacket(id, 0, 0, payload);
    }

    private static byte[] varIntPayload(int value) {
        Writer out = new Writer(); out.varInt32(value); return out.toByteArray();
    }

    private static byte[] varUIntPayload(long value) {
        Writer out = new Writer(); out.varUInt32(value); return out.toByteArray();
    }

    public static BedrockPacket createChunkPublisherUpdate(int x, int y, int z) {
        return packet(0x79, chunkPublisherPayload(x, y, z));
    }

    private static byte[] setSpawnPositionPayload(int x, int y, int z) {
        Writer out = new Writer();
        out.varInt32(1);
        out.varInt32(x); out.varInt32(y); out.varInt32(z);
        out.varInt32(0);
        out.varInt32(Integer.MIN_VALUE);
        out.varInt32(Integer.MIN_VALUE);
        out.varInt32(Integer.MIN_VALUE);
        return out.toByteArray();
    }

    private static byte[] chunkPublisherPayload(int x, int y, int z) {
        Writer out = new Writer();
        out.varInt32(x); out.varInt32(y); out.varInt32(z);
        out.varUInt32(128);
        out.uint32(0);
        return out.toByteArray();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }

    private static final class Writer {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream(512);

        void byteValue(int value) {
            output.write(value & 0xff);
        }

        void bool(boolean value) {
            byteValue(value ? 1 : 0);
        }

        void int16(int value) {
            output.write(value & 0xff);
            output.write((value >>> 8) & 0xff);
        }

        void int32(int value) {
            output.write(value & 0xff);
            output.write((value >>> 8) & 0xff);
            output.write((value >>> 16) & 0xff);
            output.write((value >>> 24) & 0xff);
        }

        void uint32(long value) {
            int32((int) value);
        }

        void int64(long value) {
            int32((int) value);
            int32((int) (value >>> 32));
        }

        void uint64(long value) {
            int64(value);
        }

        void float32(float value) {
            int32(Float.floatToRawIntBits(value));
        }

        void varInt32(int value) {
            int encoded = (value << 1) ^ (value >> 31);
            varUInt32(Integer.toUnsignedLong(encoded));
        }

        void varInt64(long value) {
            long encoded = (value << 1) ^ (value >> 63);
            varUInt64(encoded);
        }

        void varUInt32(long value) {
            if (value < 0 || value > 0xffff_ffffL) {
                throw new IllegalArgumentException("VarUInt32 out of range");
            }
            do {
                int next = (int) (value & 0x7f);
                value >>>= 7;
                if (value != 0) next |= 0x80;
                byteValue(next);
            } while (value != 0);
        }

        void varUInt64(long value) {
            do {
                int next = (int) (value & 0x7f);
                value >>>= 7;
                if (value != 0) next |= 0x80;
                byteValue(next);
            } while (value != 0);
        }

        void string(String value) {
            byte[] encoded = Objects.requireNonNull(value, "string").getBytes(StandardCharsets.UTF_8);
            varUInt32(encoded.length);
            bytes(encoded);
        }

        void bytes(byte[] value) {
            output.writeBytes(value);
        }

        byte[] toByteArray() {
            return output.toByteArray();
        }
    }
}
