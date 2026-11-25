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
    void acceptsPerpetualLicense() throws Exception {
        KeyPair pair = keyPair();
        UUID instance = UUID.randomUUID();
        String token = token(pair, "NEXORA-OWNER", instance, Long.MAX_VALUE);
        var license = LicenseManager.verifyToken(token, pair.getPublic(), instance);
        assertEquals("NEXORA-OWNER", license.licenseId());
        assertEquals(Long.MAX_VALUE, license.expiresAtEpochSeconds());
        assertFalse(license.expired());
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
    void startsWithEmbeddedUnofficialModeWhenNoOfficialPublicKeyIsPinned() throws Exception {
        var input = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(new byte[0])));
        var outputBytes = new ByteArrayOutputStream();
        var license = LicenseManager.activateOrValidate(temporaryDirectory, input, new PrintStream(outputBytes));
        assertEquals("DEV-UNOFFICIAL", license.licenseId());
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("instance.id")));
        assertTrue(outputBytes.toString(StandardCharsets.UTF_8).contains("DEVELOPMENT / UNOFFICIAL"));
    }

    @Test
    void loadsAndReusesValidOwnerLicenseFromSeparateFile() throws Exception {
        KeyPair pair = keyPair();
        UUID instance = UUID.randomUUID();
        Files.writeString(temporaryDirectory.resolve("instance.id"), instance.toString());
        String token = token(pair, "NEXORA-OWNER", instance, Long.MAX_VALUE);
        Files.writeString(temporaryDirectory.resolve("license.key"), token, StandardCharsets.US_ASCII);
        var outputBytes = new ByteArrayOutputStream();
        var output = new PrintStream(outputBytes);

        var license = LicenseManager.activateOrValidate(temporaryDirectory,
                new BufferedReader(new InputStreamReader(new ByteArrayInputStream(new byte[0]))),
                output, pair.getPublic());

        assertEquals("NEXORA-OWNER", license.licenseId());
        assertEquals(Long.MAX_VALUE, license.expiresAtEpochSeconds());
        assertTrue(outputBytes.toString(StandardCharsets.UTF_8).contains("бессрочно"));
        var secondStart = LicenseManager.activateOrValidate(temporaryDirectory,
                new BufferedReader(new InputStreamReader(new ByteArrayInputStream(new byte[0]))),
                output, pair.getPublic());
        assertEquals("NEXORA-OWNER", secondStart.licenseId());
    }

    @Test
    void startsUnofficialWhenOwnerLicenseIsInvalid() throws Exception {
        KeyPair pair = keyPair();
        UUID instance = UUID.randomUUID();
        Files.writeString(temporaryDirectory.resolve("instance.id"), instance.toString());
        Files.writeString(temporaryDirectory.resolve("license.key"), "not-a-valid-token", StandardCharsets.US_ASCII);
        var outputBytes = new ByteArrayOutputStream();
        var license = LicenseManager.activateOrValidate(temporaryDirectory,
                new BufferedReader(new InputStreamReader(new ByteArrayInputStream(new byte[0]))),
                new PrintStream(outputBytes), pair.getPublic());
        assertEquals("DEV-UNOFFICIAL", license.licenseId());
        assertTrue(outputBytes.toString(StandardCharsets.UTF_8).contains("Startup is allowed"));
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
