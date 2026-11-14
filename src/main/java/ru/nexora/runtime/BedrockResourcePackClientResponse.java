package ru.nexora.runtime;

import java.util.List;
import java.util.Objects;

/** Decoded ResourcePackClientResponse status and any requested pack IDs. */
public record BedrockResourcePackClientResponse(Status status, List<String> packIds) {
    public BedrockResourcePackClientResponse {
        Objects.requireNonNull(status, "status");
        packIds = List.copyOf(Objects.requireNonNull(packIds, "packIds"));
        if (packIds.size() > 1024) {
            throw new IllegalArgumentException("Too many resource pack IDs");
        }
    }

    public enum Status {
        REFUSED(0),
        SEND_PACKS(1),
        HAVE_ALL_PACKS(2),
        COMPLETED(3);

        private final int wireValue;

        Status(int wireValue) {
            this.wireValue = wireValue;
        }

        public int wireValue() {
            return wireValue;
        }

        public static Status fromWireValue(int value) {
            for (Status status : values()) {
                if (status.wireValue == value) {
                    return status;
                }
            }
            throw new IllegalArgumentException("Unknown resource pack response status: " + value);
        }
    }
}
