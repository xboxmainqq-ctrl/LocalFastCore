package ru.nexora.modules;

/** Explicit permissions exposed to NEXORA modules. */
public enum ModuleCapability {
    READ_PLAYER_STATE,
    MODIFY_PLAYER_STATE,
    READ_WORLD,
    MODIFY_WORLD,
    READ_INVENTORY,
    MODIFY_INVENTORY,
    REGISTER_GAMEPLAY_EVENTS,
    REGISTER_COMMANDS,
    SCHEDULE_TASKS,
    ACCESS_STORAGE,
    NETWORK_ACCESS,
    OPERATOR_API
}
