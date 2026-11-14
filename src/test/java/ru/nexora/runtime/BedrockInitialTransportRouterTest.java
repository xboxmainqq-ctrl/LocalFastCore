package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class BedrockInitialTransportRouterTest {
    private static final String JWT = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.c2ln";
    private static final String LOGIN_JSON = "{\\"chain\\":[\\"" + JWT + "\\"],\\"clientData\\":\\"" + JWT + "\\"}";
    private static final BedrockNetworkSettings SETTINGS = new BedrockNetworkSettings(
            1, BedrockNetworkSettings.CompressionAlgorithm.ZLIB, false, 0, 0.0f);

    @Test
    void sendsSettingsAuthenticatesThenForwardsPlayMessages() {
        AtomicInteger playStarts = new AtomicInteger();
        AtomicInteger playMessages = new AtomicInteger();
        BedrockInitialTransportRouter router = new BedrockInitialTransportRouter(
                SETTINGS,
                (connection, login, request) -> {
                    assertEquals(LOGIN_JSON, login.connectionRequest());
                    assertEquals(List.of(JWT), request.chain());
                    assertEquals(JWT, request.clientDataJwt());
                    return CompletableFuture.completedFuture(authenticatedPlayer());
                },
                (connection, player) -> { assertEquals("NEXORA-Test", player.displayName()); playStarts.incrementAndGet(); },
                (connection, message) -> playMessages.incrementAndGet());

        FakeConnection connection = new FakeConnection("peer-1");
        router.accept(connection);
        assertEquals(BedrockHandshakeResult.INVALID_STATE, router.markPlayReady(connection));
        connection.receive(frame(BedrockRequestNetworkSettingsPacketCodec.PACKET_ID,
                BedrockRequestNetworkSettingsPacketCodec.encodePayload(
                        new BedrockRequestNetworkSettingsPacket(2225)), false));

        assertEquals(1, connection.sent.size());
        assertEquals(BedrockNetworkSettingsPacketCodec.PACKET_ID,
                BedrockPacketBatchCodec.decodeUncompressed(
                        BedrockBatchCompressionCodec.decodeBeforeNetworkSettings(
                                connection.sent.get(0), 1024)).get(0).header().packetId());

        connection.receive(frame(BedrockLoginPacketCodec.PACKET_ID,
                BedrockLoginPacketCodec.encodePayload(new BedrockLoginPacket(2225, LOGIN_JSON)),
                true));

        assertEquals(2, connection.sent.size());
        ByteBuffer loginSuccessBatch = BedrockBatchCompressionCodec.decodeAfterNetworkSettings(
                connection.sent.get(1), SETTINGS);
        BedrockGamePacket loginSuccess = BedrockPacketBatchCodec.decodeUncompressed(loginSuccessBatch).get(0);
        assertEquals(BedrockPlayStatusPacketCodec.PACKET_ID, loginSuccess.header().packetId());
        assertEquals(BedrockPlayStatus.LOGIN_SUCCESS,
                BedrockPlayStatusPacketCodec.decodePayload(loginSuccess.payloadBuffer()));
        assertEquals(1, playStarts.get());
        assertTrue(connection.isOpen());

        // Resource-pack/world-startup responses are handled before PLAY.
        connection.receive(new TransportMessage(ByteBuffer.wrap(new byte[]{6, 5, 4}),
                DeliveryMode.RELIABLE_ORDERED));
        assertEquals(1, playMessages.get());
        assertEquals(BedrockHandshakeResult.ACCEPTED, router.markPlayReady(connection));

        connection.receive(new TransportMessage(ByteBuffer.wrap(new byte[]{9, 8, 7}),
                DeliveryMode.UNRELIABLE));
        assertEquals(2, playMessages.get());

        router.close();
        assertFalse(connection.isOpen());
    }

    @Test
    void authenticatesLoginUsingIdentityVerifiedBeforeNetherNetPeerAllocation() throws Exception {
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("EC");
        generator.initialize(new java.security.spec.ECGenParameterSpec("secp384r1"));
        java.security.interfaces.ECPublicKey key =
                (java.security.interfaces.ECPublicKey) generator.generateKeyPair().getPublic();
        NetherNetIdentityValidator.VerifiedIdentity identity =
                new NetherNetIdentityValidator.VerifiedIdentity(
                        "minecraft-services.net", "nethernet-xuid", "NetherNetPlayer", key);
        AtomicInteger playStarts = new AtomicInteger();
        BedrockInitialTransportRouter router = new BedrockInitialTransportRouter(
                SETTINGS,
                (connection, login, request) -> {
                    fail("A verified NetherNet identity must not require a second login-token verifier");
                    return CompletableFuture.completedFuture(null);
                },
                (connection, player) -> {
                    assertEquals("nethernet-xuid", player.identityId());
                    assertEquals("NetherNetPlayer", player.displayName());
                    assertSame(key, player.clientIdentityPublicKey());
                    playStarts.incrementAndGet();
                },
                (connection, message) -> { });
        FakeConnection connection = new FakeConnection("peer-nethernet-auth", identity);
        router.accept(connection);
        connection.receive(frame(BedrockRequestNetworkSettingsPacketCodec.PACKET_ID,
                BedrockRequestNetworkSettingsPacketCodec.encodePayload(
                        new BedrockRequestNetworkSettingsPacket(2225)), false));
        connection.receive(frame(BedrockLoginPacketCodec.PACKET_ID,
                BedrockLoginPacketCodec.encodePayload(new BedrockLoginPacket(2225, LOGIN_JSON)),
                true));

        assertTrue(connection.isOpen());
        assertEquals(1, playStarts.get());
        router.close();
    }

    @Test
    void rejectsLoginWhenAuthenticatorDeniesIt() {
        BedrockInitialTransportRouter router = new BedrockInitialTransportRouter(
                SETTINGS,
                (connection, login, request) -> CompletableFuture.completedFuture(null),
                (connection, player) -> fail("Rejected login must not enter play"),
                (connection, message) -> fail("Rejected login must not receive play messages"));
        FakeConnection connection = new FakeConnection("peer-2");
        router.accept(connection);

        connection.receive(frame(BedrockRequestNetworkSettingsPacketCodec.PACKET_ID,
                BedrockRequestNetworkSettingsPacketCodec.encodePayload(
                        new BedrockRequestNetworkSettingsPacket(2225)), false));
        connection.receive(frame(BedrockLoginPacketCodec.PACKET_ID,
                BedrockLoginPacketCodec.encodePayload(new BedrockLoginPacket(2225, LOGIN_JSON)),
                true));

        assertFalse(connection.isOpen());
        router.close();
    }


    @Test
    void rejectsMalformedConnectionRequestBeforeCallingAuthenticator() {
        BedrockInitialTransportRouter router = new BedrockInitialTransportRouter(
                SETTINGS,
                (connection, login, request) -> {
                    fail("Malformed JSON must not reach the authenticator");
                    return CompletableFuture.completedFuture(null);
                },
                (connection, player) -> fail("Malformed login must not enter play"),
                (connection, message) -> fail("Malformed login must not receive play messages"));
        FakeConnection connection = new FakeConnection("peer-invalid-json");
        router.accept(connection);

        connection.receive(frame(BedrockRequestNetworkSettingsPacketCodec.PACKET_ID,
                BedrockRequestNetworkSettingsPacketCodec.encodePayload(
                        new BedrockRequestNetworkSettingsPacket(2225)), false));
        connection.receive(frame(BedrockLoginPacketCodec.PACKET_ID,
                BedrockLoginPacketCodec.encodePayload(new BedrockLoginPacket(2225, "not-json")),
                true));

        assertFalse(connection.isOpen());
        router.close();
    }

    @Test
    void rejectsHandshakeFramesOnUnreliableDelivery() {
        BedrockInitialTransportRouter router = new BedrockInitialTransportRouter(
                SETTINGS,
                (connection, login, request) -> CompletableFuture.completedFuture(authenticatedPlayer()),
                (connection, player) -> fail("Invalid handshake must not enter play"),
                (connection, message) -> fail("Invalid handshake must not reach play"));
        FakeConnection connection = new FakeConnection("peer-3");
        router.accept(connection);

        connection.receive(new TransportMessage(ByteBuffer.wrap(new byte[]{1}),
                DeliveryMode.UNRELIABLE));

        assertFalse(connection.isOpen());
        router.close();
    }

    @Test
    void rejectsUnreliableMessagesUntilWorldInitializationCompletes() {
        BedrockInitialTransportRouter router = new BedrockInitialTransportRouter(
                SETTINGS,
                (connection, login, request) -> CompletableFuture.completedFuture(authenticatedPlayer()),
                (connection, player) -> { },
                (connection, message) -> fail("Unreliable traffic is not allowed before PLAY"));
        FakeConnection connection = new FakeConnection("peer-post-login-unreliable");
        router.accept(connection);
        connection.receive(frame(BedrockRequestNetworkSettingsPacketCodec.PACKET_ID,
                BedrockRequestNetworkSettingsPacketCodec.encodePayload(
                        new BedrockRequestNetworkSettingsPacket(2225)), false));
        connection.receive(frame(BedrockLoginPacketCodec.PACKET_ID,
                BedrockLoginPacketCodec.encodePayload(new BedrockLoginPacket(2225, LOGIN_JSON)),
                true));

        assertTrue(connection.isOpen());
        connection.receive(new TransportMessage(ByteBuffer.wrap(new byte[]{1, 2, 3}),
                DeliveryMode.UNRELIABLE));
        assertFalse(connection.isOpen());
        router.close();
    }

    @Test
    void closesPeerThatNeverSendsLogin() throws Exception {
        BedrockInitialTransportRouter router = new BedrockInitialTransportRouter(
                SETTINGS,
                (connection, login, request) -> CompletableFuture.completedFuture(null),
                (connection, player) -> { },
                (connection, message) -> { },
                java.time.Duration.ofMillis(40));
        FakeConnection connection = new FakeConnection("peer-login-timeout");
        router.accept(connection);
        connection.receive(frame(BedrockRequestNetworkSettingsPacketCodec.PACKET_ID,
                BedrockRequestNetworkSettingsPacketCodec.encodePayload(
                        new BedrockRequestNetworkSettingsPacket(2225)), false));

        assertEquals(1, connection.sent.size());
        assertTrue(connection.closedLatch.await(2, java.util.concurrent.TimeUnit.SECONDS));
        router.close();
    }

    private static BedrockAuthenticatedPlayer authenticatedPlayer() {
        try {
            java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("EC");
            generator.initialize(new java.security.spec.ECGenParameterSpec("secp384r1"));
            java.security.interfaces.ECPublicKey key =
                    (java.security.interfaces.ECPublicKey) generator.generateKeyPair().getPublic();
            return new BedrockAuthenticatedPlayer("test-xuid", "NEXORA-Test", key);
        } catch (java.security.GeneralSecurityException failure) {
            throw new AssertionError(failure);
        }
    }

    private static ByteBuffer frame(int packetId, ByteBuffer payload, boolean compressed) {
        byte[] bytes = new byte[payload.remaining()];
        payload.get(bytes);
        ByteBuffer batch = BedrockPacketBatchCodec.encodeUncompressed(List.of(
                new BedrockGamePacket(new BedrockPacketHeader(packetId, 0, 0), bytes)));
        return compressed
                ? BedrockBatchCompressionCodec.encodeAfterNetworkSettings(batch, SETTINGS)
                : BedrockBatchCompressionCodec.encodeBeforeNetworkSettings(batch);
    }

    private static final class FakeConnection implements NetherNetAuthenticatedTransportConnection {
        private final String id;
        private final java.util.Optional<NetherNetIdentityValidator.VerifiedIdentity> authenticatedIdentity;
        private Consumer<TransportMessage> messageListener;
        private Consumer<TransportCloseEvent> closeListener;
        private final List<ByteBuffer> sent = new ArrayList<>();
        private final java.util.concurrent.CountDownLatch closedLatch =
                new java.util.concurrent.CountDownLatch(1);
        private boolean open = true;

        private FakeConnection(String id) { this(id, null); }
        private FakeConnection(String id, NetherNetIdentityValidator.VerifiedIdentity identity) {
            this.id = id;
            this.authenticatedIdentity = java.util.Optional.ofNullable(identity);
        }
        @Override public java.util.Optional<NetherNetIdentityValidator.VerifiedIdentity> authenticatedIdentity() {
            return authenticatedIdentity;
        }
        @Override public String id() { return id; }
        @Override public boolean isOpen() { return open; }
        @Override public void setMessageListener(Consumer<TransportMessage> listener) {
            messageListener = listener;
        }
        @Override public void setCloseListener(Consumer<TransportCloseEvent> listener) {
            closeListener = listener;
        }
        @Override public TransportSendResult send(ByteBuffer payload, DeliveryMode mode) {
            if (!open) return TransportSendResult.CLOSED;
            ByteBuffer copy = ByteBuffer.allocate(payload.remaining());
            copy.put(payload.duplicate()).flip();
            sent.add(copy.asReadOnlyBuffer());
            return TransportSendResult.ACCEPTED;
        }
        @Override public void close() {
            if (!open) return;
            open = false;
            closedLatch.countDown();
            if (closeListener != null) closeListener.accept(
                    new TransportCloseEvent("closed in test", false));
        }
        private void receive(TransportMessage message) {
            assertNotNull(messageListener);
            messageListener.accept(message);
        }
    }
}
