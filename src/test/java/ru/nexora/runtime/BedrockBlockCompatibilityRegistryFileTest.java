package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class BedrockBlockCompatibilityRegistryFileTest {
    @Test
    void loadsCustomAliasesAndAllowsIntentionalOverrides() throws Exception {
        String file = """
                NEXORA-BEDROCK-BLOCK-MAPPINGS\t1
                # world id, Bedrock id, resource pack required
                nexora:mistwood_soil\tminecraft:podzol\tfalse
                minecraft:stone\tminecraft:deepslate\tfalse
                nexora:glow_crystal\tnexora:glow_crystal\ttrue
                """;
        var registry = BedrockBlockCompatibilityRegistryFile.loadWithBuiltIns(
                new ByteArrayInputStream(file.getBytes(StandardCharsets.UTF_8)));

        assertEquals("minecraft:podzol", registry.require("nexora:mistwood_soil").bedrockIdentifier());
        assertEquals("minecraft:deepslate", registry.require("minecraft:stone").bedrockIdentifier());
        assertTrue(registry.require("nexora:glow_crystal").requiresResourcePack());
        assertTrue(registry.supports("minecraft:air"));
    }

    @Test
    void rejectsUnknownHeaderMalformedRowsAndInvalidFlags() {
        assertThrows(java.io.IOException.class, () -> load("wrong-header\n"));
        assertThrows(java.io.IOException.class, () -> load(
                "NEXORA-BEDROCK-BLOCK-MAPPINGS\t1\nnexora:block\tminecraft:stone\n"));
        assertThrows(java.io.IOException.class, () -> load(
                "NEXORA-BEDROCK-BLOCK-MAPPINGS\t1\nnexora:block\tminecraft:stone\tyes\n"));
    }

    private static BedrockBlockCompatibilityRegistry load(String value) throws Exception {
        return BedrockBlockCompatibilityRegistryFile.loadWithBuiltIns(
                new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));
    }
}
