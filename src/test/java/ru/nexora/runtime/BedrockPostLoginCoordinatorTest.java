package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class BedrockPostLoginCoordinatorTest {
    private static final BedrockNetworkSettings SETTINGS = new BedrockNetworkSettings(
            0, BedrockNetworkSettings.CompressionAlgorithm.NONE, false, 0, 0.0f);

    @Test
    void negotiatesEmptyPacksThenStartsWorldBeforeMarkingPlayReady() throws Exception {
        AtomicInteger worldStarts = new AtomicInteger();
        AtomicInteger playReady = new AtomicInteger();
        AtomicInteger playMessages = new AtomicInteger();
        BedrockPostLoginCoordinator coordinator = new BedrockPostLoginCoordinator(
                SETTINGS, "1.26.60",
                (connection, player) -> {
                    assertEquals("NEXORA", player.displayName());
                    worldStarts.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                },
                (connection, message) -> playMessages.incrementAndGet());
        coordinator.bindPlayReadyTransition(connection -> {
            playReady.incrementAndGet();
            return BedrockHandshakeResult.ACCEPTED;
        });

        FakeConnection connection = new FakeConnection();
        coordinator.accept(connection, player());

        assertEquals(1, connection.sent.size());
        assertEquals(BedrockResourcePackCodec.RESOURCE_PACKS_INFO_PACKET_ID,
                decodeSentPacket(connection.sent.get(0)).header().packetId());

        coordinator.handleMessage(connection, response("downloadingfinished"));
        assertEquals(2, connection.sent.size());
        assertEquals(BedrockResourcePackCodec.RESOURCE_PACK_STACK_PACKET_ID,
                decodeSentPacket(connection.sent.get(1)).header().packetId());
        assertEquals(0, worldStarts.get());
        assertEquals(0, playReady.get());

        coordinator.handleMessage(connection, response("resourcepackstackfinished"));
        coordinator.handleMessage(connection, resourcePacksReadyForValidation());
        assertEquals(1, worldStarts.get());
        assertEquals(1, playReady.get());
        assertTrue(connection.isOpen());

        coordinator.handleMessage(connection, new TransportMessage(ByteBuffer.wrap(new byte[]{1}),
                DeliveryMode.UNRELIABLE));
        assertEquals(1, playMessages.get());
        coordinator.close();
    }


    @Test
    void downloadsRequestedResourcePackChunksBeforeSendingStack() throws Exception {
        byte[] archive = new byte[BedrockResourcePackTransferCodec.DEFAULT_CHUNK_BYTES + 13];
        for (int i = 0; i < archive.length; i++) archive[i] = (byte) (i * 17);
        BedrockResourcePackTransferCodec.Pack pack =
                new BedrockResourcePackTransferCodec.Pack(
                        java.util.UUID.fromString("12345678-1234-5678-9abc-def012345678"),
                        "1.0.0", archive);
        AtomicInteger worldStarts = new AtomicInteger();
        BedrockPostLoginCoordinator coordinator = new BedrockPostLoginCoordinator(
                SETTINGS, "1.26.60", List.of(pack), true,
                (connection, authenticated) -> {
                    worldStarts.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                }, (connection, message) -> { });
        coordinator.bindPlayReadyTransition(connection -> BedrockHandshakeResult.ACCEPTED);

        FakeConnection connection = new FakeConnection();
        coordinator.accept(connection, player());
        assertEquals(6, decodeSentPacket(connection.sent.get(0)).header().packetId());

        coordinator.handleMessage(connection, response("downloading", List.of(pack.resourceName())));
        assertEquals(2, connection.sent.size());
        BedrockGamePacket info = decodeSentPacket(connection.sent.get(1));
        assertEquals(BedrockResourcePackTransferCodec.DATA_INFO_PACKET_ID, info.header().packetId());

        coordinator.handleMessage(connection, chunkRequest(pack.resourceName(), 0));
        coordinator.handleMessage(connection, chunkRequest(pack.resourceName(), 1));
        assertEquals(4, connection.sent.size());
        BedrockGamePacket firstChunk = decodeSentPacket(connection.sent.get(2));
        BedrockGamePacket secondChunk = decodeSentPacket(connection.sent.get(3));
        assertEquals(BedrockResourcePackTransferCodec.CHUNK_DATA_PACKET_ID,
                firstChunk.header().packetId());
        assertEquals(BedrockResourcePackTransferCodec.CHUNK_DATA_PACKET_ID,
                secondChunk.header().packetId());

        coordinator.handleMessage(connection, response("downloadingfinished"));
        assertEquals(5, connection.sent.size());
        assertEquals(BedrockResourcePackCodec.RESOURCE_PACK_STACK_PACKET_ID,
                decodeSentPacket(connection.sent.get(4)).header().packetId());
        coordinator.handleMessage(connection, response("resourcepackstackfinished"));
        coordinator.handleMessage(connection, resourcePacksReadyForValidation());
        assertEquals(1, worldStarts.get());
        assertTrue(connection.isOpen());
        coordinator.close();
    }

    @Test
    void routesSubchunkRequestsWhileWorldStartupIsStillCompleting() throws Exception {
        CompletableFuture<Void> startup = new CompletableFuture<>();
        AtomicInteger gameplayMessages = new AtomicInteger();
        AtomicInteger playReady = new AtomicInteger();
        BedrockPostLoginCoordinator coordinator = new BedrockPostLoginCoordinator(
                SETTINGS, "1.26.60",
                (connection, player) -> startup,
                (connection, message) -> gameplayMessages.incrementAndGet());
        coordinator.bindPlayReadyTransition(connection -> {
            playReady.incrementAndGet();
            return BedrockHandshakeResult.ACCEPTED;
        });
        FakeConnection connection = new FakeConnection();
        coordinator.accept(connection, player());
        coordinator.handleMessage(connection, response("downloadingfinished"));
        coordinator.handleMessage(connection, response("resourcepackstackfinished"));
        coordinator.handleMessage(connection, resourcePacksReadyForValidation());

        // This frame represents a client SubChunkRequest received after
        // StartGame/skeleton chunks were sent but before startup completes.
        coordinator.handleMessage(connection, new TransportMessage(
                ByteBuffer.wrap(new byte[] {0x01}), DeliveryMode.RELIABLE_ORDERED));

        assertEquals(1, gameplayMessages.get());
        assertEquals(0, playReady.get());
        assertTrue(connection.isOpen());

        startup.complete(null);
        assertEquals(1, playReady.get());
        assertTrue(connection.isOpen());
        coordinator.close();
    }

    @Test
    void permitsEmptyDownloadingProgressButRejectsUnexpectedPackIdentifiers() throws Exception {
        BedrockPostLoginCoordinator coordinator = coordinator(
                (connection, player) -> CompletableFuture.completedFuture(null),
                connection -> BedrockHandshakeResult.ACCEPTED);
        FakeConnection connection = new FakeConnection();
        coordinator.accept(connection, player());

        coordinator.handleMessage(connection, response("downloading", List.of()));
        assertTrue(connection.isOpen());
        assertEquals(1, connection.sent.size());

        coordinator.handleMessage(connection, response("downloading", List.of("unexpected-pack")));
        assertFalse(connection.isOpen());
        coordinator.close();
    }

    @Test
    void rejectsOutOfOrderResourcePackResponses() throws Exception {
        BedrockPostLoginCoordinator coordinator = coordinator(
                (connection, player) -> CompletableFuture.completedFuture(null),
                connection -> BedrockHandshakeResult.ACCEPTED);
        FakeConnection connection = new FakeConnection();
        coordinator.accept(connection, player());

        coordinator.handleMessage(connection, response("resourcepackstackfinished"));
        assertFalse(connection.isOpen());
        coordinator.close();
    }

    @Test
    void closesConnectionWhenWorldStartupFailsOrPlayTransitionRejects() throws Exception {
        BedrockPostLoginCoordinator startupFailure = coordinator(
                (connection, player) -> CompletableFuture.failedFuture(
                        new IllegalStateException("start game failed")),
                connection -> BedrockHandshakeResult.ACCEPTED);
        FakeConnection first = new FakeConnection();
        startupFailure.accept(first, player());
        startupFailure.handleMessage(first, response("downloadingfinished"));
        startupFailure.handleMessage(first, response("resourcepackstackfinished"));
        assertFalse(first.isOpen());
        startupFailure.close();

        BedrockPostLoginCoordinator transitionFailure = coordinator(
                (connection, player) -> CompletableFuture.completedFuture(null),
                connection -> BedrockHandshakeResult.INVALID_STATE);
        FakeConnection second = new FakeConnection();
        transitionFailure.accept(second, player());
        transitionFailure.handleMessage(second, response("downloadingfinished"));
        transitionFailure.handleMessage(second, response("resourcepackstackfinished"));
        assertFalse(second.isOpen());
        transitionFailure.close();
    }

    private static BedrockPostLoginCoordinator coordinator(
            BedrockPostLoginCoordinator.WorldStartupHandler startup,
            java.util.function.Function<TransportConnection, BedrockHandshakeResult> transition
    ) {
        BedrockPostLoginCoordinator coordinator = new BedrockPostLoginCoordinator(
                SETTINGS, "1.26.60", startup, (connection, message) -> { });
        coordinator.bindPlayReadyTransition(transition);
        return coordinator;
    }

    private static BedrockAuthenticatedPlayer player() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        ECPublicKey key = (ECPublicKey) generator.generateKeyPair().getPublic();
        return new BedrockAuthenticatedPlayer("test-xuid", "NEXORA", key);
    }

    @Test
    void rejectsCompletedResponseUntilValidationReadyPacketArrives() throws Exception {
        BedrockPostLoginCoordinator coordinator = coordinator(
                (connection, player) -> CompletableFuture.completedFuture(null),
                connection -> BedrockHandshakeResult.ACCEPTED);
        FakeConnection connection = new FakeConnection();
        coordinator.accept(connection, player());
        coordinator.handleMessage(connection, response("downloadingfinished"));
        coordinator.handleMessage(connection, response("resourcepackstackfinished"));
        assertFalse(connection.isOpen());
        coordinator.close();
    }

    @Test
    void rejectsDuplicateOrNonEmptyValidationReadyPacket() throws Exception {
        BedrockPostLoginCoordinator coordinator = coordinator(
                (connection, player) -> CompletableFuture.completedFuture(null),
                connection -> BedrockHandshakeResult.ACCEPTED);
        FakeConnection connection = new FakeConnection();
        coordinator.accept(connection, player());
        coordinator.handleMessage(connection, response("downloadingfinished"));
        coordinator.handleMessage(connection, resourcePacksReadyForValidation());
        assertTrue(connection.isOpen());
        coordinator.handleMessage(connection, resourcePacksReadyForValidation());
        assertFalse(connection.isOpen());
        coordinator.close();

        BedrockPostLoginCoordinator invalid = coordinator(
                (peer, player) -> CompletableFuture.completedFuture(null),
                peer -> BedrockHandshakeResult.ACCEPTED);
        FakeConnection invalidConnection = new FakeConnection();
        invalid.accept(invalidConnection, player());
        invalid.handleMessage(invalidConnection, response("downloadingfinished"));
        invalid.handleMessage(invalidConnection, resourcePacksReadyForValidation(new byte[] {1}));
        assertFalse(invalidConnection.isOpen());
        invalid.close();
    }

    private static TransportMessage resourcePacksReadyForValidation() {
        return resourcePacksReadyForValidation(new byte[0]);
    }

    private static TransportMessage resourcePacksReadyForValidation(byte[] payload) {
        ByteBuffer batch = BedrockPacketBatchCodec.encodeUncompressed(List.of(
                new BedrockGamePacket(new BedrockPacketHeader(203, 0, 0), payload)));
        return new TransportMessage(
                BedrockBatchCompressionCodec.encodeAfterNetworkSettings(batch, SETTINGS),
                DeliveryMode.RELIABLE_ORDERED);
    }

    private static TransportMessage response(String name) {
        return response(name, List.of());
    }

    private static TransportMessage response(String name, List<String> packIds) {
        ByteBuffer payload = ByteBuffer.allocate(256);
        int status = switch (name) {
            case "cancel" -> 1;
            case "downloading" -> 2;
            case "downloadingfinished" -> 3;
            case "resourcepackstackfinished" -> 4;
            default -> throw new IllegalArgumentException("Unknown test status: " + name);
        };
        BedrockBinary.writeVarUInt32(payload, status);
        BedrockBinary.writeString(payload, name, 64);
        if ("downloading".equals(name)) {
            BedrockBinary.writeVarUInt32(payload, packIds.size());
            for (String id : packIds) {
                BedrockBinary.writeString(payload, id, 128);
            }
        }
        payload.flip();
        ByteBuffer batch = BedrockPacketBatchCodec.encodeUncompressed(List.of(
                new BedrockGamePacket(new BedrockPacketHeader(
                        BedrockResourcePackCodec.RESOURCE_PACK_CLIENT_RESPONSE_PACKET_ID, 0, 0),
                        bytes(payload))));
        return new TransportMessage(BedrockBatchCompressionCodec.encodeAfterNetworkSettings(batch, SETTINGS),
                DeliveryMode.RELIABLE_ORDERED);
    }

    private static TransportMessage chunkRequest(String resourceName, int chunkIndex) {
        ByteBuffer payload = ByteBuffer.allocate(256);
        BedrockBinary.writeString(payload, resourceName, 128);
        BedrockBinary.writeLittleEndianInt32(payload, chunkIndex);
        payload.flip();
        ByteBuffer batch = BedrockPacketBatchCodec.encodeUncompressed(List.of(
                new BedrockGamePacket(new BedrockPacketHeader(
                        BedrockResourcePackTransferCodec.CHUNK_REQUEST_PACKET_ID, 0, 0),
                        bytes(payload))));
        return new TransportMessage(BedrockBatchCompressionCodec.encodeAfterNetworkSettings(batch, SETTINGS),
                DeliveryMode.RELIABLE_ORDERED);
    }

    private static BedrockGamePacket decodeSentPacket(ByteBuffer frame) {
        ByteBuffer batch = BedrockBatchCompressionCodec.decodeAfterNetworkSettings(frame, SETTINGS);
        List<BedrockGamePacket> packets = BedrockPacketBatchCodec.decodeUncompressed(batch);
        assertEquals(1, packets.size());
        return packets.get(0);
    }

    private static byte[] bytes(ByteBuffer source) {
        byte[] result = new byte[source.remaining()];
        source.get(result);
        return result;
    }

    private static final class FakeConnection implements TransportConnection {
        private final List<ByteBuffer> sent = new ArrayList<>();
        private boolean open = true;

        @Override public String id() { return "resource-pack-peer"; }
        @Override public boolean isOpen() { return open; }
        @Override public void setMessageListener(Consumer<TransportMessage> listener) { }
        @Override public void setCloseListener(Consumer<TransportCloseEvent> listener) { }
        @Override public TransportSendResult send(ByteBuffer payload, DeliveryMode mode) {
            if (!open) return TransportSendResult.CLOSED;
            ByteBuffer copy = ByteBuffer.allocate(payload.remaining());
            copy.put(payload.duplicate()).flip();
            sent.add(copy.asReadOnlyBuffer());
            return TransportSendResult.ACCEPTED;
        }
        @Override public void close() { open = false; }
    }
}
