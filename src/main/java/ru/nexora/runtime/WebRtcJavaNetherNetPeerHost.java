package ru.nexora.runtime;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCBundlePolicy;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCContinualGatheringPolicy;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelObserver;
import dev.onvoid.webrtc.RTCDataChannelSendObserver;
import dev.onvoid.webrtc.RTCDataChannelState;
import dev.onvoid.webrtc.RTCIceGatheringState;
import dev.onvoid.webrtc.RTCIceTransportPolicy;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.RTCTcpCandidatePolicy;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Desktop/server WebRTC implementation of the NetherNet peer-host boundary.
 *
 * <p>Uses host ICE candidates only, no STUN/TURN, no TCP candidates, MAX_BUNDLE,
 * and full ICE gathering. A host must have a directly reachable UDP candidate;
 * NAT traversal/relay configuration is intentionally not added here.</p>
 */
public final class WebRtcJavaNetherNetPeerHost
        implements NetherNetPeerHost, NetworkTransport {
    public static final String RELIABLE_CHANNEL = "ReliableDataChannel";
    public static final String UNRELIABLE_CHANNEL = "UnreliableDataChannel";
    public static final int MAX_FRAME_BYTES = 64 * 1024;
    public static final int MAX_MESSAGE_BYTES = 8 * 1024 * 1024;
    public static final int MAX_FRAGMENTS = 255;
    public static final long MAX_OUTBOUND_BUFFERED_BYTES = 8L * 1024 * 1024;
    public static final int DEFAULT_MAX_PEERS = 256;
    private static final long FRAGMENT_TIMEOUT_NANOS = Duration.ofSeconds(10).toNanos();

    private final int maxPeers;
    private final ConcurrentMap<String, PeerSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PeerSession> sessionsByNetworkId = new ConcurrentHashMap<>();
    private final Semaphore peerPermits;
    private volatile Consumer<TransportConnection> connectionListener;
    private volatile PeerConnectionFactory factory;
    private volatile boolean closed;

    public WebRtcJavaNetherNetPeerHost() {
        this(DEFAULT_MAX_PEERS);
    }

    public WebRtcJavaNetherNetPeerHost(int maxPeers) {
        if (maxPeers < 1 || maxPeers > 10_000) {
            throw new IllegalArgumentException("maxPeers must be between 1 and 10000");
        }
        this.maxPeers = maxPeers;
        this.peerPermits = new Semaphore(maxPeers);
    }

    @Override
    public String id() {
        return "nethernet-webrtc-java";
    }

    @Override
    public TransportCapabilities capabilities() {
        return new TransportCapabilities(true, true, true, false);
    }

    @Override
    public void setConnectionListener(Consumer<TransportConnection> listener) {
        Objects.requireNonNull(listener, "listener");
        if (factory != null) {
            throw new IllegalStateException("Connection listener must be configured before start");
        }
        connectionListener = listener;
    }

    @Override
    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException("WebRTC peer host cannot be restarted after close");
        }
        if (factory != null) {
            throw new IllegalStateException("WebRTC peer host is already started");
        }
        if (connectionListener == null) {
            throw new IllegalStateException("Connection listener must be configured before start");
        }
        factory = new PeerConnectionFactory();
    }

    @Override
    public CompletionStage<String> createAnswer(String networkId, String webRtcOfferSdp) {
        return createAnswer(networkId, webRtcOfferSdp, null);
    }

    @Override
    public CompletionStage<String> createAnswer(
            String networkId, String webRtcOfferSdp,
            NetherNetIdentityValidator.VerifiedIdentity authenticatedIdentity) {
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(webRtcOfferSdp, "webRtcOfferSdp");
        if (authenticatedIdentity == null) {
            return CompletableFuture.failedFuture(
                    new SecurityException("A verified NetherNet identity is required"));
        }
        PeerConnectionFactory currentFactory = factory;
        if (closed || currentFactory == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("WebRTC peer host is not running"));
        }
        if (!peerPermits.tryAcquire()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("WebRTC peer limit reached"));
        }

        PeerSession session = new PeerSession(UUID.randomUUID().toString(), networkId, authenticatedIdentity);
        try {
            RTCConfiguration configuration = createConfiguration();
            RTCPeerConnection peer = currentFactory.createPeerConnection(
                    configuration, session.peerObserver);
            if (peer == null) {
                session.failAnswer(new IllegalStateException(
                        "WebRTC could not create a peer connection"));
                return session.answer;
            }
            session.peer = peer;
            peer.setAudioPlayout(false);
            peer.setAudioRecording(false);
            if (sessionsByNetworkId.putIfAbsent(networkId, session) != null) {
                session.failAnswer(new IllegalStateException("NetworkID is already active"));
                return session.answer;
            }
            sessions.put(session.id, session);
            peer.setRemoteDescription(
                    new RTCSessionDescription(RTCSdpType.OFFER, webRtcOfferSdp),
                    new SetSessionDescriptionObserver() {
                        @Override
                        public void onSuccess() {
                            createLocalAnswer(session);
                        }

                        @Override
                        public void onFailure(String error) {
                            session.failAnswer(new IllegalStateException(
                                    "Could not apply remote SDP offer: " + error));
                        }
                    });
        } catch (RuntimeException failure) {
            session.failAnswer(failure);
        }
        return session.answer;
    }

    @Override
    public void cancel(String networkId) {
        if (networkId == null) {
            return;
        }
        PeerSession session = sessionsByNetworkId.get(networkId);
        if (session != null) {
            session.close("NetherNet signaling request cancelled", false);
        }
    }

    private void createLocalAnswer(PeerSession session) {
        if (session.closed.get()) {
            return;
        }
        session.peer.createAnswer(new RTCAnswerOptions(), new dev.onvoid.webrtc.CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription description) {
                session.peer.setLocalDescription(description, new SetSessionDescriptionObserver() {
                    @Override
                    public void onSuccess() {
                        session.localDescriptionSet = true;
                        session.completeAnswerIfReady();
                    }

                    @Override
                    public void onFailure(String error) {
                        session.failAnswer(new IllegalStateException(
                                "Could not set local SDP answer: " + error));
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                session.failAnswer(new IllegalStateException(
                        "Could not create SDP answer: " + error));
            }
        });
    }

    static RTCConfiguration createConfiguration() {
        RTCConfiguration configuration = new RTCConfiguration();
        configuration.iceServers.clear();
        configuration.iceTransportPolicy = RTCIceTransportPolicy.ALL;
        configuration.bundlePolicy = RTCBundlePolicy.MAX_BUNDLE;
        configuration.tcpCandidatePolicy = RTCTcpCandidatePolicy.DISABLED;
        configuration.continualGatheringPolicy = RTCContinualGatheringPolicy.GATHER_ONCE;
        configuration.portAllocatorConfig.setDisableStun(true)
                .setDisableRelay(true)
                .setDisableTcp(true);
        return configuration;
    }

    @Override
    public synchronized void stop() {
        close();
    }

    @Override
    public synchronized void close() {
        if (closed && sessions.isEmpty() && factory == null) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        for (PeerSession session : sessions.values()) {
            try {
                session.close("WebRTC peer host stopped", false);
            } catch (RuntimeException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        sessions.clear();
        PeerConnectionFactory current = factory;
        if (current != null) {
            try {
                current.dispose();
                if (factory == current) {
                    factory = null;
                }
            } catch (RuntimeException disposeFailure) {
                if (failure == null) {
                    failure = disposeFailure;
                } else {
                    failure.addSuppressed(disposeFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private final class PeerSession {
        private final String id;
        private final String networkId;
        private final NetherNetIdentityValidator.VerifiedIdentity authenticatedIdentity;
        private final CompletableFuture<String> answer = new CompletableFuture<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean permitReleased = new AtomicBoolean();
        private final NetherNetFragmentAssembler reliableAssembler =
                new NetherNetFragmentAssembler(MAX_MESSAGE_BYTES, MAX_FRAGMENTS,
                        FRAGMENT_TIMEOUT_NANOS);
        private final PeerConnectionObserver peerObserver;
        private volatile RTCPeerConnection peer;
        private volatile RTCDataChannel reliableChannel;
        private volatile RTCDataChannel unreliableChannel;
        private volatile WebRtcTransportConnection transportConnection;
        private volatile boolean localDescriptionSet;
        private volatile boolean iceGatheringComplete;

        private PeerSession(String id, String networkId,
                            NetherNetIdentityValidator.VerifiedIdentity authenticatedIdentity) {
            this.id = id;
            this.networkId = networkId;
            this.authenticatedIdentity = Objects.requireNonNull(authenticatedIdentity, "authenticatedIdentity");
            this.peerObserver = new PeerConnectionObserver() {
                @Override
                public void onIceCandidate(dev.onvoid.webrtc.RTCIceCandidate candidate) {
                    // NetherNet uses full ICE; candidates are collected in the final SDP.
                }

                @Override
                public void onIceGatheringChange(RTCIceGatheringState state) {
                    if (state == RTCIceGatheringState.COMPLETE) {
                        iceGatheringComplete = true;
                        completeAnswerIfReady();
                    }
                }

                @Override
                public void onDataChannel(RTCDataChannel channel) {
                    attachDataChannel(channel);
                }

                @Override
                public void onConnectionChange(RTCPeerConnectionState state) {
                    if (state == RTCPeerConnectionState.FAILED
                            || state == RTCPeerConnectionState.CLOSED) {
                        close("WebRTC connection state: " + state, true);
                    }
                }
            };
        }

        private void attachDataChannel(RTCDataChannel channel) {
            if (closed.get()) {
                channel.close();
                channel.dispose();
                return;
            }
            String label = channel.getLabel();
            if (RELIABLE_CHANNEL.equals(label)) {
                if (!channel.isOrdered() || !channel.isReliable()
                        || channel.getMaxRetransmits() != -1
                        || channel.getMaxPacketLifeTime() != -1 || reliableChannel != null) {
                    rejectChannel(channel, "ReliableDataChannel has incompatible settings");
                    return;
                }
                reliableChannel = channel;
            } else if (UNRELIABLE_CHANNEL.equals(label)) {
                if (channel.isOrdered() || channel.isReliable()
                        || channel.getMaxRetransmits() != 0
                        || channel.getMaxPacketLifeTime() != -1 || unreliableChannel != null) {
                    rejectChannel(channel, "UnreliableDataChannel has incompatible settings");
                    return;
                }
                unreliableChannel = channel;
            } else {
                rejectChannel(channel, "Unexpected NetherNet data channel label");
                return;
            }

            channel.registerObserver(new RTCDataChannelObserver() {
                @Override
                public void onBufferedAmountChange(long sentDataSize) {
                    // The runtime applies explicit bounded-buffer admission in send().
                }

                @Override
                public void onStateChange() {
                    RTCDataChannelState state = channel.getState();
                    if (state == RTCDataChannelState.CLOSED) {
                        close("NetherNet data channel closed: " + label, true);
                    }
                }

                @Override
                public void onMessage(RTCDataChannelBuffer buffer) {
                    if (!buffer.binary) {
                        close("NetherNet data channel received a text message", true);
                        return;
                    }
                    ByteBuffer source = buffer.data.duplicate();
                    byte[] owned = new byte[source.remaining()];
                    source.get(owned);
                    receive(channel, ByteBuffer.wrap(owned));
                }
            });
            publishTransportConnectionIfReady();
        }

        private void rejectChannel(RTCDataChannel channel, String reason) {
            channel.close();
            channel.dispose();
            close(reason, true);
        }

        private void publishTransportConnectionIfReady() {
            if (reliableChannel == null || unreliableChannel == null || transportConnection != null
                    || closed.get()) {
                return;
            }
            WebRtcTransportConnection connection =
                    new WebRtcTransportConnection(this, reliableChannel, unreliableChannel);
            transportConnection = connection;
            try {
                connectionListener.accept(connection);
            } catch (RuntimeException callbackFailure) {
                close("Transport connection listener failed", false);
            }
        }

        private void receive(RTCDataChannel channel, ByteBuffer framed) {
            if (closed.get()) {
                return;
            }
            try {
                if (framed.remaining() > MAX_FRAME_BYTES) {
                    throw new IllegalArgumentException("NetherNet frame exceeds configured byte limit");
                }
                if (channel == reliableChannel) {
                    Optional<ByteBuffer> complete;
                    synchronized (reliableAssembler) {
                        complete = reliableAssembler.accept(framed);
                    }
                    complete.ifPresent(payload -> deliver(
                            new TransportMessage(payload, DeliveryMode.RELIABLE_ORDERED)));
                } else if (channel == unreliableChannel) {
                    ByteBuffer payload = NetherNetMessageFraming.decodeUnreliable(
                            framed, MAX_FRAME_BYTES);
                    deliver(new TransportMessage(payload, DeliveryMode.UNRELIABLE));
                } else {
                    close("Message arrived on an unrecognized data channel", true);
                }
            } catch (RuntimeException invalidFrame) {
                close("Invalid NetherNet data framing: " + invalidFrame.getMessage(), true);
            }
        }

        private void deliver(TransportMessage message) {
            WebRtcTransportConnection connection = transportConnection;
            if (connection != null) {
                connection.deliver(message);
            }
        }

        private void completeAnswerIfReady() {
            if (answer.isDone() || closed.get() || !localDescriptionSet || !iceGatheringComplete) {
                return;
            }
            RTCSessionDescription local = peer.getLocalDescription();
            if (local == null || local.sdp == null || local.sdp.isBlank()) {
                failAnswer(new IllegalStateException("WebRTC completed ICE without a local SDP answer"));
                return;
            }
            answer.complete(local.sdp);
        }

        private void failAnswer(Throwable failure) {
            answer.completeExceptionally(failure);
            close("WebRTC negotiation failed: " + failure.getMessage(), false);
        }

        private void close(String description, boolean remote) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            answer.completeExceptionally(new IllegalStateException(description));
            reliableAssembler.reset();
            sessions.remove(id, this);
            sessionsByNetworkId.remove(networkId, this);
            if (permitReleased.compareAndSet(false, true)) {
                peerPermits.release();
            }
            RTCDataChannel reliable = reliableChannel;
            RTCDataChannel unreliable = unreliableChannel;
            if (reliable != null) {
                try {
                    reliable.close();
                    reliable.dispose();
                } catch (RuntimeException ignored) {
                    // Continue releasing the other native resources.
                }
            }
            if (unreliable != null) {
                try {
                    unreliable.close();
                    unreliable.dispose();
                } catch (RuntimeException ignored) {
                    // Continue releasing the peer connection.
                }
            }
            RTCPeerConnection currentPeer = peer;
            if (currentPeer != null) {
                try {
                    currentPeer.close();
                    currentPeer.dispose();
                } catch (RuntimeException ignored) {
                    // Native close is idempotent; best-effort cleanup continues.
                }
            }
            WebRtcTransportConnection connection = transportConnection;
            if (connection != null) {
                connection.notifyClosed(description, remote);
            }
        }
    }

    private static final class WebRtcTransportConnection implements NetherNetAuthenticatedTransportConnection {
        private final PeerSession session;
        private final RTCDataChannel reliable;
        private final RTCDataChannel unreliable;
        private volatile Consumer<TransportMessage> messageListener;
        private volatile Consumer<TransportCloseEvent> closeListener;
        private final AtomicBoolean closeNotified = new AtomicBoolean();

        private WebRtcTransportConnection(PeerSession session,
                                          RTCDataChannel reliable,
                                          RTCDataChannel unreliable) {
            this.session = session;
            this.reliable = reliable;
            this.unreliable = unreliable;
        }

        @Override
        public NetherNetIdentityValidator.VerifiedIdentity authenticatedIdentity() {
            return session.authenticatedIdentity;
        }

        @Override
        public String id() {
            return session.id;
        }

        @Override
        public boolean isOpen() {
            return !session.closed.get()
                    && reliable.getState() == RTCDataChannelState.OPEN
                    && unreliable.getState() == RTCDataChannelState.OPEN;
        }

        @Override
        public void setMessageListener(Consumer<TransportMessage> listener) {
            messageListener = Objects.requireNonNull(listener, "listener");
        }

        @Override
        public void setCloseListener(Consumer<TransportCloseEvent> listener) {
            closeListener = Objects.requireNonNull(listener, "listener");
        }

        @Override
        public TransportSendResult send(ByteBuffer payload, DeliveryMode deliveryMode) {
            Objects.requireNonNull(payload, "payload");
            Objects.requireNonNull(deliveryMode, "deliveryMode");
            if (!isOpen()) {
                return TransportSendResult.CLOSED;
            }
            ByteBuffer source = payload.slice();
            if (source.remaining() > MAX_MESSAGE_BYTES) {
                throw new IllegalArgumentException("NetherNet message exceeds configured byte limit");
            }

            RTCDataChannel channel;
            List<ByteBuffer> frames;
            if (deliveryMode == DeliveryMode.RELIABLE_ORDERED) {
                channel = reliable;
                frames = NetherNetMessageFraming.fragmentReliable(
                        source, MAX_FRAME_BYTES, MAX_FRAGMENTS);
            } else {
                channel = unreliable;
                frames = List.of(NetherNetMessageFraming.frameUnreliable(source, MAX_FRAME_BYTES));
            }

            long queuedBytes = 0;
            for (ByteBuffer frame : frames) {
                queuedBytes += frame.remaining();
            }
            if (channel.getBufferedAmount() + queuedBytes > MAX_OUTBOUND_BUFFERED_BYTES) {
                return TransportSendResult.BACKPRESSURE;
            }

            try {
                for (ByteBuffer frame : frames) {
                    channel.sendAsync(new RTCDataChannelBuffer(frame.asReadOnlyBuffer(), true),
                            new RTCDataChannelSendObserver() {
                                @Override
                                public void onSuccess() {
                                    // Accepted locally; delivery is not acknowledged here.
                                }

                                @Override
                                public void onFailure(String error) {
                                    session.close("WebRTC data send failed: " + error, false);
                                }
                            });
                }
                return TransportSendResult.ACCEPTED;
            } catch (RuntimeException failure) {
                session.close("WebRTC data send failed: " + failure.getMessage(), false);
                return TransportSendResult.CLOSED;
            }
        }

        private void deliver(TransportMessage message) {
            Consumer<TransportMessage> listener = messageListener;
            if (listener != null && !session.closed.get()) {
                try {
                    listener.accept(message);
                } catch (RuntimeException callbackFailure) {
                    session.close("Transport message listener failed", true);
                }
            }
        }

        private void notifyClosed(String description, boolean remote) {
            if (!closeNotified.compareAndSet(false, true)) {
                return;
            }
            Consumer<TransportCloseEvent> listener = closeListener;
            if (listener != null) {
                try {
                    listener.accept(new TransportCloseEvent(description, remote));
                } catch (RuntimeException ignored) {
                    // Listener failures must not prevent native cleanup.
                }
            }
        }

        @Override
        public void close() {
            session.close("Transport connection closed locally", false);
        }
    }
}
