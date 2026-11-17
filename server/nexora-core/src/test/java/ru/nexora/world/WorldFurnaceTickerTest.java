package ru.nexora.world;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class WorldFurnaceTickerTest {
    @Test
    void reportsFurnaceTickExecutionDuration() throws Exception {
        World world = new World("NEXORA", 1L, new FlatWorldGenerator());
        AtomicLong elapsedNanos = new AtomicLong(-1L);
        CountDownLatch observed = new CountDownLatch(1);
        WorldFurnaceTicker ticker = new WorldFurnaceTicker(
                world, ignored -> fail(ignored), elapsed -> {
                    elapsedNanos.set(elapsed);
                    observed.countDown();
                });

        try {
            assertTrue(observed.await(2, TimeUnit.SECONDS));
            assertTrue(elapsedNanos.get() >= 0L);
        } finally {
            ticker.close();
        }
    }
}
