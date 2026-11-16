package dev.nexora.platform.api;

/** Raised when a module archive cannot be safely discovered or instantiated. */
public final class ModuleDiscoveryException extends Exception {
    public ModuleDiscoveryException(String message) {
        super(message);
    }

    public ModuleDiscoveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
