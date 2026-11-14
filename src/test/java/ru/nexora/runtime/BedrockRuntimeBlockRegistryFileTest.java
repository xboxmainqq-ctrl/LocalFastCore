package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class BedrockRuntimeBlockRegistryFileTest {
    @Test
    void loadsProtocolPinnedRuntimePaletteAndPreservesBlockProperties() throws Exception {
        String file = """
                NEXORA-BEDROCK-BLOCK-REGISTRY\t1
                protocol\t2225
                game\t1.26.60
                id-mode\truntime
                --
                minecraft:air\t0
                minecraft:oak_log\t17\taxis=y
                """;

        var loaded = BedrockRuntimeBlockRegistryFile.load(input(file));

        assertEquals(2225, loaded.protocolVersion());
        assertEquals("1.26.60", loaded.gameVersion());
        assertEquals(BedrockRuntimeBlockRegistryFile.IdMode.RUNTIME, loaded.idMode());
        assertFalse(loaded.blockNetworkIdsAreHashes());
        assertEquals(64, loaded.sha256().length());
        assertEquals(17, loaded.registry().runtimeId(
                new BlockState("minecraft:oak_log", java.util.Map.of("axis", "y"))));
    }

    @Test
    void loadsSignedHashModeForHashedBlockNetworkIds() throws Exception {
        String file = """
                NEXORA-BEDROCK-BLOCK-REGISTRY\t1
                protocol\t2225
                game\t1.26.60
                id-mode\thash
                --
                minecraft:air\t0
                minecraft:stone\t-2144268767
                """;

        var loaded = BedrockRuntimeBlockRegistryFile.load(input(file));

        assertTrue(loaded.blockNetworkIdsAreHashes());
        assertEquals(-2144268767,
                loaded.registry().runtimeId(new BlockState("minecraft:stone")));
    }

    @Test
    void rejectsWrongVersionMissingAirDuplicateIdsAndMalformedRows() {
        assertThrows(IOException.class, () -> BedrockRuntimeBlockRegistryFile.load(input("""
                NEXORA-BEDROCK-BLOCK-REGISTRY\t1
                protocol\t2224
                game\t1.26.60
                id-mode\truntime
                --
                minecraft:air\t0
                """)));
        assertThrows(IOException.class, () -> BedrockRuntimeBlockRegistryFile.load(input("""
                NEXORA-BEDROCK-BLOCK-REGISTRY\t1
                protocol\t2225
                game\t1.26.60
                id-mode\truntime
                --
                minecraft:stone\t1
                """)));
        assertThrows(IOException.class, () -> BedrockRuntimeBlockRegistryFile.load(input("""
                NEXORA-BEDROCK-BLOCK-REGISTRY\t1
                protocol\t2225
                game\t1.26.60
                id-mode\thash
                --
                minecraft:air\t7
                minecraft:stone\t7
                """)));
        assertThrows(IOException.class, () -> BedrockRuntimeBlockRegistryFile.load(input("""
                NEXORA-BEDROCK-BLOCK-REGISTRY\t1
                protocol\t2225
                game\t1.26.60
                id-mode\truntime
                --
                minecraft:air\tnot-an-int
                """)));
    }

    @Test
    void rejectsInvalidUtf8AndOversizedInput() {
        assertThrows(IOException.class, () -> BedrockRuntimeBlockRegistryFile.load(
                new ByteArrayInputStream(new byte[] {(byte) 0xC3, (byte) 0x28})));
        byte[] oversized = new byte[32 * 1024 * 1024 + 1];
        assertThrows(IOException.class, () -> BedrockRuntimeBlockRegistryFile.load(
                new ByteArrayInputStream(oversized)));
    }

    private static ByteArrayInputStream input(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
