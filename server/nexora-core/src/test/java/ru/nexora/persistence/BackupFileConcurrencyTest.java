package ru.nexora.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class BackupFileConcurrencyTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void concurrentInventoryStyleWritesKeepPrimaryAndBackupValid() throws Exception {
        Path primary = temporaryDirectory.resolve("player.nxi");
        BackupFile.FileReader<String> reader = file -> {
            String value = Files.readString(file, StandardCharsets.UTF_8);
            if (!value.matches("generation-[0-9]+-[0-9]+")) {
                throw new IOException("Invalid durable generation");
            }
            return value;
        };
        BackupFile.write(primary, "generation-0-0".getBytes(StandardCharsets.UTF_8), reader);

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> writes = new ArrayList<>();
            for (int worker = 0; worker < 8; worker++) {
                final int workerId = worker;
                writes.add(executor.submit(() -> {
                    for (int generation = 1; generation <= 40; generation++) {
                        String value = "generation-" + workerId + "-" + generation;
                        try {
                            BackupFile.write(primary, value.getBytes(StandardCharsets.UTF_8), reader);
                        } catch (IOException exception) {
                            throw new java.io.UncheckedIOException(exception);
                        }
                    }
                }));
            }
            for (Future<?> write : writes) write.get();
        } finally {
            executor.shutdownNow();
        }

        assertTrue(reader.read(primary).startsWith("generation-"));
        assertTrue(reader.read(BackupFile.backupPath(primary)).startsWith("generation-"));
        assertTrue(BackupFile.load(primary, reader).orElseThrow().startsWith("generation-"));
    }
}
