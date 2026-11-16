package ru.nexora.runtime;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChannelFactory;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChildChannel;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetHTTPServerSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.PongData;
import org.cloudburstmc.netty.util.nethernet.OperatorIdentity;
import org.cloudburstmc.netty.util.nethernet.PlayerInfo;
import org.cloudburstmc.netty.util.nethernet.TokenTrust;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Production Bedrock 1.26.60 ingress using Cloudburst's NetherNet transport.
 * The signaling layer validates Minecraft-issued identity assertions before
 * an authenticated child channel is handed to the Bedrock login router.
 */
public final class CloudburstNetherNetBedrockModule implements RuntimeModule {
    private final BedrockListenerSettings settings;
    private final BedrockWorldSessionPipeline sessions;
    private final String worldId;
    private final Consumer<String> log;

    private EventLoopGroup eventLoops;
    private NetherNetHTTPServerSignaling signaling;
    private Channel serverChannel;
    private boolean started;
    private boolean closed;

    public CloudburstNetherNetBedrockModule(
            BedrockListenerSettings settings,
            BedrockWorldSessionPipeline sessions,
            String worldId,
            Consumer<String> log
    ) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.worldId = Objects.requireNonNull(worldId, "worldId");
        this.log = Objects.requireNonNull(log, "log");
        if (!settings.enabled()) {
            throw new IllegalArgumentException("Bedrock NetherNet listener settings are disabled");
        }
        if (worldId.isBlank()) {
            throw new IllegalArgumentException("World ID must not be blank");
        }
    }

    @Override
    public String id() {
        return "bedrock-nethernet";
    }

    @Override
    public synchronized void start() throws Exception {
        if (closed || started) {
            throw new IllegalStateException("Bedrock NetherNet module cannot be started in its current state");
        }

        try {
            OperatorIdentity identity = OperatorIdentity.fromPemOrCreate(
                    settings.identityFile().toFile(), "NEXORA");
            PongData advertisement = new PongData.Builder()
                    .setServerName("NEXORA")
                    .setProtocol(BedrockProtocolTarget.BEDROCK_1_26_60_PROTOCOL)
                    .setVersion(BedrockProtocolTarget.BEDROCK_1_26_60_VERSION)
                    .setLevelName(worldId)
                    .setGameType(0)
                    .setPlayerCount(0)
                    .setMaxPlayerCount(ServerSettings.DEFAULT_MAX_PLAYERS)
                    .setOnlineAuth(true)
                    .setSelfSignedAuth(false)
                    .build();

            NetherNetHTTPServerSignaling.Builder signalingBuilder =
                    new NetherNetHTTPServerSignaling.Builder()
                            .setIdentity(identity)
                            .setTokenTrust(TokenTrust.MINECRAFT_AUTH)
                            .setMotd(advertisement)
                            .setIceOnLocalPort(settings.separateIcePort() == 0);
            if (settings.advertisedAddresses().isEmpty()) {
                // Try independent public STUN services. Some game hosts filter one provider.
                signalingBuilder.setStunServers(List.of(
                        "stun:stun.cloudflare.com:3478",
                        "stun:stun.l.google.com:19302"));
                log.accept("NetherNet ICE public-address discovery: Cloudflare STUN + Google STUN");
            } else {
                // For providers with blocked outbound STUN, announce their public UDP endpoint directly.
                signalingBuilder.setAdvertisedAddresses(settings.advertisedAddresses());
                log.accept("NetherNet ICE using configured public endpoint(s): "
                        + String.join(", ", settings.advertisedAddresses()));
            }
            signaling = signalingBuilder.build();

            eventLoops = new NioEventLoopGroup();
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(eventLoops)
                    .channelFactory(NetherNetChannelFactory.server(signaling))
                    .childHandler(new ChannelInitializer<NetherNetChildChannel>() {
                        @Override
                        protected void initChannel(NetherNetChildChannel channel) {
                            PlayerInfo player = channel.attr(NetherNetChildChannel.PLAYER_INFO).get();
                            if (player == null) {
                                log.accept("Rejected NetherNet connection without a verified Minecraft identity.");
                                channel.close();
                                return;
                            }
                            try {
                                CloudburstNetherNetTransportConnection connection =
                                        new CloudburstNetherNetTransportConnection(channel, player);
                                channel.pipeline().addLast(connection);
                                sessions.router().accept(connection);
                            } catch (RuntimeException invalidIdentity) {
                                log.accept("Rejected NetherNet connection with invalid identity metadata.");
                                channel.close();
                            }
                        }
                    });
            if (settings.separateIcePort() != 0) {
                bootstrap.option(NetherChannelOption.NETHER_SERVER_ICE_ADDRESS,
                        settings.mediaBindAddress());
            }

            serverChannel = bootstrap.bind(settings.signalingBindAddress()).sync().channel();
            started = true;
            log.accept("Bedrock 1.26.60 NetherNet active: TCP signaling "
                    + settings.signalingBindAddress() + ", UDP media " + settings.mediaBindAddress()
                    + ", identity " + settings.identityFile().toAbsolutePath().normalize());
        } catch (Throwable failure) {
            try {
                shutdownTransport();
            } catch (Throwable cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            try {
                sessions.close();
            } catch (Throwable cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            closed = true;
            if (failure instanceof Exception exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Bedrock NetherNet startup failed", failure);
        }
    }

    @Override
    public synchronized void stop() {
        if (closed) {
            return;
        }
        started = false;
        shutdownTransport();
        sessions.close();
        closed = true;
    }

    private void shutdownTransport() {
        Channel currentChannel = serverChannel;
        serverChannel = null;
        if (currentChannel != null) {
            currentChannel.close().syncUninterruptibly();
        }
        NetherNetHTTPServerSignaling currentSignaling = signaling;
        signaling = null;
        if (currentSignaling != null) {
            currentSignaling.close();
        }
        EventLoopGroup currentLoops = eventLoops;
        eventLoops = null;
        if (currentLoops != null) {
            currentLoops.shutdownGracefully().syncUninterruptibly();
        }
    }
}
