package ru.nexora.runtime;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Loads configured worlds before the server starts and persists them on shutdown.
 *
 * <p>The module owns only worlds it registers itself. Worlds already present in
 * the supplied registry are reused, which makes a stop/start cycle safe.</p>
 */
public final class WorldStorageModule implements RuntimeModule {
    private static final Pattern WORLD_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final WorldRegistry registry;
    private final FileWorldStorage storage;
    private final Map<String, WorldHeight> definitions;
    private final List<String> registeredByThisModule = new ArrayList<>();
    private boolean started;

    /**
     * @param definitions world IDs and their initial heights; stored worlds retain
     *                    the height from their saved file
     */
    public WorldStorageModule(
            WorldRegistry registry,
            FileWorldStorage storage,
            Map<String, WorldHeight> definitions
    ) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.storage = Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(definitions, "definitions");

        Map<String, WorldHeight> copy = new LinkedHashMap<>();
        definitions.forEach((rawId, height) -> {
            Objects.requireNonNull(rawId, "world id");
            Objects.requireNonNull(height, "world height");
            String id = rawId.trim();
            if (!WORLD_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("Invalid world id: " + rawId);
            }
            if (copy.putIfAbsent(id, height) != null) {
                throw new IllegalArgumentException("Duplicate world id: " + id);
            }
        });
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("At least one world must be configured");
        }
        this.definitions = Collections.unmodifiableMap(copy);
    }

    @Override
    public String id() {
        return "world-storage";
    }

    @Override
    public synchronized void start() throws IOException {
        if (started) {
            throw new IllegalStateException("World storage module is already started");
        }

        List<String> newlyRegistered = new ArrayList<>();
        try {
            for (Map.Entry<String, WorldHeight> definition : definitions.entrySet()) {
                String worldId = definition.getKey();
                if (registry.get(worldId).isPresent()) {
                    continue;
                }

                World world = storage.load(worldId)
                        .<World>map(loaded -> loaded)
                        .orElseGet(() -> new InMemoryWorld(worldId, definition.getValue()));
                registry.register(world);
                newlyRegistered.add(worldId);
            }
            registeredByThisModule.clear();
            registeredByThisModule.addAll(newlyRegistered);
            started = true;
        } catch (IOException | RuntimeException failure) {
            for (int index = newlyRegistered.size() - 1; index >= 0; index--) {
                registry.unregister(newlyRegistered.get(index));
            }
            throw failure;
        }
    }

    @Override
    public synchronized void stop() throws IOException {
        if (!started) {
            return;
        }

        IOException aggregate = null;
        for (String worldId : definitions.keySet()) {
            World world = registry.get(worldId).orElse(null);
            if (world == null) {
                continue;
            }
            try {
                storage.save(world);
            } catch (IOException failure) {
                if (aggregate == null) {
                    aggregate = new IOException("One or more NEXORA worlds could not be saved");
                }
                aggregate.addSuppressed(failure);
            }
        }

        if (aggregate != null) {
            // Keep started=true so the runtime can retry shutdown persistence.
            throw aggregate;
        }

        started = false;
        registeredByThisModule.clear();
    }

    /** IDs loaded or created by this module during its most recent successful start. */
    public synchronized List<String> managedWorldIds() {
        return List.copyOf(registeredByThisModule);
    }
}
