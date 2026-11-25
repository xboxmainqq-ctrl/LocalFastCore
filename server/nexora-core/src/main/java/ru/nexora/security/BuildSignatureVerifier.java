package ru.nexora.security;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Verifies the detached Ed25519 signature and SHA-256 manifest distributed
 * beside the official standalone server JAR.
 */
public final class BuildSignatureVerifier {
    private static final String PUBLIC_KEY_RESOURCE = "/META-INF/nexora-signing-public-key.b64";

    public enum Status {
        VALID, INVALID, UNSIGNED, KEY_NOT_CONFIGURED, DEVELOPMENT, UNAVAILABLE
    }

    public record Result(Status status, String detail) {
        public boolean valid() {
            return status == Status.VALID;
        }
    }

    private BuildSignatureVerifier() {
    }

    public static Result verifyCurrentBuild() {
        try {
            Path location = Path.of(BuildSignatureVerifier.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (!Files.isRegularFile(location)) {
                return new Result(Status.DEVELOPMENT, "running from classes directory");
            }
            Path signature = location.resolveSibling(location.getFileName() + ".sig");
            Path checksum = location.resolveSibling(location.getFileName() + ".sha256");
            if (!Files.exists(signature) && !Files.exists(checksum)) {
                return new Result(Status.UNSIGNED, "signature sidecars are not present");
            }
            if (!Files.isRegularFile(signature) || !Files.isRegularFile(checksum)) {
                return new Result(Status.INVALID, "signature or checksum sidecar is missing");
            }
            PublicKey key = loadPinnedPublicKey();
            if (key == null) {
                return new Result(Status.KEY_NOT_CONFIGURED, "official signing public key is not configured");
            }
            return verify(location, signature, checksum, key);
        } catch (IOException | URISyntaxException | GeneralSecurityException exception) {
            return new Result(Status.UNAVAILABLE, exception.getClass().getSimpleName() + ": "
                    + String.valueOf(exception.getMessage()));
        }
    }

    static PublicKey loadPinnedPublicKey() throws IOException, GeneralSecurityException {
        try (InputStream input = BuildSignatureVerifier.class.getResourceAsStream(PUBLIC_KEY_RESOURCE)) {
            if (input == null) {
                return null;
            }
            String encoded = new String(input.readAllBytes(), StandardCharsets.US_ASCII).trim();
            if (encoded.isEmpty() || encoded.equals("UNCONFIGURED")) {
                return null;
            }
            byte[] der = Base64.getMimeDecoder().decode(encoded);
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
        }
    }

    public static Result verify(Path jar, Path signatureFile, Path checksumFile, PublicKey publicKey)
            throws IOException, GeneralSecurityException {
        if (publicKey == null) {
            return new Result(Status.KEY_NOT_CONFIGURED, "public key is missing");
        }
        if (!Files.isRegularFile(jar) || !Files.isRegularFile(signatureFile) || !Files.isRegularFile(checksumFile)) {
            return new Result(Status.INVALID, "one or more build files are missing");
        }

        byte[] digest = sha256(jar);
        String expectedHex = Files.readString(checksumFile, StandardCharsets.US_ASCII).trim()
                .split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
        if (!expectedHex.matches("[0-9a-f]{64}")) {
            return new Result(Status.INVALID, "checksum file has an invalid format");
        }
        if (!MessageDigest.isEqual(HexFormat.of().parseHex(expectedHex), digest)) {
            return new Result(Status.INVALID, "SHA-256 checksum mismatch");
        }

        byte[] signatureBytes;
        try {
            signatureBytes = Base64.getMimeDecoder().decode(
                    Files.readString(signatureFile, StandardCharsets.US_ASCII).trim());
        } catch (IllegalArgumentException exception) {
            return new Result(Status.INVALID, "signature file is not valid Base64");
        }

        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        try (InputStream input = Files.newInputStream(jar)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                verifier.update(buffer, 0, read);
            }
        }
        if (!verifier.verify(signatureBytes)) {
            return new Result(Status.INVALID, "Ed25519 signature mismatch");
        }
        return new Result(Status.VALID, "SHA-256 and Ed25519 signature verified");
    }

    private static byte[] sha256(Path file) throws IOException, GeneralSecurityException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }
}
