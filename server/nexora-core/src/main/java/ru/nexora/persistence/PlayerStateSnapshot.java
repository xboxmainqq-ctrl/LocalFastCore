package ru.nexora.persistence;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Immutable, versioned representation of player data suitable for persistence
 * or transfer. It deliberately contains no live runtime/player objects.
 */
public final class PlayerStateSnapshot {
    private final int schemaVersion;
    private final long playerId;
    private final long revision;
    private final long capturedAtEpochMillis;
    private final byte[] payload;
    private final String checksum;

    public PlayerStateSnapshot(int schemaVersion, long playerId, long revision,
                               long capturedAtEpochMillis, byte[] payload) {
        if (schemaVersion <= 0) throw new IllegalArgumentException("schemaVersion must be positive");
        if (playerId < 0) throw new IllegalArgumentException("playerId < 0");
        if (revision < 0) throw new IllegalArgumentException("revision < 0");
        if (capturedAtEpochMillis < 0) throw new IllegalArgumentException("capturedAtEpochMillis < 0");
        if (payload == null) throw new NullPointerException("payload");
        this.schemaVersion = schemaVersion;
        this.playerId = playerId;
        this.revision = revision;
        this.capturedAtEpochMillis = capturedAtEpochMillis;
        this.payload = Arrays.copyOf(payload, payload.length);
        this.checksum = sha256(this.payload);
    }

    public int schemaVersion() { return schemaVersion; }
    public long playerId() { return playerId; }
    public long revision() { return revision; }
    public long capturedAtEpochMillis() { return capturedAtEpochMillis; }
    public byte[] payload() { return Arrays.copyOf(payload, payload.length); }
    public String checksum() { return checksum; }

    public boolean hasValidChecksum() {
        return checksum.equals(sha256(payload));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
