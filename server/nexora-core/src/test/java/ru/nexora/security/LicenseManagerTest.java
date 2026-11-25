package ru.nexora.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LicenseManagerTest {
    @TempDir Path temporaryDirectory;

    @Test
    void verifiesLicenseForExpectedInstance() throws Exception {
        KeyPair pair = keyPair();
        UUID instance = UUID.randomUUID();
        String token = token(pair, "LIC-001", instance, Instant.now().plusSeconds(3600).getEpochSecond());
        assertEquals("LIC-001", LicenseManager.verifyToken(token, pair.getPublic(), instance).licenseId());
    }

    @Test
    void rejectsLicenseSignedByDifferentKey() throws Exception {
        KeyPair signer = keyPair();
        KeyPair other = keyPair();
        UUID instance = UUID.randomUUID();
        String token = token(signer, "LIC-002", instance, Instant.now().plusSeconds(3600).getEpochSecond());
        assertThrows(IllegalArgumentException.class,
                () -> LicenseManager.verifyToken(token, other.getPublic(), instance));
    }

    @Test
    void rejectsLicenseBoundToAnotherInstance() throws Exception {
        KeyPair pair = keyPair();
        String token = token(pair, "LIC-003", UUID.randomUUID(), Instant.now().plusSeconds(3600).getEpochSecond());
        assertThrows(IllegalArgumentException.class,
                () -> LicenseManager.verifyToken(token, pair.getPublic(), UUID.randomUUID()));
    }

    @Test
    void rejectsExpiredLicense() throws Exception {
        KeyPair pair = keyPair();
        UUID instance = UUID.randomUUID();
        String token = token(pair, "LIC-004", instance, Instant.now().minusSeconds(10).getEpochSecond());
        assertThrows(IllegalArgumentException.class,
                () -> LicenseManager.verifyToken(token, pair.getPublic(), instance));
    }

    @Test
    void promptsAndPersistsValidLicense() throws Exception {
        KeyPair pair = keyPair();
        UUID instance = UUID.randomUUID();
        Files.writeString(temporaryDirectory.resolve("instance.id"), instance.toString());
        String token = token(pair, "LIC-005", instance, Instant.now().plusSeconds(3600).getEpochSecond());
        var input = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream((token + "\\n").getBytes(StandardCharsets.US_ASCII))));
        var output = new PrintStream(new ByteArrayOutputStream());

        var license = LicenseManager.activateOrValidate(temporaryDirectory, input, output, pair.getPublic());

        assertEquals("LIC-005", license.licenseId());
        assertEquals(token, Files.readString(temporaryDirectory.resolve("license.key")).trim());
        var secondStart = LicenseManager.activateOrValidate(temporaryDirectory,
                new BufferedReader(new InputStreamReader(new ByteArrayInputStream(new byte[0]))),
                output, pair.getPublic());
        assertEquals("LIC-005", secondStart.licenseId());
    }

    private KeyPair keyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private String token(KeyPair pair, String id, UUID instance, long expiry) throws Exception {
        byte[] payload = ("NEXORA-LICENSE|1|" + id + "|" + instance + "|" + expiry)
                .getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(payload);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString(payload) + "." + encoder.encodeToString(signer.sign());
    }
}
