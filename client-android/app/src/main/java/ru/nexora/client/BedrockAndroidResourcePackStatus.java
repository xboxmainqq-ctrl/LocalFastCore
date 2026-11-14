package ru.nexora.client;

/** Protocol-2168+ resource-pack response status wire values. */
public enum BedrockAndroidResourcePackStatus {
    REFUSED(0),
    SEND_PACKS(1),
    HAVE_ALL_PACKS(2),
    COMPLETED(3);

    private final int wireValue;

    BedrockAndroidResourcePackStatus(int wireValue) {
        this.wireValue = wireValue;
    }

    public int wireValue() {
        return wireValue;
    }
}
