package ru.nexora.network.nethernet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NetherNet identity assertions for modern Bedrock clients.
 *
 * Client GameServerTokens are verified against a configured Minecraft auth
 * JWKS endpoint. Server identity keys are persisted so clients see a stable
 * operator identity across restarts.
 */
public final class DefaultNetherNetIdentityService implements NetherNetIdentityService {
    public static final URI DEFAULT_JWKS_URI =
            URI.create("https://authorization.franchise.minecraft-services.net/.well-known/keys");
    public static final String DEFAULT_ISSUER =
            "https://authorization.franchise.minecraft-services.net/";
    public static final String DEFAULT_AUDIENCE =
            "api://auth-minecraft-services/multiplayer";

    private static final Pattern FINGERPRINT_LINE =
            Pattern.compile("^a=fingerprint:([^ ]+) ([^ ]+)\\s*$");
    private static final long JWKS_CACHE_SECONDS = 3600;
    private static final long SERVER_TOKEN_LIFETIME_SECONDS = 10L * 365 * 24 * 60 * 60;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final URI jwksUri;
    private final String expectedIssuer;
    private final String expectedAudience;
    private final String providerDomain;
    private final KeyPair serverKeyPair;
    private final String serverToken;
    private final AtomicReference<CachedKeys> cachedKeys = new AtomicReference<>();

    public DefaultNetherNetIdentityService(Path keyDirectory) throws IOException, GeneralSecurityException {
        this(keyDirectory, DEFAULT_JWKS_URI, DEFAULT_ISSUER, DEFAULT_AUDIENCE, "self");
    }

    public DefaultNetherNetIdentityService(
            Path keyDirectory,
            URI jwksUri,
            String expectedIssuer,
            String expectedAudience,
            String providerDomain
    ) throws IOException, GeneralSecurityException {
        if (jwksUri == null || !"https".equalsIgnoreCase(jwksUri.getScheme())) {
            throw new IllegalArgumentException("JWKS endpoint must use HTTPS");
        }
        this.jwksUri = jwksUri;
        this.expectedIssuer = requireText(expectedIssuer, "expectedIssuer");
        this.expectedAudience = requireText(expectedAudience, "expectedAudience");
        this.providerDomain = requireText(providerDomain, "providerDomain");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.serverKeyPair = loadOrCreateServerKeyPair(keyDirectory);
        this.serverToken = createServerIdentityToken(serverKeyPair, providerDomain);
    }

    @Override
    public String validateClientOfferAndStripIdentity(String networkId, String offerSdp) throws Exception {
        if (networkId == null || networkId.isBlank()) {
            throw new SecurityException("Missing NetherNet NetworkID");
        }
        List<String> lines = splitSdpLines(offerSdp);
        String identityLine = null;
        List<Fingerprint> fingerprints = readFingerprints(lines);
        for (String line : lines) {
            if (line.startsWith("a=identity:")) {
                if (identityLine != null) throw new SecurityException("Multiple a=identity assertions in SDP offer");
                identityLine = line.substring("a=identity:".length());
            }
        }
        if (identityLine == null || identityLine.isBlank()) {
            throw new SecurityException("Client SDP offer is missing a=identity");
        }

        JsonNode envelope = mapper.readTree(Base64.getDecoder().decode(identityLine));
        JsonNode provider = requiredNode(envelope, "idp");
        if (!"default".equals(requiredText(provider, "protocol"))) {
            throw new SecurityException("Unsupported NetherNet identity provider protocol");
        }
        String assertionJson = requiredText(envelope, "assertion");
        JsonNode assertion = mapper.readTree(assertionJson);
        String token = requiredText(assertion, "token");
        String detachedFingerprints = requiredText(assertion, "fingerprints");

        JsonNode tokenHeader = decodeJwtPart(token, 0);
        if (!"RS256".equals(requiredText(tokenHeader, "alg"))) {
            throw new SecurityException("Client GameServerToken must use RS256");
        }
        String keyId = requiredText(tokenHeader, "kid");
        JsonNode tokenClaims = decodeJwtPart(token, 1);
        verifyClientTokenSignature(token, keyId);
        validateStandardClaims(tokenClaims);

        String encodedClientKey = requiredText(tokenClaims, "cpk");
        byte[] clientKeyDer = Base64.getDecoder().decode(encodedClientKey);
        PublicKey clientKey = KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(clientKeyDer));
        if (!(clientKey instanceof ECPublicKey ecKey) || ecKey.getParams().getCurve().getField().getFieldSize() != 384) {
            throw new SecurityException("Client cpk must be a P-384 ECDSA public key");
        }

        verifyDetachedFingerprintSignature(detachedFingerprints, fingerprints, clientKey);
        return removeIdentityLine(lines);
    }

    @Override
    public String appendServerIdentityAssertion(String answerSdp) throws Exception {
        List<String> lines = splitSdpLines(answerSdp);
        List<Fingerprint> fingerprints = readFingerprints(lines);
        if (fingerprints.isEmpty()) throw new SecurityException("WebRTC answer has no DTLS fingerprint");

        String fingerprintPayload = canonicalFingerprintPayload(fingerprints);
        String jwsHeader = base64Url("{\"alg\":\"ES384\"}");
        byte[] signingInput = (jwsHeader + "." + base64Url(fingerprintPayload))
                .getBytes(StandardCharsets.US_ASCII);
        byte[] signature = signEs384(serverKeyPair.getPrivate(), signingInput);
        String detached = jwsHeader + ".." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);

        ObjectNode assertion = mapper.createObjectNode();
        assertion.put("token", serverToken);
        assertion.put("fingerprints", detached);

        ObjectNode envelope = mapper.createObjectNode();
        ObjectNode idp = envelope.putObject("idp");
        idp.put("domain", providerDomain);
        idp.put("protocol", "default");
        envelope.put("assertion", mapper.writeValueAsString(assertion));

        String identity = Base64.getEncoder().encodeToString(
                mapper.writeValueAsBytes(envelope));
        return insertIdentityLine(lines, "a=identity:" + identity);
    }

    private void verifyClientTokenSignature(String token, String keyId) throws Exception {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || parts[2].isEmpty()) {
            throw new SecurityException("Malformed client GameServerToken");
        }
        PublicKey key = getJwksKeys().get(keyId);
        if (!(key instanceof RSAPublicKey)) {
            throw new SecurityException("Unknown or invalid GameServerToken signing key");
        }
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(key);
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        if (!verifier.verify(Base64.getUrlDecoder().decode(parts[2]))) {
            throw new SecurityException("Invalid GameServerToken signature");
        }
    }

    private Map<String, PublicKey> getJwksKeys() throws Exception {
        CachedKeys current = cachedKeys.get();
        Instant now = Instant.now();
        if (current != null && now.isBefore(current.expiresAt())) return current.keys();

        synchronized (cachedKeys) {
            current = cachedKeys.get();
            now = Instant.now();
            if (current != null && now.isBefore(current.expiresAt())) return current.keys();

            HttpRequest request = HttpRequest.newBuilder(jwksUri)
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Minecraft auth JWKS request failed with HTTP " + response.statusCode());
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode keysNode = requiredNode(root, "keys");
            Map<String, PublicKey> keys = new HashMap<>();
            for (JsonNode item : keysNode) {
                if (!"RSA".equals(optionalText(item, "kty"))) continue;
                String kid = optionalText(item, "kid");
                String modulus = optionalText(item, "n");
                String exponent = optionalText(item, "e");
                if (kid == null || modulus == null || exponent == null) continue;
                BigInteger n = new BigInteger(1, Base64.getUrlDecoder().decode(modulus));
                BigInteger e = new BigInteger(1, Base64.getUrlDecoder().decode(exponent));
                PublicKey publicKey = KeyFactory.getInstance("RSA")
                        .generatePublic(new RSAPublicKeySpec(n, e));
                keys.put(kid, publicKey);
            }
            if (keys.isEmpty()) throw new SecurityException("Minecraft auth JWKS contained no usable RSA keys");
            CachedKeys refreshed = new CachedKeys(Map.copyOf(keys), now.plusSeconds(JWKS_CACHE_SECONDS));
            cachedKeys.set(refreshed);
            return refreshed.keys();
        }
    }

    private void validateStandardClaims(JsonNode claims) {
        String issuer = requiredText(claims, "iss");
        if (!expectedIssuer.equals(issuer)) throw new SecurityException("Unexpected GameServerToken issuer");
        JsonNode audience = requiredNode(claims, "aud");
        boolean audienceMatches = audience.isTextual() && expectedAudience.equals(audience.asText());
        if (audience.isArray()) {
            for (JsonNode item : audience) {
                if (item.isTextual() && expectedAudience.equals(item.asText())) audienceMatches = true;
            }
        }
        if (!audienceMatches) throw new SecurityException("Unexpected GameServerToken audience");

        long now = Instant.now().getEpochSecond();
        long expiresAt = requiredLong(claims, "exp");
        if (now > expiresAt + 30) throw new SecurityException("GameServerToken has expired");
        JsonNode notBefore = claims.get("nbf");
        if (notBefore != null && notBefore.canConvertToLong() && now + 30 < notBefore.asLong()) {
            throw new SecurityException("GameServerToken is not active yet");
        }
    }

    private static void verifyDetachedFingerprintSignature(
            String detached,
            List<Fingerprint> fingerprints,
            PublicKey clientKey
    ) throws Exception {
        String[] parts = detached.split("\\.", -1);
        if (parts.length != 3 || !parts[1].isEmpty()) {
            throw new SecurityException("Malformed detached fingerprint JWS");
        }
        JsonNode header = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(parts[0]));
        if (!"ES384".equals(requiredText(header, "alg"))) {
            throw new SecurityException("Fingerprint assertion must use ES384");
        }
        String signingInput = parts[0] + "." + base64Url(canonicalFingerprintPayload(fingerprints));
        Signature verifier = Signature.getInstance("SHA384withECDSA");
        verifier.initVerify(clientKey);
        verifier.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        byte[] joseSignature = Base64.getUrlDecoder().decode(parts[2]);
        if (!verifier.verify(joseToDer(joseSignature))) {
            throw new SecurityException("Client fingerprint signature is invalid");
        }
    }

    private String createServerIdentityToken(KeyPair keyPair, String issuer) throws Exception {
        ECPublicKey publicKey = (ECPublicKey) keyPair.getPublic();
        ObjectNode header = mapper.createObjectNode();
        header.put("alg", "ES384");
        header.put("typ", "JWT");

        ObjectNode claims = mapper.createObjectNode();
        ObjectNode cpk = claims.putObject("cpk");
        cpk.put("kty", "EC");
        cpk.put("crv", "P-384");
        cpk.put("x", Base64.getUrlEncoder().withoutPadding().encodeToString(
                toFixedUnsigned(publicKey.getW().getAffineX(), 48)));
        cpk.put("y", Base64.getUrlEncoder().withoutPadding().encodeToString(
                toFixedUnsigned(publicKey.getW().getAffineY(), 48)));
        long issuedAt = Instant.now().getEpochSecond();
        claims.put("iss", issuer);
        claims.put("iat", issuedAt);
        claims.put("exp", issuedAt + SERVER_TOKEN_LIFETIME_SECONDS);

        String encodedHeader = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mapper.writeValueAsBytes(header));
        String encodedClaims = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mapper.writeValueAsBytes(claims));
        String signingInput = encodedHeader + "." + encodedClaims;
        byte[] signature = signEs384(keyPair.getPrivate(),
                signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }

    private static KeyPair loadOrCreateServerKeyPair(Path directory)
            throws IOException, GeneralSecurityException {
        Files.createDirectories(directory);
        Path privatePath = directory.resolve("nethernet-identity.pk8");
        Path publicPath = directory.resolve("nethernet-identity.x509");
        if (Files.isSymbolicLink(privatePath) || Files.isSymbolicLink(publicPath)) {
            throw new IOException("NetherNet identity key files must not be symbolic links");
        }

        boolean hasPrivate = Files.exists(privatePath);
        boolean hasPublic = Files.exists(publicPath);
        if (hasPrivate != hasPublic) {
            throw new IOException("Incomplete NetherNet identity key pair; refusing to rotate the operator identity");
        }
        if (hasPrivate) {
            KeyFactory factory = KeyFactory.getInstance("EC");
            PrivateKey privateKey = factory.generatePrivate(
                    new java.security.spec.PKCS8EncodedKeySpec(Files.readAllBytes(privatePath)));
            PublicKey publicKey = factory.generatePublic(
                    new X509EncodedKeySpec(Files.readAllBytes(publicPath)));
            if (!(publicKey instanceof ECPublicKey ecKey)
                    || ecKey.getParams().getCurve().getField().getFieldSize() != 384) {
                throw new GeneralSecurityException("Stored NetherNet identity key is not P-384");
            }
            return new KeyPair(publicKey, privateKey);
        }

        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair pair = generator.generateKeyPair();
        Files.write(privatePath, pair.getPrivate().getEncoded(),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        Files.write(publicPath, pair.getPublic().getEncoded(),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        setOwnerOnlyPermissions(privatePath);
        setOwnerOnlyPermissions(publicPath);
        return pair;
    }

    private static void setOwnerOnlyPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows and some mounted filesystems do not expose POSIX permissions.
        }
    }

    private static List<Fingerprint> readFingerprints(List<String> lines) {
        List<Fingerprint> result = new ArrayList<>();
        for (String line : lines) {
            Matcher matcher = FINGERPRINT_LINE.matcher(line);
            if (matcher.matches()) result.add(new Fingerprint(matcher.group(1), matcher.group(2)));
        }
        if (result.isEmpty()) throw new SecurityException("SDP contains no DTLS fingerprints");
        return List.copyOf(result);
    }

    private static String canonicalFingerprintPayload(List<Fingerprint> fingerprints) {
        StringBuilder json = new StringBuilder("{\"fingerprint\":[");
        for (int i = 0; i < fingerprints.size(); i++) {
            if (i > 0) json.append(',');
            Fingerprint fingerprint = fingerprints.get(i);
            json.append("{\"algorithm\":\"").append(jsonEscape(fingerprint.algorithm()))
                    .append("\",\"digest\":\"").append(jsonEscape(fingerprint.digest())).append("\"}");
        }
        return json.append("]}").toString();
    }

    private static String insertIdentityLine(List<String> lines, String identityLine) {
        if (lines.stream().anyMatch(line -> line.startsWith("a=identity:"))) {
            throw new SecurityException("SDP answer already contains an identity assertion");
        }
        List<String> result = new ArrayList<>(lines.size() + 1);
        boolean inserted = false;
        for (String line : lines) {
            if (!inserted && line.startsWith("m=")) {
                result.add(identityLine);
                inserted = true;
            }
            result.add(line);
        }
        if (!inserted) throw new SecurityException("SDP answer has no media section");
        return String.join("\r\n", result);
    }

    private static String removeIdentityLine(List<String> lines) {
        List<String> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (!line.startsWith("a=identity:")) result.add(line);
        }
        return String.join("\r\n", result);
    }

    private static List<String> splitSdpLines(String sdp) {
        if (sdp == null || !sdp.startsWith("v=0")) throw new SecurityException("Malformed SDP");
        return List.of(sdp.replace("\r\n", "\n").split("\n", -1));
    }

    private static byte[] signEs384(PrivateKey privateKey, byte[] input) throws GeneralSecurityException {
        Signature signer = Signature.getInstance("SHA384withECDSA");
        signer.initSign(privateKey);
        signer.update(input);
        return derToJose(signer.sign(), 48);
    }

    private static byte[] derToJose(byte[] der, int coordinateSize) throws GeneralSecurityException {
        int[] offset = {0};
        if (readByte(der, offset) != 0x30) throw new GeneralSecurityException("Invalid DER ECDSA sequence");
        readLength(der, offset);
        if (readByte(der, offset) != 0x02) throw new GeneralSecurityException("Invalid DER ECDSA r");
        int rLength = readLength(der, offset);
        byte[] r = readBytes(der, offset, rLength);
        if (readByte(der, offset) != 0x02) throw new GeneralSecurityException("Invalid DER ECDSA s");
        int sLength = readLength(der, offset);
        byte[] s = readBytes(der, offset, sLength);
        if (offset[0] != der.length) throw new GeneralSecurityException("Trailing DER ECDSA data");
        byte[] jose = new byte[coordinateSize * 2];
        copyIntegerToFixed(r, jose, 0, coordinateSize);
        copyIntegerToFixed(s, jose, coordinateSize, coordinateSize);
        return jose;
    }

    private static byte[] joseToDer(byte[] jose) throws GeneralSecurityException {
        if (jose.length != 96) throw new GeneralSecurityException("ES384 signature must be 96 bytes");
        byte[] r = derInteger(ArraysSupport.copyOfRange(jose, 0, 48));
        byte[] s = derInteger(ArraysSupport.copyOfRange(jose, 48, 96));
        int bodyLength = 2 + r.length + 2 + s.length;
        byte[] der = new byte[2 + bodyLength];
        int offset = 0;
        der[offset++] = 0x30;
        der[offset++] = (byte) bodyLength;
        der[offset++] = 0x02;
        der[offset++] = (byte) r.length;
        System.arraycopy(r, 0, der, offset, r.length);
        offset += r.length;
        der[offset++] = 0x02;
        der[offset++] = (byte) s.length;
        System.arraycopy(s, 0, der, offset, s.length);
        return der;
    }

    private static byte[] derInteger(byte[] value) {
        int first = 0;
        while (first < value.length - 1 && value[first] == 0) first++;
        byte[] trimmed = ArraysSupport.copyOfRange(value, first, value.length);
        if ((trimmed[0] & 0x80) == 0) return trimmed;
        byte[] positive = new byte[trimmed.length + 1];
        System.arraycopy(trimmed, 0, positive, 1, trimmed.length);
        return positive;
    }

    private static void copyIntegerToFixed(byte[] value, byte[] destination, int offset, int length)
            throws GeneralSecurityException {
        int first = value.length > 1 && value[0] == 0 ? 1 : 0;
        int count = value.length - first;
        if (count > length) throw new GeneralSecurityException("ECDSA integer exceeds curve size");
        System.arraycopy(value, first, destination, offset + length - count, count);
    }

    private static int readByte(byte[] data, int[] offset) throws GeneralSecurityException {
        if (offset[0] >= data.length) throw new GeneralSecurityException("Truncated DER ECDSA signature");
        return Byte.toUnsignedInt(data[offset[0]++]);
    }

    private static int readLength(byte[] data, int[] offset) throws GeneralSecurityException {
        int first = readByte(data, offset);
        if ((first & 0x80) == 0) return first;
        int count = first & 0x7f;
        if (count < 1 || count > 2) throw new GeneralSecurityException("Unsupported DER length");
        int length = 0;
        for (int i = 0; i < count; i++) length = (length << 8) | readByte(data, offset);
        return length;
    }

    private static byte[] readBytes(byte[] data, int[] offset, int length) throws GeneralSecurityException {
        if (length < 1 || offset[0] + length > data.length) {
            throw new GeneralSecurityException("Truncated DER ECDSA integer");
        }
        byte[] result = ArraysSupport.copyOfRange(data, offset[0], offset[0] + length);
        offset[0] += length;
        return result;
    }

    private static byte[] toFixedUnsigned(BigInteger value, int length) {
        byte[] raw = value.toByteArray();
        int first = raw.length > 1 && raw[0] == 0 ? 1 : 0;
        int count = raw.length - first;
        if (count > length) throw new IllegalArgumentException("EC coordinate exceeds expected size");
        byte[] result = new byte[length];
        System.arraycopy(raw, first, result, length - count, count);
        return result;
    }

    private static JsonNode decodeJwtPart(String token, int index) throws IOException {
        String[] parts = token.split("\\\\.", -1);
        if (parts.length != 3) throw new SecurityException("Malformed JWT");
        return new ObjectMapper().readTree(Base64.getUrlDecoder().decode(parts[index]));
    }

    private static JsonNode requiredNode(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        if (value == null || value.isNull()) throw new SecurityException("Missing JSON field: " + name);
        return value;
    }

    private static String requiredText(JsonNode node, String name) {
        JsonNode value = requiredNode(node, name);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new SecurityException("Invalid JSON text field: " + name);
        }
        return value.asText();
    }

    private static String optionalText(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static long requiredLong(JsonNode node, String name) {
        JsonNode value = requiredNode(node, name);
        if (!value.canConvertToLong()) throw new SecurityException("Invalid JSON number field: " + name);
        return value.asLong();
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private record Fingerprint(String algorithm, String digest) {
    }

    private record CachedKeys(Map<String, PublicKey> keys, Instant expiresAt) {
    }

    /** Tiny range-copy helper keeps the crypto conversion code dependency-free. */
    private static final class ArraysSupport {
        private static byte[] copyOfRange(byte[] source, int from, int to) {
            byte[] result = new byte[to - from];
            System.arraycopy(source, from, result, 0, result.length);
            return result;
        }
    }
}
