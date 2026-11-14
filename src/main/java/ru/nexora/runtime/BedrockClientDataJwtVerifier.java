package ru.nexora.runtime;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamReadConstraints;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;
import java.util.Objects;

/**
 * Verifies the Bedrock client-data JWT with the P-384 key authenticated by
 * the NetherNet GameServerToken and detached DTLS-fingerprint proof.
 */
public final class BedrockClientDataJwtVerifier {
    public static final int MAX_JWT_CHARS = 1_048_576;
    private static final int COORDINATE_BYTES = 48;
    private static final JsonFactory JSON = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(32)
                    .maxStringLength(MAX_JWT_CHARS)
                    .maxNumberLength(128)
                    .build())
            .build();

    private BedrockClientDataJwtVerifier() {
    }

    /**
     * Verifies the ES384 compact JWS and returns its JSON payload unchanged.
     * The supplied public key must already be trusted by the transport login.
     */
    public static String verifyAndReadPayload(String compactJwt, ECPublicKey trustedClientKey) {
        Objects.requireNonNull(compactJwt, "compactJwt");
        Objects.requireNonNull(trustedClientKey, "trustedClientKey");
        if (compactJwt.isBlank() || compactJwt.length() > MAX_JWT_CHARS) {
            throw new SecurityException("Bedrock client-data JWT is empty or oversized");
        }
        if (trustedClientKey.getParams() == null
                || trustedClientKey.getParams().getCurve().getField().getFieldSize() != 384
                || trustedClientKey.getParams().getOrder().bitLength() != 384) {
            throw new SecurityException("Bedrock client-data key must be P-384");
        }

        String[] parts = compactJwt.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new SecurityException("Bedrock client-data JWT must have three compact segments");
        }
        byte[] headerBytes = decodeBase64Url(parts[0], "client-data JWT header");
        byte[] payloadBytes = decodeBase64Url(parts[1], "client-data JWT payload");
        byte[] signatureBytes = decodeBase64Url(parts[2], "client-data JWT signature");
        if (headerBytes.length > 8192 || payloadBytes.length > MAX_JWT_CHARS
                || signatureBytes.length != COORDINATE_BYTES * 2) {
            throw new SecurityException("Bedrock client-data JWT segment length is invalid");
        }

        String headerJson = decodeUtf8(headerBytes, "client-data JWT header");
        String payloadJson = decodeUtf8(payloadBytes, "client-data JWT payload");
        if (!"ES384".equals(readAlgorithm(headerJson))) {
            throw new SecurityException("Bedrock client-data JWT must use ES384");
        }
        validateJsonObject(payloadJson);

        try {
            Signature verifier = Signature.getInstance("SHA384withECDSA");
            verifier.initVerify(trustedClientKey);
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(rawEcdsaToDer(signatureBytes))) {
                throw new SecurityException("Bedrock client-data JWT signature is invalid");
            }
        } catch (SecurityException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new SecurityException("Could not verify Bedrock client-data JWT", failure);
        }
        return payloadJson;
    }

    private static String readAlgorithm(String json) {
        try (JsonParser parser = JSON.createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new SecurityException("Client-data JWT header must be a JSON object");
            }
            String algorithm = null;
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("alg".equals(field)) {
                    if (value != JsonToken.VALUE_STRING || algorithm != null) {
                        throw new SecurityException("Client-data JWT alg header is invalid");
                    }
                    algorithm = parser.getText();
                } else {
                    parser.skipChildren();
                }
            }
            if (parser.nextToken() != null || algorithm == null) {
                throw new SecurityException("Client-data JWT header is incomplete");
            }
            return algorithm;
        } catch (IOException failure) {
            throw new SecurityException("Client-data JWT header JSON is malformed", failure);
        }
    }

    private static void validateJsonObject(String json) {
        try (JsonParser parser = JSON.createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new SecurityException("Client-data JWT payload must be a JSON object");
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() == null) {
                    throw new SecurityException("Client-data JWT payload is truncated");
                }
                parser.skipChildren();
            }
            if (parser.nextToken() != null) {
                throw new SecurityException("Client-data JWT payload contains trailing JSON");
            }
        } catch (IOException failure) {
            throw new SecurityException("Client-data JWT payload JSON is malformed", failure);
        }
    }

    private static byte[] decodeBase64Url(String value, String label) {
        if (value.indexOf('=') >= 0) {
            throw new SecurityException(label + " must use unpadded Base64URL");
        }
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException failure) {
            throw new SecurityException(label + " is not valid Base64URL", failure);
        }
    }

    private static String decodeUtf8(byte[] value, String label) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value)).toString();
        } catch (CharacterCodingException failure) {
            throw new SecurityException(label + " is not valid UTF-8", failure);
        }
    }

    private static byte[] rawEcdsaToDer(byte[] raw) {
        if (raw.length != COORDINATE_BYTES * 2) {
            throw new SecurityException("ES384 signature must be 96 bytes");
        }
        byte[] r = derInteger(new BigInteger(1,
                java.util.Arrays.copyOfRange(raw, 0, COORDINATE_BYTES)).toByteArray());
        byte[] s = derInteger(new BigInteger(1,
                java.util.Arrays.copyOfRange(raw, COORDINATE_BYTES, raw.length)).toByteArray());
        int sequenceLength = 2 + r.length + 2 + s.length;
        byte[] der = new byte[2 + sequenceLength];
        der[0] = 0x30;
        der[1] = (byte) sequenceLength;
        der[2] = 0x02;
        der[3] = (byte) r.length;
        System.arraycopy(r, 0, der, 4, r.length);
        int sOffset = 4 + r.length;
        der[sOffset] = 0x02;
        der[sOffset + 1] = (byte) s.length;
        System.arraycopy(s, 0, der, sOffset + 2, s.length);
        return der;
    }

    private static byte[] derInteger(byte[] value) {
        int first = 0;
        while (first < value.length - 1 && value[first] == 0) {
            first++;
        }
        byte[] trimmed = java.util.Arrays.copyOfRange(value, first, value.length);
        if ((trimmed[0] & 0x80) == 0) {
            return trimmed;
        }
        byte[] positive = new byte[trimmed.length + 1];
        System.arraycopy(trimmed, 0, positive, 1, trimmed.length);
        return positive;
    }
}
