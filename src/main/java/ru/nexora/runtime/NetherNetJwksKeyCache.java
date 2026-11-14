package ru.nexora.runtime;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamReadConstraints;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded, rate-limited cache of RS256 public keys published by the Minecraft
 * franchise authorization service.
 */
public final class NetherNetJwksKeyCache implements NetherNetSigningKeyResolver {
    public static final URI DEFAULT_JWKS_URI = URI.create(
            "https://authorization.franchise.minecraft-services.net/.well-known/keys");
    private static final int MAX_JWKS_BYTES = 256 * 1024;
    private static final long MIN_REFRESH_NANOS = Duration.ofMinutes(5).toNanos();
    private static final JsonFactory JSON = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8).maxStringLength(16 * 1024).maxNumberLength(64).build())
            .build();

    private final HttpClient httpClient;
    private final URI jwksUri;
    private final Duration requestTimeout;
    private Map<String, RSAPublicKey> keys = Map.of();
    private long lastRefreshNanos = Long.MIN_VALUE;

    public NetherNetJwksKeyCache() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER).build(),
                DEFAULT_JWKS_URI, Duration.ofSeconds(10));
    }

    public NetherNetJwksKeyCache(HttpClient httpClient, URI jwksUri, Duration requestTimeout) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.jwksUri = Objects.requireNonNull(jwksUri, "jwksUri");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (!"https".equalsIgnoreCase(jwksUri.getScheme()) || requestTimeout.isNegative()
                || requestTimeout.isZero()) {
            throw new IllegalArgumentException("JWKS requires HTTPS and a positive timeout");
        }
    }

    @Override
    public synchronized RSAPublicKey resolve(String keyId) throws Exception {
        Objects.requireNonNull(keyId, "keyId");
        if (keyId.isBlank() || keyId.length() > 256) {
            return null;
        }
        RSAPublicKey cached = keys.get(keyId);
        if (cached != null) {
            return cached;
        }
        long now = System.nanoTime();
        if (lastRefreshNanos != Long.MIN_VALUE
                && now - lastRefreshNanos < MIN_REFRESH_NANOS) {
            return null;
        }
        lastRefreshNanos = now;
        Map<String, RSAPublicKey> fetched = fetch();
        if (fetched != null) {
            keys = Map.copyOf(fetched);
        }
        return keys.get(keyId);
    }

    private Map<String, RSAPublicKey> fetch() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(jwksUri)
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<InputStream> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new java.io.IOException("JWKS endpoint returned HTTP " + response.statusCode());
            }
            byte[] json = readBounded(body, MAX_JWKS_BYTES);
            return parseKeys(json);
        }
    }

    private static Map<String, RSAPublicKey> parseKeys(byte[] json) throws Exception {
        Map<String, RSAPublicKey> result = new HashMap<>();
        try (JsonParser parser = JSON.createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new java.io.IOException("JWKS root must be an object");
            }
            boolean keysSeen = false;
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("keys".equals(field)) {
                    if (value != JsonToken.START_ARRAY) {
                        throw new java.io.IOException("JWKS keys must be an array");
                    }
                    keysSeen = true;
                    while (parser.nextToken() == JsonToken.START_OBJECT) {
                        Map<String, String> jwk = readJwk(parser);
                        RSAPublicKey key = createRsaKey(jwk);
                        if (key != null) {
                            String kid = jwk.get("kid");
                            if (kid == null || kid.isBlank() || result.putIfAbsent(kid, key) != null) {
                                throw new java.io.IOException("JWKS contains missing or duplicate kid");
                            }
                        }
                    }
                } else {
                    parser.skipChildren();
                }
            }
            if (!keysSeen || parser.nextToken() != null) {
                throw new java.io.IOException("JWKS is missing keys or has trailing JSON");
            }
        }
        if (result.isEmpty()) {
            throw new java.io.IOException("JWKS contains no usable RS256 keys");
        }
        return result;
    }

    private static Map<String, String> readJwk(JsonParser parser) throws Exception {
        Map<String, String> values = new HashMap<>();
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String name = parser.currentName();
            JsonToken value = parser.nextToken();
            if ("kid".equals(name) || "kty".equals(name) || "n".equals(name)
                    || "e".equals(name) || "alg".equals(name) || "use".equals(name)) {
                if (value != JsonToken.VALUE_STRING) {
                    throw new java.io.IOException("JWK " + name + " must be a string");
                }
                values.put(name, parser.getText());
            } else {
                parser.skipChildren();
            }
        }
        return values;
    }

    private static RSAPublicKey createRsaKey(Map<String, String> jwk) throws Exception {
        if (!"RSA".equals(jwk.get("kty"))) {
            return null;
        }
        if (jwk.containsKey("alg") && !"RS256".equals(jwk.get("alg"))) {
            return null;
        }
        if (jwk.containsKey("use") && !"sig".equals(jwk.get("use"))) {
            return null;
        }
        byte[] modulus = decodeUrl(required(jwk, "n"));
        byte[] exponent = decodeUrl(required(jwk, "e"));
        RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                new RSAPublicKeySpec(new BigInteger(1, modulus), new BigInteger(1, exponent)));
        int bits = key.getModulus().bitLength();
        if (bits < 2048 || bits > 8192) {
            throw new java.io.IOException("JWKS RSA key size is outside safe bounds");
        }
        return key;
    }

    private static String required(Map<String, String> values, String name) throws java.io.IOException {
        String value = values.get(name);
        if (value == null || value.isBlank()) {
            throw new java.io.IOException("JWK is missing " + name);
        }
        return value;
    }

    private static byte[] decodeUrl(String value) throws java.io.IOException {
        if (value.length() > 16 * 1024 || value.indexOf('=') >= 0) {
            throw new java.io.IOException("JWK parameter is not bounded unpadded Base64URL");
        }
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new java.io.IOException("Invalid JWK Base64URL", e);
        }
    }

    private static byte[] readBounded(InputStream input, int limit) throws java.io.IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > limit) {
                throw new java.io.IOException("JWKS response exceeds byte limit");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
