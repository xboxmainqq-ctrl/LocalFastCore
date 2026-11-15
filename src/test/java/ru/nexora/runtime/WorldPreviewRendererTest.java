package ru.nexora.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorldPreviewRendererTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void exportsPngFromGeneratedChunkData() throws Exception {
        Path output = temporaryDirectory.resolve("world-preview.png");

        Path rendered = WorldPreviewRenderer.render(123456L, 0, output);

        assertEquals(output.toAbsolutePath().normalize(), rendered);
        var image = ImageIO.read(rendered.toFile());
        assertNotNull(image);
        assertEquals(16, image.getWidth());
        assertEquals(16, image.getHeight());
        assertTrue(java.nio.file.Files.size(rendered) > 100);
    }

    @Test
    void rejectsAnUnreasonablePreviewRadius() {
        assertThrows(IllegalArgumentException.class,
                () -> WorldPreviewRenderer.render(1L, 17, temporaryDirectory.resolve("too-large.png")));
    }
}
