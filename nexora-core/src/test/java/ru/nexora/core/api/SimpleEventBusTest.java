package ru.nexora.core.api;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SimpleEventBusTest {
    static final class TestEvent implements NexoraEvent {
        private final int value;
        TestEvent(int value) { this.value = value; }
        int value() { return value; }
    }

    @Test
    void publishesAndAllowsSubscriptionRemoval() {
        SimpleEventBus bus = new SimpleEventBus();
        AtomicInteger received = new AtomicInteger();
        Subscription subscription = bus.subscribe(TestEvent.class,
                event -> received.set(event.value()));

        bus.publish(new TestEvent(7));
        assertEquals(7, received.get());

        subscription.close();
        bus.publish(new TestEvent(12));
        assertEquals(7, received.get());
        assertEquals(0, bus.listenerCount(TestEvent.class));
    }
    @Test
    void dispatchesToRemainingListenersBeforeReportingFailures() {
        SimpleEventBus bus = new SimpleEventBus();
        AtomicInteger received = new AtomicInteger();
        IllegalStateException firstFailure = new IllegalStateException("first listener failed");
        IllegalArgumentException secondFailure = new IllegalArgumentException("second listener failed");

        bus.subscribe(TestEvent.class, event -> { throw firstFailure; });
        bus.subscribe(TestEvent.class, event -> received.incrementAndGet());
        bus.subscribe(TestEvent.class, event -> { throw secondFailure; });

        EventDispatchException failure = assertThrows(EventDispatchException.class,
                () -> bus.publish(new TestEvent(1)));

        assertEquals(1, received.get());
        assertSame(firstFailure, failure.getCause());
        assertArrayEquals(new Throwable[]{secondFailure}, failure.getSuppressed());
    }

    @Test
    void doesNotWrapSuccessfulDispatches() {
        SimpleEventBus bus = new SimpleEventBus();
        AtomicInteger received = new AtomicInteger();
        bus.subscribe(TestEvent.class, event -> received.incrementAndGet());

        TestEvent event = new TestEvent(3);
        assertSame(event, bus.publish(event));
        assertEquals(1, received.get());
    }

}
