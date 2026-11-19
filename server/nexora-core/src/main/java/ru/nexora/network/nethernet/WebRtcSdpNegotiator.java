package ru.nexora.network.nethernet;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PortAllocatorConfig;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCStats;
import dev.onvoid.webrtc.RTCStatsReport;
import dev.onvoid.webrtc.RTCStatsType;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelObserver;
import dev.onvoid.webrtc.RTCDataChannelState;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCIceGatheringState;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import dev.onvoid.webrtc.CreateSessionDescriptionObserver;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Semaphore;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * WebRTC answerer for NetherNet HTTP signaling.
 *
 * Client/server identity assertions are delegated to a mandatory cryptographic
 * service; this class never fabricates or skips those assertions.
 */
public final class WebRtcSdpNegotiator implements SdpNegotiator, AutoCloseable {
    private static final long DEFAULT_TIMEOUT_MILLIS = 15_000;
    private static final long OUTBOUND_HIGH_WATERMARK_BYTES = 512L * 1024L;
    private static final long OUTBOUND_LOW_WATERMARK_BYTES = 256L * 1024L;
    private static final int TRANSPORT_STATS_BATCH_SIZE = 32;

    private final PeerConnectionFactory factory;
    private final NetherNetIdentityService identityService;
    private final NetherNetDataChannelListener dataChannelListener;
    private final int maxSessions;
    private final long timeoutMillis;
    private final List<String> iceServerUrls;
    private final int minUdpPort;
    private final int maxUdpPort;
    private final Map<String, PeerSession> sessions = new ConcurrentHashMap<>();
    private final Semaphore sessionPermits;
    private final ScheduledExecutorService sessionReaper;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicLong transportStatsCursor =
            new java.util.concurrent.atomic.AtomicLong();

    public WebRtcSdpNegotiator(
            NetherNetIdentityService identityService,
            NetherNetDataChannelListener dataChannelListener,
            int maxSessions
    ) {
        this(identityService, dataChannelListener, maxSessions, List.of(), 0, 0, DEFAULT_TIMEOUT_MILLIS);
    }

    public WebRtcSdpNegotiator(
            NetherNetIdentityService identityService,
            NetherNetDataChannelListener dataChannelListener,
            int maxSessions,
            long timeoutMillis
    ) {
        this(identityService, dataChannelListener, maxSessions, List.of(), 0, 0, timeoutMillis);
    }

    public WebRtcSdpNegotiator(
            NetherNetIdentityService identityService,
            NetherNetDataChannelListener dataChannelListener,
            int maxSessions,
            List<String> iceServerUrls
    ) {
        this(identityService, dataChannelListener, maxSessions, iceServerUrls, 0, 0, DEFAULT_TIMEOUT_MILLIS);
    }

    public WebRtcSdpNegotiator(
            NetherNetIdentityService identityService,
            NetherNetDataChannelListener dataChannelListener,
            int maxSessions,
            List<String> iceServerUrls,
            int minUdpPort,
            int maxUdpPort
    ) {
        this(identityService, dataChannelListener, maxSessions, iceServerUrls,
                minUdpPort, maxUdpPort, DEFAULT_TIMEOUT_MILLIS);
    }

    public WebRtcSdpNegotiator(
            NetherNetIdentityService identityService,
            NetherNetDataChannelListener dataChannelListener,
            int maxSessions,
            List<String> iceServerUrls,
            int minUdpPort,
            int maxUdpPort,
            long timeoutMillis
    ) {
        this.identityService = Objects.requireNonNull(identityService, "identityService");
        this.dataChannelListener = Objects.requireNonNull(dataChannelListener, "dataChannelListener");
        if (maxSessions < 1) throw new IllegalArgumentException("maxSessions must be >= 1");
        if (timeoutMillis < 1) throw new IllegalArgumentException("timeoutMillis must be >= 1");
        this.maxSessions = maxSessions;
        this.sessionPermits = new Semaphore(maxSessions);
        this.iceServerUrls = List.copyOf(Objects.requireNonNull(iceServerUrls, "iceServerUrls"));
        if (this.iceServerUrls.stream().anyMatch(url -> url == null || !url.startsWith("stun:"))) {
            throw new IllegalArgumentException("Only STUN URLs are supported by this configuration");
        }
        if ((minUdpPort == 0) != (maxUdpPort == 0)
                || minUdpPort < 0 || maxUdpPort < 0
                || minUdpPort > 0 && (minUdpPort < 1024 || maxUdpPort > 65535 || minUdpPort > maxUdpPort)) {
            throw new IllegalArgumentException("UDP port range must be 0/0 or 1024 <= min <= max <= 65535");
        }
        this.minUdpPort = minUdpPort;
        this.maxUdpPort = maxUdpPort;
        this.timeoutMillis = timeoutMillis;
        this.factory = new PeerConnectionFactory();
        this.sessionReaper = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "nexora-nethernet-session-reaper");
            thread.setDaemon(true);
            return thread;
        });
        this.sessionReaper.scheduleAtFixedRate(this::reapIdleSessions, 10, 10, TimeUnit.SECONDS);
        this.sessionReaper.scheduleAtFixedRate(this::sampleTransportStats, 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public String createAnswer(String networkId, String sdpOffer) throws Exception {
        if (closed.get()) throw new IllegalStateException("WebRTC negotiator is closed");
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(sdpOffer, "sdpOffer");
        if (networkId.isBlank()) {
            throw new IllegalArgumentException("Invalid NetworkID");
        }
        if (sessions.containsKey(networkId)) {
            throw new IllegalStateException("NetworkID is already connected");
        }

        ValidatedClientOffer validatedOffer =
                identityService.validateClientOfferAndStripIdentity(networkId, sdpOffer);
        if (validatedOffer == null || validatedOffer.sanitizedSdp() == null
                || !validatedOffer.sanitizedSdp().startsWith("v=0")
                || validatedOffer.sanitizedSdp().contains("a=identity:")) {
            throw new SecurityException("Client identity service returned an invalid sanitized SDP offer");
        }
        String verifiedOffer = validatedOffer.sanitizedSdp();
        if (!sessionPermits.tryAcquire()) {
            throw new IllegalStateException("Server is full");
        }

        PeerSession session = new PeerSession(networkId, validatedOffer.identity());
        RTCConfiguration configuration = new RTCConfiguration();
        configuration.iceServers.clear();
        if (!iceServerUrls.isEmpty()) {
            RTCIceServer iceServer = new RTCIceServer();
            iceServer.urls.addAll(iceServerUrls);
            configuration.iceServers.add(iceServer);
        }
        if (minUdpPort > 0) {
            configuration.portAllocatorConfig = new PortAllocatorConfig(
                    minUdpPort, maxUdpPort, PortAllocatorConfig.PORTALLOCATOR_DISABLE_TCP);
        }
        configuration.bundlePolicy = dev.onvoid.webrtc.RTCBundlePolicy.MAX_BUNDLE;
        configuration.tcpCandidatePolicy = dev.onvoid.webrtc.RTCTcpCandidatePolicy.DISABLED;

        RTCPeerConnection peer;
        try {
            peer = factory.createPeerConnection(configuration, session);
        } catch (RuntimeException exception) {
            session.close();
            throw exception;
        }
        if (peer == null) {
            session.close();
            throw new IllegalStateException("WebRTC failed to create a PeerConnection");
        }
        session.peer = peer;

        if (closed.get() || session.sessionClosed.get()) {
            session.close();
            throw new IllegalStateException("WebRTC negotiator or peer session closed during setup");
        }
        if (sessions.putIfAbsent(networkId, session) != null) {
            session.close();
            throw new IllegalStateException("NetworkID is already connected");
        }
        if (closed.get() || session.sessionClosed.get()) {
            sessions.remove(networkId, session);
            session.close();
            throw new IllegalStateException("WebRTC negotiator or peer session closed during setup");
        }

        try {
            dataChannelListener.onIdentity(networkId, validatedOffer.identity());
            await(setRemoteDescription(peer, verifiedOffer), "setting remote SDP offer");

            RTCSessionDescription answer = await(createAnswer(peer), "creating SDP answer");
            await(setLocalDescription(peer, answer), "setting local SDP answer");

            if (peer.getIceGatheringState() != RTCIceGatheringState.COMPLETE) {
                await(session.iceGatheringComplete, "gathering ICE candidates");
            }

            RTCSessionDescription localDescription = peer.getLocalDescription();
            if (localDescription == null || localDescription.sdp == null
                    || !localDescription.sdp.startsWith("v=0")) {
                throw new IllegalStateException("WebRTC returned an empty local SDP answer");
            }

            String signedAnswer = identityService.appendServerIdentityAssertion(localDescription.sdp);
            if (signedAnswer == null || !signedAnswer.startsWith("v=0")
                    || !signedAnswer.contains("a=identity:")) {
                throw new SecurityException("Server identity service did not produce a signed a=identity assertion");
            }
            return signedAnswer;
        } catch (Exception exception) {
            sessions.remove(networkId, session);
            session.close();
            throw exception;
        }
    }

    public int activeSessionCount() {
        return sessions.size();
    }

    /** Sends an already-framed binary NetherNet message to one peer. */
    public void sendFrame(String networkId, String channelLabel, byte[] frame) throws Exception {
        Objects.requireNonNull(frame, "frame");
        PeerSession session = sessions.get(networkId);
        if (session == null) throw new IllegalStateException("Unknown NetherNet session");
        RTCDataChannel channel = session.channels.get(channelLabel);
        if (channel == null || channel.getState() != RTCDataChannelState.OPEN) {
            throw new IllegalStateException("NetherNet data channel is not open: " + channelLabel);
        }
        session.sendFrame(channelLabel, channel, frame);
    }

    private void reapIdleSessions() {
        long now = System.nanoTime();
        sessions.values().forEach(session -> {
            long idleNanos = now - session.lastActivityNanos;
            long limitNanos = TimeUnit.SECONDS.toNanos(session.connected ? 300 : 30);
            if (idleNanos > limitNanos && sessions.remove(session.networkId, session)) {
                session.close();
            }
        });
    }

    /** Returns the latest WebRTC transport-level sample, or null for an unknown peer. */
    public TransportMetrics transportMetrics(String networkId) {
        PeerSession session = sessions.get(networkId);
        return session == null ? null : session.transportMetrics();
    }

    /** A transport-level ICE RTT sample; it is not the Bedrock gameplay ping. */
    public record TransportMetrics(double rttMillis, long bytesSent, long bytesReceived,
                                   long sampledAtMillis) {
        public boolean available() {
            return sampledAtMillis > 0 && Double.isFinite(rttMillis) && rttMillis >= 0.0;
        }

        /** Returns -1 when no sample has been recorded yet. */
        public long ageMillis(long nowMillis) {
            return sampledAtMillis <= 0 ? -1L : Math.max(0L, nowMillis - sampledAtMillis);
        }

        public boolean stale(long nowMillis, long maxAgeMillis) {
            if (maxAgeMillis < 0) throw new IllegalArgumentException("maxAgeMillis must be >= 0");
            long age = ageMillis(nowMillis);
            return age < 0 || age > maxAgeMillis;
        }
    }

    /** Aggregate transport diagnostics. RTT values include fresh ICE samples only. */
    public record TransportSummary(int connectedPeers, int peersWithSamples, int freshPeers,
                                   int staleOrMissingPeers, double averageRttMillis,
                                   double maxRttMillis, long bytesSent, long bytesReceived) {
    }

    public TransportSummary transportSummary(long nowMillis, long maxAgeMillis) {
        List<TransportMetrics> metrics = sessions.values().stream()
                .filter(session -> session.connected && !session.sessionClosed.get())
                .map(PeerSession::transportMetrics)
                .toList();
        return summarizeTransportMetrics(metrics.size(), metrics, nowMillis, maxAgeMillis);
    }

    public static TransportSummary summarizeTransportMetrics(
            int connectedPeers, List<TransportMetrics> metrics, long nowMillis, long maxAgeMillis) {
        if (connectedPeers < 0) throw new IllegalArgumentException("connectedPeers must be >= 0");
        Objects.requireNonNull(metrics, "metrics");
        if (maxAgeMillis < 0) throw new IllegalArgumentException("maxAgeMillis must be >= 0");
        int withSamples = 0;
        int fresh = 0;
        int staleOrMissing = 0;
        double rttTotal = 0.0;
        double maxRtt = 0.0;
        long bytesSent = 0L;
        long bytesReceived = 0L;
        for (TransportMetrics metric : metrics) {
            if (metric == null || !metric.available()) {
                staleOrMissing++;
                continue;
            }
            withSamples++;
            if (metric.bytesSent() >= 0) bytesSent = saturatingAdd(bytesSent, metric.bytesSent());
            if (metric.bytesReceived() >= 0) bytesReceived = saturatingAdd(bytesReceived, metric.bytesReceived());
            if (metric.stale(nowMillis, maxAgeMillis)) {
                staleOrMissing++;
                continue;
            }
            fresh++;
            rttTotal += metric.rttMillis();
            maxRtt = Math.max(maxRtt, metric.rttMillis());
        }
        return new TransportSummary(connectedPeers, withSamples, fresh, staleOrMissing,
                fresh == 0 ? -1.0 : rttTotal / fresh, fresh == 0 ? -1.0 : maxRtt,
                bytesSent, bytesReceived);
    }

    private static long saturatingAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private void sampleTransportStats() {
        if (closed.get()) return;
        List<PeerSession> active = sessions.values().stream()
                .filter(session -> session.connected && !session.sessionClosed.get())
                .sorted(java.util.Comparator.comparing(session -> session.networkId))
                .toList();
        if (active.isEmpty()) return;
        // Advance by one peer each second. Advancing by the batch size can starve
        // peers when the session count shares a divisor with the batch size.
        long cursor = transportStatsCursor.getAndIncrement();
        int start = (int) Math.floorMod(cursor, active.size());
        int count = Math.min(TRANSPORT_STATS_BATCH_SIZE, active.size());
        for (int i = 0; i < count; i++) {
            active.get((start + i) % active.size()).requestTransportStats();
        }
    }

    private static CompletableFuture<Void> setRemoteDescription(
            RTCPeerConnection peer,
            String offer
    ) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        peer.setRemoteDescription(new RTCSessionDescription(RTCSdpType.OFFER, offer),
                observer(future));
        return future;
    }

    private static CompletableFuture<RTCSessionDescription> createAnswer(RTCPeerConnection peer) {
        CompletableFuture<RTCSessionDescription> future = new CompletableFuture<>();
        peer.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription description) {
                future.complete(description);
            }

            @Override
            public void onFailure(String error) {
                future.completeExceptionally(new IllegalStateException("createAnswer failed: " + error));
            }
        });
        return future;
    }

    private static CompletableFuture<Void> setLocalDescription(
            RTCPeerConnection peer,
            RTCSessionDescription answer
    ) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        peer.setLocalDescription(answer, observer(future));
        return future;
    }

    private static SetSessionDescriptionObserver observer(CompletableFuture<Void> future) {
        return new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                future.complete(null);
            }

            @Override
            public void onFailure(String error) {
                future.completeExceptionally(new IllegalStateException("SDP operation failed: " + error));
            }
        };
    }

    private <T> T await(CompletableFuture<T> future, String operation) throws Exception {
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            throw new TimeoutException("Timed out while " + operation);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) throw checked;
            throw new IllegalStateException("Failed while " + operation, cause);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        sessionReaper.shutdownNow();
        sessions.values().forEach(PeerSession::close);
        sessions.clear();
        factory.dispose();
    }

    private final class PeerSession implements PeerConnectionObserver {
        private final String networkId;
        private final NetherNetClientIdentity identity;
        private final CompletableFuture<Void> iceGatheringComplete = new CompletableFuture<>();
        private final Map<String, RTCDataChannel> channels = new ConcurrentHashMap<>();
        private final Map<String, NetherNetOutboundQueue> outboundQueues = new ConcurrentHashMap<>();
        private final Map<String, AtomicBoolean> outboundDrainScheduled = new ConcurrentHashMap<>();
        private final Object outboundLock = new Object();
        private final AtomicBoolean sessionClosed = new AtomicBoolean();
        private final AtomicBoolean transportStatsPending = new AtomicBoolean();
        private volatile double transportRttMillis = -1.0;
        private volatile long transportBytesSent = -1L;
        private volatile long transportBytesReceived = -1L;
        private volatile long transportStatsSampledAtMillis;
        private volatile long lastActivityNanos = System.nanoTime();
        private volatile boolean connected;
        private volatile RTCPeerConnection peer;

        private PeerSession(String networkId, NetherNetClientIdentity identity) {
            this.networkId = networkId;
            this.identity = identity;
        }

        @Override
        public void onIceCandidate(RTCIceCandidate candidate) {
            // Full ICE is embedded in the SDP answer; no trickle signaling is used.
        }

        @Override
        public void onIceGatheringChange(RTCIceGatheringState state) {
            if (state == RTCIceGatheringState.COMPLETE) {
                iceGatheringComplete.complete(null);
            }
        }

        @Override
        public void onConnectionChange(RTCPeerConnectionState state) {
            if (state == RTCPeerConnectionState.CONNECTED) {
                connected = true;
                lastActivityNanos = System.nanoTime();
            }
            if (state == RTCPeerConnectionState.FAILED || state == RTCPeerConnectionState.CLOSED) {
                sessions.remove(networkId, this);
                close();
            }
        }

        @Override
        public void onDataChannel(RTCDataChannel channel) {
            lastActivityNanos = System.nanoTime();
            String label = channel.getLabel();
            boolean reliableLabel = "ReliableDataChannel".equals(label);
            boolean unreliableLabel = "UnreliableDataChannel".equals(label);
            if (!reliableLabel && !unreliableLabel) {
                channel.close();
                channel.dispose();
                closeForProtocolError("unexpected data-channel label: " + label);
                return;
            }
            if (channel.isReliable() != reliableLabel || channel.isOrdered() != reliableLabel) {
                channel.close();
                channel.dispose();
                closeForProtocolError("data-channel reliability does not match its NetherNet label");
                return;
            }

            RTCDataChannel previous = channels.putIfAbsent(label, channel);
            if (previous != null) {
                channel.close();
                channel.dispose();
                closeForProtocolError("duplicate data-channel label: " + label);
                return;
            }

            channel.registerObserver(new RTCDataChannelObserver() {
                @Override
                public void onBufferedAmountChange(long sentDataSize) {
                    scheduleOutboundDrain(label, channel);
                }

                @Override
                public void onStateChange() {
                    if (channel.getState() == RTCDataChannelState.CLOSED && !sessionClosed.get()) {
                        closeForProtocolError("NetherNet data channel closed unexpectedly");
                    }
                }

                @Override
                public void onMessage(RTCDataChannelBuffer buffer) {
                    lastActivityNanos = System.nanoTime();
                    if (!buffer.binary) {
                        closeForProtocolError("NetherNet game data channel sent a text message");
                        return;
                    }
                    ByteBuffer data = buffer.data.duplicate();
                    byte[] frame = new byte[data.remaining()];
                    data.get(frame);
                    try {
                        dataChannelListener.onFrame(networkId, label, frame);
                    } catch (RuntimeException exception) {
                        closeForProtocolError("invalid NetherNet frame: " + exception.getMessage());
                    }
                }
            });
        }

        private TransportMetrics transportMetrics() {
            return new TransportMetrics(transportRttMillis, transportBytesSent,
                    transportBytesReceived, transportStatsSampledAtMillis);
        }

        private void requestTransportStats() {
            RTCPeerConnection current = peer;
            if (sessionClosed.get() || current == null
                    || !connected || !transportStatsPending.compareAndSet(false, true)) return;
            try {
                current.getStats(report -> {
                    try {
                        updateTransportMetrics(report);
                    } finally {
                        transportStatsPending.set(false);
                    }
                });
            } catch (RuntimeException failure) {
                transportStatsPending.set(false);
            }
        }

        private void updateTransportMetrics(RTCStatsReport report) {
            if (sessionClosed.get() || report == null) return;
            for (RTCStats stat : report.getStats().values()) {
                if (stat.getType() != RTCStatsType.CANDIDATE_PAIR) continue;
                Map<String, Object> attributes = stat.getAttributes();
                if (!Boolean.TRUE.equals(attributes.get("nominated"))) continue;
                if (!"succeeded".equals(attributes.get("state"))) continue;
                Object rtt = attributes.get("currentRoundTripTime");
                Object sent = attributes.get("bytesSent");
                Object received = attributes.get("bytesReceived");
                if (rtt instanceof Number rttSeconds && Double.isFinite(rttSeconds.doubleValue())
                        && rttSeconds.doubleValue() >= 0.0) {
                    transportRttMillis = rttSeconds.doubleValue() * 1000.0;
                }
                if (sent instanceof Number sentBytes && sentBytes.longValue() >= 0) {
                    transportBytesSent = sentBytes.longValue();
                }
                if (received instanceof Number receivedBytes && receivedBytes.longValue() >= 0) {
                    transportBytesReceived = receivedBytes.longValue();
                }
                transportStatsSampledAtMillis = System.currentTimeMillis();
                return;
            }
        }

        private void sendFrame(String channelLabel, RTCDataChannel channel, byte[] frame) throws Exception {
            synchronized (outboundLock) {
                if (sessionClosed.get() || channel.getState() != RTCDataChannelState.OPEN) {
                    throw new IllegalStateException("NetherNet data channel is not open: " + channelLabel);
                }
                NetherNetOutboundQueue queue = outboundQueues.computeIfAbsent(
                        channelLabel, ignored -> new NetherNetOutboundQueue());
                long bufferedAmount = channel.getBufferedAmount();
                if (!queue.isEmpty() || bufferedAmount >= OUTBOUND_HIGH_WATERMARK_BYTES
                        || frame.length > OUTBOUND_HIGH_WATERMARK_BYTES - bufferedAmount) {
                    if (!queue.offer(frame)) {
                        closeForProtocolError("outbound queue limit exceeded on " + channelLabel);
                        throw new IllegalStateException("NetherNet outbound queue limit exceeded");
                    }
                    return;
                }
                try {
                    channel.send(new RTCDataChannelBuffer(ByteBuffer.wrap(frame), true));
                } catch (Exception exception) {
                    if (channel.getState() == RTCDataChannelState.OPEN) {
                        if (queue.offer(frame)) return;
                        closeForProtocolError("outbound queue limit exceeded after channel back-pressure");
                        throw new IllegalStateException("NetherNet outbound queue limit exceeded", exception);
                    }
                    throw exception;
                }
            }
        }

        private void scheduleOutboundDrain(String channelLabel, RTCDataChannel channel) {
            if (sessionClosed.get()) return;
            AtomicBoolean scheduled = outboundDrainScheduled.computeIfAbsent(
                    channelLabel, ignored -> new AtomicBoolean());
            if (!scheduled.compareAndSet(false, true)) return;
            try {
                sessionReaper.execute(() -> {
                    try {
                        drainOutbound(channelLabel, channel);
                    } finally {
                        scheduled.set(false);
                        if (canDrainOutbound(channelLabel, channel)) {
                            scheduleOutboundDrain(channelLabel, channel);
                        }
                    }
                });
            } catch (RejectedExecutionException ignored) {
                scheduled.set(false);
            }
        }

        private boolean canDrainOutbound(String channelLabel, RTCDataChannel channel) {
            if (sessionClosed.get() || channel.getState() != RTCDataChannelState.OPEN) return false;
            NetherNetOutboundQueue queue = outboundQueues.get(channelLabel);
            if (queue == null || queue.isEmpty()) return false;
            long bufferedAmount = channel.getBufferedAmount();
            if (bufferedAmount > OUTBOUND_LOW_WATERMARK_BYTES) return false;
            int nextFrameSize = queue.nextFrameSize();
            return bufferedAmount == 0
                    || nextFrameSize <= OUTBOUND_HIGH_WATERMARK_BYTES - bufferedAmount;
        }

        private void drainOutbound(String channelLabel, RTCDataChannel channel) {
            synchronized (outboundLock) {
                if (sessionClosed.get() || channel.getState() != RTCDataChannelState.OPEN) return;
                NetherNetOutboundQueue queue = outboundQueues.get(channelLabel);
                if (queue == null || queue.isEmpty()
                        || channel.getBufferedAmount() > OUTBOUND_LOW_WATERMARK_BYTES) return;
                while (!queue.isEmpty()) {
                    long bufferedAmount = channel.getBufferedAmount();
                    int nextFrameSize = queue.nextFrameSize();
                    if (bufferedAmount >= OUTBOUND_HIGH_WATERMARK_BYTES
                            || (bufferedAmount > 0
                            && nextFrameSize > OUTBOUND_HIGH_WATERMARK_BYTES - bufferedAmount)) {
                        break;
                    }
                    byte[] frame = queue.poll();
                    try {
                        channel.send(new RTCDataChannelBuffer(ByteBuffer.wrap(frame), true));
                    } catch (Exception exception) {
                        queue.offerFirst(frame);
                        closeForProtocolError("unable to drain outbound queue on " + channelLabel
                                + ": " + exception.getMessage());
                        return;
                    }
                }
            }
        }

        private void closeForProtocolError(String reason) {
            System.err.println("NEXORA: closing NetherNet session " + networkId + ": " + reason);
            sessions.remove(networkId, this);
            close();
        }

        private void close() {
            if (!sessionClosed.compareAndSet(false, true)) return;
            channels.values().forEach(channel -> {
                try {
                    channel.unregisterObserver();
                    channel.close();
                    channel.dispose();
                } catch (RuntimeException ignored) {
                    // Continue releasing the remaining native session resources.
                }
            });
            channels.clear();
            outboundQueues.values().forEach(NetherNetOutboundQueue::clear);
            outboundQueues.clear();
            outboundDrainScheduled.clear();
            try {
                dataChannelListener.onDisconnected(networkId);
            } catch (RuntimeException ignored) {
                // Session teardown must continue even if an observer fails.
            }
            RTCPeerConnection current = peer;
            if (current != null) {
                try {
                    // RTCPeerConnection.close() releases its native resources in webrtc-java.
                } catch (RuntimeException ignored) {
                    // The native connection may already have been closed by WebRTC.
                }
            }
            sessionPermits.release();
        }
    }
}
