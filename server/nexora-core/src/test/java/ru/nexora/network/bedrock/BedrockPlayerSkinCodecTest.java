package ru.nexora.network.bedrock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class BedrockPlayerSkinCodecTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void serializesVerifiedClassicSkinFieldsInProtocol2225Order() throws Exception {
        byte[] skin = new byte[64 * 64 * 4];
        String patch = Base64.getEncoder().encodeToString(
                "{\"geometry\":{\"default\":\"geometry.humanoid.custom\"}}".getBytes(StandardCharsets.UTF_8));
        String geometry = Base64.getEncoder().encodeToString(
                "{\"format_version\":\"1.12.0\"}".getBytes(StandardCharsets.UTF_8));
        JsonNode clientData = MAPPER.readTree("""
                {
                  "SkinId":"test-skin",
                  "SkinResourcePatch":"%s",
                  "SkinData":"%s",
                  "SkinImageWidth":64,
                  "SkinImageHeight":64,
                  "SkinGeometryData":"%s",
                  "SkinGeometryDataEngineVersion":"0.0.0",
                  "ArmSize":"slim",
                  "SkinColor":"#b37b62",
                  "DeviceOS":12,
                  "PlatformOnlineId":"xbox-online-id"
                }
                """.formatted(patch, Base64.getEncoder().encodeToString(skin), geometry));

        byte[] encoded = BedrockPlayerSkinCodec.encode(clientData);
        ByteBuffer in = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("test-skin", readString(in));
        assertTrue(readString(in).contains("geometry.humanoid.custom"));
        assertEquals(64, in.getInt());
        assertEquals(64, in.getInt());
        assertEquals(skin.length, readVarUInt32(in));
        in.position(in.position() + skin.length);
        assertEquals(0, readVarUInt32(in)); // animated images
        assertEquals(0, in.getInt()); // cape width
        assertEquals(0, in.getInt()); // cape height
        assertEquals(0, readVarUInt32(in)); // cape bytes
        assertTrue(readString(in).contains("format_version"));
        assertEquals("0.0.0", readString(in));
        assertEquals("", readString(in)); // animation data
        assertEquals("", readString(in)); // cape ID
        assertEquals("", readString(in)); // full ID
        assertEquals(0, Byte.toUnsignedInt(in.get())); // slim
        assertEquals(0xffb37b62, in.getInt());
        assertEquals(0, readVarUInt32(in)); // persona pieces
        assertEquals(0, readVarUInt32(in)); // piece tint colors
        assertFalse(in.get() != 0); // premium
        assertFalse(in.get() != 0); // persona
        assertFalse(in.get() != 0); // cape on classic skin
        assertFalse(in.get() != 0); // primary user
        assertFalse(in.get() != 0); // override appearance
        assertEquals("False", readString(in));
        assertEquals("", readString(in));
        assertFalse(in.hasRemaining());
    }

    @Test
    void matchesNukkitMotShortAndInvalidSkinColorFallbacks() throws Exception {
        String skin = Base64.getEncoder().encodeToString(new byte[64 * 64 * 4]);
        JsonNode shortDecimal = MAPPER.readTree("""
                {"SkinId":"short","SkinResourcePatch":"e30=","SkinData":"%s",
                 "SkinImageWidth":64,"SkinImageHeight":64,"SkinColor":"123"}
                """.formatted(skin));
        JsonNode invalid = MAPPER.readTree("""
                {"SkinId":"invalid","SkinResourcePatch":"e30=","SkinData":"%s",
                 "SkinImageWidth":64,"SkinImageHeight":64,"SkinColor":"not-a-color"}
                """.formatted(skin));

        assertEquals(123, encodedSkinColor(shortDecimal));
        assertEquals(0, encodedSkinColor(invalid));
    }

    @Test
    void acceptsPersonaPrefixedPieceTypesFromLoginClientData() throws Exception {
        String skin = Base64.getEncoder().encodeToString(new byte[64 * 64 * 4]);
        JsonNode clientData = MAPPER.readTree("""
                {
                  "SkinId":"persona-test",
                  "SkinResourcePatch":"e30=",
                  "SkinData":"%s",
                  "SkinImageWidth":64,
                  "SkinImageHeight":64,
                  "PersonaPieces":[
                    {"PieceId":"skeleton","PieceType":"persona_skeleton","PackId":"00000000-0000-0000-0000-000000000000"},
                    {"PieceId":"hand","PieceType":"persona_hand","PackId":"00000000-0000-0000-0000-000000000000"}
                  ]
                }
                """.formatted(skin));
        assertDoesNotThrow(() -> BedrockPlayerSkinCodec.encode(clientData));
    }

    @Test
    void acceptsZeroPersonaTintAndProtocol2225CocoAndUnsupportedTypes() throws Exception {
        String skin = Base64.getEncoder().encodeToString(new byte[64 * 64 * 4]);
        JsonNode clientData = MAPPER.readTree("""
                {
                  "SkinId":"persona-tint-test",
                  "SkinResourcePatch":"e30=",
                  "SkinData":"%s",
                  "SkinImageWidth":64,
                  "SkinImageHeight":64,
                  "PersonaPieces":[
                    {"PieceId":"coco","PieceType":"persona_coco"},
                    {"PieceId":"unsupported","PieceType":"persona_unsupported"}
                  ],
                  "PieceTintColors":[
                    {"PieceType":"persona_eyes","Colors":["#ffa12722","#ff2f1f0f","#ff3aafd9","#0"]}
                  ]
                }
                """.formatted(skin));
        byte[] encoded = BedrockPlayerSkinCodec.encode(clientData);
        assertTrue(encoded.length > 64 * 64 * 4);
    }

    @Test
    void mapsClientDeviceOsToProtocolBuildPlatform() throws Exception {
        assertEquals(1, BedrockPlayerSkinCodec.buildPlatform(MAPPER.readTree("{\"DeviceOS\":1}")));
        assertEquals(8, BedrockPlayerSkinCodec.buildPlatform(MAPPER.readTree("{\"DeviceOS\":7}")));
        assertEquals(13, BedrockPlayerSkinCodec.buildPlatform(MAPPER.readTree("{\"DeviceOS\":12}")));
        assertEquals(-1, BedrockPlayerSkinCodec.buildPlatform(MAPPER.readTree("{\"DeviceOS\":999}")));
    }

    @Test
    void rejectsInvalidSkinImageLength() throws Exception {
        JsonNode clientData = MAPPER.readTree("""
                {"SkinId":"x","SkinResourcePatch":"e30=","SkinData":"AQ==",
                 "SkinImageWidth":64,"SkinImageHeight":64}
                """);
        assertThrows(IllegalArgumentException.class, () -> BedrockPlayerSkinCodec.encode(clientData));
    }

    private static int encodedSkinColor(JsonNode clientData) {
        ByteBuffer in = ByteBuffer.wrap(BedrockPlayerSkinCodec.encode(clientData)).order(ByteOrder.LITTLE_ENDIAN);
        readString(in); // SkinId
        readString(in); // SkinResourcePatch
        in.getInt(); // skin width
        in.getInt(); // skin height
        int skinBytes = readVarUInt32(in);
        in.position(in.position() + skinBytes);
        assertEquals(0, readVarUInt32(in)); // animations
        in.getInt(); // cape width
        in.getInt(); // cape height
        int capeBytes = readVarUInt32(in);
        in.position(in.position() + capeBytes);
        readString(in); // geometry
        readString(in); // geometry engine version
        readString(in); // animation data
        readString(in); // cape ID
        readString(in); // full skin ID
        in.get(); // arm size
        return in.getInt();
    }

    private static String readString(ByteBuffer in) {
        int length = readVarUInt32(in);
        byte[] bytes = new byte[length];
        in.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int readVarUInt32(ByteBuffer in) {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int next = Byte.toUnsignedInt(in.get());
            value |= (next & 0x7f) << shift;
            if ((next & 0x80) == 0) return value;
        }
        throw new AssertionError("Malformed VarUInt32");
    }
}
