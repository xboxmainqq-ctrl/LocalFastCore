package ru.nexora.network.nethernet;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChannelFactory;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChildChannel;
import org.cloudburstmc.netty.channel.nethernet.NetherNetPacket;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetHTTPServerSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.PongData;
import org.cloudburstmc.netty.util.nethernet.Identity;
import org.cloudburstmc.netty.util.nethernet.OperatorIdentity;
import org.cloudburstmc.netty.util.nethernet.PlayerInfo;
import org.cloudburstmc.netty.util.nethernet.TokenTrust;
import org.jose4j.jwt.JwtClaims;
import ru.nexora.network.bedrock.BedrockSessionManager;
import tel.schich.libdatachannel.PeerConnectionConfiguration;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

/**
 * Production NetherNet transport backed by CloudburstMC's Netty transport.
 *
 * Cloudburst owns HTTP signaling, identity assertion validation, ICE, DTLS, SCTP,
 * and the on-wire NetherNet segmentation. The small adapter below preserves the
 * NEXORA session manager's existing framed-message boundary without putting that
 * private framing on the wire.
 */
public final class CloudburstNetherNetTransport implements AutoCloseable {
    private static final int MAX_SCTP_MESSAGE_BYTES = 256 * 1024;
    private static final int MAX_OUTBOUND_CHANNELS = 16_384;

    private final InetSocketAddress signalingAddress;
    private final String motd;
    private final String worldName;
    private final int maxPlayers;
    private final int udpPortMin;
    private final int udpPortMax;
    private final List<String> stunUrls;
    private final String advertisedHost;
    private final int publicUdpPort;
    private final Path dataDirectory;
    private final BedrockSessionManager sessions;
    private final IntSupplier playerCount;
    private final CapturingMinecraftTokenTrust tokenTrust = new CapturingMinecraftTokenTrust();
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();
    private final Map<ChannelKey, NetherNetMessageFramer.Reassembler> outboundAssemblers =
            new ConcurrentHashMap<>();
    private final AtomicLong bytesSent = new AtomicLong();
    private final AtomicLong bytesReceived = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    private EventLoopGroup eventLoopGroup;
    private NetherNetHTTPServerSignaling signaling;
    private Channel serverChannel;

    public CloudburstNetherNetTransport(
            InetSocketAddress signalingAddress,
            String motd,
            String worldName,
            int maxPlayers,
            int udpPortMin,
            int udpPortMax,
            List<String> stunUrls,
            Map<String, String> environment,
            Path dataDirectory,
            BedrockSessionManager sessions,
            IntSupplier playerCount
    ) {
        this.signalingAddress = Objects.requireNonNull(signalingAddress, "signalingAddress");
        this.motd = requireText(motd, "motd");
        this.worldName = requireText(worldName, "worldName");
        if (maxPlayers < 1) throw new IllegalArgumentException("maxPlayers must be positive");
        if (udpPortMin < 1 || udpPortMax > 65535 || udpPortMin > udpPortMax) {
            throw new IllegalArgumentException("Invalid NetherNet UDP port range");
        }
        this.maxPlayers = maxPlayers;
        this.udpPortMin = udpPortMin;
        this.udpPortMax = udpPortMax;
        this.stunUrls = List.copyOf(Objects.requireNonNull(stunUrls, "stunUrls"));
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.playerCount = Objects.requireNonNull(playerCount, "playerCount");
        Map<String, String> env = Objects.requireNonNull(environment, "environment");
        this.advertisedHost = blankToNull(env.get("NEXORA_ADVERTISED_HOST"));
        this.publicUdpPort = parsePort(env.get("NEXORA_PUBLIC_UDP_PORT"), 0);
        if (this.publicUdpPort > 0 && udpPortMin != udpPortMax) {
            throw new IllegalArgumentException(
                    "NEXORA_PUBLIC_UDP_PORT requires a fixed ICE port: set NEXORA_UDP_PORT_MIN and NEXORA_UDP_PORT_MAX to the same port");
        }
        if (this.publicUdpPort > 0 && this.advertisedHost == null) {
            throw new IllegalArgumentException("NEXORA_PUBLIC_UDP_PORT requires NEXORA_ADVERTISED_HOST");
        }
        if (!this.stunUrls.isEmpty() && this.advertisedHost == null && udpPortMin != udpPortMax) {
            throw new IllegalArgumentException(
                    "Cloudburst STUN discovery requires one fixed ICE UDP port; set NEXORA_UDP_PORT_MIN and NEXORA_UDP_PORT_MAX to the same value");
        }
    }

    public synchronized void start() throws IOException {
        if (closed.get()) throw new IllegalStateException("NetherNet transport is closed");
        if (serverChannel != null) return;

        OperatorIdentity identity;
        try {
            identity = OperatorIdentity.fromPemOrCreate(
                    dataDirectory.resolve("identity").resolve("nethernet-operator.pem").toFile(),
                    System.getenv().getOrDefault("NEXORA_IDENTITY_DOMAIN", "NEXOR"));
        } catch (Exception failure) {
            throw new IOException("Unable to load persistent NetherNet operator identity", failure);
        }

        NetherNetHTTPServerSignaling.Builder signalingBuilder =
                new NetherNetHTTPServerSignaling.Builder()
                        .setIdentity(identity)
                        .setTokenTrust(tokenTrust)
                        .setServeHttp(true)
                        .setIceOnLocalPort(false)
                        .setMotdProvider((host, remoteAddress, client) -> new PongData.Builder()
                                .setServerName(motd)
                                .setProtocol(2225)
                                .setVersion("1.26.62")
                                .setLevelName(worldName)
                                .setGameType(0)
                                .setPlayerCount(Math.min(playerCount.getAsInt(), maxPlayers))
                                .setMaxPlayerCount(maxPlayers)
                                .setOnlineAuth(true)
                                .setSelfSignedAuth(false)
                                .build());

        if (advertisedHost != null) {
            String endpointHost = advertisedHost.contains(":") && !advertisedHost.startsWith("[")
                    ? "[" + advertisedHost + "]" : advertisedHost;
            String endpoint = publicUdpPort > 0
                    ? endpointHost + ":" + publicUdpPort : endpointHost;
            signalingBuilder.setAdvertisedAddresses(List.of(endpoint));
        } else if (!stunUrls.isEmpty()) {
            signalingBuilder.setStunServers(stunUrls);
        }
        signaling = signalingBuilder.build();

        PeerConnectionConfiguration peerConfig = PeerConnectionConfiguration.DEFAULT
                .withPortRangeBegin(udpPortMin)
                .withPortRangeEnd(udpPortMax);

        eventLoopGroup = new NioEventLoopGroup(Math.max(2,
                Math.min(4, Runtime.getRuntime().availableProcessors())));
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(eventLoopGroup)
                .channelFactory(NetherNetChannelFactory.server(signaling))
                .option(ChannelOption.SO_BACKLOG, 128)
                .option(NetherChannelOption.NETHER_PEER_CONNECTION_CONFIG, peerConfig)
                .option(NetherChannelOption.NETHER_SERVER_RTC_HANDSHAKE_TIMEOUT_SECONDS, 30)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel channel) {
                        if (!(channel instanceof NetherNetChildChannel child)) {
                            channel.close();
                            return;
                        }
                        PlayerInfo player = child.attr(NetherNetChildChannel.PLAYER_INFO).get();
                        if (player == null || player.networkId() == null || player.networkId().isBlank()) {
                            child.close();
                            return;
                        }
                        String networkId = player.networkId();
                        AtomicBoolean admitted = new AtomicBoolean();
                        child.closeFuture().addListener(ignored -> {
                            if (admitted.get()) {
                                channels.remove(networkId, child);
                                removeOutboundAssemblers(networkId);
                                sessions.onDisconnected(networkId);
                            }
                        });
                        child.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                            private boolean reliable = true;

                            @Override
                            public void channelActive(ChannelHandlerContext context) throws Exception {
                                try {
                                    NetherNetClientIdentity clientIdentity = toClientIdentity(player);
                                    if (channels.putIfAbsent(networkId, child) != null) {
                                        throw new SecurityException("NetworkID already has an active transport");
                                    }
                                    admitted.set(true);
                                    sessions.onIdentity(networkId, clientIdentity);
                                    super.channelActive(context);
                                } catch (Exception failure) {
                                    child.close();
                                    throw failure;
                                }
                            }

                            @Override
                            public void userEventTriggered(ChannelHandlerContext context, Object event)
                                    throws Exception {
                                if (event instanceof NetherNetPacket.Delivery delivery) {
                                    reliable = delivery.reliable();
                                }
                                super.userEventTriggered(context, event);
                            }

                            @Override
                            protected void channelRead0(ChannelHandlerContext context, ByteBuf message) {
                                byte[] payload = new byte[message.readableBytes()];
                                message.getBytes(message.readerIndex(), payload);
                                String label = reliable ? "ReliableDataChannel" : "UnreliableDataChannel";
                                for (byte[] frame : NetherNetMessageFramer.fragment(
                                        payload, MAX_SCTP_MESSAGE_BYTES, reliable)) {
                                    sessions.onFrame(networkId, label, frame);
                                }
                                bytesReceived.addAndGet(payload.length);
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
                                System.err.println("NEXORA: NetherNet peer " + networkId
                                        + " failed: " + cause.getMessage());
                                child.close();
                            }
                        });
                    }
                });

        if (udpPortMin == udpPortMax) {
            bootstrap.option(NetherChannelOption.NETHER_SERVER_ICE_ADDRESS,
                    new InetSocketAddress("0.0.0.0", udpPortMin));
        }
        ChannelFuture bind = bootstrap.bind(signalingAddress).syncUninterruptibly();
        if (!bind.isSuccess()) {
            close();
            throw new IOException("Unable to bind Cloudburst NetherNet signaling at "
                    + signalingAddress, bind.cause());
        }
        serverChannel = bind.channel();
    }

    public void sendFrame(String networkId, String channelLabel, byte[] frame) {
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(channelLabel, "channelLabel");
        Objects.requireNonNull(frame, "frame");
        boolean reliable;
        if ("ReliableDataChannel".equals(channelLabel)) {
            reliable = true;
        } else if ("UnreliableDataChannel".equals(channelLabel)) {
            reliable = false;
        } else {
            throw new IllegalArgumentException("Unknown NetherNet channel: " + channelLabel);
        }

        ChannelKey key = new ChannelKey(networkId, channelLabel);
        NetherNetMessageFramer.Reassembler assembler = outboundAssembler(key);
        var complete = assembler.accept(frame, reliable);
        if (complete.isEmpty()) return;

        Channel channel = channels.get(networkId);
        if (channel == null || !channel.isActive()) {
            throw new IllegalStateException("NetherNet peer is not active: " + networkId);
        }
        byte[] payload = complete.orElseThrow();
        ByteBuf buffer = Unpooled.wrappedBuffer(payload);
        Object outboundMessage = reliable ? buffer : new NetherNetPacket(buffer, false);
        channel.writeAndFlush(outboundMessage).addListener(future -> {
            if (future.isSuccess()) {
                bytesSent.addAndGet(payload.length);
            } else {
                System.err.println("NEXORA: NetherNet send failed for " + networkId
                        + ": " + future.cause());
            }
        });
    }

    private NetherNetMessageFramer.Reassembler outboundAssembler(ChannelKey key) {
        NetherNetMessageFramer.Reassembler current = outboundAssemblers.get(key);
        if (current != null) return current;
        synchronized (outboundAssemblers) {
            current = outboundAssemblers.get(key);
            if (current != null) return current;
            if (outboundAssemblers.size() >= MAX_OUTBOUND_CHANNELS) {
                throw new IllegalStateException("NetherNet outbound channel limit reached");
            }
            current = new NetherNetMessageFramer.Reassembler();
            outboundAssemblers.put(key, current);
            return current;
        }
    }

    private void removeOutboundAssemblers(String networkId) {
        synchronized (outboundAssemblers) {
            var iterator = outboundAssemblers.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<ChannelKey, NetherNetMessageFramer.Reassembler> entry = iterator.next();
                if (entry.getKey().networkId().equals(networkId)) {
                    entry.getValue().close();
                    iterator.remove();
                }
            }
        }
    }

    private NetherNetClientIdentity toClientIdentity(PlayerInfo player) throws Exception {
        String encodedKey = player.claims().getClaimValueAsString("cpk");
        if (encodedKey == null || encodedKey.isBlank()) {
            throw new SecurityException("Validated NetherNet identity has no cpk");
        }
        byte[] publicKeyDer = Base64.getDecoder().decode(encodedKey);
        String token = tokenTrust.take(encodedKey);
        if (token == null) {
            throw new SecurityException("Validated NetherNet GameServerToken was not retained");
        }
        return new NetherNetClientIdentity(
                token,
                publicKeyDer,
                player.xuid(),
                player.displayName(),
                claim(player.claims(), "mid"),
                claim(player.claims(), "tid"),
                claim(player.claims(), "leguuid"));
    }

    private static String claim(JwtClaims claims, String name) {
        try {
            return claims.getClaimValueAsString(name);
        } catch (Exception ignored) {
            return null;
        }
    }

    public int connectedPeerCount() {
        return channels.size();
    }

    public long bytesSent() {
        return bytesSent.get();
    }

    public long bytesReceived() {
        return bytesReceived.get();
    }

    public boolean isStarted() {
        return serverChannel != null && serverChannel.isActive();
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        Channel current = serverChannel;
        serverChannel = null;
        if (current != null) current.close().syncUninterruptibly();
        NetherNetHTTPServerSignaling currentSignaling = signaling;
        signaling = null;
        if (currentSignaling != null) currentSignaling.close();
        for (NetherNetMessageFramer.Reassembler assembler : outboundAssemblers.values()) {
            assembler.close();
        }
        outboundAssemblers.clear();
        channels.clear();
        EventLoopGroup group = eventLoopGroup;
        eventLoopGroup = null;
        if (group != null) group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
    }

    private static int parsePort(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 0 || value > 65535) throw new NumberFormatException("outside 0..65535");
            return value;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid NEXORA_PUBLIC_UDP_PORT", failure);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private record ChannelKey(String networkId, String channelLabel) {
    }

    private static final class CapturingMinecraftTokenTrust implements TokenTrust {
        private static final long TOKEN_RETENTION_NANOS = TimeUnit.SECONDS.toNanos(60);
        private static final int MAX_TOKENS_PER_KEY = 8;
        private final Map<String, ConcurrentLinkedDeque<TokenEntry>> pending = new ConcurrentHashMap<>();

        @Override
        public JwtClaims claims(Identity identity) throws Exception {
            JwtClaims claims = TokenTrust.MINECRAFT_AUTH.claims(identity);
            String cpk = claims.getClaimValueAsString("cpk");
            if (cpk == null || cpk.isBlank()) {
                throw new SecurityException("Minecraft GameServerToken has no cpk");
            }
            ConcurrentLinkedDeque<TokenEntry> queue =
                    pending.computeIfAbsent(cpk, ignored -> new ConcurrentLinkedDeque<>());
            long now = System.nanoTime();
            TokenEntry entry = new TokenEntry(identity.assertion().token(), now);
            queue.addLast(entry);
            while (queue.size() > MAX_TOKENS_PER_KEY) queue.pollFirst();
            purgeExpired(queue, now);
            return claims;
        }

        @Override
        public void prepare() {
            TokenTrust.MINECRAFT_AUTH.prepare();
        }

        String take(String cpk) {
            ConcurrentLinkedDeque<TokenEntry> queue = pending.get(cpk);
            if (queue == null) return null;
            long now = System.nanoTime();
            purgeExpired(queue, now);
            TokenEntry entry = queue.pollFirst();
            if (queue.isEmpty()) pending.remove(cpk, queue);
            return entry == null ? null : entry.token();
        }

        private static void purgeExpired(ConcurrentLinkedDeque<TokenEntry> queue, long now) {
            TokenEntry first;
            while ((first = queue.peekFirst()) != null
                    && now - first.createdAtNanos() > TOKEN_RETENTION_NANOS) {
                queue.pollFirst();
            }
        }
    }

    private record TokenEntry(String token, long createdAtNanos) {
    }
}
