package ru.nexora.server;

import ru.nexora.runtime.AdaptiveRuntime;
import ru.nexora.runtime.RuntimeSlice;
import ru.nexora.runtime.WorkGraph;
import ru.nexora.world.WorldState;

import java.util.Objects;

/**
 * Minimal deterministic NEXORA server loop boundary.
 *
 * <p>Networking and protocol adapters are intentionally outside this class.
 * The loop owns admission and world tick ordering, not gameplay implementations.</p>
 */
public final class ServerLoop {
    private final AdaptiveRuntime runtime;
    private final RuntimeSlice slice;
    private final WorldState world;

    public ServerLoop(AdaptiveRuntime runtime, WorldState world) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.slice = new RuntimeSlice(runtime);
        this.world = Objects.requireNonNull(world, "world");
    }

    public TickResult tick(long worldDelta, WorkGraph work) {
        long started = System.nanoTime();
        runtime.beginTick();
        world.tick(worldDelta);
        int admitted = slice.admit(work).size();
        runtime.observeTick(System.nanoTime() - started);
        return new TickResult(admitted, runtime.snapshot());
    }

    public record TickResult(int admittedTasks, AdaptiveRuntime.Snapshot runtime) {}
}
