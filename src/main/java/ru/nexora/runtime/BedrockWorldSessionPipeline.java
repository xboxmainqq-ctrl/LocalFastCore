package ru.nexora.runtime;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * Complete, consistently wired protocol-2225 post-login world pipeline.
 * The caller supplies the transport host and passes {@link #router()} each
 * accepted authenticated transport connection.
 */
public final class BedrockWorldSessionPipeline implements AutoCloseable {
    private final BedrockRuntimeBlockRegistryProfile blockProfile;
    private final BedrockSubChunkRequestHandler subChunkHandler;
    private final BedrockWorldStartupHandler worldStartupHandler;
    private final BedrockPostLoginCoordinator postLoginCoordinator;
    private final BedrockInitialTransportRouter router;
    private final AtomicBoolean closed = new AtomicBoolean();

    public BedrockWorldSessionPipeline(
            BedrockNetworkSettings networkSettings,
            World world,
            int dimension,
            int clientRequestSubChunkLimit,
            List<ChunkPosition> initialChunks,
            BedrockRuntimeBlockRegistryProfile blockProfile,
            BiFunction<TransportConnection, BedrockAuthenticatedPlayer,
                    BedrockStartGamePacketCodec.Settings> settingsFactory,
            BedrockInitialTransportRouter.LoginAuthenticator loginAuthenticator,
            BiConsumer<TransportConnection, TransportMessage> otherPlayMessageHandler
    ) {
        Objects.requireNonNull(networkSettings, "networkSettings");
        Objects.requireNonNull(world, "world");
        this.blockProfile = Objects.requireNonNull(blockProfile, "blockProfile");
        Objects.requireNonNull(settingsFactory, "settingsFactory");
        Objects.requireNonNull(loginAuthenticator, "loginAuthenticator");
        Objects.requireNonNull(otherPlayMessageHandler, "otherPlayMessageHandler");

        BedrockSubChunkRequestHandler subChunks = null;
        BedrockWorldStartupHandler startup = null;
        BedrockPostLoginCoordinator postLogin = null;
        BedrockInitialTransportRouter initialRouter = null;
        try {
            subChunks = new BedrockSubChunkRequestHandler(
                    networkSettings, world, ignored -> dimension, blockProfile,
                    otherPlayMessageHandler);
            startup = new BedrockWorldStartupHandler(
                    networkSettings, world, dimension, clientRequestSubChunkLimit,
                    initialChunks, blockProfile, settingsFactory);
            postLogin = new BedrockPostLoginCoordinator(
                    networkSettings, BedrockRuntimeBlockRegistryFile.GAME_VERSION,
                    startup, subChunks);
            initialRouter = new BedrockInitialTransportRouter(
                    networkSettings, loginAuthenticator, postLogin, subChunks);
            postLogin.attachTo(initialRouter);
        } catch (RuntimeException failure) {
            if (initialRouter != null) {
                initialRouter.close();
            }
            if (postLogin != null) {
                postLogin.close();
            }
            throw failure;
        }

        this.subChunkHandler = subChunks;
        this.worldStartupHandler = startup;
        this.postLoginCoordinator = postLogin;
        this.router = initialRouter;
    }

    public BedrockRuntimeBlockRegistryProfile blockProfile() {
        return blockProfile;
    }

    public BedrockSubChunkRequestHandler subChunkHandler() {
        return subChunkHandler;
    }

    public BedrockWorldStartupHandler worldStartupHandler() {
        return worldStartupHandler;
    }

    public BedrockPostLoginCoordinator postLoginCoordinator() {
        return postLoginCoordinator;
    }

    public BedrockInitialTransportRouter router() {
        if (closed.get()) {
            throw new IllegalStateException("World session pipeline is closed");
        }
        return router;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        router.close();
        postLoginCoordinator.close();
    }
}
