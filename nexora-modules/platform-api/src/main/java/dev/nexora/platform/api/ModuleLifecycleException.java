package dev.nexora.platform.api;

/** Checked failure raised while invoking a module lifecycle callback. */
public final class ModuleLifecycleException extends Exception {
    public ModuleLifecycleException(String message, Throwable cause) {
        super(message, cause);
    }
}
