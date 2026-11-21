package ru.nexora.network.bedrock;

import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;

/**
 * Replays captured vanilla protocol-2225 jigsaw rules.
 * The resource is raw network NBT encoded as Base64 (not gzip-compressed).
 */
public final class BedrockJigsawStructureDataCodec {
    public static final int JIGSAW_STRUCTURE_DATA_PACKET_ID = 0x139;
    private static final String RESOURCE = "/bedrock/jigsaw_structure_data_2225.nbt.gz.b64";
    private static final byte[] STRUCTURE_DATA = loadStructureData();

    private BedrockJigsawStructureDataCodec() {
    }

    public static BedrockPacket createPacket() {
        return new BedrockPacket(JIGSAW_STRUCTURE_DATA_PACKET_ID, 0, 0, STRUCTURE_DATA);
    }

    private static byte[] loadStructureData() {
        try (InputStream input = BedrockJigsawStructureDataCodec.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing protocol-2225 jigsaw structure data resource");
            }
            String encoded = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII);
            byte[] payload = Base64.getMimeDecoder().decode(encoded);
            if (payload.length < 3 || Byte.toUnsignedInt(payload[0]) != 0x0a
                    || payload[1] != 0) {
                throw new IllegalStateException("Invalid protocol-2225 jigsaw network-NBT root");
            }
            return payload;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load protocol-2225 jigsaw structure data", exception);
        }
    }
}
