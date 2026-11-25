package ru.nexora.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BuildSignatureVerifierTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsUntamperedJarWithMatchingEd25519SignatureAndChecksum() throws Exception {
        KeyPair pair = keyPair();
        Path jar = writeJar("official build");
        Path signature = writeSignature(jar, pair);
        Path checksum = writeChecksum(jar);

        assertEquals(BuildSignatureVerifier.Status.VALID,
                BuildSignatureVerifier.verify(jar, signature, checksum, pair.getPublic()).status());
    }

    @Test
    void rejectsJarModifiedAfterSigning() throws Exception {
        KeyPair pair = keyPair();
        Path jar = writeJar("official build");
        Path signature = writeSignature(jar, pair);
        Path checksum = writeChecksum(jar);
        Files.writeString(jar, "modified build", StandardCharsets.UTF_8);

        assertEquals(BuildSignatureVerifier.Status.INVALID,
                BuildSignatureVerifier.verify(jar, signature, checksum, pair.getPublic()).status());
    }

    @Test
    void rejectsSignatureFromAnotherKey() throws Exception {
        KeyPair signer = keyPair();
        KeyPair other = keyPair();
        Path jar = writeJar("official build");
        Path signature = writeSignature(jar, signer);
        Path checksum = writeChecksum(jar);

        assertEquals(BuildSignatureVerifier.Status.INVALID,
                BuildSignatureVerifier.verify(jar, signature, checksum, other.getPublic()).status());
    }

    private KeyPair keyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private Path writeJar(String content) throws Exception {
        Path jar = temporaryDirectory.resolve("server.jar");
        Files.writeString(jar, content, StandardCharsets.UTF_8);
        return jar;
    }

    private Path writeSignature(Path jar, KeyPair pair) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(Files.readAllBytes(jar));
        Path signature = jar.resolveSibling("server.jar.sig");
        Files.writeString(signature, Base64.getEncoder().encodeToString(signer.sign()), StandardCharsets.US_ASCII);
        return signature;
    }

    private Path writeChecksum(Path jar) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar));
        Path checksum = jar.resolveSibling("server.jar.sha256");
        Files.writeString(checksum, HexFormat.of().formatHex(digest) + "  server.jar\n", StandardCharsets.US_ASCII);
        return checksum;
    }
}
