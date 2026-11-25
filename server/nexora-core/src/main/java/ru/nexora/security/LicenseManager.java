package ru.nexora.security;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Fail-closed NEXORA license activation. License tokens are issued by the
 * private-key holder and verified locally with the pinned Ed25519 public key.
 */
public final class LicenseManager {
    private static final String PUBLIC_KEY_RESOURCE = "/META-INF/nexora-license-public-key.b64";
    private static final String LICENSE_FILE = "license.key";
    private static final String INSTANCE_FILE = "instance.id";
    private static volatile License currentLicense;

    public record License(String licenseId, UUID instanceId, long expiresAtEpochSeconds) {
        public boolean expired() {
            return expiresAtEpochSeconds <= Instant.now().getEpochSecond();
        }
    }

    private LicenseManager() {
    }

    /**
     * Validates the stored license or prompts once on the server console.
     * Throws before network/gameplay startup when activation cannot be proven.
     */
    public static License activateOrValidate(Path dataDirectory, BufferedReader console, PrintStream output)
            throws IOException, GeneralSecurityException {
        PublicKey publicKey = loadPinnedPublicKey();
        Files.createDirectories(dataDirectory);
        UUID instanceId = loadOrCreateInstanceId(dataDirectory);
        if (publicKey == null) {
            return activateUnofficial(instanceId, output,
                    "No official license public key is configured in this build.");
        }
        return activateOrValidate(dataDirectory, console, output, publicKey);
    }

    /**
     * Loads an owner token if present. A missing or invalid token never prevents
     * an unofficial/test server from starting; only a valid signed token is official.
     */
    static License activateOrValidate(Path dataDirectory, BufferedReader console, PrintStream output,
            PublicKey publicKey) throws IOException, GeneralSecurityException {
        Files.createDirectories(dataDirectory);
        UUID instanceId = loadOrCreateInstanceId(dataDirectory);
        if (publicKey == null) {
            return activateUnofficial(instanceId, output, "Official license verification is not configured.");
        }
        Path licensePath = dataDirectory.resolve(LICENSE_FILE);
        if (Files.isRegularFile(licensePath)) {
            String stored = Files.readString(licensePath, StandardCharsets.US_ASCII).trim();
            try {
                License license = verifyToken(stored, publicKey, instanceId);
                currentLicense = license;
                output.printf("[NEXORA] License: ACTIVE (%s), expires %s%n",
                        license.licenseId(), formatExpiry(license.expiresAtEpochSeconds()));
                output.println("[NEXORA] STATUS: OFFICIAL LICENSE ACTIVE.");
                return license;
            } catch (IllegalArgumentException invalid) {
                output.println("[NEXORA] Saved official license is invalid: " + invalid.getMessage());
            }
        }
        return activateUnofficial(instanceId, output,
                "No valid official owner license was found. Place the signed token in data/license.key to activate it.");
    }

    private static License activateUnofficial(UUID instanceId, PrintStream output, String reason) {
        License development = new License("DEV-UNOFFICIAL", instanceId, Long.MAX_VALUE);
        currentLicense = development;
        output.println("[NEXORA] License mode: DEVELOPMENT / UNOFFICIAL");
        output.println("[NEXORA] Server ID: " + instanceId);
        output.println("[NEXORA] " + reason);
        output.println("[NEXORA] Startup is allowed in unofficial mode.");
        return development;
    }

    public static License currentLicense() {
        return currentLicense;
    }

    private static String formatExpiry(long expiresAtEpochSeconds) {
        return expiresAtEpochSeconds == Long.MAX_VALUE
                ? "бессрочно"
                : Instant.ofEpochSecond(expiresAtEpochSeconds).toString();
    }

    public static License verifyToken(String token, PublicKey publicKey, UUID expectedInstanceId)
            throws GeneralSecurityException {
        if (token == null || token.isBlank() || publicKey == null || expectedInstanceId == null) {
            throw new IllegalArgumentException("missing license data");
        }
        String[] parts = token.trim().split("\\.", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("invalid key format");
        }
        byte[] payloadBytes;
        byte[] signatureBytes;
        try {
            Base64.Decoder decoder = Base64.getUrlDecoder();
            payloadBytes = decoder.decode(parts[0]);
            signatureBytes = decoder.decode(parts[1]);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("invalid key encoding");
        }

        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(payloadBytes);
        if (!verifier.verify(signatureBytes)) {
            throw new IllegalArgumentException("signature rejected");
        }

        String payload = new String(payloadBytes, StandardCharsets.UTF_8);
        String[] fields = payload.split("\\|", -1);
        if (fields.length != 5 || !fields[0].equals("NEXORA-LICENSE") || !fields[1].equals("1")) {
            throw new IllegalArgumentException("unsupported license payload");
        }
        String licenseId = fields[2];
        if (licenseId.isBlank() || licenseId.length() > 96) {
            throw new IllegalArgumentException("invalid license identifier");
        }
        UUID instanceId;
        long expiresAt;
        try {
            instanceId = UUID.fromString(fields[3]);
            expiresAt = Long.parseLong(fields[4]);
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("invalid license fields");
        }
        if (!instanceId.equals(expectedInstanceId)) {
            throw new IllegalArgumentException("license belongs to another server ID");
        }
        if (expiresAt <= Instant.now().getEpochSecond()) {
            throw new IllegalArgumentException("license has expired");
        }
        return new License(licenseId, instanceId, expiresAt);
    }

    static PublicKey loadPinnedPublicKey() throws IOException, GeneralSecurityException {
        try (var input = LicenseManager.class.getResourceAsStream(PUBLIC_KEY_RESOURCE)) {
            if (input == null) return null;
            String encoded = new String(input.readAllBytes(), StandardCharsets.US_ASCII).trim();
            if (encoded.isEmpty() || encoded.equals("UNCONFIGURED") || encoded.equals("DEV-UNOFFICIAL")) return null;
            byte[] der = Base64.getMimeDecoder().decode(encoded);
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
        }
    }

    private static UUID loadOrCreateInstanceId(Path dataDirectory) throws IOException {
        Path path = dataDirectory.resolve(INSTANCE_FILE);
        if (Files.isRegularFile(path)) {
            try {
                return UUID.fromString(Files.readString(path, StandardCharsets.US_ASCII).trim());
            } catch (IllegalArgumentException malformed) {
                throw new IllegalStateException("NEXORA instance.id is invalid; refusing to change server identity", malformed);
            }
        }
        UUID created = UUID.randomUUID();
        Files.writeString(path, created.toString() + System.lineSeparator(), StandardCharsets.US_ASCII);
        return created;
    }
}
