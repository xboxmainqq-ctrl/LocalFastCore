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
        byte[] wrongContainer = transaction(action(0, 1, 0, empty(), empty()));
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
