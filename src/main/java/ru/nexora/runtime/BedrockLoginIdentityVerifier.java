package ru.nexora.runtime;

import java.util.Objects;
import java.util.function.IntPredicate;

/**
 * Verifies the modern Bedrock Login Token payload using the same signed
 * Minecraft multiplayer token contract used by NetherNet GameServerToken.
 *
 * <p>This proves token authenticity and returns the client's cpk. It does not
 * prove possession of that private key; the caller must complete the Bedrock
 * ECDH/encryption handshake with the returned public key before admitting play.</p>
 */
public final class BedrockLoginIdentityVerifier {
    private final NetherNetGameServerTokenVerifier tokenVerifier;
    private final IntPredicate authenticationTypePolicy;

    /** Production default: only FULL (AuthenticationType 0) accounts are admitted. */
    public BedrockLoginIdentityVerifier(NetherNetGameServerTokenVerifier tokenVerifier) {
        this(tokenVerifier, type -> type == 0);
    }

    public BedrockLoginIdentityVerifier(NetherNetGameServerTokenVerifier tokenVerifier,
                                        IntPredicate authenticationTypePolicy) {
        this.tokenVerifier = Objects.requireNonNull(tokenVerifier, "tokenVerifier");
        this.authenticationTypePolicy = Objects.requireNonNull(
                authenticationTypePolicy, "authenticationTypePolicy");
    }

    public NetherNetGameServerTokenVerifier.VerifiedClientIdentity verify(
            BedrockConnectionRequest request) throws Exception {
        Objects.requireNonNull(request, "request");
        if (!request.isTokenPayload()) {
            throw new SecurityException(
                    "Bedrock Login must contain a modern multiplayer Token payload");
        }
        int authenticationType = request.authenticationType();
        if (!authenticationTypePolicy.test(authenticationType)) {
            throw new SecurityException("Bedrock AuthenticationType is not permitted");
        }
        return tokenVerifier.verifyToken(request.token());
    }
}
