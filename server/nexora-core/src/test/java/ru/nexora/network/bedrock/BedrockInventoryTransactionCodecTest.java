package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import ru.nexora.inventory.InventoryTransactionService;
import ru.nexora.inventory.PlayerInventory;
import ru.nexora.inventory.ItemStack;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BedrockInventoryTransactionCodecTest {
    private static final BedrockItemRegistry REGISTRY = new BedrockItemRegistry(List.of(
            new BedrockItemRegistry.Entry("nexora:mana_crystal",
                    new BedrockInventoryContentCodec.ItemDefinition(257, 0, 0, new byte[0]), 64)));

    @Test
    void decodesNormalPlayerInventoryTransferForProtocol2225() {
        byte[] payload = transaction(
                action(0, 0, 0, stack(257, 10), stack(257, 6)),
                action(0, 0, 1, empty(), stack(257, 4)));

        var decoded = BedrockInventoryTransactionCodec.decode(
                new BedrockPacket(0x1e, 0, 0, payload), REGISTRY);

        assertEquals(0, decoded.transactionType());
        assertEquals(2, decoded.actions().size());
        PlayerInventory inventory = new PlayerInventory();
        inventory.setSlot(0, new ItemStack("nexora:mana_crystal", 10, 64));
        var result = new InventoryTransactionService().applyPlayerInventoryTransaction(
                inventory, decoded.actions());
        assertEquals(InventoryTransactionService.Result.APPLIED, result);
        assertEquals(6, inventory.getSlot(0).orElseThrow().count());
        assertEquals(4, inventory.getSlot(1).orElseThrow().count());
    }

    @Test
    void decodesEmptySlotActionsAndResolvesRegisteredRuntimeItems() {
        byte[] payload = transaction(
                action(0, 0, 0, empty(), stack(257, 5)));

        var decoded = BedrockInventoryTransactionCodec.decode(
                new BedrockPacket(0x1e, 0, 0, payload), REGISTRY);
        assertTrue(decoded.actions().getFirst().from().isEmpty());
        assertEquals("nexora:mana_crystal", decoded.actions().getFirst().to().orElseThrow().itemId());
        assertEquals(5, decoded.actions().getFirst().to().orElseThrow().count());
    }

    @Test
    void decodesEnchantedItemMetadataFromClientInventoryTransaction() {
        ItemStack enchanted = new ItemStack("nexora:mana_crystal", 1, 64,
                java.util.Map.of("minecraft:unbreaking", 2), 4);
        byte[] userData = BedrockItemStackUserDataCodec.encode(enchanted, new byte[0]);
        byte[] payload = transaction(action(0, 0, 0, empty(), stack(257, 1, 0, 0, userData)));

        var decoded = BedrockInventoryTransactionCodec.decode(
                new BedrockPacket(0x1e, 0, 0, payload), REGISTRY);
        ItemStack result = decoded.actions().getFirst().to().orElseThrow();

        assertEquals(enchanted.enchantments(), result.enchantments());
        assertEquals(4, result.repairCost());
    }

    @Test
    void rejectsNonZeroLegacyRequestIdAndUnsupportedTransactionVariants() {
        byte[] legacy = new byte[] {2, 0, 0}; // ZigZag(1), then unused bytes
        assertThrows(UnsupportedOperationException.class,
                () -> BedrockInventoryTransactionCodec.decode(
                        new BedrockPacket(0x1e, 0, 0, legacy), REGISTRY));

        byte[] useItem = new byte[] {0, 2};
        assertThrows(UnsupportedOperationException.class,
                () -> BedrockInventoryTransactionCodec.decode(
                        new BedrockPacket(0x1e, 0, 0, useItem), REGISTRY));
    }

    @Test
    void decodesFoodItemReleaseConsumeTransaction() {
        BedrockItemRegistry foods = BedrockVanillaItemCatalog.createRegistry();
        byte[] payload = itemRelease(1, 0, 261, 1, 0.5f, 65.0f, 0.5f);
        BedrockPacket packet = new BedrockPacket(0x1e, 0, 0, payload);

        assertEquals(4, BedrockInventoryTransactionCodec.transactionType(packet));
        var decoded = BedrockInventoryTransactionCodec.decodeItemRelease(packet, foods);
        assertEquals(1, decoded.actionType());
        assertEquals(0, decoded.hotbarSlot());
        assertEquals("minecraft:bread", decoded.heldItem().orElseThrow().itemId());
        assertEquals(66.62f, decoded.fromY(), 0.0001f);
        assertEquals(0, decoded.hand());
    }

    @Test
    void decodesFoodUseCancellationAndRejectsUnsupportedReleaseActions() {
        BedrockItemRegistry foods = BedrockVanillaItemCatalog.createRegistry();
        var cancelled = BedrockInventoryTransactionCodec.decodeItemRelease(
                new BedrockPacket(0x1e, 0, 0, itemRelease(0, 0, 261, 1, 0, 65, 0)), foods);
        assertEquals(0, cancelled.actionType());

        byte[] malformed = itemRelease(1, 1, 261, 1, 0, 65, 0);
        assertThrows(UnsupportedOperationException.class, () ->
                BedrockInventoryTransactionCodec.decodeItemRelease(
                        new BedrockPacket(0x1e, 0, 0, malformed), foods));
    }

    @Test
    void rejectsUnknownRuntimeIdsAndForgedDescriptorMetadata() {
        byte[] unknown = transaction(action(0, 0, 0, empty(), stack(999, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockInventoryTransactionCodec.decode(
                        new BedrockPacket(0x1e, 0, 0, unknown), REGISTRY));

        byte[] forged = transaction(action(0, 0, 0, empty(), stack(257, 1, 1, 0, new byte[0])));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockInventoryTransactionCodec.decode(
                        new BedrockPacket(0x1e, 0, 0, forged), REGISTRY));
    }

    @Test
    void preservesUnsignedUint32AuxAndBlockRuntimeValues() {
        var registry = new BedrockItemRegistry(List.of(
                new BedrockItemRegistry.Entry("nexora:runed_stone",
                        new BedrockInventoryContentCodec.ItemDefinition(
                                258, 0xffff_ffffL, 0xffff_ffffL, new byte[] {1, 2}), 1)));
        byte[] payload = transaction(action(0, 0, 0, empty(),
                stack(258, 1, 0xffff_ffffL, 0xffff_ffffL, new byte[] {1, 2})));

        var decoded = BedrockInventoryTransactionCodec.decode(
                new BedrockPacket(0x1e, 0, 0, payload), registry);
        assertEquals("nexora:runed_stone", decoded.actions().getFirst().to().orElseThrow().itemId());
        assertEquals(1, decoded.actions().getFirst().to().orElseThrow().count());
    }

    @Test
    void rejectsNetIdsMalformedBooleansAndTrailingBytes() {
        byte[] withNetId = transaction(action(0, 0, 0, empty(), stackWithNetId(257, 1)));
        assertThrows(UnsupportedOperationException.class,
                () -> BedrockInventoryTransactionCodec.decode(
                        new BedrockPacket(0x1e, 0, 0, withNetId), REGISTRY));

        byte[] malformedBoolean = transaction(action(0, 0, 0, empty(), stack(257, 1)));
        malformedBoolean[6] = 2; // first descriptor's hasNetId flag
        assertThrows(IllegalArgumentException.class,
                () -> BedrockInventoryTransactionCodec.decode(
                        new BedrockPacket(0x1e, 0, 0, malformedBoolean), REGISTRY));

        byte[] valid = transaction(action(0, 0, 0, empty(), empty()));
        byte[] trailing = java.util.Arrays.copyOf(valid, valid.length + 1);
        trailing[trailing.length - 1] = 99;
        assertThrows(IllegalArgumentException.class,
                () -> BedrockInventoryTransactionCodec.decode(
                        new BedrockPacket(0x1e, 0, 0, trailing), REGISTRY));
    }

    @Test
    void rejectsMalformedSourceAndActionCounts() {
        byte[] wrongContainer = transaction(action(0, 101, 0, empty(), empty()));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockInventoryTransactionCodec.decode(
                        new BedrockPacket(0x1e, 0, 0, wrongContainer), REGISTRY));

        assertThrows(IllegalArgumentException.class,
                () -> BedrockInventoryTransactionCodec.decode(
                        new BedrockPacket(0x1e, 0, 0, new byte[] {0, 0, 0}), REGISTRY));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockInventoryTransactionCodec.decode(
                        new BedrockPacket(0x1e, 0, 0, new byte[] {0, 0, 65}), REGISTRY));
    }

    @Test
    void decodesBoundedBlockContainerWindowSources() {
        byte[] payload = transaction(
                action(0, 7, 0, empty(), stack(257, 4)));
        var decoded = BedrockInventoryTransactionCodec.decode(
                new BedrockPacket(0x1e, 0, 0, payload), REGISTRY);
        assertEquals(7, decoded.actions().getFirst().containerId());
        assertEquals(4, decoded.actions().getFirst().to().orElseThrow().count());

        byte[] invalidWindow = transaction(action(0, 101, 0, empty(), stack(257, 1)));
        assertThrows(IllegalArgumentException.class, () ->
                BedrockInventoryTransactionCodec.decode(
                        new BedrockPacket(0x1e, 0, 0, invalidWindow), REGISTRY));
    }

    @Test
    void decodesProtocol2225UseOnActorAttackWithHandByte() {
        BedrockItemRegistry items = BedrockVanillaItemCatalog.createRegistry();
        byte[] payload = useOnActor(9_000_001L, 1, 2, 0,
                stack(257, 1), 1.5f, 65.0f, -2.5f, 0.25f, 1.1f, -0.4f);
        BedrockPacket packet = new BedrockPacket(0x1e, 0, 0, payload);

        assertEquals(3, BedrockInventoryTransactionCodec.transactionType(packet));
        var decoded = BedrockInventoryTransactionCodec.decodeUseOnActor(packet, REGISTRY);

        assertEquals(9_000_001L, decoded.runtimeEntityId());
        assertEquals(1, decoded.actionType());
        assertEquals(2, decoded.slot());
        assertEquals(0, decoded.hand());
        assertEquals("nexora:mana_crystal", decoded.item().orElseThrow().itemId());
        assertEquals(1.5f, decoded.fromX(), 0.0001f);
        assertEquals(65.0f, decoded.fromY(), 0.0001f);
        assertEquals(-0.4f, decoded.hitZ(), 0.0001f);
    }

    @Test
    void rejectsMalformedActorUseAndUnsupportedInventoryDeltas() {
        byte[] badAction = useOnActor(5, 9, 0, 0, empty(), 0, 65, 0, 0, 0, 0);
        assertThrows(IllegalArgumentException.class, () ->
                BedrockInventoryTransactionCodec.decodeUseOnActor(
                        new BedrockPacket(0x1e, 0, 0, badAction), REGISTRY));

        byte[] badHand = useOnActor(5, 1, 0, 2, empty(), 0, 65, 0, 0, 0, 0);
        assertThrows(IllegalArgumentException.class, () ->
                BedrockInventoryTransactionCodec.decodeUseOnActor(
                        new BedrockPacket(0x1e, 0, 0, badHand), REGISTRY));

        byte[] unsupportedDelta = useOnActor(5, 1, 0, 0, empty(), 0, 65, 0, 0, 0, 0);
        unsupportedDelta[2] = 1; // one inventory action, not supported by actor handler yet
        assertThrows(UnsupportedOperationException.class, () ->
                BedrockInventoryTransactionCodec.decodeUseOnActor(
                        new BedrockPacket(0x1e, 0, 0, unsupportedDelta), REGISTRY));
    }

    private static byte[] transaction(byte[]... actions) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0); // LegacyRequestId = 0
        out.write(0); // NormalTransactionData
        writeVarUInt(out, actions.length);
        for (byte[] action : actions) out.writeBytes(action);
        return out.toByteArray();
    }

    private static byte[] action(int sourceType, int containerId, int slot, byte[] from, byte[] to) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarUInt(out, sourceType);
        out.write(1); // Container ID is present
        out.write(containerId);
        out.write(0); // Source flags are absent
        writeVarUInt(out, slot);
        out.writeBytes(from);
        out.writeBytes(to);
        return out.toByteArray();
    }

    private static byte[] empty() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeShort(out, 0); writeShort(out, 0);
        out.write(0); // aux
        out.write(0); // has StackNetId
        out.write(0); // block runtime ID
        out.write(0); // user data length
        return out.toByteArray();
    }

    private static byte[] stack(int runtimeId, int count) {
        return stack(runtimeId, count, 0, 0, new byte[0]);
    }

    private static byte[] stack(int runtimeId, int count, long aux, long blockRuntimeId, byte[] userData) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeShort(out, runtimeId); writeShort(out, count);
        writeVarUInt(out, aux);
        out.write(0); // has StackNetId = false
        writeVarUInt(out, blockRuntimeId);
        writeVarUInt(out, userData.length);
        out.writeBytes(userData);
        return out.toByteArray();
    }

    private static byte[] stackWithNetId(int runtimeId, int count) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeShort(out, runtimeId); writeShort(out, count);
        out.write(0); // aux
        out.write(1); // has StackNetId
        out.write(2); // ZigZag(1)
        out.write(0); // block runtime ID
        out.write(0); // user data
        return out.toByteArray();
    }

    private static byte[] useOnActor(long runtimeId, int actionType, int slot, int hand,
                                     byte[] item, float fromX, float fromY, float fromZ,
                                     float hitX, float hitY, float hitZ) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, 0); // LegacyRequestId
        writeVarInt(out, 3); // ItemUseOnActor transaction
        writeVarUInt(out, 0); // no inventory actions
        writeVarUInt(out, runtimeId);
        writeVarInt(out, actionType);
        writeVarInt(out, slot);
        out.write(hand); // protocol 2225 Hand byte precedes Item
        out.writeBytes(item);
        writeFloat(out, fromX); writeFloat(out, fromY); writeFloat(out, fromZ);
        writeFloat(out, hitX); writeFloat(out, hitY); writeFloat(out, hitZ);
        return out.toByteArray();
    }

    private static byte[] itemRelease(
            int actionType, int actionCount, int runtimeItemId, int count,
            float headX, float headY, float headZ
    ) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, 0); // LegacyRequestId
        writeVarInt(out, 4); // ItemRelease transaction
        writeVarUInt(out, actionCount);
        if (actionCount != 0) {
            writeVarInt(out, 0); // Container source
            out.write(0); // placeholder action bytes
        }
        writeVarInt(out, actionType);
        writeVarInt(out, 0); // hotbar slot
        writeShort(out, runtimeItemId);
        writeShort(out, count);
        writeVarUInt(out, 0); // aux
        out.write(0); // has StackNetId
        writeVarUInt(out, 0); // block runtime ID
        writeVarUInt(out, 0); // user data
        writeFloat(out, headX); writeFloat(out, headY); writeFloat(out, headZ); // protocol From Position
        out.write(0); // protocol 2225 main hand
        return out.toByteArray();
    }

    private static void writeFloat(ByteArrayOutputStream out, float value) {
        int bits = Float.floatToIntBits(value);
        out.write(bits); out.write(bits >>> 8); out.write(bits >>> 16); out.write(bits >>> 24);
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        writeVarUInt(out, Integer.toUnsignedLong((value << 1) ^ (value >> 31)));
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private static void writeVarUInt(ByteArrayOutputStream out, long value) {
        do {
            int next = (int) (value & 0x7f);
            value >>>= 7;
            if (value != 0) next |= 0x80;
            out.write(next);
        } while (value != 0);
    }
}
