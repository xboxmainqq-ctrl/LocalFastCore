package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import ru.nexora.inventory.CraftingRecipe;
import ru.nexora.inventory.CraftingRecipeRegistry;

import static org.junit.jupiter.api.Assertions.*;

class BedrockCraftingEventCodecTest {
    @Test
    void decodesRegisteredShapelessCraftingEventSnapshots() {
        var items = BedrockVanillaItemCatalog.createRegistry();
        CraftingRecipe recipe = CraftingRecipeRegistry.vanillaSubset()
                .find("minecraft:iron_nuggets_from_iron_ingot");
        int inputId = items.findByIdentifier("minecraft:iron_ingot").networkDefinition().runtimeItemId();
        int outputId = items.findByIdentifier("minecraft:iron_nugget").networkDefinition().runtimeItemId();

        var decoded = BedrockCraftingEventCodec.decode(new BedrockPacket(
                BedrockCraftingEventCodec.CRAFTING_EVENT_PACKET_ID, 0, 0,
                BedrockCraftingDataCodecTest.craftingEvent(recipe.uuid(), inputId, 1, outputId, 9)), items);

        assertEquals(0, decoded.windowId());
        assertEquals(BedrockCraftingEventCodec.TYPE_SHAPELESS, decoded.type());
        assertEquals(recipe.uuid(), decoded.recipeId());
        assertEquals("minecraft:iron_ingot", decoded.input().getFirst().orElseThrow().itemId());
        assertEquals(1, decoded.input().getFirst().orElseThrow().count());
        assertEquals("minecraft:iron_nugget", decoded.output().getFirst().orElseThrow().itemId());
        assertEquals(9, decoded.output().getFirst().orElseThrow().count());
    }

    @Test
    void rejectsUnknownItemsWrongPacketIdsAndMalformedSnapshots() {
        var items = BedrockVanillaItemCatalog.createRegistry();
        var recipe = CraftingRecipeRegistry.vanillaSubset()
                .find("minecraft:iron_nuggets_from_iron_ingot");

        assertThrows(IllegalArgumentException.class, () ->
                BedrockCraftingEventCodec.decode(new BedrockPacket(0x34, 0, 0, new byte[0]), items));
        assertThrows(IllegalArgumentException.class, () ->
                BedrockCraftingEventCodec.decode(new BedrockPacket(0x35, 0, 0, new byte[0]), items));

        byte[] unknownItem = BedrockCraftingDataCodecTest.craftingEvent(recipe.uuid(), 32760, 1, 579, 9);
        assertThrows(IllegalArgumentException.class, () ->
                BedrockCraftingEventCodec.decode(new BedrockPacket(0x35, 0, 0, unknownItem), items));

        byte[] mismatchedOutput = BedrockCraftingDataCodecTest.craftingEvent(recipe.uuid(), 307, 1, 32759, 9);
        assertThrows(IllegalArgumentException.class, () ->
                BedrockCraftingEventCodec.decode(new BedrockPacket(0x35, 0, 0, mismatchedOutput), items));

        byte[] trailing = java.util.Arrays.copyOf(
                BedrockCraftingDataCodecTest.craftingEvent(recipe.uuid(), 307, 1, 579, 9), 64);
        assertThrows(IllegalArgumentException.class, () ->
                BedrockCraftingEventCodec.decode(new BedrockPacket(0x35, 0, 0, trailing), items));
    }
}
