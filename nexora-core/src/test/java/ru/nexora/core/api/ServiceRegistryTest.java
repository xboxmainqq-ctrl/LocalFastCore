package ru.nexora.core.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServiceRegistryTest {
    interface ExampleService { }
    static final class ExampleServiceImpl implements ExampleService { }

    @Test
    void registersFindsAndUnregistersTypedServices() {
        ServiceRegistry registry = new ServiceRegistry();
        ExampleServiceImpl service = new ExampleServiceImpl();

        registry.register(ExampleService.class, service);

        assertSame(service, registry.require(ExampleService.class));
        assertTrue(registry.isRegistered(ExampleService.class));
        assertTrue(registry.unregister(ExampleService.class, service));
        assertTrue(registry.find(ExampleService.class).isEmpty());
    }

    @Test
    void rejectsDuplicateRegistration() {
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(ExampleService.class, new ExampleServiceImpl());

        assertThrows(IllegalStateException.class,
                () -> registry.register(ExampleService.class, new ExampleServiceImpl()));
    }
}
