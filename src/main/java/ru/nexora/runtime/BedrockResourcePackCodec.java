package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Protocol-2225 resource-pack negotiation codecs for a server with no packs.
 *
 * <p>This intentionally implements the empty-pack path only. It does not
 * advertise, download, decrypt, or apply resource packs.</p>
 */
public final class BedrockResourcePackCodec {
    public static final int RESOURCE_PACKS_INFO_PACKET_ID = 6;
    public static final int RESOURCE_PACK_STACK_PACKET_ID = 7;
    public static final int RESOURCE_PACK_CLIENT_RESPONSE_PACKET_ID = 8;

    private static final int MAX_DOWNLOADING_PACKS = 1024;
    private static final int MAX_PACK_ID_BYTES = 128;
    private static final int MAX_RESPONSE_NAME_BYTES = 64;
    private static final String EMPTY_WORLD_TEMPLATE_VERSION = "1.0.0";

    private BedrockResourcePackCodec() {
    }

    public enum ClientResponseType {
        CANCEL("Cancel"),
        DOWNLOADING("Downloading"),
        DOWNLOADING_FINISHED("DownloadingFinished"),
        RESOURCE_PACK_STACK_FINISHED("ResourcePackStackFinished");

        private final String wireName;

        ClientResponseType(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        private static ClientResponseType fromWireName(String name) {
            for (ClientResponseType type : values()) {
                if (type.wireName.equals(name)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown resource-pack response type");
        }
    }

    public record ClientResponse(ClientResponseType type, List<String> downloadingPackIds) {
        public ClientResponse {
            Objects.requireNonNull(type, "type");
            downloadingPackIds = List.copyOf(Objects.requireNonNull(
                    downloadingPackIds, "downloadingPackIds"));
            if (type != ClientResponseType.DOWNLOADING && !downloadingPackIds.isEmpty()) {
                throw new IllegalArgumentException("Only Downloading may contain pack identifiers");
            }
            if (downloadingPackIds.size() > MAX_DOWNLOADING_PACKS) {
                throw new IllegalArgumentException("Too many downloading resource packs");
            }
            for (String id : downloadingPackIds) {
                Objects.requireNonNull(id, "pack id");
                if (id.isBlank() || id.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        > MAX_PACK_ID_BYTES) {
                    throw new IllegalArgumentException("Invalid resource-pack identifier");
                }
            }
        }
    }

    /**
     * Encodes ResourcePacksInfo with no required packs, no add-ons and no scripts.
     * The world-template UUID is zero and its version is the neutral 1.0.0 value.
     */
    public static ByteBuffer encodeEmptyResourcePacksInfoPayload() {
        ByteBuffer output = ByteBuffer.allocate(4 + Long.BYTES * 2 + 1 + EMPTY_WORLD_TEMPLATE_VERSION.length() + 1);
        output.put((byte) 0); // Resource Pack Required
        output.put((byte) 0); // Has Addon Packs
        output.put((byte) 0); // Has Scripts
        output.put((byte) 0); // Force Disable Vibrant Visuals
        output.order(ByteOrder.LITTLE_ENDIAN);
        output.putLong(0L); // World template UUID, most-significant bits
        output.putLong(0L); // World template UUID, least-significant bits
        BedrockBinary.writeString(output, EMPTY_WORLD_TEMPLATE_VERSION, 16);
        BedrockBinary.writeVarUInt32(output, 0); // Resource Packs
        output.flip();
        return output.asReadOnlyBuffer();
    }

    /**
     * Encodes ResourcePackStack for a world with no resource packs or experiments.
     * Experiment toggle count uses the schema's fixed-width (uncompressed) uint32.
     */
    public static ByteBuffer encodeEmptyResourcePackStackPayload(String baseGameVersion) {
        if (baseGameVersion == null || baseGameVersion.isBlank()) {
            throw new IllegalArgumentException("baseGameVersion must not be blank");
        }
        ByteBuffer output = ByteBuffer.allocate(1 + 1 + 1
                + baseGameVersion.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + Integer.BYTES + 1 + 1);
        output.put((byte) 0); // Texture Pack Required
        BedrockBinary.writeVarUInt32(output, 0); // Texture Pack List
        BedrockBinary.writeString(output, baseGameVersion, 64);
        output.order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(0); // Experiments toggles: No size compression (uint32 LE)
        output.put((byte) 0); // Experiments Ever Toggled
        output.put((byte) 0); // Include Editor Packs
        output.flip();
        return output.asReadOnlyBuffer();
    }

    /** Decodes the protocol-2225 ResourcePackClientResponse payload. */
    public static ClientResponse decodeClientResponsePayload(ByteBuffer payload) {
        Objects.requireNonNull(payload, "payload");
        String responseName = BedrockBinary.readString(payload, MAX_RESPONSE_NAME_BYTES);
        ClientResponseType type = ClientResponseType.fromWireName(responseName);
        List<String> downloading = new ArrayList<>();
        if (type == ClientResponseType.DOWNLOADING) {
            int count = BedrockBinary.readVarUInt32(payload);
            if (count < 0 || count > MAX_DOWNLOADING_PACKS) {
                throw new IllegalArgumentException("Downloading pack count is outside the allowed range");
            }
            for (int index = 0; index < count; index++) {
                downloading.add(BedrockBinary.readString(payload, MAX_PACK_ID_BYTES));
            }
        }
        if (payload.hasRemaining()) {
            throw new IllegalArgumentException("Trailing data in resource-pack response");
        }
        return new ClientResponse(type, downloading);
    }
}
