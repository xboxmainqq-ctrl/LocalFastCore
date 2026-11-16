package ru.nexora.network.bedrock;

import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;

/** Loads the captured vanilla protocol-2225 biome-definition packet body. */
public final class BedrockBiomeDefinitionCodec {
    public static final int BIOME_DEFINITION_LIST_PACKET_ID = 0x7a;
    private static final String RESOURCE = "/bedrock/biome_definitions_2225.b64";

    private BedrockBiomeDefinitionCodec() {
    }

    public static BedrockPacket createPacket() {
        try (InputStream input = BedrockBiomeDefinitionCodec.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("Missing Bedrock biome definitions resource");
            String encoded = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII);
            byte[] payload = Base64.getMimeDecoder().decode(encoded);
            if (payload.length < 2 || Byte.toUnsignedInt(payload[0]) != 89) {
                throw new IllegalStateException("Unexpected protocol-2225 biome definition count");
            }
            return new BedrockPacket(BIOME_DEFINITION_LIST_PACKET_ID, 0, 0, payload);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load Bedrock biome definitions", exception);
        }
    }
}
