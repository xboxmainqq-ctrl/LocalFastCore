package ru.nexora.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Validates a complete set of pack manifests and resolves a deterministic,
 * dependency-first load plan. This class handles metadata only: it never
 * extracts archives, loads classes, executes scripts, or grants capabilities.
 */
public final class ResourcePackManager {
    private enum Visit { VISITING, VISITED }

    private final String supportedApiVersion;
    private final Map<String, ResourcePackManifest> packs = new TreeMap<>();
    private boolean frozen;
    private List<ResourcePackManifest> frozenPlan;

    public ResourcePackManager(String supportedApiVersion) {
        this.supportedApiVersion = Objects.requireNonNull(supportedApiVersion, "supportedApiVersion");
        // Delegate version syntax validation to the manifest contract.
        new ResourcePackManifest("nexora:manager", "1.0.0", supportedApiVersion,
                ResourcePackManifest.Type.RESOURCE, List.of(), Set.of());
    }

    /** Registers one manifest. Duplicate IDs are always an error. */
    public synchronized void add(ResourcePackManifest manifest) {
        ensureMutable();
        ResourcePackManifest checked = validateCompatible(manifest);
        if (packs.containsKey(checked.id())) {
            throw new IllegalArgumentException("Duplicate resource pack: " + checked.id());
        }
        packs.put(checked.id(), checked);
    }

    /**
     * Registers a batch atomically: if any entry is invalid, no entry from the
     * batch is added.
     */
    public synchronized void addAll(Collection<ResourcePackManifest> manifests) {
        ensureMutable();
        Objects.requireNonNull(manifests, "manifests");
        Map<String, ResourcePackManifest> candidate = new TreeMap<>(packs);
        for (ResourcePackManifest manifest : manifests) {
            ResourcePackManifest checked = validateCompatible(manifest);
            if (candidate.putIfAbsent(checked.id(), checked) != null) {
                throw new IllegalArgumentException("Duplicate resource pack: " + checked.id());
            }
        }
        packs.clear();
        packs.putAll(candidate);
    }

    /**
     * Validates all dependencies and freezes registration only after successful
     * resolution. A failed resolution leaves the manager mutable for correction.
     */
    public synchronized List<ResourcePackManifest> freezeAndResolve() {
        if (frozen) return frozenPlan;
        List<ResourcePackManifest> plan = resolveInternal();
        frozenPlan = plan;
        frozen = true;
        return frozenPlan;
    }

    /** Resolves without freezing, allowing callers to inspect and correct errors. */
    public synchronized List<ResourcePackManifest> resolveOrder() {
        if (frozen) return frozenPlan;
        return resolveInternal();
    }

    /** Returns a stable, immutable snapshot ordered by pack ID. */
    public synchronized Map<String, ResourcePackManifest> snapshot() {
        return Collections.unmodifiableMap(new TreeMap<>(packs));
    }

    public synchronized boolean isFrozen() {
        return frozen;
    }

    public String supportedApiVersion() {
        return supportedApiVersion;
    }

    private ResourcePackManifest validateCompatible(ResourcePackManifest manifest) {
        ResourcePackManifest checked = Objects.requireNonNull(manifest, "manifest");
        if (!supportedApiVersion.equals(checked.apiVersion())) {
            throw new IllegalArgumentException("Pack " + checked.id() + " requires NEXORA pack API "
                    + checked.apiVersion() + " but runtime supports " + supportedApiVersion);
        }
        return checked;
    }

    private List<ResourcePackManifest> resolveInternal() {
        Map<String, Visit> visits = new HashMap<>();
        List<ResourcePackManifest> ordered = new ArrayList<>(packs.size());
        // TreeMap iteration and sorted dependency IDs make the result independent
        // of discovery order and filesystem enumeration order.
        for (String id : packs.keySet()) {
            visit(id, visits, ordered);
        }
        return List.copyOf(ordered);
    }

    private void visit(String id, Map<String, Visit> visits, List<ResourcePackManifest> ordered) {
        Visit state = visits.get(id);
        if (state == Visit.VISITED) return;
        if (state == Visit.VISITING) {
            throw new IllegalArgumentException("Resource pack dependency cycle includes: " + id);
        }

        ResourcePackManifest pack = packs.get(id);
        visits.put(id, Visit.VISITING);
        List<ResourcePackManifest.Dependency> dependencies = pack.dependencies().stream()
                .sorted(java.util.Comparator.comparing(ResourcePackManifest.Dependency::id))
                .toList();
        for (ResourcePackManifest.Dependency dependency : dependencies) {
            ResourcePackManifest target = packs.get(dependency.id());
            if (target == null) {
                throw new IllegalArgumentException("Missing dependency " + dependency.id()
                        + " required by " + pack.id());
            }
            if (!target.version().equals(dependency.version())) {
                throw new IllegalArgumentException("Pack " + pack.id() + " requires "
                        + dependency.id() + "@" + dependency.version()
                        + " but found " + target.version());
            }
            visit(target.id(), visits, ordered);
        }
        visits.put(id, Visit.VISITED);
        ordered.add(pack);
    }

    private void ensureMutable() {
        if (frozen) throw new IllegalStateException("Resource pack manager is frozen");
    }
}
