package ru.nexora.runtime;

/**
 * Read-only lookup for operator public-key pins. The Android UI must obtain
 * explicit user approval before persisting a first-use fingerprint.
 */
@FunctionalInterface
public interface NetherNetOperatorTrustStore {
    boolean isTrusted(String sha256PublicKeyFingerprint);
}
