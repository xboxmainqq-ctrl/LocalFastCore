package ru.nexora.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Loads validated NEXORA resource-pack ZIPs for Bedrock delivery.
 *
 * <p>Only RESOURCE packs are transferable through the Bedrock resource-pack
 * channel. Manifests and dependencies are validated before archive bytes are
 * read into the bounded transfer snapshots.</p>
 */
public final class BedrockResourcePackArchiveLoader {
    private final ResourcePackArchiveValidator validator;

    public BedrockResourcePackArchiveLoader() {
        this(new ResourcePackArchiveValidator());
    }

    public BedrockResourcePackArchiveLoader(ResourcePackArchiveValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    /**
     * Loads all *.zip files in stable filename order, resolves dependencies
     * dependency-first, and returns immutable transfer snapshots in that order.
     * A missing directory means that no server resource packs are configured.
     */
    public List<BedrockResourcePackTransferCodec.Pack> load(
            Path directory, String supportedPackApiVersion) throws IOException {
        Path root = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        Objects.requireNonNull(supportedPackApiVersion, "supportedPackApiVersion");
        if (!Files.exists(root)) return List.of();
        if (!Files.isDirectory(root)) throw new IOException("Resource-pack path is not a directory: " + root);

        List<Path> archives;
        try (var entries = Files.list(root)) {
            archives = entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                            .endsWith(".zip"))
                    .sorted(Comparator.comparing(path ->
                            path.getFileName().toString().toLowerCase(java.util.Locale.ROOT)))
                    .toList();
        }

        ResourcePackManager manager = new ResourcePackManager(supportedPackApiVersion);
        Map<String, ResourcePackArchiveValidator.ValidatedArchive> byId = new HashMap<>();
        for (Path archive : archives) {
            ResourcePackArchiveValidator.ValidatedArchive validated = validator.validate(archive);
            if (validated.manifest().type() != ResourcePackManifest.Type.RESOURCE) {
                throw new IOException("Bedrock delivery supports RESOURCE packs only: "
                        + validated.manifest().id());
            }
            try {
                manager.add(validated.manifest());
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid resource-pack set: " + invalid.getMessage(), invalid);
            }
            byId.put(validated.manifest().id(), validated);
        }

        final List<ResourcePackManifest> ordered;
        try {
            ordered = manager.freezeAndResolve();
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Resource-pack dependencies could not be resolved: "
                    + invalid.getMessage(), invalid);
        }

        List<BedrockResourcePackTransferCodec.Pack> result = new ArrayList<>(ordered.size());
        for (ResourcePackManifest manifest : ordered) {
            ResourcePackArchiveValidator.ValidatedArchive validated = byId.get(manifest.id());
            if (validated == null) throw new IOException("Validated archive snapshot disappeared: " + manifest.id());
            long size = Files.size(validated.archive());
            if (size < 1 || size > BedrockResourcePackTransferCodec.MAX_PACK_BYTES) {
                throw new IOException("Bedrock resource pack exceeds transfer size limit: " + manifest.id());
            }
            byte[] bytes = new byte[Math.toIntExact(size)];
            try (InputStream input = Files.newInputStream(validated.archive())) {
                int offset = 0;
                while (offset < bytes.length) {
                    int count = input.read(bytes, offset, bytes.length - offset);
                    if (count < 0) {
                        throw new IOException("Resource pack changed while being loaded: " + manifest.id());
                    }
                    if (count == 0) continue;
                    offset += count;
                }
                if (input.read() != -1) {
                    throw new IOException("Resource pack grew while being loaded: " + manifest.id());
                }
            }
            UUID uuid = UUID.nameUUIDFromBytes(manifest.id().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try {
                result.add(new BedrockResourcePackTransferCodec.Pack(uuid, manifest.version(), bytes));
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Resource pack cannot be transferred: " + manifest.id(), invalid);
            }
        }
        return List.copyOf(result);
    }
}
