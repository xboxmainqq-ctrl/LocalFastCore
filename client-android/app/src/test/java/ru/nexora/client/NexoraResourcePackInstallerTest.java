package ru.nexora.client;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class NexoraResourcePackInstallerTest {
    @Test
    public void acceptsBedrockTexturePath() {
        assertEquals("grass_top.png", NexoraResourcePackInstaller.supportedTextureName(
                "textures/blocks/grass_top.png"));
    }

    @Test
    public void acceptsJavaPackWithRootFolderAndMapsGrassNames() {
        assertEquals("grass_top.png", NexoraResourcePackInstaller.supportedTextureName(
                "MyPack/assets/minecraft/textures/block/grass_block_top.png"));
        assertEquals("grass_side.png", NexoraResourcePackInstaller.supportedTextureName(
                "assets/minecraft/textures/block/grass_block_side.png"));
    }

    @Test
    public void mapsJavaWoodAndWaterTextures() {
        assertEquals("log_side.png", NexoraResourcePackInstaller.supportedTextureName(
                "assets/minecraft/textures/block/oak_log.png"));
        assertEquals("log_top.png", NexoraResourcePackInstaller.supportedTextureName(
                "assets/minecraft/textures/block/spruce_log_top.png"));
        assertEquals("leaves.png", NexoraResourcePackInstaller.supportedTextureName(
                "assets/minecraft/textures/block/cherry_leaves.png"));
        assertEquals("water.png", NexoraResourcePackInstaller.supportedTextureName(
                "assets/minecraft/textures/block/water_still.png"));
        assertEquals("planks.png", NexoraResourcePackInstaller.supportedTextureName(
                "assets/minecraft/textures/block/oak_planks.png"));
    }

    @Test
    public void ignoresUnknownOrNestedFiles() {
        assertNull(NexoraResourcePackInstaller.supportedTextureName(
                "assets/minecraft/textures/item/diamond.png"));
        assertNull(NexoraResourcePackInstaller.supportedTextureName(
                "assets/minecraft/textures/block/subfolder/stone.png"));
        assertNull(NexoraResourcePackInstaller.supportedTextureName(
                "textures/blocks/not-a-supported-texture.png"));
    }
}
