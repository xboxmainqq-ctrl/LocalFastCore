package ru.nexora.persistence;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Immutable, versioned representation of player data suitable for persistence
 * or transfer. It deliberately contains no live runtime/player objects.
 */
public final class PlayerStateSnapshot {
    private final int schemaVersion;
    private final String playerId;
    private final long revision;
    private final long capturedAtEpochMillis;
    private final byte[] payload;
    private final String checksum;

    public PlayerStateSnapshot(int schemaVersion, String playerId, long revision,
                               long capturedAtEpochMillis, byte[] payload) {
        if (schemaVersion <= 0) throw new IllegalArgumentException("schemaVersion must be positive");
        if (playerId == null || !playerId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid player XUID");
        }
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
    public String playerId() { return playerId; }
    public long revision() { return revision; }
    public long capturedAtEpochMillis() { return capturedAtEpochMillis; }
    public byte[] payload() { return Arrays.copyOf(payload, payload.length); }
    public String checksum() { return checksum; }

    public boolean hasValidChecksum() {
        return checksum.equals(sha256(payload));
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            char[] hex = new char[digest.length * 2];
            char[] digits = "0123456789abcdef".toCharArray();
            for (int i = 0; i < digest.length; i++) {
                int value = digest[i] & 0xff;
                hex[i * 2] = digits[value >>> 4];
                hex[i * 2 + 1] = digits[value & 0x0f];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
