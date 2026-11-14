package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BedrockResourcePackCodecTest {
    @Test
    void encodesEmptyResourcePacksInfoInProtocolOrder() {
        ByteBuffer payload = BedrockResourcePackCodec.encodeEmptyResourcePacksInfoPayload();
        assertEquals(27, payload.remaining());
        assertEquals(0, payload.get(0)); // required
        assertEquals(0, payload.get(1)); // add-ons
        assertEquals(0, payload.get(2)); // scripts
        assertEquals(0, payload.get(3)); // force-disable vibrant visuals
        for (int index = 4; index < 20; index++) {
            assertEquals(0, payload.get(index)); // zero world-template UUID
        }
        assertEquals(5, Byte.toUnsignedInt(payload.get(20))); // "1.0.0"
        assertEquals(0, payload.get(26)); // empty pack list
    }

    @Test
    void encodesEmptyResourcePackStackAndFixedWidthExperimentCount() {
        ByteBuffer payload = BedrockResourcePackCodec.encodeEmptyResourcePackStackPayload("1.26.60");
        assertEquals(16, payload.remaining());
        assertEquals(0, payload.get(0)); // texture pack required
        assertEquals(0, payload.get(1)); // texture pack list
        assertEquals(7, Byte.toUnsignedInt(payload.get(2))); // base game version length
        assertEquals('1', payload.get(3));
        assertEquals('0', payload.get(9));
        assertEquals(0, payload.get(10));
        assertEquals(0, payload.get(11));
        assertEquals(0, payload.get(12));
        assertEquals(0, payload.get(13)); // experiments ever toggled
        assertEquals(0, payload.get(14)); // include editor packs
        assertEquals(0, payload.get(15)); // include editor packs
    }

    @Test
    void decodesProtocol2225ResponseNamesAndDownloadingList() {
        assertEquals(BedrockResourcePackCodec.ClientResponseType.CANCEL,
                BedrockResourcePackCodec.decodeClientResponsePayload(stringPayload("Cancel")).type());
        assertEquals(BedrockResourcePackCodec.ClientResponseType.DOWNLOADING_FINISHED,
                BedrockResourcePackCodec.decodeClientResponsePayload(
                        stringPayload("DownloadingFinished")).type());
        assertEquals(BedrockResourcePackCodec.ClientResponseType.RESOURCE_PACK_STACK_FINISHED,
                BedrockResourcePackCodec.decodeClientResponsePayload(
                        stringPayload("ResourcePackStackFinished")).type());

        ByteBuffer downloading = ByteBuffer.allocate(32);
        BedrockBinary.writeString(downloading, "Downloading", 64);
        BedrockBinary.writeVarUInt32(downloading, 1);
        BedrockBinary.writeString(downloading, "pack-id", 128);
        downloading.flip();
        var decoded = BedrockResourcePackCodec.decodeClientResponsePayload(downloading);
        assertEquals(BedrockResourcePackCodec.ClientResponseType.DOWNLOADING, decoded.type());
        assertEquals(List.of("pack-id"), decoded.downloadingPackIds());
    }

    @Test
    void rejectsUnknownTruncatedAndTrailingResponseData() {
        assertThrows(IllegalArgumentException.class,
                () -> BedrockResourcePackCodec.decodeClientResponsePayload(stringPayload("Unknown")));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockResourcePackCodec.decodeClientResponsePayload(ByteBuffer.wrap(new byte[]{5, 'C'})));

        ByteBuffer trailing = ByteBuffer.allocate(16);
        BedrockBinary.writeString(trailing, "Cancel", 64);
        trailing.put((byte) 1).flip();
        assertThrows(IllegalArgumentException.class,
                () -> BedrockResourcePackCodec.decodeClientResponsePayload(trailing));
    }

    @Test
    void rejectsInvalidServerStackVersion() {
        assertThrows(IllegalArgumentException.class,
                () -> BedrockResourcePackCodec.encodeEmptyResourcePackStackPayload(" "));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockResourcePackCodec.encodeEmptyResourcePackStackPayload(null));
    }

    private static ByteBuffer stringPayload(String value) {
        ByteBuffer output = ByteBuffer.allocate(64);
        BedrockBinary.writeString(output, value, 64);
        output.flip();
        return output.order(ByteOrder.LITTLE_ENDIAN);
    }
}
