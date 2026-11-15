package ru.nexora.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads assets from a previously validated NEXORA pack without extracting ZIP
 * entries to the filesystem. Only exact paths in the validated snapshot can be
 * opened, and each read is bounded independently.
 */
public final class ResourcePackAssetReader implements AutoCloseable {
    public static final int DEFAULT_MAX_ASSET_BYTES = 16 * 1024 * 1024;

    private final ZipFile zip;
    private final Set<String> allowedFiles;
    private final int maxAssetBytes;
    private boolean closed;

    public ResourcePackAssetReader(ResourcePackArchiveValidator.ValidatedArchive validated)
            throws IOException {
        this(validated, DEFAULT_MAX_ASSET_BYTES);
    }

    public ResourcePackAssetReader(ResourcePackArchiveValidator.ValidatedArchive validated,
                                   int maxAssetBytes) throws IOException {
        Objects.requireNonNull(validated, "validated");
        if (maxAssetBytes < 1 || maxAssetBytes > DEFAULT_MAX_ASSET_BYTES) {
            throw new IllegalArgumentException("maxAssetBytes must be between 1 and "
                    + DEFAULT_MAX_ASSET_BYTES);
        }
        this.maxAssetBytes = maxAssetBytes;

        // Revalidate immediately before opening. This detects replacement or
        // modification of the archive after the original validation step.
        ResourcePackArchiveValidator.ValidatedArchive current =
                new ResourcePackArchiveValidator().validate(validated.archive());
        if (!sameSnapshot(validated, current)) {
            throw new IOException("Resource pack archive changed after validation");
        }

        this.allowedFiles = new HashSet<>(current.entries());
        this.zip = new ZipFile(current.archive().toFile(), StandardCharsets.UTF_8);
    }

    /** Returns a bounded copy of one exact, validated, non-directory asset. */
    public synchronized byte[] read(String relativePath) throws IOException {
        ensureOpen();
        if (relativePath == null || relativePath.isEmpty()
                || relativePath.startsWith("/") || relativePath.indexOf('\\') >= 0
                || relativePath.endsWith("/")) {
            throw new IOException("Invalid resource pack asset path");
        }
        if (!allowedFiles.contains(relativePath)) {
            throw new IOException("Asset is not present in the validated resource pack: " + relativePath);
        }

        ZipEntry entry = zip.getEntry(relativePath);
        if (entry == null || entry.isDirectory()) {
            throw new IOException("Resource pack asset is unavailable: " + relativePath);
        }
        if (entry.getSize() > maxAssetBytes) {
            throw new IOException("Resource pack asset exceeds read limit: " + relativePath);
        }

        try (InputStream input = zip.getInputStream(entry);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maxAssetBytes) {
                    throw new IOException("Expanded resource pack asset exceeds read limit: " + relativePath);
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    /** Reads a validated text asset using strict UTF-8 decoding. */
    public String readUtf8(String relativePath) throws IOException {
        byte[] bytes = read(relativePath);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformed) {
            throw new IOException("Resource pack text asset is not valid UTF-8: " + relativePath, malformed);
        }
    }

    /** Returns an immutable copy of the validated archive entry names. */
    public List<String> entries() {
        return allowedFiles.stream().sorted().toList();
    }

    private static boolean sameSnapshot(
            ResourcePackArchiveValidator.ValidatedArchive first,
            ResourcePackArchiveValidator.ValidatedArchive second) {
        ResourcePackManifest a = first.manifest();
        ResourcePackManifest b = second.manifest();
        return a.id().equals(b.id())
                && a.version().equals(b.version())
                && a.apiVersion().equals(b.apiVersion())
                && a.type() == b.type()
                && a.dependencies().equals(b.dependencies())
                && a.capabilities().equals(b.capabilities())
                && first.entries().equals(second.entries())
                && first.expandedBytes() == second.expandedBytes();
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Resource pack asset reader is closed");
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            zip.close();
        }
    }
}
