package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class TransportMessageTest {
    @Test
    void exposesOnlyRemainingBytesAsReadOnlyPayload() {
        ByteBuffer source = ByteBuffer.wrap(new byte[] {10, 20, 30, 40});
        source.position(1);
        TransportMessage message = new TransportMessage(source, DeliveryMode.RELIABLE_ORDERED);

        assertEquals(3, message.sizeBytes());
        assertEquals(1, source.position());

        ByteBuffer firstView = message.payload();
        assertTrue(firstView.isReadOnly());
        assertEquals(20, firstView.get());

        ByteBuffer secondView = message.payload();
        assertEquals(20, secondView.get());
        assertEquals(3, message.sizeBytes());
    }

    @Test
    void rejectsNullPayloadAndDeliveryMode() {
        assertThrows(NullPointerException.class,
                () -> new TransportMessage(null, DeliveryMode.UNRELIABLE));
        assertThrows(NullPointerException.class,
                () -> new TransportMessage(ByteBuffer.allocate(0), null));
    }
}
