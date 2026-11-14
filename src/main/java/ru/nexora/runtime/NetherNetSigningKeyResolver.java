package ru.nexora.runtime;

import java.security.interfaces.RSAPublicKey;

/** Resolves a published Minecraft authorization-service JWT signing key by kid. */
@FunctionalInterface
public interface NetherNetSigningKeyResolver {
    RSAPublicKey resolve(String keyId) throws Exception;
}
