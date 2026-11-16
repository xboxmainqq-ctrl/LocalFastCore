package ru.nexora.runtime;

/**
 * Verifies that the Bedrock Login identity is the same identity authenticated
 * by the NetherNet signaling offer. NetherNet's DTLS identity must not be
 * treated as interchangeable with a separately supplied Bedrock login token.
 */
public interface NetherNetTransportIdentityBinding {
    boolean matchesAuthenticatedPlayer(BedrockAuthenticatedPlayer player);
}
