package ru.nexora.world;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

/** Persistable player identity and last known location for a single NEXORA world. */
public record PlayerData(
        String xuid,
        String displayName,
        String worldName,
        float x,
        float y,
        float z,
        float yaw,
        float pitch,
        long lastSeenEpochMillis,
        PlayerVitals vitals,
        int experiencePoints
) {
    public PlayerData(
            String xuid, String displayName, String worldName,
            float x, float y, float z, float yaw, float pitch, long lastSeenEpochMillis
    ) {
        this(xuid, displayName, worldName, x, y, z, yaw, pitch, lastSeenEpochMillis, PlayerVitals.DEFAULT, 0);
    }
    public PlayerData(
            String xuid, String displayName, String worldName,
            float x, float y, float z, float yaw, float pitch, long lastSeenEpochMillis,
            PlayerVitals vitals
    ) {
        this(xuid, displayName, worldName, x, y, z, yaw, pitch, lastSeenEpochMillis, vitals, 0);
    }
    private static final Pattern SAFE_XUID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public PlayerData {
        if (xuid == null || !SAFE_XUID.matcher(xuid).matches()) {
            throw new IllegalArgumentException("Invalid player XUID");
        }
        validateText(displayName, "displayName", 128);
        validateText(worldName, "worldName", 128);
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("Player position and rotation must be finite");
        }
        if (Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000
                || y < WorldChunk.MIN_Y - 16 || y > WorldChunk.MAX_Y + 16) {
            throw new IllegalArgumentException("Player position is outside supported world bounds");
        }
        if (lastSeenEpochMillis < 0) throw new IllegalArgumentException("Invalid last-seen timestamp");
        Objects.requireNonNull(vitals, "vitals");
        if (experiencePoints < 0 || experiencePoints > 2_000_000_000) {
            throw new IllegalArgumentException("Invalid player experience points");
        }
    }

    private static void validateText(String value, String field, int maxBytes) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(field + " must be non-blank and at most " + maxBytes + " UTF-8 bytes");
        }
    }
}
