package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * Bounded, lossless extractor for the NetherNet SDP identity attribute.
 *
 * <p>The original SDP is retained for signature verification. A second SDP
 * copy has only the session-level a=identity line removed and may be passed
 * to a WebRTC SDP parser. This class does not parse or verify JWT/JWS claims.</p>
 */
public record NetherNetSdpDocument(
        String originalSdp,
        String webRtcSdp,
        String encodedIdentity,
        String identityEnvelopeJson,
        List<String> fingerprintLines
) {
    public static final int DEFAULT_MAX_SDP_BYTES = 256 * 1024;
    public static final int DEFAULT_MAX_IDENTITY_BYTES = 16 * 1024;

    public NetherNetSdpDocument {
        Objects.requireNonNull(originalSdp, "originalSdp");
        Objects.requireNonNull(webRtcSdp, "webRtcSdp");
        if ((encodedIdentity == null) != (identityEnvelopeJson == null)) {
            throw new IllegalArgumentException("Encoded identity and envelope must both be present or absent");
        }
        fingerprintLines = List.copyOf(Objects.requireNonNull(fingerprintLines, "fingerprintLines"));
    }

    public static NetherNetSdpDocument parseOffer(String sdp) {
        return parseOffer(sdp, DEFAULT_MAX_SDP_BYTES, DEFAULT_MAX_IDENTITY_BYTES);
    }

    public static NetherNetSdpDocument parseOffer(String sdp, int maxSdpBytes,
                                                   int maxIdentityBytes) {
        return parseOffer(sdp, maxSdpBytes, maxIdentityBytes, true);
    }

    /**
     * Parses a full-ICE SDP offer. Missing identity is permitted only for an
     * explicitly configured unauthenticated development admission policy.
     */
    public static NetherNetSdpDocument parseOffer(String sdp, int maxSdpBytes,
                                                   int maxIdentityBytes,
                                                   boolean requireIdentity) {
        Objects.requireNonNull(sdp, "sdp");
        if (maxSdpBytes < 1 || maxIdentityBytes < 1) {
            throw new IllegalArgumentException("SDP and identity limits must be positive");
        }
        if (sdp.getBytes(StandardCharsets.UTF_8).length > maxSdpBytes) {
            throw new IllegalArgumentException("SDP exceeds configured byte limit");
        }
        if (sdp.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("SDP contains a NUL character");
        }

        String[] lines = sdp.split("\\r?\\n", -1);
        List<String> fingerprints = new ArrayList<>();
        String encodedIdentity = null;
        int identityLineIndex = -1;
        int firstMediaLine = -1;
        int lastSessionFingerprintLine = -1;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("m=") && firstMediaLine < 0) {
                firstMediaLine = i;
            }
            if (line.startsWith("a=fingerprint:")) {
                fingerprints.add(line);
                if (firstMediaLine < 0) {
                    lastSessionFingerprintLine = i;
                }
            }
            if (line.startsWith("a=identity:")) {
                if (identityLineIndex >= 0) {
                    throw new IllegalArgumentException("SDP contains multiple a=identity attributes");
                }
                if (i == 0 || firstMediaLine >= 0) {
                    throw new IllegalArgumentException("a=identity must be a session-level SDP attribute");
                }
                encodedIdentity = line.substring("a=identity:".length()).trim();
                identityLineIndex = i;
            }
        }

        if (firstMediaLine < 0 || fingerprints.isEmpty()) {
            throw new IllegalArgumentException("SDP offer requires a DTLS fingerprint and media section");
        }
        if (identityLineIndex < 0) {
            if (requireIdentity) {
                throw new IllegalArgumentException("SDP offer is missing a=identity");
            }
            return new NetherNetSdpDocument(sdp, sdp, null, null, fingerprints);
        }
        if (lastSessionFingerprintLine < 0) {
            throw new IllegalArgumentException(
                    "Authenticated SDP identity requires a session-level fingerprint");
        }
        if (encodedIdentity == null || encodedIdentity.isBlank()) {
            throw new IllegalArgumentException("SDP a=identity is empty");
        }
        if (identityLineIndex <= lastSessionFingerprintLine) {
            throw new IllegalArgumentException("a=identity must follow session fingerprint attributes");
        }
        if (identityLineIndex >= firstMediaLine) {
            throw new IllegalArgumentException("a=identity must precede the first media section");
        }
        if (encodedIdentity.length() > maxIdentityBytes * 2L) {
            throw new IllegalArgumentException("Encoded identity exceeds configured limit");
        }

        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedIdentity);
        } catch (IllegalArgumentException invalidBase64) {
            throw new IllegalArgumentException("a=identity is not valid Base64", invalidBase64);
        }
        if (decoded.length > maxIdentityBytes) {
            throw new IllegalArgumentException("Decoded identity exceeds configured byte limit");
        }

        final String envelope;
        try {
            envelope = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decoded)).toString();
        } catch (CharacterCodingException invalidUtf8) {
            throw new IllegalArgumentException("a=identity is not valid UTF-8", invalidUtf8);
        }
        if (envelope.isBlank() || envelope.charAt(0) != '{'
                || envelope.charAt(envelope.length() - 1) != '}') {
            throw new IllegalArgumentException("a=identity envelope must be a JSON object");
        }

        StringBuilder cleaned = new StringBuilder(sdp.length());
        for (int i = 0; i < lines.length; i++) {
            if (i == identityLineIndex) {
                continue;
            }
            if (i > 0) {
                cleaned.append(sdp.contains("\r\n") ? "\r\n" : "\n");
            }
            cleaned.append(lines[i]);
        }
        return new NetherNetSdpDocument(sdp, cleaned.toString(), encodedIdentity,
                envelope, fingerprints);
    }
}
