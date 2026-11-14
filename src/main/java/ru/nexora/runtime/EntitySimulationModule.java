package ru.nexora.runtime;

import java.util.Objects;

/**
 * Connects entity simulation to the server's shared tick scheduler.
 * The module does not create a thread; GameLoop remains the single tick owner.
 */
public final class EntitySimulationModule implements RuntimeModule {
    private final TickScheduler scheduler;
    private final EntityTickEngine engine;
    private TickScheduler.TaskHandle task;

    public EntitySimulationModule(TickScheduler scheduler, EntityTickEngine engine) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @Override
    public String id() {
        return "entity-simulation";
    }

    @Override
    public synchronized void start() {
        if (task != null && !task.isDone()) {
            throw new IllegalStateException("Entity simulation is already running");
        }
        task = scheduler.scheduleRepeating(1, 1, engine::tick);
    }

    @Override
    public synchronized void stop() {
        if (task == null) {
            return;
        }
        task.cancel();
        task = null;
    }
}
