package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Synthetic benchmark for reverse-indexed chunk interest versus scanning every session. */
class ActorReplicationIndexBenchmarkTest {
    private static final int SESSION_COUNT = 1_000;
    private static final int ACTOR_UPDATES = 10_000;

    @Test
    void comparesFullSessionScanWithChunkViewerLookup() {
        ActorReplicationIndex index = new ActorReplicationIndex();
        Map<String, Set<Long>> sessions = new java.util.HashMap<>();
        long buildStarted = System.nanoTime();
        for (int session = 0; session < SESSION_COUNT; session++) {
            int centerX = session % 50;
            int centerZ = session / 50;
            Set<Long> chunks = new HashSet<>();
            for (int x = centerX - 1; x <= centerX + 1; x++) {
                for (int z = centerZ - 1; z <= centerZ + 1; z++) {
                    chunks.add(chunkKey(x, z));
                }
            }
            String networkId = "bench-session-" + session;
            sessions.put(networkId, chunks);
            index.replaceChunkView(networkId, chunks);
        }
        long buildNanos = System.nanoTime() - buildStarted;

        long fullScanStarted = System.nanoTime();
        long fullScanRecipients = 0;
        for (int update = 0; update < ACTOR_UPDATES; update++) {
            long key = chunkKey((update * 17) % 50, (update * 31) % 20);
            int recipients = 0;
            for (Set<Long> chunks : sessions.values()) {
                if (chunks.contains(key)) recipients++;
            }
            fullScanRecipients += recipients;
        }
        long fullScanNanos = System.nanoTime() - fullScanStarted;

        long indexStarted = System.nanoTime();
        long indexedRecipients = 0;
        for (int update = 0; update < ACTOR_UPDATES; update++) {
            long key = chunkKey((update * 17) % 50, (update * 31) % 20);
            indexedRecipients += index.chunkViewers(key).size();
        }
        long indexedNanos = System.nanoTime() - indexStarted;

        assertEquals(fullScanRecipients, indexedRecipients);
        System.out.printf(
                "NEXORA actor-fanout index benchmark: sessions=%d actor_updates=%d full_scan_avg_us=%.3f indexed_lookup_avg_us=%.3f index_build_ms=%.3f recipients=%d%n",
                SESSION_COUNT, ACTOR_UPDATES,
                fullScanNanos / (double) ACTOR_UPDATES / 1_000.0,
                indexedNanos / (double) ACTOR_UPDATES / 1_000.0,
                buildNanos / 1_000_000.0, indexedRecipients);
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffff_ffffL);
    }
}
