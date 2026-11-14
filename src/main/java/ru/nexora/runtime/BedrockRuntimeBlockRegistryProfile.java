package ru.nexora.runtime;

import java.util.Objects;

/**
 * Immutable, protocol-pinned pairing of a Bedrock block ID mode and its lookup.
 * Use a loaded external table for indexed runtime IDs, or the FNV profile for
 * hash-based block network IDs.
 */
public final class BedrockRuntimeBlockRegistryProfile {
    public enum IdMode {
        RUNTIME(false),
        HASH(true);

        private final boolean hashes;

        IdMode(boolean hashes) {
            this.hashes = hashes;
        }

        public boolean blockNetworkIdsAreHashes() {
            return hashes;
        }
    }

    private final int protocolVersion;
    private final String gameVersion;
    private final IdMode idMode;
    private final String sourceDescription;
    private final BedrockRuntimeBlockRegistry registry;

    private BedrockRuntimeBlockRegistryProfile(
            int protocolVersion,
            String gameVersion,
            IdMode idMode,
            String sourceDescription,
            BedrockRuntimeBlockRegistry registry
    ) {
        if (protocolVersion != BedrockRuntimeBlockRegistryFile.PROTOCOL_VERSION
                || !BedrockRuntimeBlockRegistryFile.GAME_VERSION.equals(gameVersion)) {
            throw new IllegalArgumentException("Block registry profile must target Bedrock 1.26.60 / protocol 2225");
        }
        this.protocolVersion = protocolVersion;
        this.gameVersion = Objects.requireNonNull(gameVersion, "gameVersion");
        this.idMode = Objects.requireNonNull(idMode, "idMode");
        this.sourceDescription = Objects.requireNonNull(sourceDescription, "sourceDescription");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public static BedrockRuntimeBlockRegistryProfile from(
            BedrockRuntimeBlockRegistryFile.Loaded loaded
    ) {
        Objects.requireNonNull(loaded, "loaded");
        return new BedrockRuntimeBlockRegistryProfile(
                loaded.protocolVersion(),
                loaded.gameVersion(),
                loaded.idMode() == BedrockRuntimeBlockRegistryFile.IdMode.HASH
                        ? IdMode.HASH : IdMode.RUNTIME,
                "sha256:" + loaded.sha256(),
                loaded.registry());
    }

    /** Uses the protocol's hashed block-state ID mode without shipping a Mojang palette file. */
    public static BedrockRuntimeBlockRegistryProfile hashing() {
        return new BedrockRuntimeBlockRegistryProfile(
                BedrockRuntimeBlockRegistryFile.PROTOCOL_VERSION,
                BedrockRuntimeBlockRegistryFile.GAME_VERSION,
                IdMode.HASH,
                "computed-fnv1a-little-endian-nbt",
                BedrockRuntimeBlockRegistry.hashing());
    }

    public int protocolVersion() {
        return protocolVersion;
    }

    public String gameVersion() {
        return gameVersion;
    }

    public IdMode idMode() {
        return idMode;
    }

    public String sourceDescription() {
        return sourceDescription;
    }

    public BedrockRuntimeBlockRegistry registry() {
        return registry;
    }

    public boolean blockNetworkIdsAreHashes() {
        return idMode.blockNetworkIdsAreHashes();
    }

    public void validateStartGameSettings(BedrockStartGamePacketCodec.Settings settings) {
        Objects.requireNonNull(settings, "settings");
        if (settings.blockNetworkIdsAreHashes() != blockNetworkIdsAreHashes()) {
            throw new IllegalArgumentException(
                    "StartGame block-network-ID mode does not match the loaded block registry profile");
        }
    }
}
