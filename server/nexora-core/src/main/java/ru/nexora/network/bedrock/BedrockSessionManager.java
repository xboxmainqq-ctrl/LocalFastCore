package ru.nexora.network.bedrock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.nexora.network.nethernet.NetherNetClientIdentity;
import ru.nexora.network.nethernet.NetherNetDataChannelListener;
import ru.nexora.network.nethernet.NetherNetInboundPipeline;
import ru.nexora.network.nethernet.NetherNetMessageFramer;
import ru.nexora.world.FlatWorldGenerator;
import ru.nexora.world.BlockType;
import ru.nexora.world.World;
import ru.nexora.world.WorldChunk;
import ru.nexora.world.WorldCollision;
import ru.nexora.world.PlayerData;
import ru.nexora.world.PlayerDataStorage;

import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Bedrock login stages above NetherNet:
 * RequestNetworkSettings -> NetworkSettings -> Login -> ClientToServerHandshake
 * -> PlayStatus -> ResourcePacksInfo -> ResourcePackStack.
 */
public final class BedrockSessionManager implements NetherNetDataChannelListener, AutoCloseable {
    public static final int LOGIN_FAILED_CLIENT_OLD = 1;
    public static final int LOGIN_FAILED_SERVER_OLD = 2;
    public static final int LOGIN_SUCCESS = 0;
    public static final int DEFAULT_COMPRESSION_THRESHOLD = 256;
    public static final int SCTP_MAX_MESSAGE_BYTES = 262_144;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final int serverProtocol;
    private final String serverVersion;
    private final int compressionThreshold;
    private final World world;
    private final PlayerDataStorage playerStorage;
    private final OutboundFrameSender outbound;
    private final Consumer<PacketEvent> packetSink;
    private final LongSupplier nanoTime;
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final AtomicLong nextEntityId = new AtomicLong(1);
    private final NetherNetInboundPipeline inbound;

    public BedrockSessionManager(
            int serverProtocol,
            OutboundFrameSender outbound,
            Consumer<PacketEvent> packetSink
    ) {
        this(serverProtocol, "1.26.62", DEFAULT_COMPRESSION_THRESHOLD, outbound, packetSink);
    }

    public BedrockSessionManager(
            int serverProtocol,
            String serverVersion,
            OutboundFrameSender outbound,
            Consumer<PacketEvent> packetSink
    ) {
        this(serverProtocol, serverVersion, DEFAULT_COMPRESSION_THRESHOLD, outbound, packetSink);
    }

    public BedrockSessionManager(
            int serverProtocol,
            String serverVersion,
            int compressionThreshold,
            OutboundFrameSender outbound,
            Consumer<PacketEvent> packetSink
    ) {
        this(serverProtocol, serverVersion, compressionThreshold,
                new World("NEXORA World", 0L, new FlatWorldGenerator()), outbound, packetSink);
    }

    public BedrockSessionManager(
            int serverProtocol,
            String serverVersion,
            int compressionThreshold,
            World world,
            OutboundFrameSender outbound,
            Consumer<PacketEvent> packetSink
    ) {
        this(serverProtocol, serverVersion, compressionThreshold, world, null,
                outbound, packetSink, System::nanoTime);
    }

    public BedrockSessionManager(
            int serverProtocol,
            String serverVersion,
            int compressionThreshold,
            World world,
            PlayerDataStorage playerStorage,
            OutboundFrameSender outbound,
            Consumer<PacketEvent> packetSink
    ) {
        this(serverProtocol, serverVersion, compressionThreshold, world, playerStorage,
                outbound, packetSink, System::nanoTime);
    }

    BedrockSessionManager(
            int serverProtocol,
            String serverVersion,
            int compressionThreshold,
            World world,
            OutboundFrameSender outbound,
            Consumer<PacketEvent> packetSink,
            LongSupplier nanoTime
    ) {
        this(serverProtocol, serverVersion, compressionThreshold, world, null,
                outbound, packetSink, nanoTime);
    }

    BedrockSessionManager(
            int serverProtocol,
            String serverVersion,
            int compressionThreshold,
            World world,
            PlayerDataStorage playerStorage,
            OutboundFrameSender outbound,
            Consumer<PacketEvent> packetSink,
            LongSupplier nanoTime
    ) {
        if (serverProtocol < 1) throw new IllegalArgumentException("serverProtocol must be positive");
        if (serverVersion == null || serverVersion.isBlank()) {
            throw new IllegalArgumentException("serverVersion must not be blank");
        }
        if (compressionThreshold < 1 || compressionThreshold > 0xffff) {
            throw new IllegalArgumentException("compressionThreshold must be 1..65535");
        }
        this.serverProtocol = serverProtocol;
        this.serverVersion = serverVersion;
        this.compressionThreshold = compressionThreshold;
        this.world = Objects.requireNonNull(world, "world");
        this.playerStorage = playerStorage;
        this.outbound = Objects.requireNonNull(outbound, "outbound");
        this.packetSink = Objects.requireNonNull(packetSink, "packetSink");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.inbound = new NetherNetInboundPipeline(this::handleCompleteMessage);
    }

    @Override
    public void onIdentity(String networkId, NetherNetClientIdentity identity) {
        SessionState state = sessions.computeIfAbsent(networkId, ignored -> new SessionState());
        synchronized (state) {
            if (state.identity != null && !state.identity.xuid().equals(identity.xuid())) {
                throw new SecurityException("NetherNet identity changed during an active session");
            }
            state.identity = Objects.requireNonNull(identity, "identity");
        }
    }

    @Override
    public void onFrame(String networkId, String channelLabel, byte[] frame) {
        inbound.onFrame(networkId, channelLabel, frame);
    }

    @Override
    public void onDisconnected(String networkId) {
        SessionState state = sessions.get(networkId);
        if (state != null) {
            synchronized (state) {
                try {
                    savePlayer(state);
                } catch (IOException exception) {
                    System.err.println("NEXORA: unable to save disconnected player: " + exception.getMessage());
                }
            }
            sessions.remove(networkId, state);
        }
        inbound.onDisconnected(networkId);
    }

    public void savePlayers() throws IOException {
        IOException failure = null;
        for (SessionState state : sessions.values()) {
            synchronized (state) {
                try {
                    savePlayer(state);
                } catch (IOException exception) {
                    if (failure == null) failure = exception;
                    else failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) throw failure;
    }

    private void savePlayer(SessionState state) throws IOException {
        if (playerStorage == null || !state.loginAuthenticated || !state.positionInitialized
                || state.identity == null) return;
        String displayName = state.identity.displayName() == null ? "unknown" : state.identity.displayName();
        try {
            playerStorage.save(new PlayerData(
                    state.identity.xuid(), displayName, world.name(),
                    state.positionX, state.positionY, state.positionZ,
                    state.yaw, state.pitch, System.currentTimeMillis()));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Authenticated player data cannot be persisted", exception);
        }
    }

    public int activeSessionCount() {
        return sessions.size();
    }

    public long authenticatedPlayerCount() {
        return sessions.values().stream().filter(state -> state.loginAuthenticated).count();
    }

    public long resourcePackReadyCount() {
        return sessions.values().stream().filter(state -> state.resourcePacksCompleted).count();
    }

    public Optional<PlayerPosition> playerPosition(String networkId) {
        SessionState state = sessions.get(networkId);
        if (state == null) return Optional.empty();
        synchronized (state) {
            if (!state.clientInitialized) return Optional.empty();
            return Optional.of(new PlayerPosition(state.positionX, state.positionY, state.positionZ,
                    state.yaw, state.pitch, state.lastInputTick));
        }
    }

    private void handleCompleteMessage(NetherNetInboundPipeline.InboundMessage message) {
        SessionState state = sessions.computeIfAbsent(message.networkId(), ignored -> new SessionState());
        synchronized (state) {
            if (state.rejected) return;

            if (!state.compressionNegotiated) {
                handleNetworkSettingsRequest(message, state);
                return;
            }

            List<BedrockPacket> packets = BedrockBatchCodec.decode(message.payload(), true);
            boolean resourcePacksInfoWasSent = state.resourcePacksInfoSent;
            boolean resourcePackStackWasSent = state.resourcePackStackSent;
            for (BedrockPacket packet : packets) {
                switch (packet.packetId()) {
                    case BedrockLoginPacketParser.LOGIN_PACKET_ID -> handleLogin(message, state, packet);
                    case BedrockBatchCodec.CLIENT_TO_SERVER_HANDSHAKE_PACKET_ID ->
                            handleClientHandshake(message.networkId(), state, packet);
                    case BedrockBatchCodec.RESOURCE_PACK_CLIENT_RESPONSE_PACKET_ID ->
                            handleResourcePackResponse(message.networkId(), state, packet,
                                    resourcePacksInfoWasSent, resourcePackStackWasSent);
                    case 0x71 -> handleClientInitialized(message, state, packet);
                    case BedrockPlayerAuthInputCodec.PLAYER_AUTH_INPUT_PACKET_ID ->
                            handlePlayerAuthInput(message.networkId(), state, packet);
                    default -> packetSink.accept(new PacketEvent(
                            message.networkId(), message.channelLabel(), packet));
                }
            }
        }
    }

    private void handleNetworkSettingsRequest(
            NetherNetInboundPipeline.InboundMessage message,
            SessionState state
    ) {
        if (!message.reliable()) {
            throw new IllegalArgumentException("RequestNetworkSettings must arrive on ReliableDataChannel");
        }
        List<BedrockPacket> packets = BedrockBatchCodec.decode(message.payload(), false);
        if (packets.size() != 1) {
            throw new IllegalArgumentException("Initial Bedrock batch must contain only RequestNetworkSettings");
        }
        int clientProtocol = BedrockBatchCodec.readRequestedProtocol(packets.getFirst());
        if (clientProtocol != serverProtocol) {
            int status = clientProtocol < serverProtocol
                    ? LOGIN_FAILED_CLIENT_OLD
                    : LOGIN_FAILED_SERVER_OLD;
            state.rejected = true;
            sendPacket(message.networkId(), BedrockBatchCodec.createPlayStatus(status), false);
            System.out.printf("NEXORA: rejected Bedrock protocol %d; server requires %d%n",
                    clientProtocol, serverProtocol);
            return;
        }

        state.compressionNegotiated = true;
        state.clientProtocol = clientProtocol;
        sendPacket(message.networkId(), BedrockBatchCodec.createNetworkSettings(
                compressionThreshold, BedrockBatchCodec.COMPRESSION_ZLIB), false);
        System.out.printf("NEXORA: negotiated Bedrock protocol %d for %s%n",
                clientProtocol, message.networkId());
    }

    private void handleLogin(
            NetherNetInboundPipeline.InboundMessage message,
            SessionState state,
            BedrockPacket packet
    ) {
        if (!message.reliable()) throw new SecurityException("Login must arrive on ReliableDataChannel");
        if (state.loginAuthenticated) throw new SecurityException("Duplicate Login packet");
        if (state.identity == null) throw new SecurityException("Login has no verified NetherNet identity");

        BedrockLoginPacketParser.LoginRequest login = BedrockLoginPacketParser.parse(packet);
        if (login.authenticationType() != 0) {
            throw new SecurityException("Only authenticated Xbox Live login is supported");
        }
        if (!state.identity.gameServerToken().equals(login.multiplayerToken())) {
            throw new SecurityException("Login GameServerToken does not match the verified NetherNet identity");
        }
        verifyClientDataToken(login.rawClientDataToken(), state.identity);
        state.loginAuthenticated = true;
        state.entityId = nextEntityId.getAndIncrement();
        if (state.entityId < 1) {
            throw new IllegalStateException("NEXORA entity ID space exhausted");
        }

        String displayName = state.identity.displayName() == null
                ? "unknown" : state.identity.displayName();
        if (playerStorage != null) {
            try {
                Optional<PlayerData> saved = playerStorage.load(state.identity.xuid());
                if (saved.isPresent() && saved.get().worldName().equals(world.name())) {
                    PlayerData player = saved.get();
                    if (WorldCollision.canOccupy(world, player.x(), player.y(), player.z())) {
                        state.positionX = player.x();
                        state.positionY = player.y();
                        state.positionZ = player.z();
                        state.yaw = player.yaw();
                        state.pitch = player.pitch();
                        state.positionInitialized = true;
                    } else {
                        System.err.println("NEXORA: saved position for " + displayName
                                + " is obstructed; using the world spawn");
                    }
                }
            } catch (IOException | RuntimeException exception) {
                System.err.println("NEXORA: unable to load player profile for " + displayName
                        + ": " + exception.getMessage());
            }
        }
        System.out.printf("NEXORA: authenticated player %s%n", displayName);
    }

    private void handleClientHandshake(String networkId, SessionState state, BedrockPacket packet) {
        if (!state.loginAuthenticated) {
            throw new SecurityException("ClientToServerHandshake arrived before authenticated Login");
        }
        if (state.clientHandshakeReceived) {
            throw new SecurityException("Duplicate ClientToServerHandshake packet");
        }
        if (packet.payload().length != 0) {
            throw new IllegalArgumentException("ClientToServerHandshake must have an empty payload");
        }

        // NetherNet's DTLS layer provides transport encryption; no Bedrock application cipher is enabled.
        state.clientHandshakeReceived = true;
        state.resourcePacksInfoSent = true;
        sendPacket(networkId, BedrockBatchCodec.createPlayStatus(LOGIN_SUCCESS), true);
        sendPacket(networkId, BedrockBatchCodec.createResourcePacksInfo(), true);
    }

    private void handleResourcePackResponse(
            String networkId,
            SessionState state,
            BedrockPacket packet,
            boolean resourcePacksInfoWasSent,
            boolean resourcePackStackWasSent
    ) {
        if (!state.clientHandshakeReceived || !resourcePacksInfoWasSent) {
            throw new SecurityException("ResourcePackClientResponse arrived before ResourcePacksInfo");
        }
        BedrockBatchCodec.ResourcePackClientResponseData response =
                BedrockBatchCodec.parseResourcePackClientResponse(packet);
        switch (response.response()) {
            case 0 -> throw new SecurityException("Client refused resource-pack negotiation");
            case 1 -> throw new SecurityException("Client requested packs from an empty server pack list");
            case 2 -> {
                if (state.resourcePackStackSent) {
                    throw new SecurityException("Duplicate all-packs-downloaded response");
                }
                state.resourcePackStackSent = true;
                sendPacket(networkId, BedrockBatchCodec.createResourcePackStack(serverVersion), true);
            }
            case 3 -> {
                if (!resourcePackStackWasSent) {
                    throw new SecurityException("Resource-pack stack completed before the server sent its stack");
                }
                state.resourcePacksCompleted = true;
                if (!state.gameStarted) {
                    state.gameStarted = true;
                    if (!state.positionInitialized) {
                        state.positionX = world.spawnX() + 0.5f;
                        state.positionY = world.spawnY();
                        state.positionZ = world.spawnZ() + 0.5f;
                        if (!WorldCollision.canOccupy(world, state.positionX, state.positionY, state.positionZ)) {
                            throw new IllegalStateException("Configured NEXORA world spawn is obstructed");
                        }
                        state.positionInitialized = true;
                    }
                    state.lastMovementNanos = nanoTime.getAsLong();
                    state.movementBudget = 2.0;
                    for (BedrockPacket worldPacket : BedrockStartGameCodec.createInitialWorldPackets(
                            state.entityId,
                            world.name(),
                            serverVersion,
                            serverVersion,
                            world.seed(),
                            world.spawnX(),
                            world.spawnY() - 1,
                            world.spawnZ(),
                            state.positionX,
                            state.positionY,
                            state.positionZ,
                            state.pitch,
                            state.yaw
                    )) {
                        sendPacket(networkId, worldPacket, true);
                    }
                    sendPacket(networkId, BedrockBiomeDefinitionCodec.createPacket(), true);
                    sendInitialChunkArea(networkId, state);
                }
                System.out.printf("NEXORA: resource-pack negotiation completed for %s%n", networkId);
            }
            default -> throw new IllegalArgumentException("Unknown resource-pack response");
        }
    }


    private void handlePlayerAuthInput(String networkId, SessionState state, BedrockPacket packet) {
        if (!state.clientInitialized) {
            throw new SecurityException("PlayerAuthInput arrived before client initialization");
        }
        BedrockPlayerAuthInputCodec.PlayerInput input = BedrockPlayerAuthInputCodec.decode(packet);
        if (state.hasReceivedInput && input.tick() <= state.lastInputTick) return;

        if (Math.abs(input.x()) > 30_000_000 || Math.abs(input.z()) > 30_000_000
                || input.y() < WorldChunk.MIN_Y - 16 || input.y() > WorldChunk.MAX_Y + 16) {
            return;
        }
        long nowNanos = nanoTime.getAsLong();
        double dx = input.x() - state.positionX;
        double dy = input.y() - state.positionY;
        double dz = input.z() - state.positionZ;
        long elapsedNanos = Math.max(0, nowNanos - state.lastMovementNanos);
        double movementBudget = Math.min(2.0,
                state.movementBudget + (Math.min(elapsedNanos, 1_000_000_000L) / 1_000_000_000.0) * 8.0);
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance > movementBudget) {
            return; // Token-bucket speed guard; full movement simulation follows later.
        }
        if (!WorldCollision.canMove(world,
                state.positionX, state.positionY, state.positionZ,
                input.x(), input.y(), input.z())) {
            return; // Reject movement that clips through solid world blocks.
        }
        state.movementBudget = movementBudget - distance;
        state.lastMovementNanos = nowNanos;
        state.hasReceivedInput = true;
        state.positionX = input.x();
        state.positionY = input.y();
        state.positionZ = input.z();
        state.yaw = input.yaw();
        state.pitch = input.pitch();
        state.lastInputTick = input.tick();

        for (BedrockPlayerAuthInputCodec.BlockAction action : input.blockActions()) {
            handleBlockAction(networkId, state, action);
        }

        int chunkX = Math.floorDiv((int) Math.floor(input.x()), 16);
        int chunkZ = Math.floorDiv((int) Math.floor(input.z()), 16);
        if (chunkX != state.lastChunkX || chunkZ != state.lastChunkZ) {
            sendPacket(networkId, BedrockStartGameCodec.createChunkPublisherUpdate(
                    (int) Math.floor(input.x()), (int) Math.floor(input.y()), (int) Math.floor(input.z())), true);
            sendChunkArea(networkId, state, chunkX, chunkZ);
        }
    }

    private void handleBlockAction(
            String networkId,
            SessionState state,
            BedrockPlayerAuthInputCodec.BlockAction action
    ) {
        if (Math.abs((long) action.x()) > 30_000_000
                || Math.abs((long) action.z()) > 30_000_000
                || action.y() < WorldChunk.MIN_Y || action.y() > WorldChunk.MAX_Y
                || (action.action() == 0 && (action.face() < 0 || action.face() > 5))) {
            return;
        }
        if (action.action() == 0) { // START_DESTROY_BLOCK
            if (!isWithinBlockReach(state, action.x(), action.y(), action.z())) return;
            BlockType type = world.getBlock(action.x(), action.y(), action.z());
            if (type == BlockType.AIR || type == BlockType.BEDROCK) return;
            if (!matchesBreakTarget(state.breakTarget, action)) {
                state.breakTarget = new BreakTarget(action.x(), action.y(), action.z(), nanoTime.getAsLong());
            }
            return;
        }

        if (action.action() == 1) { // ABORT_DESTROY_BLOCK
            if (matchesBreakTarget(state, action)) state.breakTarget = null;
            return;
        }

        if (action.action() != 2 && action.action() != 26 && action.action() != 27) return;
        // STOP / PREDICT / CONTINUE_DESTROY_BLOCK
        BreakTarget target = state.breakTarget;
        if (target == null || !matchesBreakTarget(target, action)) return;

        if (!isWithinBlockReach(state, target.x(), target.y(), target.z())) {
            state.breakTarget = null;
            return;
        }
        BlockType current = world.getBlock(target.x(), target.y(), target.z());
        if (current == BlockType.AIR) {
            state.breakTarget = null;
            return;
        }
        long requiredNanos = requiredBreakNanos(current);
        if (requiredNanos == Long.MAX_VALUE
                || nanoTime.getAsLong() - target.startedAtNanos() < requiredNanos) return;

        world.setBlock(target.x(), target.y(), target.z(), BlockType.AIR);
        state.breakTarget = null;
        BedrockPacket update = BedrockBlockUpdateCodec.createPacket(
                target.x(), target.y(), target.z(), BlockType.AIR);
        int changedChunkX = Math.floorDiv(target.x(), 16);
        int changedChunkZ = Math.floorDiv(target.z(), 16);
        long changedChunkKey = ((long) changedChunkX << 32) | (changedChunkZ & 0xffff_ffffL);
        for (var entry : sessions.entrySet()) {
            SessionState recipient = entry.getValue();
            // Only update clients that currently have this chunk in their sent view.
            // The set is concurrent because different session event loops can publish chunks
            // while another player breaks a block.
            if (recipient.clientInitialized && recipient.sentChunkKeys.contains(changedChunkKey)) {
                sendPacket(entry.getKey(), update, true);
            }
        }
    }

    private static boolean matchesBreakTarget(SessionState state, BedrockPlayerAuthInputCodec.BlockAction action) {
        return matchesBreakTarget(state.breakTarget, action);
    }

    private static boolean matchesBreakTarget(BreakTarget target, BedrockPlayerAuthInputCodec.BlockAction action) {
        return target != null && target.x() == action.x() && target.y() == action.y() && target.z() == action.z();
    }

    private static boolean isWithinBlockReach(SessionState state, int x, int y, int z) {
        double dx = state.positionX - (x + 0.5);
        double dy = state.positionY - (y + 0.5);
        double dz = state.positionZ - (z + 0.5);
        return dx * dx + dy * dy + dz * dz <= 36.0;
    }

    private static long requiredBreakNanos(BlockType type) {
        return switch (type) {
            case AIR, BEDROCK -> Long.MAX_VALUE;
            case DIRT, GRASS_BLOCK -> 600_000_000L; // 0.6 seconds
            case STONE -> 7_500_000_000L; // 7.5 seconds without a tool
        };
    }

    private void sendInitialChunkArea(String networkId, SessionState state) {
        int centerX = Math.floorDiv((int) Math.floor(state.positionX), 16);
        int centerZ = Math.floorDiv((int) Math.floor(state.positionZ), 16);
        sendChunkArea(networkId, state, centerX, centerZ);
    }

    private void sendChunkArea(String networkId, SessionState state, int centerX, int centerZ) {
        int radius = 1;
        Set<Long> desiredChunks = new HashSet<>();
        for (int ring = 0; ring <= radius; ring++) {
            for (int offsetZ = -ring; offsetZ <= ring; offsetZ++) {
                for (int offsetX = -ring; offsetX <= ring; offsetX++) {
                    if (Math.max(Math.abs(offsetX), Math.abs(offsetZ)) != ring) continue;
                    int chunkX = centerX + offsetX;
                    int chunkZ = centerZ + offsetZ;
                    long key = ((long) chunkX << 32) | (chunkZ & 0xffff_ffffL);
                    desiredChunks.add(key);
                    if (state.sentChunkKeys.add(key)) {
                        WorldChunk chunk = world.getChunk(chunkX, chunkZ);
                        sendPacket(networkId, BedrockLevelChunkCodec.createPacket(chunk), true);
                    }
                }
            }
        }
        state.sentChunkKeys.retainAll(desiredChunks);
        state.lastChunkX = centerX;
        state.lastChunkZ = centerZ;
    }

    private void handleClientInitialized(
            NetherNetInboundPipeline.InboundMessage message,
            SessionState state,
            BedrockPacket packet
    ) {
        if (!message.reliable()) {
            throw new SecurityException("SetLocalPlayerAsInitialized must arrive on ReliableDataChannel");
        }
        if (!state.gameStarted) {
            throw new SecurityException("SetLocalPlayerAsInitialized arrived before StartGame");
        }
        if (state.clientInitialized) {
            throw new SecurityException("Duplicate SetLocalPlayerAsInitialized packet");
        }
        long runtimeEntityId = readUnsignedVarLong(packet.payload());
        if (runtimeEntityId != state.entityId) {
            throw new SecurityException("SetLocalPlayerAsInitialized runtime ID does not match this player");
        }
        state.clientInitialized = true;
        sendPacket(message.networkId(), BedrockBatchCodec.createPlayStatus(3), true);
    }

    private static long readUnsignedVarLong(byte[] payload) {
        long value = 0;
        int shift = 0;
        for (int i = 0; i < 10; i++) {
            if (i >= payload.length) throw new IllegalArgumentException("Truncated unsigned VarLong");
            int current = Byte.toUnsignedInt(payload[i]);
            if (i == 9 && (current & 0xfe) != 0) {
                throw new IllegalArgumentException("Unsigned VarLong exceeds 64 bits");
            }
            value |= (long) (current & 0x7f) << shift;
            if ((current & 0x80) == 0) {
                if (i + 1 != payload.length) throw new IllegalArgumentException("Trailing bytes in unsigned VarLong");
                if (value < 0) throw new IllegalArgumentException("Runtime entity ID exceeds signed long range");
                return value;
            }
            shift += 7;
        }
        throw new IllegalArgumentException("Unsigned VarLong is too long");
    }

    private void sendPacket(String networkId, BedrockPacket packet, boolean compressionNegotiated) {
        byte[] batch = BedrockBatchCodec.encode(
                List.of(packet), compressionNegotiated, compressionThreshold);
        List<byte[]> frames = NetherNetMessageFramer.fragment(
                batch, SCTP_MAX_MESSAGE_BYTES, true);
        try {
            for (byte[] frame : frames) {
                outbound.sendFrame(networkId, "ReliableDataChannel", frame);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to send Bedrock login handshake packet", exception);
        }
    }

    private static void verifyClientDataToken(String token, NetherNetClientIdentity identity) {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || parts[2].isEmpty()) {
                throw new SecurityException("Malformed client-data JWT");
            }
            JsonNode header = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if (!"ES384".equals(header.path("alg").asText())) {
                throw new SecurityException("Client-data JWT must use ES384");
            }

            byte[] publicKeyDer = identity.publicKeyDer();
            String x5u = header.path("x5u").asText("");
            if (!x5u.isBlank()) {
                byte[] headerKey = Base64.getDecoder().decode(x5u);
                if (!MessageDigest.isEqual(publicKeyDer, headerKey)) {
                    throw new SecurityException("Client-data JWT x5u does not match the verified client key");
                }
            }

            PublicKey publicKey = KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(publicKeyDer));
            Signature verifier = Signature.getInstance("SHA384withECDSA");
            verifier.initVerify(publicKey);
            verifier.update((parts[0] + "." + parts[1]).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            if (!verifier.verify(joseToDer(Base64.getUrlDecoder().decode(parts[2])))) {
                throw new SecurityException("Invalid client-data JWT signature");
            }

            JsonNode claims = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
            long now = Instant.now().getEpochSecond();
            JsonNode expires = claims.get("exp");
            if (expires != null && (!expires.canConvertToLong() || now > expires.asLong() + 30)) {
                throw new SecurityException("Client-data JWT has expired or has an invalid expiry");
            }
            JsonNode notBefore = claims.get("nbf");
            if (notBefore != null && (!notBefore.canConvertToLong() || now + 30 < notBefore.asLong())) {
                throw new SecurityException("Client-data JWT is not active yet");
            }
        } catch (SecurityException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SecurityException("Unable to validate client-data JWT", exception);
        }
    }

    private static byte[] joseToDer(byte[] jose) {
        if (jose.length != 96) throw new SecurityException("ES384 signature must be 96 bytes");
        byte[] r = derInteger(jose, 0);
        byte[] s = derInteger(jose, 48);
        int bodyLength = 2 + r.length + 2 + s.length;
        byte[] der = new byte[2 + bodyLength];
        int offset = 0;
        der[offset++] = 0x30;
        der[offset++] = (byte) bodyLength;
        der[offset++] = 0x02;
        der[offset++] = (byte) r.length;
        System.arraycopy(r, 0, der, offset, r.length);
        offset += r.length;
        der[offset++] = 0x02;
        der[offset++] = (byte) s.length;
        System.arraycopy(s, 0, der, offset, s.length);
        return der;
    }

    private static byte[] derInteger(byte[] jose, int offset) {
        int first = offset;
        int end = offset + 48;
        while (first < end - 1 && jose[first] == 0) first++;
        int length = end - first;
        boolean needsZero = (jose[first] & 0x80) != 0;
        byte[] result = new byte[length + (needsZero ? 1 : 0)];
        System.arraycopy(jose, first, result, result.length - length, length);
        return result;
    }

    @Override
    public void close() {
        try {
            savePlayers();
        } catch (IOException exception) {
            System.err.println("NEXORA: unable to save player profiles on shutdown: " + exception.getMessage());
        }
        sessions.clear();
    }

    @FunctionalInterface
    public interface OutboundFrameSender {
        void sendFrame(String networkId, String channelLabel, byte[] frame) throws Exception;
    }

    public record PacketEvent(String networkId, String channelLabel, BedrockPacket packet) {
        public PacketEvent {
            Objects.requireNonNull(networkId, "networkId");
            Objects.requireNonNull(channelLabel, "channelLabel");
            Objects.requireNonNull(packet, "packet");
        }
    }

    public record PlayerPosition(float x, float y, float z, float yaw, float pitch, long tick) {
    }

    private record BreakTarget(int x, int y, int z, long startedAtNanos) {
    }

    private static final class SessionState {
        private boolean compressionNegotiated;
        private volatile boolean loginAuthenticated;
        private boolean clientHandshakeReceived;
        private boolean resourcePacksInfoSent;
        private boolean resourcePackStackSent;
        private volatile boolean resourcePacksCompleted;
        private boolean gameStarted;
        private volatile boolean clientInitialized;
        private boolean positionInitialized;
        private float positionX;
        private float positionY;
        private float positionZ;
        private float yaw;
        private float pitch;
        private long lastInputTick;
        private boolean hasReceivedInput;
        private long lastMovementNanos;
        private double movementBudget = 2.0;
        private int lastChunkX = Integer.MIN_VALUE;
        private int lastChunkZ = Integer.MIN_VALUE;
        private final Set<Long> sentChunkKeys = ConcurrentHashMap.newKeySet();
        private BreakTarget breakTarget;
        private long entityId;
        private boolean rejected;
        private int clientProtocol;
        private NetherNetClientIdentity identity;
    }
}
