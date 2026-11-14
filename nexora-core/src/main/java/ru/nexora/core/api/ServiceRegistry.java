package ru.nexora.core.api;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Thread-safe typed service registry shared by Nexora modules. */
public final class ServiceRegistry {
    private final ConcurrentMap<Class<?>, Object> services = new ConcurrentHashMap<>();

    public <T> void register(Class<T> serviceType, T service) {
        Objects.requireNonNull(serviceType, "serviceType");
        Objects.requireNonNull(service, "service");
        if (!serviceType.isInstance(service)) {
            throw new IllegalArgumentException("Service does not implement " + serviceType.getName());
        }
        Object previous = services.putIfAbsent(serviceType, service);
        if (previous != null) {
            throw new IllegalStateException("Service already registered: " + serviceType.getName());
        }
    }

    public <T> Optional<T> find(Class<T> serviceType) {
        Objects.requireNonNull(serviceType, "serviceType");
        Object service = services.get(serviceType);
        return service == null ? Optional.empty() : Optional.of(serviceType.cast(service));
    }

    public <T> T require(Class<T> serviceType) {
        return find(serviceType).orElseThrow(() ->
                new IllegalStateException("Required service is not registered: " + serviceType.getName()));
    }

    public <T> boolean unregister(Class<T> serviceType, T expectedService) {
        Objects.requireNonNull(serviceType, "serviceType");
        Objects.requireNonNull(expectedService, "expectedService");
        return services.remove(serviceType, expectedService);
    }

    public boolean isRegistered(Class<?> serviceType) {
        return services.containsKey(Objects.requireNonNull(serviceType, "serviceType"));
    }

    public int size() {
        return services.size();
    }
}
