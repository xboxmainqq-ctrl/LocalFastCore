package ru.nexora.runtime;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.Objects;

/**
 * Verifies the GameServerToken issuer signature and time claims, then decodes
 * the cpk public key for the detached fingerprint proof.
 */
public final class NetherNetGameServerTokenVerifier {
    public static final long DEFAULT_CLOCK_SKEW_SECONDS = 120L;

    private final NetherNetSigningKeyResolver keyResolver;
    private final Clock clock;
    private final long clockSkewSeconds;

    public NetherNetGameServerTokenVerifier(NetherNetSigningKeyResolver keyResolver) {
        this(keyResolver, Clock.systemUTC(), DEFAULT_CLOCK_SKEW_SECONDS);
    }

    public NetherNetGameServerTokenVerifier(NetherNetSigningKeyResolver keyResolver,
                                             Clock clock, long clockSkewSeconds) {
        this.keyResolver = Objects.requireNonNull(keyResolver, "keyResolver");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (clockSkewSeconds < 0 || clockSkewSeconds > 600) {
            throw new IllegalArgumentException("Clock skew must be between 0 and 600 seconds");
        }
        this.clockSkewSeconds = clockSkewSeconds;
    }

    public VerifiedClientIdentity verify(NetherNetIdentityAssertion assertion) throws Exception {
        Objects.requireNonNull(assertion, "assertion");
        if (!"RS256".equals(assertion.jwtAlgorithm())
                || !"https://authorization.franchise.minecraft-services.net/".equals(assertion.issuer())
                || !"api://auth-minecraft-services/multiplayer".equals(assertion.audience())) {
            throw new SecurityException("GameServerToken header or claims are not trusted");
        }

        RSAPublicKey signingKey = keyResolver.resolve(assertion.jwtKeyId());
        if (signingKey == null) {
            throw new SecurityException("Unknown GameServerToken signing key");
        }

        String[] parts = assertion.token().split("\\.", -1);
        if (parts.length != 3) {
            throw new SecurityException("Malformed GameServerToken");
        }
        byte[] signatureBytes = decodeUrl(parts[2]);
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(signingKey);
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        if (!verifier.verify(signatureBytes)) {
            throw new SecurityException("GameServerToken signature verification failed");
        }

        long now = clock.instant().getEpochSecond();
        if (assertion.expiresAtEpochSeconds() + clockSkewSeconds <= now) {
            throw new SecurityException("GameServerToken has expired");
        }
        Long nbf = assertion.notBeforeEpochSeconds();
        if (nbf != null && nbf - clockSkewSeconds > now) {
            throw new SecurityException("GameServerToken is not yet valid");
        }

        byte[] spki;
        try {
            spki = Base64.getDecoder().decode(assertion.clientPublicKeySpkiBase64());
        } catch (IllegalArgumentException e) {
            throw new SecurityException("Invalid cpk SubjectPublicKeyInfo", e);
        }
        ECPublicKey clientKey = (ECPublicKey) KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(spki));
        if (clientKey.getParams().getCurve().getField().getFieldSize() != 384) {
            throw new SecurityException("GameServerToken cpk must be a P-384 public key");
        }
        return new VerifiedClientIdentity(assertion.xuid(), assertion.displayName(), clientKey,
                assertion.expiresAtEpochSeconds());
    }

    private static byte[] decodeUrl(String value) {
        if (value.indexOf('=') >= 0) {
            throw new IllegalArgumentException("JWT signature must be unpadded Base64URL");
        }
        return Base64.getUrlDecoder().decode(value);
    }

    public record VerifiedClientIdentity(String xuid, String displayName,
                                         ECPublicKey clientPublicKey,
                                         long expiresAtEpochSeconds) {
        public VerifiedClientIdentity {
            Objects.requireNonNull(clientPublicKey, "clientPublicKey");
        }
    }
}
