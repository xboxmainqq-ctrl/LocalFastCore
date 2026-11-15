package ru.nexora.runtime;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Opens the frozen resource-pack plan as a read-only asset namespace.
 *
 * <p>Packs are opened only after their manifests have been registered and
 * dependency-checked by {@link ResourcePackManager}. No archive is extracted.
 * When multiple packs contain the same path, the last pack in dependency-first
 * load order wins, allowing dependent packs to override their dependencies.</p>
 */
public final class ResourcePackAssetCatalog implements AutoCloseable {
    private final List<String> loadOrder;
    private final Map<String, ResourcePackAssetReader> readers;
    private boolean closed;

    private ResourcePackAssetCatalog(List<String> loadOrder,
                                     Map<String, ResourcePackAssetReader> readers) {
        this.loadOrder = List.copyOf(loadOrder);
        this.readers = Collections.unmodifiableMap(new LinkedHashMap<>(readers));
    }

    /**
     * Opens every archive required by the manager's frozen plan.
     *
     * @param manager a manager whose dependency plan has been frozen
     * @param archives validated archive snapshots keyed by manifest ID
     */
    public static ResourcePackAssetCatalog open(
            ResourcePackManager manager,
            Map<String, ResourcePackArchiveValidator.ValidatedArchive> archives
    ) throws IOException {
        Objects.requireNonNull(manager, "manager");
        Objects.requireNonNull(archives, "archives");
        if (!manager.isFrozen()) {
            throw new IllegalStateException("Resource pack manager must be frozen before opening assets");
        }

        List<ResourcePackManifest> plan = manager.freezeAndResolve();
        Map<String, ResourcePackArchiveValidator.ValidatedArchive> supplied =
                new LinkedHashMap<>(archives);
        if (supplied.size() != archives.size()) {
            throw new IllegalArgumentException("Duplicate resource pack archive IDs");
        }
        if (!supplied.keySet().equals(manager.snapshot().keySet())) {
            throw new IllegalArgumentException("Archive IDs must exactly match registered resource packs");
        }

        Map<String, ResourcePackAssetReader> opened = new LinkedHashMap<>();
        try {
            for (ResourcePackManifest manifest : plan) {
                ResourcePackArchiveValidator.ValidatedArchive archive = supplied.get(manifest.id());
                if (!sameManifest(manifest, archive.manifest())) {
                    throw new IOException("Archive manifest does not match registered pack: " + manifest.id());
                }
                opened.put(manifest.id(), new ResourcePackAssetReader(archive));
            }
            List<String> order = plan.stream().map(ResourcePackManifest::id).toList();
            return new ResourcePackAssetCatalog(order, opened);
        } catch (IOException | RuntimeException failure) {
            for (ResourcePackAssetReader reader : opened.values()) {
                try {
                    reader.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
    }

    /** Reads an asset from one explicitly named pack. */
    public synchronized byte[] read(String packId, String relativePath) throws IOException {
        ensureOpen();
        ResourcePackAssetReader reader = readers.get(packId);
        if (reader == null) {
            throw new IOException("Unknown resource pack: " + packId);
        }
        return reader.read(relativePath);
    }

    /**
     * Resolves a path using pack override order. Returns the winning pack ID
     * and asset bytes, or an empty result when no pack contains the path.
     */
    public synchronized Optional<ResolvedAsset> find(String relativePath) throws IOException {
        ensureOpen();
        for (int i = loadOrder.size() - 1; i >= 0; i--) {
            String packId = loadOrder.get(i);
            ResourcePackAssetReader reader = readers.get(packId);
            if (reader.entries().contains(relativePath)) {
                return Optional.of(new ResolvedAsset(packId, reader.read(relativePath)));
            }
        }
        return Optional.empty();
    }

    /** Dependency-first pack order, exposed as an immutable list. */
    public List<String> loadOrder() {
        return loadOrder;
    }

    private static boolean sameManifest(ResourcePackManifest a, ResourcePackManifest b) {
        return a.id().equals(b.id())
                && a.version().equals(b.version())
                && a.apiVersion().equals(b.apiVersion())
                && a.type() == b.type()
                && a.dependencies().equals(b.dependencies())
                && a.capabilities().equals(b.capabilities());
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Resource pack asset catalog is closed");
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        for (ResourcePackAssetReader reader : readers.values()) {
            try {
                reader.close();
            } catch (IOException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) throw failure;
    }

    public record ResolvedAsset(String packId, byte[] bytes) {
        public ResolvedAsset {
            packId = Objects.requireNonNull(packId, "packId");
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
