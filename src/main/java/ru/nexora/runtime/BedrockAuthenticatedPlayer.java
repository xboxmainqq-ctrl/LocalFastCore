package ru.nexora.runtime;

import java.security.interfaces.ECPublicKey;
import java.util.Objects;

/**
 * Identity returned only after the configured Bedrock Login authenticator has
 * verified the client's credentials and certificate/token chain.
 */
public record BedrockAuthenticatedPlayer(
        String identityId,
        String displayName,
        ECPublicKey clientIdentityPublicKey
) {
    public BedrockAuthenticatedPlayer {
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(clientIdentityPublicKey, "clientIdentityPublicKey");
        if (identityId.isBlank() || identityId.length() > 128
                || displayName.isBlank() || displayName.length() > 64
                || containsControl(identityId) || containsControl(displayName)) {
            throw new IllegalArgumentException("Authenticated Bedrock player identity is invalid");
        }
        if (clientIdentityPublicKey.getParams() == null
                || clientIdentityPublicKey.getParams().getOrder().bitLength() != 384
                || clientIdentityPublicKey.getParams().getCurve().getField().getFieldSize() != 384) {
            throw new IllegalArgumentException("Authenticated Bedrock identity key must use P-384");
        }
    }

    private static boolean containsControl(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }
}
