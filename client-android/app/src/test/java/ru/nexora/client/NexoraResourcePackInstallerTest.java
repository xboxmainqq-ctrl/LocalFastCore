package ru.nexora.client;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class NexoraResourcePackInstallerTest {
    @Test
    public void acceptsBedrockTexturePath() {
        assertEquals("grass_top.png", NexoraResourcePackInstaller.supportedTextureName(
                "textures/blocks/grass_top.png"));
        assertEquals("stone.png", NexoraResourcePackInstaller.supportedTextureName(
                "MyBedrockPack/textures/blocks/stone.png"));
    }

    @Test
    public void mapsBedrockWoodAndWaterTextureNames() {
        assertEquals("log_side.png", NexoraResourcePackInstaller.supportedTextureName(
                "textures/blocks/log_oak.png"));
        assertEquals("log_top.png", NexoraResourcePackInstaller.supportedTextureName(
                "textures/blocks/log_spruce_top.png"));
        assertEquals("leaves.png", NexoraResourcePackInstaller.supportedTextureName(
                "textures/blocks/leaves_oak.png"));
        assertEquals("water.png", NexoraResourcePackInstaller.supportedTextureName(
                "textures/blocks/water_still.png"));
        assertEquals("planks.png", NexoraResourcePackInstaller.supportedTextureName(
                "textures/blocks/planks_oak.png"));
    }

    @Test
    public void rejectsJavaEditionPaths() {
        assertNull(NexoraResourcePackInstaller.supportedTextureName(
                "assets/minecraft/textures/block/grass_block_top.png"));
        assertNull(NexoraResourcePackInstaller.supportedTextureName(
                "MyPack/assets/minecraft/textures/block/stone.png"));
    }

    @Test
    public void ignoresUnknownOrNestedFiles() {
        assertNull(NexoraResourcePackInstaller.supportedTextureName(
                "textures/items/diamond.png"));
        assertNull(NexoraResourcePackInstaller.supportedTextureName(
                "textures/blocks/subfolder/stone.png"));
        assertNull(NexoraResourcePackInstaller.supportedTextureName(
                "textures/blocks/not-a-supported-texture.png"));
    }
}
