package ru.nexora.lifecycle;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns server resources and closes them in reverse registration order.
 *
 * <p>Register dependencies before the resources that depend on them. Closing is
 * idempotent, attempts every resource even when one close operation fails, and
 * preserves subsequent failures as suppressed exceptions.</p>
 */
public final class ServerLifecycle implements AutoCloseable {
    private final Deque<AutoCloseable> resources = new ArrayDeque<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Registers a resource and returns it for convenient construction wiring.
     *
     * @throws IllegalStateException if shutdown has already begun
     */
    public synchronized <T extends AutoCloseable> T register(T resource) {
        Objects.requireNonNull(resource, "resource");
        if (closed.get()) {
            throw new IllegalStateException("Server lifecycle is already closed");
        }
        resources.push(resource);
        return resource;
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() throws Exception {
        if (!closed.compareAndSet(false, true)) return;

        Exception failure = null;
        while (true) {
            AutoCloseable resource;
            synchronized (this) {
                resource = resources.poll();
            }
            if (resource == null) break;
            try {
                resource.close();
            } catch (Exception exception) {
                if (failure == null) {
                    failure = new Exception("One or more server resources failed to close");
                }
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) throw failure;
    }
}
