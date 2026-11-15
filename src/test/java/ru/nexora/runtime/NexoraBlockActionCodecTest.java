package ru.nexora.runtime;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;

class NexoraBlockActionCodecTest {
    @Test
    void breakAndPlaceRequestsRoundTrip() {
        var breakRequest = new NexoraBlockActionCodec.Request(
                NexoraBlockActionCodec.Action.BREAK, -3, 64, 9, "");
        var placeRequest = new NexoraBlockActionCodec.Request(
                NexoraBlockActionCodec.Action.PLACE, 1, 65, -2, "minecraft:dirt");
        assertEquals(breakRequest, NexoraBlockActionCodec.decodeRequest(
                ByteBuffer.wrap(NexoraBlockActionCodec.encodeRequest(breakRequest))));
        assertEquals(placeRequest, NexoraBlockActionCodec.decodeRequest(
                ByteBuffer.wrap(NexoraBlockActionCodec.encodeRequest(placeRequest))));
    }

    @Test
    void blockUpdatesRoundTripAndAllowAirForRemoval() {
        var update = new NexoraBlockActionCodec.Update(3, 64, -7, "minecraft:stone");
        var decoded = NexoraBlockActionCodec.decodeUpdate(
                ByteBuffer.wrap(NexoraBlockActionCodec.encodeUpdate(update)));
        assertEquals(update, decoded);
        var removal = new NexoraBlockActionCodec.Update(3, 64, -7, "minecraft:air");
        assertEquals(removal, NexoraBlockActionCodec.decodeUpdate(
                ByteBuffer.wrap(NexoraBlockActionCodec.encodeUpdate(removal))));
    }

    @Test
    void rejectsBreakWithBlockIdAndInvalidPlaceIdentifier() {
        byte[] packet = NexoraBlockActionCodec.encodeRequest(new NexoraBlockActionCodec.Request(
                NexoraBlockActionCodec.Action.BREAK, 0, 64, 0, ""));
        packet[20] = 1;
        assertThrows(IllegalArgumentException.class,
                () -> NexoraBlockActionCodec.decodeRequest(ByteBuffer.wrap(packet)));
        assertThrows(IllegalArgumentException.class, () -> NexoraBlockActionCodec.encodeRequest(
                new NexoraBlockActionCodec.Request(NexoraBlockActionCodec.Action.PLACE,
                        0, 64, 0, "")));
    }
}
