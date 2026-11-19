package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ActorReplicationIndexTest {
    @Test
    void chunkViewReplacementRemovesOldInterestAndAddsNewInterest() {
        ActorReplicationIndex index = new ActorReplicationIndex();
        index.replaceChunkView("player-a", Set.of(10L, 11L));
        index.replaceChunkView("player-b", Set.of(10L));

        assertEquals(2, index.chunkViewerCount(10L));
        assertEquals(1, index.chunkViewerCount(11L));

        index.replaceChunkView("player-a", Set.of(11L, 12L));

        assertEquals(1, index.chunkViewerCount(10L));
        assertEquals(1, index.chunkViewerCount(11L));
        assertEquals(1, index.chunkViewerCount(12L));
        assertTrue(index.chunkViewers(10L).contains("player-b"));
        assertFalse(index.chunkViewers(10L).contains("player-a"));
    }

    @Test
    void actorViewerLifecycleIsRemovedOnDespawnAndDisconnect() {
        ActorReplicationIndex index = new ActorReplicationIndex();
        index.addActorViewer(100L, "player-a");
        index.addActorViewer(100L, "player-b");
        index.addActorViewer(200L, "player-a");

        assertEquals(2, index.actorViewerCount(100L));
        assertEquals(1, index.actorViewerCount(200L));

        index.removeActorViewer(100L, "player-a");
        assertEquals(1, index.actorViewerCount(100L));

        index.removeSession("player-a");
        assertEquals(1, index.actorViewerCount(100L));
        assertEquals(0, index.actorViewerCount(200L));
    }

    @Test
    void emptyIndexesAreReleasedAfterLastViewerLeaves() {
        ActorReplicationIndex index = new ActorReplicationIndex();
        index.replaceChunkView("player-a", Set.of(10L));
        index.addActorViewer(100L, "player-a");

        index.removeSession("player-a");

        assertEquals(0, index.chunkViewerCount(10L));
        assertEquals(0, index.actorViewerCount(100L));
        assertTrue(index.chunkViewers(10L).isEmpty());
        assertTrue(index.actorViewers(100L).isEmpty());
    }
}
