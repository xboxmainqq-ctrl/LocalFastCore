package ru.nexora.runtime;

/** Bedrock PlayStatus packet values used during login and spawn. */
public enum BedrockPlayStatus {
    LOGIN_SUCCESS(0),
    LOGIN_FAILED_CLIENT_OLD(1),
    LOGIN_FAILED_SERVER_OLD(2),
    PLAYER_SPAWN(3),
    LOGIN_FAILED_INVALID_TENANT(4),
    LOGIN_FAILED_EDITION_MISMATCH_EDU_TO_VANILLA(5),
    LOGIN_FAILED_EDITION_MISMATCH_VANILLA_TO_EDU(6),
    LOGIN_FAILED_SERVER_FULL_SUB_CLIENT(7),
    LOGIN_FAILED_EDITOR_MISMATCH_EDITOR_TO_VANILLA(8),
    LOGIN_FAILED_EDITOR_MISMATCH_VANILLA_TO_EDITOR(9);

    private final int wireValue;

    BedrockPlayStatus(int wireValue) {
        this.wireValue = wireValue;
    }

    public int wireValue() {
        return wireValue;
    }

    public static BedrockPlayStatus fromWireValue(int value) {
        for (BedrockPlayStatus status : values()) {
            if (status.wireValue == value) return status;
        }
        throw new IllegalArgumentException("Unknown Bedrock PlayStatus value: " + value);
    }
}
