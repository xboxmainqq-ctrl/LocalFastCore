package ru.nexora.core.security;

/**
 * Platform-neutral permission source.
 * Integrations should adapt the current command sender/player without exposing
 * platform-specific classes to NEXORA modules.
 */
@FunctionalInterface
public interface PermissionSubject {
    boolean hasPermission(String permission);
}
