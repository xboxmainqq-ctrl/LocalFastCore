package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class NetherNetGameServerTokenVerifierTest {
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
    private static final String ISSUER = "https://authorization.franchise.minecraft-services.net/";
    private static final String AUDIENCE = "api://auth-minecraft-services/multiplayer";

    @Test
    void verifiesRs256TokenAndExtractsP384Cpk() throws Exception {
        KeyPairGenerator rsaGenerator = KeyPairGenerator.getInstance("RSA");
        rsaGenerator.initialize(new RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4));
        KeyPair rsa = rsaGenerator.generateKeyPair();

        KeyPairGenerator ecGenerator = KeyPairGenerator.getInstance("EC");
        ecGenerator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair cpk = ecGenerator.generateKeyPair();

        String token = signedToken(rsa, cpk.getPublic().getEncoded(), 5000L);
        NetherNetIdentityAssertion assertion = parseAssertion(token);
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1000L), ZoneOffset.UTC);
        NetherNetGameServerTokenVerifier verifier = new NetherNetGameServerTokenVerifier(
                kid -> "issuer-key".equals(kid) ? (java.security.interfaces.RSAPublicKey) rsa.getPublic() : null,
                clock, 0L);

        NetherNetGameServerTokenVerifier.VerifiedClientIdentity identity = verifier.verify(assertion);

        assertEquals("123456789", identity.xuid());
        assertEquals("NexoraTester", identity.displayName());
        assertArrayEquals(cpk.getPublic().getEncoded(), identity.clientPublicKey().getEncoded());
    }

    @Test
    void rejectsBadSignatureAndExpiredToken() throws Exception {
        KeyPairGenerator rsaGenerator = KeyPairGenerator.getInstance("RSA");
        rsaGenerator.initialize(2048);
        KeyPair rsa = rsaGenerator.generateKeyPair();

        KeyPairGenerator ecGenerator = KeyPairGenerator.getInstance("EC");
        ecGenerator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair cpk = ecGenerator.generateKeyPair();

        String valid = signedToken(rsa, cpk.getPublic().getEncoded(), 900L);
        NetherNetIdentityAssertion parsed = parseAssertion(valid);
        NetherNetGameServerTokenVerifier expired = new NetherNetGameServerTokenVerifier(
                kid -> (java.security.interfaces.RSAPublicKey) rsa.getPublic(),
                Clock.fixed(Instant.ofEpochSecond(1000L), ZoneOffset.UTC), 0L);
        assertThrows(SecurityException.class, () -> expired.verify(parsed));

        String invalidSignature = valid.substring(0, valid.lastIndexOf('.') + 1)
                + URL.encodeToString(new byte[] {1, 2, 3});
        NetherNetIdentityAssertion tampered = parseAssertion(invalidSignature);
        NetherNetGameServerTokenVerifier verifier = new NetherNetGameServerTokenVerifier(
                kid -> (java.security.interfaces.RSAPublicKey) rsa.getPublic(),
                Clock.fixed(Instant.ofEpochSecond(500L), ZoneOffset.UTC), 0L);
        assertThrows(SecurityException.class, () -> verifier.verify(tampered));
    }

    @Test
    void rejectsIdentityFieldsThatDoNotMatchTheSignedJwt() throws Exception {
        KeyPairGenerator rsaGenerator = KeyPairGenerator.getInstance("RSA");
        rsaGenerator.initialize(2048);
        KeyPair rsa = rsaGenerator.generateKeyPair();
        KeyPairGenerator ecGenerator = KeyPairGenerator.getInstance("EC");
        ecGenerator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair cpk = ecGenerator.generateKeyPair();

        NetherNetIdentityAssertion parsed = parseAssertion(
                signedToken(rsa, cpk.getPublic().getEncoded(), 5000L));
        NetherNetIdentityAssertion spoofed = new NetherNetIdentityAssertion(
                parsed.identityProviderDomain(), parsed.identityProviderProtocol(),
                parsed.token(), parsed.jwtKeyId(), parsed.jwtAlgorithm(),
                parsed.clientPublicKeySpkiBase64(), parsed.issuer(), parsed.audience(),
                parsed.expiresAtEpochSeconds(), parsed.notBeforeEpochSeconds(),
                "spoofed-xuid", parsed.displayName(),
                parsed.detachedFingerprintsJws(), parsed.jwsAlgorithm());
        NetherNetGameServerTokenVerifier verifier = new NetherNetGameServerTokenVerifier(
                kid -> (java.security.interfaces.RSAPublicKey) rsa.getPublic(),
                Clock.fixed(Instant.ofEpochSecond(1000L), ZoneOffset.UTC), 0L);

        assertThrows(SecurityException.class, () -> verifier.verify(spoofed));
    }


    @Test
    void verifiesModernBedrockLoginTokenPayload() throws Exception {
        KeyPairGenerator rsaGenerator = KeyPairGenerator.getInstance("RSA");
        rsaGenerator.initialize(new RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4));
        KeyPair rsa = rsaGenerator.generateKeyPair();
        KeyPairGenerator ecGenerator = KeyPairGenerator.getInstance("EC");
        ecGenerator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair cpk = ecGenerator.generateKeyPair();

        String token = signedToken(rsa, cpk.getPublic().getEncoded(), 5000L);
        BedrockConnectionRequest request = BedrockConnectionRequest.parse(
                "{\"AuthenticationType\":0,\"Token\":\"" + token + "\",\"Certificate\":\"\"}");
        NetherNetGameServerTokenVerifier tokenVerifier = new NetherNetGameServerTokenVerifier(
                kid -> "issuer-key".equals(kid) ? (java.security.interfaces.RSAPublicKey) rsa.getPublic() : null,
                Clock.fixed(Instant.ofEpochSecond(1000L), ZoneOffset.UTC), 0L);

        NetherNetGameServerTokenVerifier.VerifiedClientIdentity identity =
                new BedrockLoginIdentityVerifier(tokenVerifier).verify(request);

        assertEquals("123456789", identity.xuid());
        assertEquals("NexoraTester", identity.displayName());
        assertArrayEquals(cpk.getPublic().getEncoded(), identity.clientPublicKey().getEncoded());
    }

    @Test
    void rejectsOfflineAuthenticationTypeByDefault() throws Exception {
        KeyPairGenerator rsaGenerator = KeyPairGenerator.getInstance("RSA");
        rsaGenerator.initialize(2048);
        KeyPair rsa = rsaGenerator.generateKeyPair();
        KeyPairGenerator ecGenerator = KeyPairGenerator.getInstance("EC");
        ecGenerator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair cpk = ecGenerator.generateKeyPair();

        String token = signedToken(rsa, cpk.getPublic().getEncoded(), 5000L);
        BedrockConnectionRequest request = BedrockConnectionRequest.parse(
                "{\"AuthenticationType\":2,\"Token\":\"" + token + "\",\"Certificate\":\"\"}");
        NetherNetGameServerTokenVerifier tokenVerifier = new NetherNetGameServerTokenVerifier(
                kid -> (java.security.interfaces.RSAPublicKey) rsa.getPublic(),
                Clock.fixed(Instant.ofEpochSecond(1000L), ZoneOffset.UTC), 0L);

        assertThrows(SecurityException.class,
                () -> new BedrockLoginIdentityVerifier(tokenVerifier).verify(request));
    }

    private static String signedToken(KeyPair signingKey, byte[] cpkSpki, long exp)
            throws Exception {
        String header = "{\"alg\":\"RS256\",\"kid\":\"issuer-key\"}";
        String claims = "{\"iss\":\"" + ISSUER + "\",\"aud\":\"" + AUDIENCE
                + "\",\"exp\":" + exp + ",\"nbf\":1,\"xid\":\"123456789\","
                + "\"xname\":\"NexoraTester\",\"cpk\":\""
                + Base64.getEncoder().encodeToString(cpkSpki) + "\"}";
        String encodedHeader = b64(header);
        String encodedClaims = b64(claims);
        String input = encodedHeader + "." + encodedClaims;
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(signingKey.getPrivate());
        signer.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + URL.encodeToString(signer.sign());
    }

    private static NetherNetIdentityAssertion parseAssertion(String token) {
        String inner = "{\"token\":\"" + token + "\",\"fingerprints\":\""
                + b64("{\"alg\":\"ES384\"}") + ".." + URL.encodeToString(new byte[96]) + "\"}";
        String envelope = "{\"idp\":{\"domain\":\"authorization.franchise.minecraft-services.net\","
                + "\"protocol\":\"default\"},\"assertion\":\""
                + inner.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        String identity = Base64.getEncoder().encodeToString(envelope.getBytes(StandardCharsets.UTF_8));
        String sdp = "v=0\na=fingerprint:sha-256 "
                + "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:"
                + "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99\n"
                + "a=identity:" + identity + "\nm=application 9 x";
        return NetherNetIdentityAssertion.parse(NetherNetSdpDocument.parseOffer(sdp));
    }

    private static String b64(String value) {
        return URL.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
