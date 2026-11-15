package ru.nexora.client;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Validated ResourcePacksInfo metadata received from a Bedrock server. */
record BedrockAndroidResourcePackInfo(
        boolean required,
        boolean hasAddonPacks,
        boolean hasScripts,
        boolean forceDisableVibrantVisuals,
        List<Pack> packs
) {
    BedrockAndroidResourcePackInfo {
        packs = List.copyOf(Objects.requireNonNull(packs, "packs"));
    }

    record Pack(
            UUID uuid,
            String version,
            long size,
            String contentKey,
            String subpackName,
            String contentIdentity,
            boolean hasScripts,
            boolean addon,
            boolean rayTracing,
            String cdnUrl
    ) {
        Pack {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(contentKey, "contentKey");
            Objects.requireNonNull(subpackName, "subpackName");
            Objects.requireNonNull(contentIdentity, "contentIdentity");
            Objects.requireNonNull(cdnUrl, "cdnUrl");
            if (size < 1 || size > 64L * 1024L * 1024L) {
                throw new IllegalArgumentException("Resource pack size is outside the supported range");
            }
        }

        String resourceName() {
            return uuid + "_" + version;
        }
    }
}
