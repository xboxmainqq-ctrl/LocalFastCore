package ru.nexora.runtime;

import java.security.interfaces.ECPublicKey;
import java.util.Objects;

/**
 * Identity returned only after player token and client-data signature checks
 * have succeeded. The original client-data JSON is retained for later skin and
 * device metadata processing.
 */
public record BedrockAuthenticatedPlayer(
        String identityId,
        String displayName,
        ECPublicKey clientIdentityPublicKey,
        String clientDataJson
) {
    public BedrockAuthenticatedPlayer(String identityId, String displayName,
                                      ECPublicKey clientIdentityPublicKey) {
        this(identityId, displayName, clientIdentityPublicKey, "{}");
    }

    public BedrockAuthenticatedPlayer {
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(clientIdentityPublicKey, "clientIdentityPublicKey");
        Objects.requireNonNull(clientDataJson, "clientDataJson");
        if (identityId.isBlank() || identityId.length() > 128
                || displayName.isBlank() || displayName.length() > 64
                || containsControl(identityId) || containsControl(displayName)
                || clientDataJson.isBlank() || clientDataJson.length() > 1_048_576) {
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
