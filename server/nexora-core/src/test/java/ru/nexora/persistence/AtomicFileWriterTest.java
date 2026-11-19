package ru.nexora.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AtomicFileWriterTest {
    @TempDir
    Path directory;

    @Test
    void writesAndReplacesFileThroughForcedTemporarySnapshot() throws Exception {
        Path target = directory.resolve("player.nxps");
        byte[] first = new byte[] {1, 2, 3, 4};
        byte[] second = new byte[] {9, 8, 7, 6, 5};

        AtomicFileWriter.WriteResult firstWrite = AtomicFileWriter.writeAtomically(target, first);
        assertTrue(firstWrite.fileContentsForced());
        assertArrayEquals(first, Files.readAllBytes(target));

        AtomicFileWriter.WriteResult replacement = AtomicFileWriter.writeAtomically(target, second);
        assertTrue(replacement.fileContentsForced());
        assertArrayEquals(second, Files.readAllBytes(target));
        try (var files = Files.list(directory)) {
            List<Path> remaining = files.toList();
            assertEquals(List.of(target), remaining,
                    "successful replacement must not leave temporary persistence files behind");
        }
    }

    @Test
    void rejectsTargetsWhoseParentDirectoryDoesNotExist() {
        Path target = directory.resolve("missing").resolve("state.nxp");
        assertThrows(java.io.IOException.class,
                () -> AtomicFileWriter.writeAtomically(target, new byte[] {1}));
        assertFalse(Files.exists(target));
    }
}
