package dev.nexora.platform.api;

import java.util.Optional;

/**
 * Typed service lookup shared by the host and its modules.
 *
 * Registration ownership, replacement policy and shutdown behavior are
 * deliberately left to the platform implementation.
 */
public interface ServiceRegistry {
    <T> void register(Class<T> serviceType, T service);

    <T> Optional<T> find(Class<T> serviceType);

    default <T> T require(Class<T> serviceType) {
        return find(serviceType).orElseThrow(
                () -> new IllegalStateException("Required service is not registered: "
                        + serviceType.getName())
        );
    }
}
