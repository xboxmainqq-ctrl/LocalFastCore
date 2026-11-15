package ru.nexora.runtime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Strict loader for administrator-authored NEXORA-to-Bedrock block aliases. */
public final class BedrockBlockCompatibilityRegistryFile {
    private static final String HEADER = "NEXORA-BEDROCK-BLOCK-MAPPINGS\t1";
    private static final int MAX_LINES = 100_000;
    private static final int MAX_LINE_LENGTH = 1024;

    private BedrockBlockCompatibilityRegistryFile() { }

    /**
     * Loads a UTF-8 TSV mapping file. Rows contain world identifier, Bedrock
     * identifier, and resource-pack flag (true/false). Built-ins are included;
     * custom rows may deliberately override a built-in mapping.
     */
    public static BedrockBlockCompatibilityRegistry loadWithBuiltIns(InputStream input)
            throws IOException {
        Objects.requireNonNull(input, "input");
        Map<String, BedrockBlockMapping> mappings = new LinkedHashMap<>();
        for (BedrockBlockMapping mapping : BedrockBlockCompatibilityRegistry.builtIn().mappings()) {
            mappings.put(mapping.worldIdentifier(), mapping);
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (!HEADER.equals(header)) {
                throw new IOException("Unsupported Bedrock block mapping file header");
            }
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (lineNumber > MAX_LINES) {
                    throw new IOException("Bedrock block mapping file has too many lines");
                }
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                if (line.length() > MAX_LINE_LENGTH) {
                    throw new IOException("Oversized mapping row at line " + lineNumber);
                }
                String[] fields = line.split("\\t", -1);
                if (fields.length != 3) {
                    throw new IOException("Mapping row must have exactly 3 tab-separated fields at line "
                            + lineNumber);
                }
                boolean requiresPack;
                if ("true".equals(fields[2])) {
                    requiresPack = true;
                } else if ("false".equals(fields[2])) {
                    requiresPack = false;
                } else {
                    throw new IOException("Resource-pack flag must be true or false at line " + lineNumber);
                }
                try {
                    BedrockBlockMapping mapping = new BedrockBlockMapping(
                            fields[0], fields[1], requiresPack);
                    mappings.put(mapping.worldIdentifier(), mapping);
                } catch (RuntimeException invalid) {
                    throw new IOException("Invalid block mapping at line " + lineNumber, invalid);
                }
            }
        }
        return new BedrockBlockCompatibilityRegistry(new ArrayList<>(mappings.values()));
    }
}
