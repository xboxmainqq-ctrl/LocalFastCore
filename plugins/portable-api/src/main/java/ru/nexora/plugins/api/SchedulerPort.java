package ru.nexora.plugins.api;

import java.time.Duration;

/** Scheduling boundary. Implementations must document which thread runs each task. */
public interface SchedulerPort {
    TaskHandle executeSync(Runnable task);

    TaskHandle executeAsync(Runnable task);

    TaskHandle scheduleSync(Duration delay, Runnable task);

    TaskHandle repeatSync(Duration initialDelay, Duration period, Runnable task);
}
