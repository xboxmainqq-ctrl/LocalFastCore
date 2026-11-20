package ru.nexora.client;

/** Protocol-2168+ resource-pack response status wire values. */
public enum BedrockAndroidResourcePackStatus {
    REFUSED(1),
    SEND_PACKS(2),
    HAVE_ALL_PACKS(3),
    COMPLETED(4);

    private final int wireValue;

    BedrockAndroidResourcePackStatus(int wireValue) {
        this.wireValue = wireValue;
    }

    public int wireValue() {
        return wireValue;
    }
}
