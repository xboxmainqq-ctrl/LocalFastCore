package ru.nexora.client;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class NexoraWorldPreviewGeneratorTest {
    @Test
    public void sameSeedProducesIdenticalSharedCoreChunks() {
        NexoraWorldPreviewGenerator.PreviewData first =
                NexoraWorldPreviewGenerator.generate(741852963L);
        NexoraWorldPreviewGenerator.PreviewData second =
                NexoraWorldPreviewGenerator.generate(741852963L);

        assertEquals(9, first.chunks().size());
        assertEquals(first, second);
        assertTrue(first.spawnY() > -64);
        assertTrue(first.chunks().stream()
                .allMatch(chunk -> !chunk.blocks().isEmpty()));
    }

    @Test
    public void differentSeedsCanProduceDifferentTerrain() {
        NexoraWorldPreviewGenerator.PreviewData first =
                NexoraWorldPreviewGenerator.generate(11L);
        NexoraWorldPreviewGenerator.PreviewData second =
                NexoraWorldPreviewGenerator.generate(22L);

        assertTrue(!first.chunks().equals(second.chunks()));
    }
}
