package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class BedrockWorldSessionPipelineTest {
    @Test
    void buildsAndClosesProfileConsistentPostLoginPipeline() {
        BedrockNetworkSettings network = new BedrockNetworkSettings(
                0, BedrockNetworkSettings.CompressionAlgorithm.NONE, false, 0, 0.0f);
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(-64, 320));
        world.loadChunk(new GeneratedChunk(new ChunkPosition(0, 0), Map.of()));
        BedrockRuntimeBlockRegistryProfile profile = BedrockRuntimeBlockRegistryProfile.hashing();

        BedrockWorldSessionPipeline pipeline = new BedrockWorldSessionPipeline(
                network, world, 0, -1, List.of(new ChunkPosition(0, 0)), profile,
                (connection, player) -> startSettings(),
                (connection, login, request) -> CompletableFuture.failedFuture(
                        new UnsupportedOperationException("Test authenticator is not invoked")),
                (connection, message) -> { });

        assertSame(profile, pipeline.blockProfile());
        assertNotNull(pipeline.subChunkHandler());
        assertNotNull(pipeline.worldStartupHandler());
        assertNotNull(pipeline.postLoginCoordinator());
        assertNotNull(pipeline.router());

        pipeline.close();
        pipeline.close();
        assertThrows(IllegalStateException.class, pipeline::router);
    }

    private static BedrockStartGamePacketCodec.Settings startSettings() {
        var level = new BedrockLevelSettingsCodec.Settings(
                1L, 0, new BedrockStartGamePrimitivesCodec.BlockPosition(0, 64, 0),
                2, -1, 4, "1.26.60");
        return new BedrockStartGamePacketCodec.Settings(
                1L, 1L, 0,
                new BedrockStartGamePrimitivesCodec.Vec3(0, 65, 0),
                new BedrockStartGamePrimitivesCodec.Vec2(0, 0),
                level, "nexora", "NEXORA", "", false,
                new BedrockStartGamePrimitivesCodec.SyncedPlayerMovementSettings(0, false),
                0L, 0, "test", false, "NEXORA/0.1",
                BedrockStartGamePacketCodec.PlayerPropertyData.empty(),
                0L, 0L, 0L, false, true, false,
                BedrockStartGamePacketCodec.Telemetry.empty());
    }
}
