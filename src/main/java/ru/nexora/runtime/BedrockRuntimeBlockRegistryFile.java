package ru.nexora.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/**
 * Strict loader for externally generated, version-pinned Bedrock block palettes.
 *
 * <p>The repository intentionally does not invent or bundle runtime IDs. A
 * palette provider must supply values extracted from the exact Bedrock 1.26.60
 * (protocol 2225) block-state registry.</p>
 */
public final class BedrockRuntimeBlockRegistryFile {
    public static final int PROTOCOL_VERSION = 2225;
    public static final String GAME_VERSION = "1.26.60";
    private static final int MAX_FILE_BYTES = 32 * 1024 * 1024;
    private static final int MAX_ENTRIES = 500_000;
    private static final int MAX_LINE_CHARS = 8192;
    private static final String HEADER = "NEXORA-BEDROCK-BLOCK-REGISTRY\t1";

    public enum IdMode {
        RUNTIME(false),
        HASH(true);

        private final boolean hashes;

        IdMode(boolean hashes) {
            this.hashes = hashes;
        }

        public boolean blockNetworkIdsAreHashes() {
            return hashes;
        }
    }

    public record Loaded(
            int protocolVersion,
            String gameVersion,
            IdMode idMode,
            String sha256,
            Map<BlockState, Integer> entries
    ) {
        public Loaded {
            Objects.requireNonNull(gameVersion, "gameVersion");
            Objects.requireNonNull(idMode, "idMode");
            Objects.requireNonNull(sha256, "sha256");
            entries = Map.copyOf(Objects.requireNonNull(entries, "entries"));
        }

        public BedrockRuntimeBlockRegistry registry() {
            return BedrockRuntimeBlockRegistry.of(entries);
        }

        public boolean blockNetworkIdsAreHashes() {
            return idMode.blockNetworkIdsAreHashes();
        }

        /** Fails fast if StartGame advertises an ID scheme different from this registry. */
        public void validateStartGameSettings(BedrockStartGamePacketCodec.Settings settings) {
            Objects.requireNonNull(settings, "settings");
            if (settings.blockNetworkIdsAreHashes() != blockNetworkIdsAreHashes()) {
                throw new IllegalArgumentException(
                        "StartGame block-network-ID mode does not match the loaded block registry");
            }
        }
    }

    private BedrockRuntimeBlockRegistryFile() {
    }

    /**
     * Loads a UTF-8 tab-separated registry file without closing the supplied stream.
     *
     * <p>Format: header, protocol, game, id-mode, a literal "--" separator,
     * then rows of identifier, signed int32 ID, and zero or more key=value
     * block-state properties.</p>
     */
    public static Loaded load(InputStream input, String expectedSha256) throws IOException {
        Objects.requireNonNull(expectedSha256, "expectedSha256");
        if (!expectedSha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IOException("Expected registry SHA-256 must be 64 hexadecimal characters");
        }
        Loaded loaded = load(input);
        if (!loaded.sha256().equalsIgnoreCase(expectedSha256)) {
            throw new IOException("Bedrock block registry SHA-256 mismatch");
        }
        return loaded;
    }

    public static Loaded load(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IOException("Bedrock block registry file exceeds 32 MiB");
        }

        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformed) {
            throw new IOException("Bedrock block registry is not valid UTF-8", malformed);
        }

        String[] lines = text.split("\\R", -1);
        if (lines.length < 6 || !HEADER.equals(lines[0])) {
            throw new IOException("Unsupported NEXORA block registry header");
        }
        if (!("protocol\t" + PROTOCOL_VERSION).equals(lines[1])) {
            throw new IOException("Block registry protocol must be " + PROTOCOL_VERSION);
        }
        if (!("game\t" + GAME_VERSION).equals(lines[2])) {
            throw new IOException("Block registry game version must be " + GAME_VERSION);
        }

        IdMode mode = switch (lines[3]) {
            case "id-mode\truntime" -> IdMode.RUNTIME;
            case "id-mode\thash" -> IdMode.HASH;
            default -> throw new IOException("Block registry id-mode must be runtime or hash");
        };
        if (!"--".equals(lines[4])) {
            throw new IOException("Missing block registry data separator");
        }

        Map<BlockState, Integer> entries = new HashMap<>();
        for (int lineIndex = 5; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex];
            if (line.isEmpty() && lineIndex == lines.length - 1) {
                continue; // one trailing newline is allowed
            }
            if (line.isEmpty() || line.length() > MAX_LINE_CHARS) {
                throw new IOException("Empty or oversized block registry row at line " + (lineIndex + 1));
            }
            String[] fields = line.split("\\t", -1);
            if (fields.length < 2) {
                throw new IOException("Block registry row requires identifier and ID at line " + (lineIndex + 1));
            }

            final int id;
            try {
                id = Integer.parseInt(fields[1]);
            } catch (NumberFormatException malformedId) {
                throw new IOException("Invalid signed int32 block ID at line " + (lineIndex + 1), malformedId);
            }

            Map<String, String> properties = new HashMap<>();
            for (int fieldIndex = 2; fieldIndex < fields.length; fieldIndex++) {
                int separator = fields[fieldIndex].indexOf('=');
                if (separator <= 0 || separator == fields[fieldIndex].length() - 1) {
                    throw new IOException("Invalid block property at line " + (lineIndex + 1));
                }
                String key = fields[fieldIndex].substring(0, separator);
                String value = fields[fieldIndex].substring(separator + 1);
                if (properties.putIfAbsent(key, value) != null) {
                    throw new IOException("Duplicate block property at line " + (lineIndex + 1));
                }
            }

            final BlockState state;
            try {
                state = new BlockState(fields[0], properties);
            } catch (RuntimeException invalidState) {
                throw new IOException("Invalid block state at line " + (lineIndex + 1), invalidState);
            }
            if (entries.putIfAbsent(state, id) != null) {
                throw new IOException("Duplicate block state at line " + (lineIndex + 1));
            }
            if (entries.size() > MAX_ENTRIES) {
                throw new IOException("Block registry contains too many entries");
            }
        }

        if (!entries.containsKey(BlockState.AIR)) {
            throw new IOException("Block registry does not define minecraft:air");
        }
        try {
            // Validate that every signed ID is unique before exposing the registry.
            BedrockRuntimeBlockRegistry.of(entries);
        } catch (RuntimeException invalidRegistry) {
            throw new IOException("Invalid Bedrock block registry", invalidRegistry);
        }

        return new Loaded(PROTOCOL_VERSION, GAME_VERSION, mode, sha256(bytes), entries);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
