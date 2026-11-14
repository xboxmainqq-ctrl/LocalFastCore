package ru.nexora.client;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

    private volatile Listener listener;
    private volatile PeerConnectionFactory factory;
    private volatile PeerConnection peer;
    private volatile DataChannel reliableChannel;
    private volatile DataChannel unreliableChannel;
    private volatile CompletableFuture<Boolean> trustDecision;
    private final Object fragmentLock = new Object();
    private ByteArrayOutputStream reliableAssembly;
    private int expectedFragments;
    private long reliableAssemblyStartedNanos;
    private volatile boolean networkSettingsReceived;

    public NetherNetAndroidClient(Context context) {
        appContext = context.getApplicationContext();
        trustStore = appContext.getSharedPreferences(
                AndroidNetherNetServerIdentityVerifier.TRUST_PREFERENCES, Context.MODE_PRIVATE);
    }

    public void connect(String serverAddress, Listener listener) {
        if (this.listener != null || closed.get()) {
            throw new IllegalStateException("This NEXORA client instance has already been used");
        }
        this.listener = listener;
        executor.execute(() -> connectBlocking(serverAddress));
    }

    private void connectBlocking(String address) {
        try {
            reportStatus("Проверяем сервер...");
            URI baseUri = normalizeServerAddress(address);
            JSONObject serverInfo = new JSONObject(httpRequest(baseUri.resolve("/v1/join"),
                    "GET", null, null));
            int protocol = serverInfo.getInt("protocol");
            if (protocol != 2225) {
                throw new IllegalStateException("Сервер использует другой протокол: " + protocol);
            }

            initializePeerConnection();
            reportStatus("Создаём WebRTC offer...");
            String offerSdp = createOfferAndGatherIce();
            if (offerSdp.getBytes(StandardCharsets.UTF_8).length > MAX_SDP_BYTES) {
                throw new IllegalStateException("SDP offer превышает лимит размера");
            }

            String networkId = UUID.randomUUID().toString();
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
        } catch (Exception failure) {
            reportError(failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage());
            closePeerConnection();
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
                    reportError("NetherNet data channel закрыт");
                    return;
                }
                DataChannel a = reliableChannel;
                DataChannel b = unreliableChannel;
                if (a != null && b != null
                        && a.state() == DataChannel.State.OPEN
                        && b.state() == DataChannel.State.OPEN
                        && connectedNotified.compareAndSet(false, true)) {
                    mainHandler.post(() -> listener.onConnected());
                    if (!sendNetworkSettingsRequest()) {
                        reportError("Не удалось отправить RequestNetworkSettings");
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
            Listener current = listener;
            if (current != null) {
                mainHandler.post(() -> current.onMessage(payload, false));
            }
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
            if (!networkSettingsReceived) {
                try {
                    NetworkSettings settings = decodeNetworkSettings(complete);
                    networkSettingsReceived = true;
                    Listener current = listener;
                    if (current != null) {
                        mainHandler.post(() -> current.onNetworkSettingsReceived(settings));
                    }
                    return;
                } catch (RuntimeException malformed) {
                    reportError("Некорректный NetworkSettings пакет: " + malformed.getMessage());
                    return;
                }
            }
            byte[] delivered = complete;
            Listener current = listener;
            if (current != null) {
                mainHandler.post(() -> current.onMessage(delivered, true));
            }
        }
    }

    private boolean sendNetworkSettingsRequest() {
        ByteBuffer batch = ByteBuffer.allocate(16);
        writeVarUInt32(batch, 6);
        writeVarUInt32(batch, 193);
        batch.putInt(2225);
        batch.flip();
        byte[] payload = new byte[batch.remaining()];
        batch.get(payload);
        return sendReliable(payload);
    }

    private static NetworkSettings decodeNetworkSettings(byte[] frame) {
        ByteBuffer batch = ByteBuffer.wrap(frame);
        int packetLength = readVarUInt32(batch);
        if (packetLength <= 0 || packetLength != batch.remaining()) {
            throw new IllegalArgumentException("NetworkSettings batch must contain one complete packet");
        }
        int packetStart = batch.position();
        int header = readVarUInt32(batch);
        if ((header & 0x3ff) != 143 || (header & 0x3c00) != 0
                || (header & ~0x3fff) != 0) {
            throw new IllegalArgumentException("Expected NetworkSettings packet ID 143");
        }
        int headerBytes = batch.position() - packetStart;
        if (packetLength - headerBytes != 10) {
            throw new IllegalArgumentException("NetworkSettings payload must be 10 bytes");
        }
        batch.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int threshold = Short.toUnsignedInt(batch.getShort());
        int algorithm = Short.toUnsignedInt(batch.getShort());
        boolean throttle = batch.get() != 0;
        int throttleThreshold = Byte.toUnsignedInt(batch.get());
        float throttleScalar = batch.getFloat();
        if (algorithm != 0 && algorithm != 1 && algorithm != 0xffff) {
            throw new IllegalArgumentException("Unsupported compression algorithm " + algorithm);
        }
        if (!Float.isFinite(throttleScalar)) {
            throw new IllegalArgumentException("Invalid client throttle scalar");
        }
        return new NetworkSettings(threshold, algorithm, throttle,
                throttleThreshold, throttleScalar);
    }

    private static int readVarUInt32(ByteBuffer input) {
        int result = 0;
        for (int index = 0; index < 5; index++) {
            if (!input.hasRemaining()) {
                throw new IllegalArgumentException("Truncated Bedrock varuint32");
            }
            int current = Byte.toUnsignedInt(input.get());
            if (index == 4 && (current & 0xf0) != 0) {
                throw new IllegalArgumentException("Bedrock varuint32 exceeds 32 bits");
            }
            result |= (current & 0x7f) << (index * 7);
            if ((current & 0x80) == 0) {
                return result;
            }
        }
        throw new IllegalArgumentException("Bedrock varuint32 is too long");
    }

    private static void writeVarUInt32(ByteBuffer output, int value) {
        while ((value & 0xffffff80) != 0) {
            output.put((byte) ((value & 0x7f) | 0x80));
            value >>>= 7;
        }
        output.put((byte) value);
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
        executor.execute(this::closePeerConnection);
        executor.shutdown();
    }

    public interface Listener {
        void onStatus(String status);

        void onTrustConfirmationRequired(String domain, String fingerprint,
                                         Consumer<Boolean> decision);

        void onConnected();

        void onError(String message);

        default void onNetworkSettingsReceived(NetworkSettings settings) {
        }

        default void onMessage(byte[] payload, boolean reliable) {
        }
    }

    public static final class NetworkSettings {
        private final int compressionThreshold;
        private final int compressionAlgorithm;
        private final boolean clientThrottleEnabled;
        private final int clientThrottleThreshold;
        private final float clientThrottleScalar;

        private NetworkSettings(int compressionThreshold, int compressionAlgorithm,
                                boolean clientThrottleEnabled, int clientThrottleThreshold,
                                float clientThrottleScalar) {
            this.compressionThreshold = compressionThreshold;
            this.compressionAlgorithm = compressionAlgorithm;
            this.clientThrottleEnabled = clientThrottleEnabled;
            this.clientThrottleThreshold = clientThrottleThreshold;
            this.clientThrottleScalar = clientThrottleScalar;
        }

        public int compressionThreshold() { return compressionThreshold; }
        public int compressionAlgorithm() { return compressionAlgorithm; }
        public boolean clientThrottleEnabled() { return clientThrottleEnabled; }
        public int clientThrottleThreshold() { return clientThrottleThreshold; }
        public float clientThrottleScalar() { return clientThrottleScalar; }
    }
}
