package ru.nexora.runtime;

/** Observable lifecycle states of the standalone server process. */
public enum ServerState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    FAILED
}
