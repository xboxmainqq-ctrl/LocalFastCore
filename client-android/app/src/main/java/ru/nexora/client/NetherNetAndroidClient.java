package ru.nexora.client;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;
import livekit.org.webrtc.DataChannel;
import livekit.org.webrtc.IceCandidate;
import livekit.org.webrtc.MediaConstraints;
import livekit.org.webrtc.MediaStream;
import livekit.org.webrtc.PeerConnection;
import livekit.org.webrtc.PeerConnectionFactory;
import livekit.org.webrtc.RtpReceiver;
import livekit.org.webrtc.RtpTransceiver;
import livekit.org.webrtc.SdpObserver;
import livekit.org.webrtc.SessionDescription;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.security.SecureRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Android-side NetherNet offerer: creates both channels, gathers full ICE,
 * performs HTTP signaling, verifies the signed operator answer, and waits
 * until both data channels are open.
 */
public final class NetherNetAndroidClient implements AutoCloseable {
    private static final String RELIABLE_LABEL = "ReliableDataChannel";
    private static final String UNRELIABLE_LABEL = "UnreliableDataChannel";
    private static final int MAX_FRAME_BYTES = 64 * 1024;
    private static final int MAX_MESSAGE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_FRAGMENTS = 255;
    private static final long MAX_BUFFERED_BYTES = 8L * 1024 * 1024;
    private static final int MAX_SDP_BYTES = 256 * 1024;
    private static final int MAX_LOGIN_REQUEST_BYTES = 1024 * 1024;
    private static final SecureRandom NETWORK_ID_RANDOM = new SecureRandom();
    private static final Object FACTORY_INIT_LOCK = new Object();
    private static boolean factoryInitialized;

    private final Context appContext;
    private final SharedPreferences trustStore;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "nexora-android-nethernet");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean connectedNotified = new AtomicBoolean();
    private final AtomicBoolean loginSent = new AtomicBoolean();
    private final AtomicBoolean loginSuccessReceived = new AtomicBoolean();
    private final Set<Long> requestedChunkKeys = ConcurrentHashMap.newKeySet();

    private volatile Listener listener;
    private volatile String playerName = "Игрок";
    private volatile int playerSkinIndex;
    private volatile PeerConnectionFactory factory;
    private volatile PeerConnection peer;
    private volatile DataChannel reliableChannel;
    private volatile DataChannel unreliableChannel;
    private volatile BedrockAndroidNetworkSettings networkSettings;
    private volatile CompletableFuture<Boolean> trustDecision;
    private final Object fragmentLock = new Object();
    private ByteArrayOutputStream reliableAssembly;
    private int expectedFragments;
    private long reliableAssemblyStartedNanos;
    private volatile boolean networkSettingsReceived;
    private volatile boolean nexoraProtocolReady;
    private volatile boolean worldInfoReceived;
    private volatile BedrockAndroidResourcePackInfo pendingResourcePackInfo;
    private volatile BedrockAndroidResourcePackInfo.Pack pendingResourcePack;
    private volatile BedrockAndroidResourcePackDataInfo currentResourcePackDataInfo;
    private volatile BedrockAndroidResourcePackDownload resourcePackDownload;

    public NetherNetAndroidClient(Context context) {
        appContext = context.getApplicationContext();
        trustStore = appContext.getSharedPreferences(
                AndroidNetherNetServerIdentityVerifier.TRUST_PREFERENCES, Context.MODE_PRIVATE);
    }

    public void connect(String serverAddress, Listener listener) {
        connect(serverAddress, "Игрок", 0, listener);
    }

    public void connect(String serverAddress, String playerName, Listener listener) {
        connect(serverAddress, playerName, 0, listener);
    }

    public void connect(String serverAddress, String playerName, int playerSkinIndex, Listener listener) {
        if (this.listener != null || closed.get()) throw new IllegalStateException("This NEXORA client instance has already been used");
        if (playerName == null || playerName.isBlank() || playerName.length() > 16) throw new IllegalArgumentException("Ник должен содержать от 1 до 16 символов");
        if (playerSkinIndex < 0 || playerSkinIndex > 4) throw new IllegalArgumentException("Invalid player skin");
        this.playerName = playerName.trim();
        this.playerSkinIndex = playerSkinIndex;
        this.listener = listener;
        executor.execute(() -> connectBlocking(serverAddress));
    }

    private void connectBlocking(String address) {
        try {
            reportStatus("Проверяем сервер...");
            URI baseUri = normalizeServerAddress(address);
            JSONObject serverInfo = new JSONObject(httpRequest(baseUri.resolve("/v1/join"),
                    "GET", null, null));
            int nexoraProtocol = serverInfo.getInt("nexoraProtocol");
            if (nexoraProtocol != NexoraAndroidProtocolCodec.MAJOR_VERSION) {
                throw new IllegalStateException("Сервер использует другую версию протокола NEXORA: "
                        + nexoraProtocol);
            }

            initializePeerConnection();
            reportStatus("Создаём WebRTC offer...");
            String offerSdp = createOfferAndGatherIce();
            if (offerSdp.getBytes(StandardCharsets.UTF_8).length > MAX_SDP_BYTES) {
                throw new IllegalStateException("SDP offer превышает лимит размера");
            }

            String networkId = Long.toUnsignedString(NETWORK_ID_RANDOM.nextLong());
            reportStatus("Обмениваемся SDP...");
            String answerSdp = httpRequest(baseUri.resolve(
                    "/v1/join/" + networkId), "POST", "application/sdp", offerSdp);
            if (answerSdp.getBytes(StandardCharsets.UTF_8).length > MAX_SDP_BYTES) {
                throw new IllegalStateException("SDP answer превышает лимит размера");
            }

            boolean tls = "https".equalsIgnoreCase(baseUri.getScheme());
            AndroidNetherNetServerIdentityVerifier.VerificationResult identity =
                    AndroidNetherNetServerIdentityVerifier.verify(answerSdp, tls, trustStore);
            if (identity.trustStatus()
                    == AndroidNetherNetServerIdentityVerifier.TrustStatus.FIRST_USE_CONFIRMATION_REQUIRED) {
                CompletableFuture<Boolean> decision = new CompletableFuture<>();
                trustDecision = decision;
                mainHandler.post(() -> listener.onTrustConfirmationRequired(
                        identity.displayDomain(), identity.keyFingerprint(), decision::complete));
                boolean approved = decision.get(120, TimeUnit.SECONDS);
                trustDecision = null;
                if (!approved) {
                    throw new SecurityException("Пользователь отклонил ключ сервера");
                }
                AndroidNetherNetServerIdentityVerifier.rememberAfterUserApproval(
                        trustStore, identity.keyFingerprint(), true);
            }

            reportStatus("Проверяем SDP answer и устанавливаем соединение...");
            setRemoteDescription(identity.cleanedSdp());
            reportStatus("SDP согласован, ожидаем открытия каналов...");
            mainHandler.postDelayed(() -> {
                if (!closed.get() && !connectedNotified.get()) {
                    reportError("Тайм-аут ожидания открытия NetherNet каналов");
                }
            }, 30_000L);
        } catch (Exception failure) {
            reportError(failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage());
            closed.set(true);
            closePeerConnection();
            executor.shutdown();
        }
    }

    private void initializePeerConnection() throws Exception {
        synchronized (FACTORY_INIT_LOCK) {
            if (!factoryInitialized) {
                PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions.builder(appContext)
                                .createInitializationOptions());
                factoryInitialized = true;
            }
        }
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory();
        if (factory == null) {
            throw new IllegalStateException("Не удалось создать WebRTC PeerConnectionFactory");
        }

        PeerConnection.RTCConfiguration configuration =
                new PeerConnection.RTCConfiguration(new ArrayList<>());
        configuration.iceTransportsType = PeerConnection.IceTransportsType.ALL;
        configuration.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        configuration.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED;
        configuration.continualGatheringPolicy =
                PeerConnection.ContinualGatheringPolicy.GATHER_ONCE;
        configuration.iceCandidatePoolSize = 0;

        peer = factory.createPeerConnection(configuration, new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState state) {
            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                if (state == PeerConnection.IceConnectionState.FAILED) {
                    failIceGathering("ICE-соединение завершилось ошибкой");
                    reportError("ICE-соединение потеряно: " + state);
                }
            }

            @Override
            public void onStandardizedIceConnectionChange(PeerConnection.IceConnectionState state) {
            }

            @Override
            public void onConnectionChange(PeerConnection.PeerConnectionState state) {
                if (state == PeerConnection.PeerConnectionState.FAILED) {
                    failIceGathering("WebRTC peer connection failed");
                    reportError("WebRTC соединение завершилось ошибкой");
                }
            }

            @Override
            public void onIceConnectionReceivingChange(boolean receiving) {
            }

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState state) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) {
                    CompletableFuture<Void> waiting = iceGatheringFuture;
                    if (waiting != null) {
                        waiting.complete(null);
                    }
                }
            }

            @Override
            public void onIceCandidate(IceCandidate candidate) {
                // NetherNet uses full ICE; candidates remain in the final SDP.
            }

            @Override
            public void onIceCandidatesRemoved(IceCandidate[] candidates) {
            }

            @Override
            public void onAddStream(MediaStream stream) {
            }

            @Override
            public void onRemoveStream(MediaStream stream) {
            }

            @Override
            public void onDataChannel(DataChannel channel) {
            }

            @Override
            public void onRenegotiationNeeded() {
            }

            @Override
            public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {
            }

            @Override
            public void onTrack(RtpTransceiver transceiver) {
            }
        });
        if (peer == null) {
            throw new IllegalStateException("Не удалось создать WebRTC PeerConnection");
        }

        DataChannel.Init reliableInit = new DataChannel.Init();
        reliableInit.ordered = true;
        reliableInit.maxRetransmits = -1;
        reliableChannel = peer.createDataChannel(RELIABLE_LABEL, reliableInit);

        DataChannel.Init unreliableInit = new DataChannel.Init();
        unreliableInit.ordered = false;
        unreliableInit.maxRetransmits = 0;
        unreliableChannel = peer.createDataChannel(UNRELIABLE_LABEL, unreliableInit);

        if (reliableChannel == null || unreliableChannel == null) {
            throw new IllegalStateException("Не удалось создать NetherNet data channels");
        }
        reliableChannel.registerObserver(channelObserver(true));
        unreliableChannel.registerObserver(channelObserver(false));
    }

    private String createOfferAndGatherIce() throws Exception {
        CompletableFuture<SessionDescription> offerFuture = new CompletableFuture<>();
        peer.createOffer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription description) {
                offerFuture.complete(description);
            }

            @Override
            public void onSetSuccess() {
            }

            @Override
            public void onCreateFailure(String error) {
                offerFuture.completeExceptionally(new IllegalStateException(error));
            }

            @Override
            public void onSetFailure(String error) {
            }
        }, new MediaConstraints());
        SessionDescription offer = offerFuture.get(20, TimeUnit.SECONDS);

        CompletableFuture<Void> localSet = new CompletableFuture<>();
        CompletableFuture<Void> iceComplete = new CompletableFuture<>();
        iceGatheringFuture = iceComplete;
        peer.setLocalDescription(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription description) {
            }

            @Override
            public void onSetSuccess() {
                localSet.complete(null);
            }

            @Override
            public void onCreateFailure(String error) {
            }

            @Override
            public void onSetFailure(String error) {
                localSet.completeExceptionally(new IllegalStateException(error));
            }
        }, offer);

        // The observer is installed at PeerConnection creation; query state too
        // in case gathering completed before this waiter was created.
        if (peer.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) {
            iceComplete.complete(null);
        }
        localSet.get(10, TimeUnit.SECONDS);
        iceComplete.get(30, TimeUnit.SECONDS);
        iceGatheringFuture = null;
        SessionDescription local = peer.getLocalDescription();
        if (local == null || local.description == null || local.description.isEmpty()) {
            throw new IllegalStateException("WebRTC не вернул полный SDP offer");
        }
        return local.description;
    }

    private volatile CompletableFuture<Void> iceGatheringFuture;

    private void failIceGathering(String reason) {
        CompletableFuture<Void> waiting = iceGatheringFuture;
        if (waiting != null) {
            waiting.completeExceptionally(new IllegalStateException(reason));
        }
    }

    private void setRemoteDescription(String sdp) throws Exception {
        CompletableFuture<Void> result = new CompletableFuture<>();
        peer.setRemoteDescription(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription description) {
            }

            @Override
            public void onSetSuccess() {
                result.complete(null);
            }

            @Override
            public void onCreateFailure(String error) {
            }

            @Override
            public void onSetFailure(String error) {
                result.completeExceptionally(new IllegalStateException(error));
            }
        }, new SessionDescription(SessionDescription.Type.ANSWER, sdp));
        result.get(15, TimeUnit.SECONDS);
    }

    private DataChannel.Observer channelObserver(boolean reliable) {
        return new DataChannel.Observer() {
            @Override
            public void onBufferedAmountChange(long previousAmount) {
            }

            @Override
            public void onStateChange() {
                if (closed.get()) {
                    return;
                }
                DataChannel current = reliable ? reliableChannel : unreliableChannel;
                if (current != null && current.state() == DataChannel.State.CLOSED) {
                    reportError(networkSettingsReceived
                            ? "Транспорт закрыт: Bedrock Login ещё не реализован в клиенте"
                            : "NetherNet data channel закрыт");
                    return;
                }
                DataChannel a = reliableChannel;
                DataChannel b = unreliableChannel;
                if (a != null && b != null
                        && a.state() == DataChannel.State.OPEN
                        && b.state() == DataChannel.State.OPEN
                        && connectedNotified.compareAndSet(false, true)) {
                    mainHandler.post(() -> listener.onConnected());
                    if (!sendNexoraClientHello()) {
                        reportError("Не удалось отправить NEXORA ClientHello");
                    } else {
                        reportStatus("NetherNet открыт • согласуем протокол NEXORA...");
                        mainHandler.postDelayed(() -> {
                            if (!closed.get() && !nexoraProtocolReady) {
                                reportError("Тайм-аут рукопожатия NEXORA");
                            }
                        }, 15_000L);
                    }
                }
            }

            @Override
            public void onMessage(DataChannel.Buffer buffer) {
                if (!buffer.binary) {
                    reportError("Получено текстовое сообщение вместо бинарного");
                    return;
                }
                ByteBuffer source = buffer.data.duplicate();
                byte[] frame = new byte[source.remaining()];
                source.get(frame);
                receiveFrame(frame, reliable);
            }
        };
    }

    private void receiveFrame(byte[] frame, boolean reliable) {
        if (frame.length == 0 || frame.length > MAX_FRAME_BYTES) {
            reportError("Недопустимый размер NetherNet frame");
            return;
        }
        int remaining = frame[0] & 0xff;
        byte[] payload = java.util.Arrays.copyOfRange(frame, 1, frame.length);
        if (!reliable) {
            if (remaining != 0) {
                reportError("Фрагментация запрещена на UnreliableDataChannel");
                return;
            }
            if (!networkSettingsReceived) {
                reportError("Unreliable Bedrock batch arrived before NetworkSettings");
                return;
            }
            deliverBedrockBatch(payload, false);
            return;
        }

        byte[] complete = null;
        synchronized (fragmentLock) {
            if (reliableAssembly == null) {
                if (remaining == 0) {
                    if (payload.length > MAX_MESSAGE_BYTES) {
                        reportError("NetherNet message exceeds the configured limit");
                        return;
                    }
                    complete = payload;
                } else {
                    if (remaining + 1 > MAX_FRAGMENTS || payload.length > MAX_MESSAGE_BYTES) {
                        reportError("Недопустимый NetherNet fragment");
                        return;
                    }
                    reliableAssembly = new ByteArrayOutputStream(Math.max(32, payload.length));
                    expectedFragments = remaining;
                    reliableAssemblyStartedNanos = System.nanoTime();
                    reliableAssembly.write(payload, 0, payload.length);
                }
            } else {
                if (System.nanoTime() - reliableAssemblyStartedNanos
                        > TimeUnit.SECONDS.toNanos(10)
                        || remaining != expectedFragments - 1
                        || reliableAssembly.size() + payload.length > MAX_MESSAGE_BYTES) {
                    reliableAssembly = null;
                    expectedFragments = 0;
                    reliableAssemblyStartedNanos = 0;
                    reportError("Нарушен порядок или превышен тайм-аут NetherNet fragments");
                    return;
                }
                reliableAssembly.write(payload, 0, payload.length);
                expectedFragments = remaining;
                if (remaining == 0) {
                    complete = reliableAssembly.toByteArray();
                    reliableAssembly = null;
                    expectedFragments = 0;
                    reliableAssemblyStartedNanos = 0;
                }
            }
        }
        if (complete != null) {
            if (!nexoraProtocolReady) {
                try {
                    NexoraAndroidProtocolCodec.ServerResponse response =
                            NexoraAndroidProtocolCodec.decodeServerResponse(complete);
                    if (!response.accepted()) {
                        reportError("Сервер отклонил протокол NEXORA (" + response.rejectionCode()
                                + "): " + response.rejectionReason());
                        return;
                    }
                    nexoraProtocolReady = true;
                    reportStatus("Протокол NEXORA " + NexoraAndroidProtocolCodec.MAJOR_VERSION
                            + "." + NexoraAndroidProtocolCodec.MINOR_VERSION + " согласован");
                    mainHandler.postDelayed(() -> {
                        if (!closed.get() && nexoraProtocolReady && !worldInfoReceived) {
                            reportError("Тайм-аут ожидания сведений о мире NEXORA");
                        }
                    }, 15_000L);
                    Listener current = listener;
                    if (current != null) {
                        mainHandler.post(() -> current.onNexoraReady(
                                response.serverId(), response.maxMessageBytes()));
                    }
                    return;
                } catch (RuntimeException malformed) {
                    reportError("Некорректный ответ протокола NEXORA: " + malformed.getMessage());
                    return;
                }
            }
            if (!worldInfoReceived) {
                try {
                    NexoraAndroidProtocolCodec.WorldInfo world =
                            NexoraAndroidProtocolCodec.decodeWorldInfo(complete);
                    worldInfoReceived = true;
                    reportStatus("Мир получен • " + world.worldId() + " • высота "
                            + world.minY() + ".." + (world.maxYExclusive() - 1));
                    Listener current = listener;
                    if (current != null) {
                        mainHandler.post(() -> current.onWorldInfoReceived(world));
                    }
                    requestChunksAround(Math.floorDiv(world.spawnX(), 16),
                            Math.floorDiv(world.spawnZ(), 16));
                    return;
                } catch (RuntimeException malformed) {
                    reportError("Некорректные сведения о мире NEXORA: " + malformed.getMessage());
                    return;
                }
            }
            if (complete.length >= 7 && complete[6] == 15) {
                try {
                    NexoraAndroidProtocolCodec.BlockUpdate update =
                            NexoraAndroidProtocolCodec.decodeBlockUpdate(complete);
                    Listener current = listener;
                    if (current != null) mainHandler.post(() -> current.onBlockUpdate(update));
                } catch (RuntimeException malformed) {
                    reportError("Некорректное обновление блока NEXORA: " + malformed.getMessage());
                }
                return;
            }
            if (complete.length >= 7 && complete[6] == 8) {
                try {
                    NexoraAndroidProtocolCodec.PlayerPosition position =
                            NexoraAndroidProtocolCodec.decodeServerPosition(complete);
                    Listener current = listener;
                    if (current != null) {
                        mainHandler.post(() -> current.onServerPosition(position));
                    }
                } catch (RuntimeException malformed) {
                    reportError("Некорректная позиция сервера NEXORA: " + malformed.getMessage());
                }
                return;
            }
            if (complete.length >= 7 && complete[6] == 9) {
                try {
                    NexoraAndroidProtocolCodec.RemotePlayerState player =
                            NexoraAndroidProtocolCodec.decodeRemotePlayerState(complete);
                    Listener current = listener;
                    if (current != null) {
                        mainHandler.post(() -> current.onRemotePlayerState(player));
                    }
                } catch (RuntimeException malformed) {
                    reportError("Некорректная позиция другого игрока NEXORA: "
                            + malformed.getMessage());
                }
                return;
            }
            if (complete.length >= 7 && complete[6] == 10) {
                try {
                    java.util.UUID playerId =
                            NexoraAndroidProtocolCodec.decodeRemotePlayerLeft(complete);
                    Listener current = listener;
                    if (current != null) {
                        mainHandler.post(() -> current.onRemotePlayerLeft(playerId));
                    }
                } catch (RuntimeException malformed) {
                    reportError("Некорректное отключение игрока NEXORA: "
                            + malformed.getMessage());
                }
                return;
            }
            if (complete.length >= 7 && complete[6] == 13) {
                try {
                    NexoraAndroidProtocolCodec.InventoryActionResult result =
                            NexoraAndroidProtocolCodec.decodeInventoryActionResult(complete);
                    Listener current = listener;
                    if (current != null) {
                        mainHandler.post(() -> current.onInventoryActionResult(result));
                    }
                } catch (RuntimeException malformed) {
                    reportError("Некорректный ответ действия инвентаря: " + malformed.getMessage());
                }
                return;
            }
            if (complete.length >= 7 && complete[6] == 11) {
                try {
                    NexoraAndroidProtocolCodec.InventoryState inventory =
                            NexoraAndroidProtocolCodec.decodeInventory(complete);
                    Listener current = listener;
                    if (current != null) {
                        mainHandler.post(() -> current.onInventoryReceived(inventory));
                    }
                } catch (RuntimeException malformed) {
                    reportError("Некорректный инвентарь NEXORA: " + malformed.getMessage());
                }
                return;
            }
            try {
                NexoraAndroidProtocolCodec.ChunkData chunk =
                        NexoraAndroidProtocolCodec.decodeChunkData(complete);
                Listener current = listener;
                if (current != null) {
                    mainHandler.post(() -> current.onChunkDataReceived(chunk));
                }
            } catch (RuntimeException malformed) {
                reportError("Некорректные данные чанка NEXORA: " + malformed.getMessage());
            }
            return;
        }
    }

    private void deliverBedrockBatch(byte[] delivered, boolean reliable) {
        try {
            List<BedrockAndroidServerPacket> packets =
                    BedrockAndroidPacketCodec.decodeServerBatch(delivered, networkSettings);
            for (BedrockAndroidServerPacket packet : packets) {
                int packetId = packet.packetId();
                if (packetId == 6 || packetId == 7 || packetId == 82 || packetId == 83) {
                    if (!closed.get()) executor.execute(() -> handleResourcePackPacket(packet));
                }
            }
            Listener current = listener;
            if (current != null) {
                mainHandler.post(() -> {
                    current.onMessage(delivered, reliable);
                    for (BedrockAndroidServerPacket packet : packets) {
                        if (packet.packetId() == 2) {
                            BedrockAndroidPlayStatus status =
                                    BedrockAndroidPacketCodec.decodePlayStatus(packet);
                            current.onPlayStatus(status);
                            if (status == BedrockAndroidPlayStatus.LOGIN_SUCCESS) {
                                loginSuccessReceived.set(true);
                                current.onStatus("Bedrock Login подтверждён сервером");
                            } else if (status == BedrockAndroidPlayStatus.PLAYER_SPAWN) {
                                loginSuccessReceived.set(true);
                                current.onStatus("Игрок появился в мире");
                            } else {
                                current.onError("Bedrock Login отклонён: " + status);
                            }
                        } else {
                            current.onBedrockPacket(packet);
                        }
                    }
                });
            }
        } catch (RuntimeException malformed) {
            reportError("Некорректный Bedrock server batch: " + malformed.getMessage());
        }
    }

    private void handleResourcePackPacket(BedrockAndroidServerPacket packet) {
        if (closed.get()) return;
        try {
            switch (packet.packetId()) {
                case 6 -> handleResourcePacksInfo(packet.payload());
                case 82 -> handleResourcePackDataInfo(packet.payload());
                case 83 -> handleResourcePackChunkData(packet.payload());
                case 7 -> handleResourcePackStack(packet.payload());
                default -> { }
            }
        } catch (Exception failure) {
            reportError("Не удалось загрузить ресурс-пак сервера: "
                    + (failure.getMessage() == null ? failure.getClass().getSimpleName()
                    : failure.getMessage()));
            close();
        }
    }

    private void handleResourcePacksInfo(byte[] payload) {
        BedrockAndroidResourcePackInfo info =
                BedrockAndroidPacketCodec.decodeResourcePacksInfo(payload);
        closeResourcePackDownload();
        pendingResourcePackInfo = info;
        pendingResourcePack = null;
        if (info.packs().isEmpty()) {
            sendPackResponse(BedrockAndroidResourcePackStatus.HAVE_ALL_PACKS, List.of());
            return;
        }
        if (info.packs().size() != 1 || info.hasAddonPacks() || info.hasScripts()
                || info.forceDisableVibrantVisuals()) {
            throw new UnsupportedOperationException(
                    "Клиент пока поддерживает один обычный ресурс-пак без скриптов");
        }
        BedrockAndroidResourcePackInfo.Pack pack = info.packs().get(0);
        if (!pack.contentKey().isEmpty() || !pack.subpackName().isEmpty()
                || !pack.contentIdentity().isEmpty() || pack.hasScripts()
                || pack.addon() || pack.rayTracing() || !pack.cdnUrl().isEmpty()) {
            throw new UnsupportedOperationException(
                    "Зашифрованные, дополнительные и CDN-паки пока не поддерживаются");
        }
        pendingResourcePack = pack;
        sendPackResponse(BedrockAndroidResourcePackStatus.SEND_PACKS,
                List.of(pack.resourceName()));
        reportStatus("Сервер предлагает ресурс-пак • запрашиваем загрузку...");
    }

    private void handleResourcePackDataInfo(byte[] payload) throws Exception {
        BedrockAndroidResourcePackDataInfo info =
                BedrockAndroidPacketCodec.decodeResourcePackDataInfo(payload);
        BedrockAndroidResourcePackInfo.Pack pack = pendingResourcePack;
        if (pack == null || !pack.resourceName().equals(info.resourceName())
                || pack.size() != info.fileSize() || resourcePackDownload != null) {
            throw new IllegalArgumentException("ResourcePackDataInfo не совпадает с объявленным паком");
        }
        currentResourcePackDataInfo = info;
        resourcePackDownload = new BedrockAndroidResourcePackDownload(appContext.getCacheDir(), info);
        requestNextResourcePackChunk();
    }

    private void handleResourcePackChunkData(byte[] payload) throws Exception {
        BedrockAndroidResourcePackDownload download = resourcePackDownload;
        BedrockAndroidResourcePackDataInfo info = currentResourcePackDataInfo;
        if (download == null || info == null) {
            throw new IllegalArgumentException("Получены данные до ResourcePackDataInfo");
        }
        download.accept(BedrockAndroidPacketCodec.decodeResourcePackChunkData(payload));
        if (!download.isComplete()) {
            requestNextResourcePackChunk();
            return;
        }

        File archive = download.finishAndVerify();
        try {
            int installed = NexoraResourcePackInstaller.install(appContext, archive);
            if (installed < 1) throw new IllegalStateException("В паке нет поддерживаемых текстур");
            NexoraResourcePackLibrary.registerImportedActive(appContext, info.resourceName());
            reportStatus("Ресурс-пак сервера загружен • текстур: " + installed);
        } finally {
            archive.delete();
            download.close();
            resourcePackDownload = null;
            currentResourcePackDataInfo = null;
        }
        sendPackResponse(BedrockAndroidResourcePackStatus.HAVE_ALL_PACKS, List.of());
    }

    private void handleResourcePackStack(byte[] payload) {
        BedrockAndroidResourcePackStack stack =
                BedrockAndroidPacketCodec.decodeResourcePackStack(payload);
        BedrockAndroidResourcePackInfo info = pendingResourcePackInfo;
        List<String> expected = info == null ? List.of()
                : info.packs().stream().map(BedrockAndroidResourcePackInfo.Pack::resourceName).toList();
        if (!stack.resourceNames().equals(expected)) {
            throw new IllegalArgumentException("ResourcePackStack не совпадает со скачанными пакетами");
        }
        if (stack.required() && expected.isEmpty()) {
            throw new IllegalArgumentException("Сервер требует отсутствующий ресурс-пак");
        }
        if (!sendReliable(BedrockAndroidPacketCodec.encodeResourcePacksReadyForValidation(networkSettings))) {
            throw new IllegalStateException("Не удалось подтвердить готовность ресурс-паков");
        }
        sendPackResponse(BedrockAndroidResourcePackStatus.COMPLETED, List.of());
        pendingResourcePackInfo = null;
        pendingResourcePack = null;
        reportStatus("Ресурс-паки сервера применены к сессии");
    }

    private void requestNextResourcePackChunk() {
        BedrockAndroidResourcePackDownload download = resourcePackDownload;
        BedrockAndroidResourcePackDataInfo info = currentResourcePackDataInfo;
        if (download == null || info == null) throw new IllegalStateException("Загрузка ресурс-пака не подготовлена");
        int index = download.nextChunkIndex();
        if (!sendReliable(BedrockAndroidPacketCodec.encodeResourcePackChunkRequest(
                info.resourceName(), index, networkSettings))) {
            throw new IllegalStateException("Не удалось запросить часть ресурс-пака");
        }
    }

    private void sendPackResponse(BedrockAndroidResourcePackStatus status, List<String> ids) {
        if (!sendReliable(BedrockAndroidPacketCodec.encodeResourcePackClientResponse(
                status, ids, networkSettings))) {
            throw new IllegalStateException("Не удалось отправить ответ о ресурс-паке");
        }
    }

    private void closeResourcePackDownload() {
        BedrockAndroidResourcePackDownload download = resourcePackDownload;
        resourcePackDownload = null;
        currentResourcePackDataInfo = null;
        if (download != null) download.close();
    }

    private boolean sendNexoraClientHello() {
        return sendReliable(NexoraAndroidProtocolCodec.encodeClientHello("android-0.1.0-dev", playerName, playerSkinIndex));
    }

    /** Requests the 3x3 chunk area around the authoritative player location. */
    public void requestChunksAround(int centerChunkX, int centerChunkZ) {
        if (!nexoraProtocolReady || !worldInfoReceived || !isConnected()) return;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;
                long key = ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
                if (!requestedChunkKeys.add(key)) continue;
                if (!sendReliable(NexoraAndroidProtocolCodec.encodeChunkRequest(chunkX, chunkZ))) {
                    requestedChunkKeys.remove(key);
                    reportError("Не удалось запросить чанк " + chunkX + ", " + chunkZ);
                    return;
                }
            }
        }
    }

    /** Sends a client movement proposal; the server reply is authoritative. */
    public boolean sendPlayerMove(double x, double y, double z) {
        if (!nexoraProtocolReady || !worldInfoReceived || !isConnected()) return false;
        try {
            return sendUnreliable(NexoraAndroidProtocolCodec.encodePlayerMove(x, y, z));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /** Sends a server-authoritative inventory operation. */
    public boolean sendBlockAction(boolean place, int x, int y, int z, String blockId) {
        if (!nexoraProtocolReady || !worldInfoReceived || !isConnected()) return false;
        try {
            return sendReliable(NexoraAndroidProtocolCodec.encodeBlockAction(
                    place, x, y, z, blockId == null ? "" : blockId));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    public boolean sendInventoryAction(NexoraAndroidProtocolCodec.InventoryAction action,
                                       int sourceSlot, int targetSlot) {
        if (!nexoraProtocolReady || !worldInfoReceived || !isConnected()) return false;
        try {
            return sendReliable(NexoraAndroidProtocolCodec.encodeInventoryAction(
                    action, sourceSlot, targetSlot));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private boolean sendNetworkSettingsRequest() {
        return sendReliable(BedrockAndroidPacketCodec.encodeRequestNetworkSettings());
    }

    /**
     * Sends the Bedrock Login packet after NetworkSettings has been negotiated.
     * The caller must supply both the authenticated auth-payload JSON and the
     * client-data JWT produced by a separate Microsoft/Xbox credential provider.
     */
    public boolean sendLogin(String authenticationPayloadJson, String clientDataJwt) {
        if (authenticationPayloadJson == null || clientDataJwt == null
                || !networkSettingsReceived || !isConnected()
                || !loginSent.compareAndSet(false, true)) {
            return false;
        }
        final byte[] framedBatch;
        try {
            framedBatch = BedrockAndroidPacketCodec.encodeLoginBatch(
                    authenticationPayloadJson, clientDataJwt, networkSettings);
        } catch (RuntimeException failure) {
            loginSent.set(false);
            throw failure;
        }
        boolean accepted = sendReliable(framedBatch);
        if (!accepted) {
            loginSent.set(false);
        } else {
            reportStatus("Bedrock Login отправлен, ожидаем ответ сервера");
            mainHandler.postDelayed(() -> {
                if (!closed.get() && !loginSuccessReceived.get()) {
                    reportError("Тайм-аут ожидания ответа Bedrock Login");
                }
            }, 20_000L);
        }
        return accepted;
    }

    public boolean sendReliable(byte[] payload) {
        if (payload == null || payload.length > MAX_MESSAGE_BYTES || !isConnected()) {
            return false;
        }
        int chunkSize = MAX_FRAME_BYTES - 1;
        int fragments = Math.max(1, (payload.length + chunkSize - 1) / chunkSize);
        if (fragments > MAX_FRAGMENTS
                || reliableChannel.bufferedAmount() + payload.length + fragments
                > MAX_BUFFERED_BYTES) {
            return false;
        }
        int offset = 0;
        for (int index = 0; index < fragments; index++) {
            int count = Math.min(chunkSize, payload.length - offset);
            byte[] frame = new byte[count + 1];
            frame[0] = (byte) (fragments - index - 1);
            System.arraycopy(payload, offset, frame, 1, count);
            offset += count;
            if (!reliableChannel.send(new DataChannel.Buffer(ByteBuffer.wrap(frame), true))) {
                reportError("Не удалось отправить reliable NetherNet frame");
                executor.execute(this::closePeerConnection);
                return false;
            }
        }
        return true;
    }

    public boolean sendUnreliable(byte[] payload) {
        if (payload == null || !isConnected() || payload.length + 1 > MAX_FRAME_BYTES
                || unreliableChannel.bufferedAmount() + payload.length + 1 > MAX_BUFFERED_BYTES) {
            return false;
        }
        byte[] frame = new byte[payload.length + 1];
        System.arraycopy(payload, 0, frame, 1, payload.length);
        boolean accepted = unreliableChannel.send(new DataChannel.Buffer(ByteBuffer.wrap(frame), true));
        if (!accepted) {
            reportError("Не удалось отправить unreliable NetherNet frame");
        }
        return accepted;
    }

    public boolean isConnected() {
        return !closed.get() && reliableChannel != null && unreliableChannel != null
                && reliableChannel.state() == DataChannel.State.OPEN
                && unreliableChannel.state() == DataChannel.State.OPEN;
    }

    private static URI normalizeServerAddress(String address) {
        if (address == null || address.trim().isEmpty()) {
            throw new IllegalArgumentException("Введите адрес сервера");
        }
        String value = address.trim();
        if (!value.contains("://")) {
            value = "http://" + value;
        }
        URI input = URI.create(value);
        String scheme = input.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Поддерживаются только HTTP и HTTPS адреса");
        }
        if (input.getHost() == null || input.getRawAuthority() == null) {
            throw new IllegalArgumentException("Некорректный адрес сервера");
        }
        return URI.create(scheme.toLowerCase() + "://" + input.getRawAuthority() + "/");
    }

    private static String httpRequest(URI uri, String method,
                                     String contentType, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(20_000);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
        if (contentType != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", contentType);
        }
        try {
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_SDP_BYTES) {
                    throw new IllegalArgumentException("SDP request exceeds byte limit");
                }
                try (var output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 400
                    ? connection.getInputStream() : connection.getErrorStream();
            byte[] bytes = stream == null ? new byte[0] : readBounded(stream, MAX_SDP_BYTES);
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("Сигнализация вернула HTTP " + status);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readBounded(InputStream input, int maxBytes) throws Exception {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IllegalStateException("Ответ сервера превышает лимит размера");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private void reportStatus(String message) {
        if (closed.get()) {
            return;
        }
        Listener current = listener;
        if (current != null) {
            mainHandler.post(() -> current.onStatus(message));
        }
    }

    private void reportError(String message) {
        if (closed.get()) {
            return;
        }
        Listener current = listener;
        if (current != null) {
            mainHandler.post(() -> current.onError(message));
        }
    }

    private void closePeerConnection() {
        DataChannel reliable = reliableChannel;
        DataChannel unreliable = unreliableChannel;
        reliableChannel = null;
        unreliableChannel = null;
        if (reliable != null) {
            try {
                reliable.close();
                reliable.dispose();
            } catch (RuntimeException ignored) {
            }
        }
        if (unreliable != null) {
            try {
                unreliable.close();
                unreliable.dispose();
            } catch (RuntimeException ignored) {
            }
        }
        PeerConnection currentPeer = peer;
        peer = null;
        if (currentPeer != null) {
            currentPeer.close();
            currentPeer.dispose();
        }
        PeerConnectionFactory currentFactory = factory;
        factory = null;
        if (currentFactory != null) {
            currentFactory.dispose();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture<Boolean> pending = trustDecision;
        if (pending != null) {
            pending.complete(false);
        }
        failIceGathering("NEXORA client was closed");
        closeResourcePackDownload();
        pendingResourcePackInfo = null;
        pendingResourcePack = null;
        executor.execute(this::closePeerConnection);
        executor.shutdown();
    }

    public interface Listener {
        void onStatus(String status);

        void onTrustConfirmationRequired(String domain, String fingerprint,
                                         Consumer<Boolean> decision);

        void onConnected();

        void onError(String message);

        default void onNexoraReady(String serverId, int maxMessageBytes) {
        }

        default void onWorldInfoReceived(NexoraAndroidProtocolCodec.WorldInfo world) {
        }

        default void onChunkDataReceived(NexoraAndroidProtocolCodec.ChunkData chunk) {
        }

        default void onBlockUpdate(NexoraAndroidProtocolCodec.BlockUpdate update) {
        }

        default void onServerPosition(NexoraAndroidProtocolCodec.PlayerPosition position) {
        }

        default void onRemotePlayerState(NexoraAndroidProtocolCodec.RemotePlayerState player) {
        }

        default void onRemotePlayerLeft(java.util.UUID playerId) {
        }

        default void onInventoryReceived(NexoraAndroidProtocolCodec.InventoryState inventory) {
        }

        default void onInventoryActionResult(
                NexoraAndroidProtocolCodec.InventoryActionResult result) {
        }

        default void onNetworkSettingsReceived(BedrockAndroidNetworkSettings settings) {
        }

        default void onMessage(byte[] payload, boolean reliable) {
        }

        default void onPlayStatus(BedrockAndroidPlayStatus status) {
        }

        default void onBedrockPacket(BedrockAndroidServerPacket packet) {
        }
    }

}
