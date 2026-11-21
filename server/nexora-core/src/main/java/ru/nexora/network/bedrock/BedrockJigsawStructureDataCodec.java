package ru.nexora.network.bedrock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.zip.GZIPInputStream;

/**
 * Replays the captured vanilla protocol-2225 jigsaw rules.
 *
 * Bedrock 1.26.50+ requires this network-NBT compound before StartGame,
 * even when the server does not implement client-side structure generation.
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
            byte[] compressed = Base64.getMimeDecoder().decode(encoded);
            try (GZIPInputStream gzip = new GZIPInputStream(new java.io.ByteArrayInputStream(compressed));
                 ByteArrayOutputStream output = new ByteArrayOutputStream(256 * 1024)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = gzip.read(buffer)) != -1) {
                    if (output.size() + read > 4 * 1024 * 1024) {
                        throw new IllegalStateException("Protocol-2225 jigsaw structure data exceeds 4 MiB");
                    }
                    output.write(buffer, 0, read);
                }
                byte[] payload = output.toByteArray();
                // Network little-endian NBT compound with an empty root name.
                if (payload.length < 3 || Byte.toUnsignedInt(payload[0]) != 0x0a
                        || payload[1] != 0) {
                    throw new IllegalStateException("Invalid protocol-2225 jigsaw network-NBT root");
                }
                return payload;
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load protocol-2225 jigsaw structure data", exception);
        }
    }
}
