package ru.nexora.runtime;

/** Server policy applied only after both NetherNet identity signatures verify. */
@FunctionalInterface
public interface NetherNetPlayerAdmissionPolicy {
    void authorize(String networkId, NetherNetIdentityValidator.VerifiedIdentity identity)
            throws Exception;
}
