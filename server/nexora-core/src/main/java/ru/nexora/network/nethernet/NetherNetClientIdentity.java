package ru.nexora.network.nethernet;

import java.util.Objects;

/** Authenticated player identity bound to the client's NetherNet DTLS key. */
public record NetherNetClientIdentity(
        String gameServerToken,
        byte[] publicKeyDer,
        String xuid,
        String displayName,
        String playFabId,
        String playFabTitleId,
        String identityUuid
) {
    public NetherNetClientIdentity {
        Objects.requireNonNull(gameServerToken, "gameServerToken");
        Objects.requireNonNull(publicKeyDer, "publicKeyDer");
        Objects.requireNonNull(xuid, "xuid");
        if (gameServerToken.isBlank() || xuid.isBlank() || publicKeyDer.length == 0) {
            throw new IllegalArgumentException("Incomplete authenticated NetherNet identity");
        }
        publicKeyDer = publicKeyDer.clone();
    }

    @Override
    public byte[] publicKeyDer() {
        return publicKeyDer.clone();
    }
}
