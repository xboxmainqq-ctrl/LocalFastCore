package dev.nexora.platform.api;

/** Runtime state of a registered module. */
public enum ModuleState {
    REGISTERED,
    LOADED,
    ENABLED,
    DISABLED,
    FAILED
}
