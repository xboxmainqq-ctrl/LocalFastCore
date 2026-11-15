package ru.nexora.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Performs bounded, read-only validation of a NEXORA resource-pack ZIP.
 *
 * <p>No files are extracted and no pack code is executed. The validator checks
 * archive size, entry count, expanded byte limits, portable entry paths,
 * duplicate paths and the root manifest before returning a validated snapshot.</p>
 */
public final class ResourcePackArchiveValidator {
    public static final String MANIFEST_NAME = "manifest.json";

    public record Limits(long maxArchiveBytes, int maxEntries,
                         long maxEntryBytes, long maxTotalExpandedBytes,
                         int maxManifestBytes) {
        public Limits {
            if (maxArchiveBytes < 1 || maxEntries < 1 || maxEntryBytes < 1
                    || maxTotalExpandedBytes < 1 || maxManifestBytes < 1
                    || maxManifestBytes > maxEntryBytes) {
                throw new IllegalArgumentException("All archive limits must be positive and manifest limit "
                        + "must not exceed the per-entry limit");
            }
        }

        public static Limits defaults() {
            return new Limits(64L * 1024 * 1024, 4096,
                    16L * 1024 * 1024, 128L * 1024 * 1024,
                    64 * 1024);
        }
    }

    public record ValidatedArchive(Path archive, ResourcePackManifest manifest,
                                   List<String> entries, long expandedBytes) {
        public ValidatedArchive {
            archive = Objects.requireNonNull(archive, "archive");
            manifest = Objects.requireNonNull(manifest, "manifest");
            entries = List.copyOf(entries);
            if (expandedBytes < 0) throw new IllegalArgumentException("expandedBytes must not be negative");
        }
    }

    private final Limits limits;

    public ResourcePackArchiveValidator() {
        this(Limits.defaults());
    }

    public ResourcePackArchiveValidator(Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /**
     * Validates an archive without extracting it. Every entry is fully read so
     * actual decompressed byte counts, rather than ZIP metadata alone, enforce limits.
     */
    public ValidatedArchive validate(Path archive) throws IOException {
        Path checkedPath = Objects.requireNonNull(archive, "archive").toAbsolutePath().normalize();
        if (!Files.isRegularFile(checkedPath)) {
            throw new IOException("Resource pack archive is not a regular file: " + checkedPath);
        }
        long archiveBytes = Files.size(checkedPath);
        if (archiveBytes > limits.maxArchiveBytes()) {
            throw new IOException("Resource pack archive exceeds compressed size limit");
        }

        try (ZipFile zip = new ZipFile(checkedPath.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> enumeration = zip.entries();
            Set<String> uniquePaths = new HashSet<>();
            List<String> names = new ArrayList<>();
            byte[] manifestBytes = null;
            long totalExpanded = 0;
            int count = 0;

            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                if (++count > limits.maxEntries()) {
                    throw new IOException("Resource pack contains too many ZIP entries");
                }

                String name = validateEntryName(entry.getName(), entry.isDirectory());
                String collisionKey = Normalizer.normalize(name, Normalizer.Form.NFC)
                        .toLowerCase(Locale.ROOT);
                if (!uniquePaths.add(collisionKey)) {
                    throw new IOException("Duplicate or case-colliding resource pack path: " + name);
                }
                names.add(name);

                long declaredSize = entry.getSize();
                if (declaredSize > limits.maxEntryBytes()) {
                    throw new IOException("Resource pack entry exceeds size limit: " + name);
                }
                if (MANIFEST_NAME.equals(name) && entry.isDirectory()) {
                    throw new IOException("Resource pack manifest must be a file");
                }

                long entryBytes = 0;
                ByteArrayOutputStream manifestBuffer = MANIFEST_NAME.equals(name)
                        ? new ByteArrayOutputStream() : null;
                try (InputStream input = zip.getInputStream(entry)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        entryBytes += read;
                        totalExpanded += read;
                        if (entryBytes > limits.maxEntryBytes()) {
                            throw new IOException("Expanded resource pack entry exceeds size limit: " + name);
                        }
                        if (totalExpanded > limits.maxTotalExpandedBytes()) {
                            throw new IOException("Resource pack exceeds total expanded size limit");
                        }
                        if (manifestBuffer != null) {
                            if (entryBytes > limits.maxManifestBytes()) {
                                throw new IOException("Resource pack manifest exceeds size limit");
                            }
                            manifestBuffer.write(buffer, 0, read);
                        }
                    }
                }
                if (manifestBuffer != null) manifestBytes = manifestBuffer.toByteArray();
            }

            if (manifestBytes == null) {
                throw new IOException("Resource pack is missing root manifest.json");
            }
            String manifestJson = decodeUtf8(manifestBytes);
            final ResourcePackManifest manifest;
            try {
                manifest = ResourcePackManifest.parse(manifestJson);
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid resource pack manifest: " + invalid.getMessage(), invalid);
            }
            Collections.sort(names);
            return new ValidatedArchive(checkedPath, manifest, names, totalExpanded);
        }
    }

    private static String validateEntryName(String rawName, boolean directory) throws IOException {
        if (rawName == null || rawName.isEmpty() || rawName.indexOf('\\') >= 0
                || rawName.startsWith("/")
                || rawName.matches("^[A-Za-z]:.*")) {
            throw new IOException("Unsafe resource pack ZIP path: " + rawName);
        }
        String name = directory && rawName.endsWith("/")
                ? rawName.substring(0, rawName.length() - 1) : rawName;
        if (name.isEmpty()) throw new IOException("Empty resource pack ZIP path");
        String[] segments = name.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)
                    || segment.endsWith(".") || segment.endsWith(" ")) {
                throw new IOException("Unsafe resource pack ZIP path: " + rawName);
            }
            for (int i = 0; i < segment.length(); i++) {
                char c = segment.charAt(i);
                if (c < 0x20 || c == 0x7f || c == ':') {
                    throw new IOException("Unsafe character in resource pack ZIP path: " + rawName);
                }
            }
        }
        return name;
    }

    private static String decodeUtf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformed) {
            throw new IOException("Resource pack manifest is not valid UTF-8", malformed);
        }
    }
}
