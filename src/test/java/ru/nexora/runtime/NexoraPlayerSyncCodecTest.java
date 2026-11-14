package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NexoraPlayerSyncCodecTest {
    @Test
    void roundTripsPlayerState() {
        NexoraPlayerSyncCodec.PlayerState expected =
                new NexoraPlayerSyncCodec.PlayerState(
                        UUID.fromString("c7f9a7c3-18b0-4dc5-a3c2-7b7f10f2f1a1"),
                        "Игрок", 2, 12.5, 65.0, -8.25, 45.0);

        NexoraPlayerSyncCodec.PlayerState actual =
                NexoraPlayerSyncCodec.decodePlayerState(
                        ByteBuffer.wrap(NexoraPlayerSyncCodec.encodePlayerState(expected)));

        assertEquals(expected, actual);
    }

    @Test
    void roundTripsPlayerNickname() {
        NexoraPlayerSyncCodec.PlayerState expected = new NexoraPlayerSyncCodec.PlayerState(
                UUID.randomUUID(), "ТестовыйИгрок", 3, 1.5, 70.0, -2.5, 135.0);
        NexoraPlayerSyncCodec.PlayerState actual = NexoraPlayerSyncCodec.decodePlayerState(
                ByteBuffer.wrap(NexoraPlayerSyncCodec.encodePlayerState(expected)));
        assertEquals(expected, actual);
        assertEquals(3, actual.skinIndex());
    }
    @Test
    void roundTripsPlayerLeave() {
        UUID expected = UUID.randomUUID();

        UUID actual = NexoraPlayerSyncCodec.decodePlayerLeft(
                ByteBuffer.wrap(NexoraPlayerSyncCodec.encodePlayerLeft(expected)));

        assertEquals(expected, actual);
    }

    @Test
    void rejectsInvalidCoordinates() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
                () -> new NexoraPlayerSyncCodec.PlayerState(id, Double.NaN, 0, 0));
    }

    @Test
    void rejectsWrongPacketSize() {
        assertThrows(IllegalArgumentException.class,
                () -> NexoraPlayerSyncCodec.decodePlayerState(ByteBuffer.allocate(10)));
    }
}
