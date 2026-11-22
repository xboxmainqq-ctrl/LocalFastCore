package ru.nexora.network.bedrock;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Serializes the verified Login client-data skin into protocol-2225 SerializedSkin. */
public final class BedrockPlayerSkinCodec {
    private static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_SKIN_JSON_BYTES = 512 * 1024;
    private static final Set<Integer> PIECE_TYPES = Set.of(
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
            15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29);

    private BedrockPlayerSkinCodec() { }

    public static byte[] encode(JsonNode clientData) {
        if (clientData == null || !clientData.isObject()) {
            throw new IllegalArgumentException("Verified client data must be a JSON object");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeString(out, text(clientData, "SkinId", "unknown"));
        writeString(out, decodeBase64Text(text(clientData, "SkinResourcePatch", "")));
        int skinWidth = dimension(clientData, "SkinImageWidth");
        int skinHeight = dimension(clientData, "SkinImageHeight");
        byte[] skinBytes = decodeImage(clientData, "SkinData", skinWidth, skinHeight, true);
        writeImage(out, skinWidth, skinHeight, skinBytes);

        JsonNode animations = clientData.path("AnimatedImageData");
        if (!animations.isArray()) animations = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        if (animations.size() > 64) throw new IllegalArgumentException("Too many skin animations");
        writeVarUInt32(out, animations.size());
        for (JsonNode animation : animations) {
            int width = dimension(animation, "ImageWidth");
            int height = dimension(animation, "ImageHeight");
            byte[] image = decodeBase64(text(animation, "Image", ""), "animated skin image");
            validateImage(width, height, image, false);
            writeImage(out, width, height, image);
            int type = integer(animation, "Type", 0);
            int expression = integer(animation, "AnimationExpression", 0);
            if (type < 0 || type > 3 || expression < 0 || expression > 1) {
                throw new IllegalArgumentException("Invalid skin animation enum");
            }
            writeVarUInt32(out, type);
            writeFloat32(out, (float) number(animation, "Frames", 0.0));
            writeVarUInt32(out, expression);
        }

        int capeWidth = dimension(clientData, "CapeImageWidth");
        int capeHeight = dimension(clientData, "CapeImageHeight");
        byte[] capeBytes = decodeImage(clientData, "CapeData", capeWidth, capeHeight, false);
        writeImage(out, capeWidth, capeHeight, capeBytes);
        writeString(out, decodeBase64Text(text(clientData, "SkinGeometryData", "")));
        writeString(out, text(clientData, "SkinGeometryDataEngineVersion", "0.0.0"));
        writeString(out, text(clientData, "SkinAnimationData", ""));
        writeString(out, text(clientData, "CapeId", ""));
        writeString(out, text(clientData, "FullId", ""));
        out.write("slim".equalsIgnoreCase(text(clientData, "ArmSize", "wide")) ? 0 : 1);
        writeInt32(out, parseColor(text(clientData, "SkinColor", "#000000")));

        JsonNode pieces = clientData.path("PersonaPieces");
        if (!pieces.isArray()) pieces = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        if (pieces.size() > 128) throw new IllegalArgumentException("Too many persona skin pieces");
        writeVarUInt32(out, pieces.size());
        for (JsonNode piece : pieces) {
            writeString(out, text(piece, "PieceId", ""));
            int type = pieceType(text(piece, "PieceType", ""));
            if (!PIECE_TYPES.contains(type)) throw new IllegalArgumentException("Invalid persona piece type");
            writeInt32(out, type);
            writeUuid(out, parseUuid(text(piece, "PackId", "")));
            out.write(bool(piece, "IsDefault", false) ? 1 : 0);
            writeString(out, text(piece, "ProductId", ""));
        }

        JsonNode tints = clientData.path("PieceTintColors");
        if (!tints.isArray()) tints = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        if (tints.size() > 128) throw new IllegalArgumentException("Too many persona tint entries");
        Set<String> tintKeys = new HashSet<>();
        writeVarUInt32(out, tints.size());
        for (JsonNode tint : tints) {
            String key = pieceTintWireType(text(tint, "PieceType", ""));
            if (key.isBlank() || !tintKeys.add(key)) {
                throw new IllegalArgumentException("Invalid or duplicate persona tint key");
            }
            writeString(out, key);
            JsonNode colors = tint.path("Colors");
            if (!colors.isArray() || colors.size() != 4) {
                throw new IllegalArgumentException("Persona tint entry must contain four colors");
            }
            for (JsonNode color : colors) writeInt32(out, parseColor(color.asText("")));
        }

        out.write(bool(clientData, "PremiumSkin", false) ? 1 : 0);
        out.write(bool(clientData, "PersonaSkin", false) ? 1 : 0);
        out.write(bool(clientData, "CapeOnClassicSkin", false) ? 1 : 0);
        out.write(bool(clientData, "IsPrimaryUser", false) ? 1 : 0);
        out.write(bool(clientData, "OverrideSkin", false) ? 1 : 0);
        writeString(out, bool(clientData, "TrustedSkin", false) ? "True" : "False");
        writeString(out, text(clientData, "ProfileHash", ""));
        if (out.size() > MAX_SKIN_JSON_BYTES) {
            throw new IllegalArgumentException("Serialized player skin exceeds size limit");
        }
        return out.toByteArray();
    }

    public static int buildPlatform(JsonNode clientData) {
        return switch (integer(clientData, "DeviceOS", -1)) {
            case 1 -> 1; case 2 -> 2; case 3 -> 3; case 4 -> 4;
            case 7 -> 8; case 8 -> 9; case 10 -> 11; case 11 -> 12;
            case 12 -> 13; case 14 -> 15; default -> -1;
        };
    }

    private static byte[] decodeImage(JsonNode node, String field, int width, int height, boolean required) {
        String encoded = text(node, field, "");
        if (encoded.isBlank() && !required) {
            if (width != 0 || height != 0) throw new IllegalArgumentException(field + " dimensions without image bytes");
            return new byte[0];
        }
        byte[] bytes = decodeBase64(encoded, field);
        validateImage(width, height, bytes, required);
        return bytes;
    }

    private static void validateImage(int width, int height, byte[] bytes, boolean required) {
        if (!required && bytes.length == 0 && width == 0 && height == 0) return;
        if (width < 1 || height < 1 || width > 4096 || height > 4096) {
            throw new IllegalArgumentException("Invalid skin image dimensions");
        }
        long expected = (long) width * height * 4L;
        if (expected != bytes.length || bytes.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("Skin image byte length does not match its dimensions");
        }
    }

    private static byte[] decodeBase64(String value, String field) {
        if (value == null || value.isBlank()) return new byte[0];
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length > MAX_IMAGE_BYTES) throw new IllegalArgumentException(field + " exceeds size limit");
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid base64 in " + field, exception);
        }
    }

    private static String decodeBase64Text(String value) {
        if (value == null || value.isBlank()) return "";
        byte[] decoded = decodeBase64(value, "skin JSON");
        if (decoded.length > MAX_SKIN_JSON_BYTES) throw new IllegalArgumentException("Skin JSON exceeds size limit");
        return new String(decoded, StandardCharsets.UTF_8);
    }

    private static void writeImage(ByteArrayOutputStream out, int width, int height, byte[] bytes) {
        writeInt32(out, width); writeInt32(out, height); writeVarUInt32(out, bytes.length); out.writeBytes(bytes);
    }
    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_SKIN_JSON_BYTES) throw new IllegalArgumentException("Skin string exceeds size limit");
        writeVarUInt32(out, bytes.length); out.writeBytes(bytes);
    }
    private static void writeUuid(ByteArrayOutputStream out, UUID uuid) {
        writeLong64(out, uuid.getMostSignificantBits()); writeLong64(out, uuid.getLeastSignificantBits());
    }
    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) return new UUID(0L, 0L);
        try { return UUID.fromString(value); } catch (IllegalArgumentException ignored) { return new UUID(0L, 0L); }
    }
    private static int pieceType(String value) {
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("persona_")) normalized = normalized.substring("persona_".length());
        if (normalized.equals("hand")) normalized = "hands";
        return switch (normalized) {
            case "unknown" -> 0; case "skeleton" -> 1; case "body" -> 2; case "skin" -> 3;
            case "bottom" -> 4; case "feet" -> 5; case "dress" -> 6; case "top" -> 7;
            case "high_pants", "highpants" -> 8; case "hands" -> 9; case "outerwear" -> 10;
            case "facialhair", "facial_hair" -> 11; case "mouth" -> 12; case "eyes" -> 13;
            case "hair" -> 14; case "hood" -> 15; case "back" -> 16;
            case "faceaccessory", "face_accessory" -> 17; case "head" -> 18; case "legs" -> 19;
            case "leftleg", "left_leg" -> 20; case "rightleg", "right_leg" -> 21; case "arms" -> 22;
            case "leftarm", "left_arm" -> 23; case "rightarm", "right_arm" -> 24; case "capes" -> 25;
            case "classicskin", "classic_skin" -> 26; case "emote" -> 27;
            case "coco" -> 28; case "unsupported" -> 29; default -> -1;
        };
    }
    private static String pieceTintWireType(String value) {
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        if (normalized.equals("persona_hand")) return "hands";
        if (normalized.startsWith("persona_")) return normalized.substring("persona_".length());
        return normalized;
    }
    private static int parseColor(String value) {
        if (value == null || value.isBlank() || value.equals("0") || value.equalsIgnoreCase("#0")) return 0;
        String hex = value.startsWith("#") ? value.substring(1) : value;
        if (hex.length() == 6) hex = "ff" + hex;
        if (hex.length() != 8) throw new IllegalArgumentException("Invalid skin color");
        try { return (int) Long.parseLong(hex, 16); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException("Invalid skin color", exception); }
    }
    private static int dimension(JsonNode node, String field) {
        int value = integer(node, field, 0);
        if (value < 0 || value > 4096) throw new IllegalArgumentException("Invalid " + field);
        return value;
    }
    private static int integer(JsonNode node, String field, int fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.canConvertToInt() ? value.asInt() : fallback;
    }
    private static double number(JsonNode node, String field, double fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isNumber() ? value.asDouble() : fallback;
    }
    private static boolean bool(JsonNode node, String field, boolean fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isBoolean() ? value.asBoolean() : fallback;
    }
    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText() : fallback;
    }
    private static void writeInt32(ByteArrayOutputStream out, int value) {
        out.write(value); out.write(value >>> 8); out.write(value >>> 16); out.write(value >>> 24);
    }
    private static void writeLong64(ByteArrayOutputStream out, long value) {
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) out.write((int) (value >>> shift));
    }
    private static void writeFloat32(ByteArrayOutputStream out, float value) {
        out.writeBytes(ByteBuffer.allocate(Float.BYTES).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array());
    }
    private static void writeVarUInt32(ByteArrayOutputStream out, int value) {
        if (value < 0) throw new IllegalArgumentException("VarUInt32 value must not be negative");
        do { int next = value & 0x7f; value >>>= 7; out.write(value == 0 ? next : next | 0x80); } while (value != 0);
    }
}
