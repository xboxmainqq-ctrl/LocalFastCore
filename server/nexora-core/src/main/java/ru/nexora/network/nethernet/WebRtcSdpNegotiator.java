package ru.nexora.network.nethernet;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelObserver;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceGatheringState;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import dev.onvoid.webrtc.CreateSessionDescriptionObserver;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebRTC answerer for NetherNet HTTP signaling.
 *
 * Client/server identity assertions are delegated to a mandatory cryptographic
 * service; this class never fabricates or skips those assertions.
 */
public final class WebRtcSdpNegotiator implements SdpNegotiator, AutoCloseable {
    private static final long DEFAULT_TIMEOUT_MILLIS = 15_000;

    private final PeerConnectionFactory factory;
    private final NetherNetIdentityService identityService;
    private final NetherNetDataChannelListener dataChannelListener;
    private final int maxSessions;
    private final long timeoutMillis;
    private final Map<String, PeerSession> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public WebRtcSdpNegotiator(
            NetherNetIdentityService identityService,
            NetherNetDataChannelListener dataChannelListener,
            int maxSessions
    ) {
        this(identityService, dataChannelListener, maxSessions, DEFAULT_TIMEOUT_MILLIS);
    }

    public WebRtcSdpNegotiator(
            NetherNetIdentityService identityService,
            NetherNetDataChannelListener dataChannelListener,
            int maxSessions,
            long timeoutMillis
    ) {
        this.identityService = Objects.requireNonNull(identityService, "identityService");
        this.dataChannelListener = Objects.requireNonNull(dataChannelListener, "dataChannelListener");
        if (maxSessions < 1) throw new IllegalArgumentException("maxSessions must be >= 1");
        if (timeoutMillis < 1) throw new IllegalArgumentException("timeoutMillis must be >= 1");
        this.maxSessions = maxSessions;
        this.timeoutMillis = timeoutMillis;
        this.factory = new PeerConnectionFactory();
    }

    @Override
    public String createAnswer(String networkId, String sdpOffer) throws Exception {
        if (closed.get()) throw new IllegalStateException("WebRTC negotiator is closed");
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(sdpOffer, "sdpOffer");
        if (networkId.isBlank() || networkId.length() > 256) {
            throw new IllegalArgumentException("Invalid NetworkID");
        }
        if (sessions.size() >= maxSessions || sessions.containsKey(networkId)) {
            throw new IllegalStateException("Server is full or NetworkID is already connected");
        }

        String verifiedOffer = identityService.validateClientOfferAndStripIdentity(networkId, sdpOffer);
        if (verifiedOffer == null || !verifiedOffer.startsWith("v=0")
                || verifiedOffer.contains("a=identity:")) {
            throw new SecurityException("Client identity service returned an invalid sanitized SDP offer");
        }

        PeerSession session = new PeerSession(networkId);
        RTCConfiguration configuration = new RTCConfiguration();
        configuration.iceServers.clear();
        configuration.bundlePolicy = dev.onvoid.webrtc.RTCBundlePolicy.MAX_BUNDLE;
        configuration.tcpCandidatePolicy = dev.onvoid.webrtc.RTCTcpCandidatePolicy.DISABLED;

        RTCPeerConnection peer = factory.createPeerConnection(configuration, session);
        if (peer == null) throw new IllegalStateException("WebRTC failed to create a PeerConnection");
        session.peer = peer;

        if (sessions.putIfAbsent(networkId, session) != null) {
            session.close();
            throw new IllegalStateException("NetworkID is already connected");
        }

        try {
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
        sessions.values().forEach(PeerSession::close);
        sessions.clear();
        factory.dispose();
    }

    private final class PeerSession implements PeerConnectionObserver {
        private final String networkId;
        private final CompletableFuture<Void> iceGatheringComplete = new CompletableFuture<>();
        private final Map<String, RTCDataChannel> channels = new ConcurrentHashMap<>();
        private final AtomicBoolean sessionClosed = new AtomicBoolean();
        private volatile RTCPeerConnection peer;

        private PeerSession(String networkId) {
            this.networkId = networkId;
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
            if (state == RTCPeerConnectionState.FAILED || state == RTCPeerConnectionState.CLOSED) {
                sessions.remove(networkId, this);
                close();
            }
        }

        @Override
        public void onDataChannel(RTCDataChannel channel) {
            String label = channel.getLabel();
            if (!"ReliableDataChannel".equals(label) && !"UnreliableDataChannel".equals(label)) {
                channel.close();
                channel.dispose();
                return;
            }

            RTCDataChannel previous = channels.put(label, channel);
            if (previous != null) {
                previous.unregisterObserver();
                previous.close();
                previous.dispose();
            }

            channel.registerObserver(new RTCDataChannelObserver() {
                @Override
                public void onBufferedAmountChange(long sentDataSize) {
                    // The core will add explicit back-pressure when outbound game batches are wired.
                }

                @Override
                public void onStateChange() {
                    // Session lifetime is tracked by the owning PeerConnection.
                }

                @Override
                public void onMessage(RTCDataChannelBuffer buffer) {
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
                        System.err.println("NEXORA: data-channel listener failed: " + exception.getMessage());
                    }
                }
            });
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
            RTCPeerConnection current = peer;
            if (current != null) {
                try {
                    current.close();
                    current.dispose();
                } catch (RuntimeException ignored) {
                    // The native connection may already have been closed by WebRTC.
                }
            }
        }
    }
}
