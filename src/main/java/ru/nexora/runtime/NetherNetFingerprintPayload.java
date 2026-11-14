package ru.nexora.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Canonical JSON payload reconstructed from SDP a=fingerprint attributes.
 * Key order and whitespace follow the NetherNet onboarding guide.
 */
public record NetherNetFingerprintPayload(List<Fingerprint> fingerprints) {
    private static final Pattern DIGEST = Pattern.compile("(?i)([0-9a-f]{2}:){1,63}[0-9a-f]{2}");

    public NetherNetFingerprintPayload {
        fingerprints = List.copyOf(Objects.requireNonNull(fingerprints, "fingerprints"));
        if (fingerprints.isEmpty()) {
            throw new IllegalArgumentException("At least one SDP fingerprint is required");
        }
    }

    public static NetherNetFingerprintPayload fromSdp(NetherNetSdpDocument document) {
        Objects.requireNonNull(document, "document");
        List<Fingerprint> parsed = new ArrayList<>();
        for (String line : document.fingerprintLines()) {
            String value = line.substring("a=fingerprint:".length()).trim();
            int separator = value.indexOf(' ');
            if (separator <= 0 || separator == value.length() - 1) {
                throw new IllegalArgumentException("Malformed SDP fingerprint attribute");
            }
            String algorithm = value.substring(0, separator).toLowerCase(Locale.ROOT);
            String digest = value.substring(separator + 1).trim();
            int expectedBytes = switch (algorithm) {
                case "sha-256" -> 32;
                case "sha-384" -> 48;
                case "sha-512" -> 64;
                default -> throw new IllegalArgumentException("Unsupported SDP fingerprint algorithm");
            };
            if (!DIGEST.matcher(digest).matches()
                    || digest.length() != expectedBytes * 3 - 1) {
                throw new IllegalArgumentException("Malformed SDP fingerprint digest");
            }
            parsed.add(new Fingerprint(algorithm, digest));
        }
        return new NetherNetFingerprintPayload(parsed);
    }

    /** Returns the exact UTF-8 JSON bytes that the detached JWS signs. */
    public byte[] canonicalJsonBytes() {
        return canonicalJson().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public String canonicalJson() {
        StringBuilder json = new StringBuilder("{\"fingerprint\":[");
        for (int i = 0; i < fingerprints.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            Fingerprint fingerprint = fingerprints.get(i);
            json.append("{\"algorithm\":").append(quote(fingerprint.algorithm()))
                    .append(",\"digest\":").append(quote(fingerprint.digest())).append('}');
        }
        return json.append("]}").toString();
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    public record Fingerprint(String algorithm, String digest) {
        public Fingerprint {
            Objects.requireNonNull(algorithm, "algorithm");
            Objects.requireNonNull(digest, "digest");
        }
    }
}
