package ru.nexora.network.bedrock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.nexora.network.nethernet.NetherNetClientIdentity;
import ru.nexora.network.nethernet.NetherNetDataChannelListener;
import ru.nexora.network.nethernet.NetherNetInboundPipeline;
import ru.nexora.network.nethernet.NetherNetMessageFramer;
import ru.nexora.inventory.PlayerInventory;
import ru.nexora.inventory.PlayerInventoryStorage;
import ru.nexora.inventory.PlayerEquipment;
import ru.nexora.inventory.PlayerEquipmentStorage;
import ru.nexora.inventory.EquipmentSlot;
import ru.nexora.inventory.EquipmentSlotRegistry;
import ru.nexora.inventory.PlayerEquipmentService;
import ru.nexora.inventory.ArmorSlotRegistry;
import ru.nexora.inventory.EquipmentSlotRegistry;
import ru.nexora.inventory.EquipmentStats;
import ru.nexora.inventory.InventoryTransactionService;
import ru.nexora.world.FlatWorldGenerator;
import ru.nexora.world.BlockType;
import ru.nexora.world.World;
import ru.nexora.world.WorldChunk;
import ru.nexora.world.WorldCollision;
import ru.nexora.world.WorldRaycast;
import ru.nexora.world.PlayerData;
import ru.nexora.world.PlayerDataStorage;

import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.Objects;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Bedrock login stages above NetherNet:
 * RequestNetworkSettings -> NetworkSettings -> Login -> ClientToServerHandshake
 * -> PlayStatus -> ResourcePacksInfo -> ResourcePackStack.
 */
public final class BedrockSessionManager implements NetherNetDataChannelListener, AutoCloseable {
    public static final int LOGIN_FAILED_CLIENT_OLD = 1;
    public static final int LOGIN_FAILED_SERVER_OLD = 2;
    public static final int LOGIN_SUCCESS = 0;
    public static final int DEFAULT_COMPRESSION_THRESHOLD = 256;
    public static final int SCTP_MAX_MESSAGE_BYTES = 262_144;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final int serverProtocol;
    private final String serverVersion;
    private final int compressionThreshold;
    private final World world;
    private final PlayerDataStorage playerStorage;
    private final PlayerInventoryStorage inventoryStorage;
    private final PlayerEquipmentStorage equipmentStorage;
    private volatile BedrockItemRegistry itemRegistry = BedrockItemRegistry.empty();
    private volatile ArmorSlotRegistry armorSlotRegistry = ArmorSlotRegistry.empty();
    private volatile EquipmentSlotRegistry equipmentSlotRegistry = EquipmentSlotRegistry.empty();
    private final InventoryTransactionService inventoryTransactionService = new InventoryTransactionService();
    private final PlayerEquipmentService playerEquipmentService = new PlayerEquipmentService();
    private final OutboundFrameSender outbound;
    private final Consumer<PacketEvent> packetSink;
    private final LongSupplier nanoTime;
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final AtomicLong nextEntityId = new AtomicLong(1);
    private final AtomicLong nextFormId = new AtomicLong(1);
    private final NetherNetInboundPipeline inbound;

    public BedrockSessionManager(
            int serverProtocol,
            OutboundFrameSender outbound,
            Consumer<PacketEvent> packetSink
    ) {
        this(serverProtocol, "1.26.62", DEFAULT_COMPRESSION_THRESHOLD, outbound, packetSink);
    }

    public BedrockSessionManager(
            int serverProtocol,
            String serverVersion,
            OutboundFrameSender outbound,
            Consumer<PacketEvent> packetSink
    ) {
        this(serverProtocol, serverVersion, DEFAULT_COMPRESSION_THRESHOLD, outbound, packetSink);
    }

    public BedrockSessionManager(
            int serverProtocol,
            String serverVersion,
            int compressionThreshold,
            OutboundFrameSender outbound,
            Consumer<PacketEvent> packetSink
    ) {
        this(serverProtocol, serverVersion, compressionThreshold,
                new World("NEXORA World", 0L, new FlatWorldGenerator()), outbound, packetSink);
    }

    public BedrockSessionManager(
            int serverProtocol,
            String serverVersion,
            int compressionThreshold,
            World world,
            OutboundFrameSender outbound,
            Consumer<PacketEvent> packetSink
    ) {
        this(serverProtocol, serverVersion, compressionThreshold, world, null,
                outbound, packetSink, System::nanoTime);
    }

    public BedrockSessionManager(
            int serverProtocol,
            String serverVersion,
            int compressionThreshold,
            World world,
            PlayerDataStorage playerStorage,
            OutboundFrameSender outbound,
            Consumer<PacketEvent> packetSink
    ) {
        this(serverProtocol, serverVersion, compressionThreshold, world, playerStorage, null,
                outbound, packetSink, System::nanoTime);
    }

    public BedrockSessionManager(
            int serverProtocol,
            String serverVersion,
            int compressionThreshold,
            World world,
            PlayerDataStorage playerStorage,
            PlayerInventoryStorage inventoryStorage,
            OutboundFrameSender outbound,
            Consumer<PacketEvent> packetSink
    ) {
        this(serverProtocol, serverVersion, compressionThreshold, world, playerStorage, inventoryStorage,
                null, outbound, packetSink, System::nanoTime);
    }

    public BedrockSessionManager(
            int serverProtocol,
            String serverVersion,
            int compressionThreshold,
            World world,
            PlayerDataStorage playerStorage,
            PlayerInventoryStorage inventoryStorage,
            PlayerEquipmentStorage equipmentStorage,
            OutboundFrameSender outbound,
            Consumer<PacketEvent> packetSink
    ) {
        this(serverProtocol, serverVersion, compressionThreshold, world, playerStorage, inventoryStorage,
                equipmentStorage, outbound, packetSink, System::nanoTime);
    }

    BedrockSessionManager(
            int serverProtocol,
            String serverVersion,
            int compressionThreshold,
            World world,
            OutboundFrameSender outbound,
            Consumer<PacketEvent> packetSink,
            LongSupplier nanoTime
    ) {
        this(serverProtocol, serverVersion, compressionThreshold, world, null,
                outbound, packetSink, nanoTime);
    }

    BedrockSessionManager(
            int serverProtocol,
            String serverVersion,
            int compressionThreshold,
            World world,
            PlayerDataStorage playerStorage,
            OutboundFrameSender outbound,
            Consumer<PacketEvent> packetSink,
            LongSupplier nanoTime
    ) {
        this(serverProtocol, serverVersion, compressionThreshold, world, playerStorage, null,
                outbound, packetSink, nanoTime);
    }

    BedrockSessionManager(
            int serverProtocol,
            String serverVersion,
            int compressionThreshold,
            World world,
            PlayerDataStorage playerStorage,
            PlayerInventoryStorage inventoryStorage,
            OutboundFrameSender outbound,
            Consumer<PacketEvent> packetSink,
            LongSupplier nanoTime
    ) {
        this(serverProtocol, serverVersion, compressionThreshold, world, playerStorage, inventoryStorage,
                null, outbound, packetSink, nanoTime);
    }

    BedrockSessionManager(
            int serverProtocol,
            String serverVersion,
            int compressionThreshold,
            World world,
            PlayerDataStorage playerStorage,
            PlayerInventoryStorage inventoryStorage,
            PlayerEquipmentStorage equipmentStorage,
            OutboundFrameSender outbound,
            Consumer<PacketEvent> packetSink,
            LongSupplier nanoTime
    ) {
        if (serverProtocol < 1) throw new IllegalArgumentException("serverProtocol must be positive");
        if (serverVersion == null || serverVersion.isBlank()) {
            throw new IllegalArgumentException("serverVersion must not be blank");
        }
        if (compressionThreshold < 1 || compressionThreshold > 0xffff) {
            throw new IllegalArgumentException("compressionThreshold must be 1..65535");
        }
        this.serverProtocol = serverProtocol;
        this.serverVersion = serverVersion;
        this.compressionThreshold = compressionThreshold;
        this.world = Objects.requireNonNull(world, "world");
        this.playerStorage = playerStorage;
        this.inventoryStorage = inventoryStorage;
        this.equipmentStorage = equipmentStorage;
        this.outbound = Objects.requireNonNull(outbound, "outbound");
        this.packetSink = Objects.requireNonNull(packetSink, "packetSink");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.inbound = new NetherNetInboundPipeline(this::handleCompleteMessage);
    }

    @Override
    public void onIdentity(String networkId, NetherNetClientIdentity identity) {
        SessionState state = sessions.computeIfAbsent(networkId, ignored -> new SessionState());
        synchronized (state) {
            if (state.identity != null && !state.identity.xuid().equals(identity.xuid())) {
                throw new SecurityException("NetherNet identity changed during an active session");
            }
            state.identity = Objects.requireNonNull(identity, "identity");
        }
    }

    @Override
    public void onFrame(String networkId, String channelLabel, byte[] frame) {
        inbound.onFrame(networkId, channelLabel, frame);
    }

    @Override
    public void onDisconnected(String networkId) {
        SessionState state = sessions.get(networkId);
        if (state != null) {
            synchronized (state) {
                try {
                    savePlayer(state);
                    saveInventory(state);
                    saveEquipment(state);
                } catch (IOException exception) {
                    System.err.println("NEXORA: unable to save disconnected player: " + exception.getMessage());
                }
            }
            sessions.remove(networkId, state);
        }
        inbound.onDisconnected(networkId);
    }

    public void savePlayers() throws IOException {
        IOException failure = null;
        for (SessionState state : sessions.values()) {
            synchronized (state) {
                try {
                    savePlayer(state);
                    saveInventory(state);
                    saveEquipment(state);
                } catch (IOException exception) {
                    if (failure == null) failure = exception;
                    else failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) throw failure;
    }

    private void savePlayer(SessionState state) throws IOException {
        if (playerStorage == null || !state.loginAuthenticated || !state.positionInitialized
                || state.identity == null) return;
        String displayName = state.identity.displayName() == null ? "unknown" : state.identity.displayName();
        try {
            playerStorage.save(new PlayerData(
                    state.identity.xuid(), displayName, world.name(),
                    state.positionX, state.positionY, state.positionZ,
                    state.yaw, state.pitch, System.currentTimeMillis()));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Authenticated player data cannot be persisted", exception);
        }
    }

    private void saveInventory(SessionState state) throws IOException {
        if (inventoryStorage == null || !state.loginAuthenticated || state.identity == null) return;
        inventoryStorage.save(state.identity.xuid(), state.inventory);
    }

    private void saveEquipment(SessionState state) throws IOException {
        if (equipmentStorage == null || !state.loginAuthenticated || state.identity == null) return;
        equipmentStorage.save(state.identity.xuid(), state.equipment);
    }

    public int activeSessionCount() {
        return sessions.size();
    }

    public long authenticatedPlayerCount() {
        return sessions.values().stream().filter(state -> state.loginAuthenticated).count();
    }

    public long resourcePackReadyCount() {
        return sessions.values().stream().filter(state -> state.resourcePacksCompleted).count();
    }

    /** Returns the authoritative inventory for an authenticated player session. */
    public Optional<PlayerInventory> playerInventory(String networkId) {
        SessionState state = sessions.get(networkId);
        if (state == null) return Optional.empty();
        synchronized (state) {
            if (!state.loginAuthenticated) return Optional.empty();
            return Optional.of(state.inventory);
        }
    }

    /** Returns authoritative armor, offhand and cursor state for an authenticated player. */
    public Optional<PlayerEquipment> playerEquipment(String networkId) {
        SessionState state = sessions.get(networkId);
        if (state == null) return Optional.empty();
        synchronized (state) {
            if (!state.loginAuthenticated) return Optional.empty();
            return Optional.of(state.equipment);
        }
    }

    /** Returns derived magical equipment attributes for an authenticated player. */
    public Optional<EquipmentStats> playerEquipmentStats(String networkId) {
        SessionState state = sessions.get(networkId);
        if (state == null) return Optional.empty();
        synchronized (state) {
            if (!state.loginAuthenticated) return Optional.empty();
            return Optional.of(EquipmentStats.calculate(state.equipment, equipmentSlotRegistry));
        }
    }

    /**
     * Equips one registered magical item from the player's inventory.
     * Successful mutations are persisted before authoritative inventory state is sent.
     */
    public PlayerEquipmentService.Result equipMagicItem(
            String networkId, EquipmentSlot slot, int inventorySlot
    ) throws IOException {
        SessionState state = sessions.get(networkId);
        if (state == null) return PlayerEquipmentService.Result.EMPTY_INVENTORY_SLOT;
        synchronized (state) {
            if (!state.loginAuthenticated || !state.clientInitialized) {
                return PlayerEquipmentService.Result.EMPTY_INVENTORY_SLOT;
            }
            List<Optional<ru.nexora.inventory.ItemStack>> previousInventory = state.inventory.snapshot();
            int previousSelectedSlot = state.inventory.selectedHotbarSlot();
            PlayerEquipment.Snapshot previousEquipment = state.equipment.snapshot();
            PlayerEquipmentService.Result result = playerEquipmentService.equipFromInventory(
                    state.inventory, state.equipment, slot, inventorySlot, equipmentSlotRegistry);
            if (result != PlayerEquipmentService.Result.EQUIPPED) return result;
            try {
                saveInventory(state);
                saveEquipment(state);
            } catch (IOException | RuntimeException exception) {
                state.inventory.restore(previousInventory, previousSelectedSlot);
                state.equipment.restore(previousEquipment);
                try {
                    saveInventory(state);
                    saveEquipment(state);
                } catch (IOException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                if (exception instanceof IOException ioException) throw ioException;
                throw exception;
            }
            sendInventoryAndEquipmentState(networkId, state);
            return result;
        }
    }

    /** Unequips a magical item back into the player's inventory without losing it if full. */
    public PlayerEquipmentService.Result unequipMagicItem(String networkId, EquipmentSlot slot)
            throws IOException {
        SessionState state = sessions.get(networkId);
        if (state == null) return PlayerEquipmentService.Result.EMPTY_EQUIPMENT_SLOT;
        synchronized (state) {
            if (!state.loginAuthenticated || !state.clientInitialized) {
                return PlayerEquipmentService.Result.EMPTY_EQUIPMENT_SLOT;
            }
            List<Optional<ru.nexora.inventory.ItemStack>> previousInventory = state.inventory.snapshot();
            int previousSelectedSlot = state.inventory.selectedHotbarSlot();
            PlayerEquipment.Snapshot previousEquipment = state.equipment.snapshot();
            PlayerEquipmentService.Result result = playerEquipmentService.unequipToInventory(
                    state.inventory, state.equipment, slot);
            if (result != PlayerEquipmentService.Result.UNEQUIPPED) return result;
            try {
                saveInventory(state);
                saveEquipment(state);
            } catch (IOException | RuntimeException exception) {
                state.inventory.restore(previousInventory, previousSelectedSlot);
                state.equipment.restore(previousEquipment);
                try {
                    saveInventory(state);
                    saveEquipment(state);
                } catch (IOException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                if (exception instanceof IOException ioException) throw ioException;
                throw exception;
            }
            sendInventoryAndEquipmentState(networkId, state);
            return result;
        }
    }

    private void sendInventoryAndEquipmentState(String networkId, SessionState state) {
        List<BedrockPacket> packets = new java.util.ArrayList<>();
        packets.add(BedrockInventoryContentCodec.createPlayerInventory(state.inventory, itemRegistry));
        packets.addAll(createEquipmentReconciliationPackets(state));
        sendPackets(networkId, packets, true);
    }

    /**
     * Installs the protocol item registry before clients connect. The registry is
     * immutable so it cannot change underneath an active inventory transaction.
     */
    public synchronized void setItemRegistry(BedrockItemRegistry registry) {
        if (!sessions.isEmpty()) {
            throw new IllegalStateException("The Bedrock item registry must be installed before sessions are created");
        }
        this.itemRegistry = Objects.requireNonNull(registry, "registry");
    }

    /** Installs server-owned armor compatibility rules before clients connect. */
    public synchronized void setArmorSlotRegistry(ArmorSlotRegistry registry) {
        if (!sessions.isEmpty()) {
            throw new IllegalStateException("The armor slot registry must be installed before sessions are created");
        }
        this.armorSlotRegistry = Objects.requireNonNull(registry, "registry");
    }

    /** Installs server-owned magical item compatibility and attribute definitions before clients connect. */
    public synchronized void setEquipmentSlotRegistry(EquipmentSlotRegistry registry) {
        if (!sessions.isEmpty()) {
            throw new IllegalStateException("The magical equipment registry must be installed before sessions are created");
        }
        this.equipmentSlotRegistry = Objects.requireNonNull(registry, "registry");
    }

    public Optional<PlayerPosition> playerPosition(String networkId) {
        SessionState state = sessions.get(networkId);
        if (state == null) return Optional.empty();
        synchronized (state) {
            if (!state.clientInitialized) return Optional.empty();
            return Optional.of(new PlayerPosition(state.positionX, state.positionY, state.positionZ,
                    state.yaw, state.pitch, state.lastInputTick));
        }
    }

    private void handleCompleteMessage(NetherNetInboundPipeline.InboundMessage message) {
        SessionState state = sessions.computeIfAbsent(message.networkId(), ignored -> new SessionState());
        synchronized (state) {
            if (state.rejected) return;

            if (!state.compressionNegotiated) {
                handleNetworkSettingsRequest(message, state);
                return;
            }

            List<BedrockPacket> packets = BedrockBatchCodec.decode(message.payload(), true);
            boolean resourcePacksInfoWasSent = state.resourcePacksInfoSent;
            boolean resourcePackStackWasSent = state.resourcePackStackSent;
            for (BedrockPacket packet : packets) {
                switch (packet.packetId()) {
                    case BedrockLoginPacketParser.LOGIN_PACKET_ID -> handleLogin(message, state, packet);
                    case BedrockBatchCodec.CLIENT_TO_SERVER_HANDSHAKE_PACKET_ID ->
                            handleClientHandshake(message.networkId(), state, packet);
                    case BedrockBatchCodec.RESOURCE_PACK_CLIENT_RESPONSE_PACKET_ID ->
                            handleResourcePackResponse(message.networkId(), state, packet,
                                    resourcePacksInfoWasSent, resourcePackStackWasSent);
                    case 0x71 -> handleClientInitialized(message, state, packet);
                    case BedrockPlayerAuthInputCodec.PLAYER_AUTH_INPUT_PACKET_ID ->
                            handlePlayerAuthInput(message.networkId(), state, packet);
                    case BedrockInventoryTransactionCodec.INVENTORY_TRANSACTION_PACKET_ID ->
                            handleInventoryTransaction(message, state, packet);
                    case BedrockMobEquipmentCodec.MOB_EQUIPMENT_PACKET_ID ->
                            handleMobEquipment(message, state, packet);
                    case BedrockCommandRequestCodec.COMMAND_REQUEST_PACKET_ID ->
                            handleCommandRequest(message, state, packet);
                    case BedrockModalFormCodec.MODAL_FORM_RESPONSE_PACKET_ID ->
                            handleModalFormResponse(message, state, packet);
                    default -> packetSink.accept(new PacketEvent(
                            message.networkId(), message.channelLabel(), packet));
                }
            }
        }
    }

    private void handleCommandRequest(
            NetherNetInboundPipeline.InboundMessage message, SessionState state, BedrockPacket packet
    ) {
        if (!message.reliable()) {
            throw new SecurityException("CommandRequest must arrive on ReliableDataChannel");
        }
        if (!state.loginAuthenticated || !state.clientInitialized) {
            throw new SecurityException("CommandRequest arrived before player initialization");
        }
        BedrockCommandRequestCodec.DecodedCommand command;
        try {
            command = BedrockCommandRequestCodec.decode(packet);
        } catch (IllegalArgumentException exception) {
            throw new SecurityException("Malformed Bedrock CommandRequest", exception);
        }
        if (!"Player".equals(command.originType()) || command.internal()
                || command.playerId() != state.entityId) {
            throw new SecurityException("CommandRequest origin does not match the authenticated player");
        }

        String normalized = command.command().trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("/equipment") || normalized.equals("/equip")) {
            openEquipmentMenu(message.networkId(), state, "");
            return;
        }
        packetSink.accept(new PacketEvent(message.networkId(), message.channelLabel(), packet));
    }

    private void handleModalFormResponse(
            NetherNetInboundPipeline.InboundMessage message, SessionState state, BedrockPacket packet
    ) {
        if (!message.reliable()) {
            throw new SecurityException("ModalFormResponse must arrive on ReliableDataChannel");
        }
        if (!state.loginAuthenticated || !state.clientInitialized) {
            throw new SecurityException("ModalFormResponse arrived before player initialization");
        }
        BedrockModalFormCodec.DecodedResponse response;
        try {
            response = BedrockModalFormCodec.decodeResponse(packet);
        } catch (IllegalArgumentException exception) {
            throw new SecurityException("Malformed Bedrock ModalFormResponse", exception);
        }
        PendingEquipmentForm pending = state.pendingEquipmentForm;
        if (pending == null || pending.formId() != response.formId()) return;
        state.pendingEquipmentForm = null;
        if (response.cancelled()) return;

        final com.fasterxml.jackson.databind.JsonNode selected;
        try {
            selected = MAPPER.readTree(response.responseJson());
        } catch (IOException exception) {
            throw new SecurityException("Invalid JSON in ModalFormResponse", exception);
        }
        if (selected == null || !selected.isIntegralNumber() || !selected.canConvertToInt()) return;
        int index = selected.intValue();

        if (pending.kind() == EquipmentFormKind.MAIN) {
            EquipmentSlot[] slots = EquipmentSlot.values();
            if (index < 0 || index >= slots.length) return;
            openEquipmentSlotMenu(message.networkId(), state, slots[index], "");
            return;
        }

        if (index >= 0 && index < pending.inventorySlots().size()) {
            int inventorySlot = pending.inventorySlots().get(index);
            try {
                PlayerEquipmentService.Result result = equipMagicItem(
                        message.networkId(), pending.slot(), inventorySlot);
                String status = result == PlayerEquipmentService.Result.EQUIPPED
                        ? "Предмет надет." : "Не удалось надеть предмет: " + result.name();
                openEquipmentSlotMenu(message.networkId(), state, pending.slot(), status);
            } catch (IOException exception) {
                openEquipmentSlotMenu(message.networkId(), state, pending.slot(),
                        "Не удалось сохранить экипировку. Предмет возвращён в исходное состояние.");
            }
            return;
        }

        int unequipIndex = pending.inventorySlots().size();
        if (pending.hasUnequip() && index == unequipIndex) {
            try {
                PlayerEquipmentService.Result result = unequipMagicItem(message.networkId(), pending.slot());
                String status = result == PlayerEquipmentService.Result.UNEQUIPPED
                        ? "Предмет снят." : "Не удалось снять предмет: " + result.name();
                openEquipmentSlotMenu(message.networkId(), state, pending.slot(), status);
            } catch (IOException exception) {
                openEquipmentSlotMenu(message.networkId(), state, pending.slot(),
                        "Не удалось сохранить экипировку. Изменения отменены.");
            }
            return;
        }

        int backIndex = pending.inventorySlots().size() + (pending.hasUnequip() ? 1 : 0);
        if (index == backIndex) openEquipmentMenu(message.networkId(), state, "");
    }

    private void openEquipmentMenu(String networkId, SessionState state, String status) {
        com.fasterxml.jackson.databind.node.ObjectNode form = MAPPER.createObjectNode();
        form.put("type", "form");
        form.put("title", "NEXORA — Экипировка");
        StringBuilder description = new StringBuilder("Магическая экипировка персонажа.");
        if (status != null && !status.isBlank()) description.append("\n").append(status);
        description.append("\\nВыбери слот:");
        form.put("content", description.toString());
        com.fasterxml.jackson.databind.node.ArrayNode buttons = form.putArray("buttons");
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            String item = state.equipment.magicSlot(slot).map(ru.nexora.inventory.ItemStack::itemId)
                    .orElse("пусто");
            buttons.addObject().put("text", slot.displayName() + " — " + item);
        }
        sendEquipmentForm(networkId, state, form, new PendingEquipmentForm(
                0, EquipmentFormKind.MAIN, null, List.of(), false));
    }

    private void openEquipmentSlotMenu(
            String networkId, SessionState state, EquipmentSlot slot, String status
    ) {
        List<Integer> inventorySlots = new java.util.ArrayList<>();
        List<String> labels = new java.util.ArrayList<>();
        for (int i = 0; i < state.inventory.size(); i++) {
            Optional<ru.nexora.inventory.ItemStack> optional = state.inventory.getSlot(i);
            if (optional.isEmpty()) continue;
            ru.nexora.inventory.ItemStack stack = optional.orElseThrow();
            if (!equipmentSlotRegistry.allows(stack, slot)) continue;
            inventorySlots.add(i);
            labels.add("Инвентарь " + (i + 1) + " — " + stack.itemId());
        }

        boolean hasUnequip = state.equipment.magicSlot(slot).isPresent();
        com.fasterxml.jackson.databind.node.ObjectNode form = MAPPER.createObjectNode();
        form.put("type", "form");
        form.put("title", "Экипировка — " + slot.displayName());
        String current = state.equipment.magicSlot(slot)
                .map(ru.nexora.inventory.ItemStack::itemId).orElse("пусто");
        StringBuilder description = new StringBuilder("Сейчас: ").append(current);
        if (status != null && !status.isBlank()) description.append("\n").append(status);
        if (labels.isEmpty()) description.append("\\nВ инвентаре нет подходящих предметов.");
        else description.append("\\nВыбери предмет для этого слота:");
        form.put("content", description.toString());

        com.fasterxml.jackson.databind.node.ArrayNode buttons = form.putArray("buttons");
        for (String label : labels) buttons.addObject().put("text", label);
        if (hasUnequip) buttons.addObject().put("text", "Снять надетый предмет");
        buttons.addObject().put("text", "Назад");

        sendEquipmentForm(networkId, state, form, new PendingEquipmentForm(
                0, EquipmentFormKind.SLOT, slot, List.copyOf(inventorySlots), hasUnequip));
    }

    private void sendEquipmentForm(
            String networkId, SessionState state,
            com.fasterxml.jackson.databind.node.ObjectNode form, PendingEquipmentForm pending
    ) {
        long next = nextFormId.getAndIncrement();
        if (next < 1 || next > Integer.MAX_VALUE) {
            throw new IllegalStateException("Bedrock form ID space exhausted");
        }
        int formId = (int) next;
        state.pendingEquipmentForm = new PendingEquipmentForm(
                formId, pending.kind(), pending.slot(), pending.inventorySlots(), pending.hasUnequip());
        try {
            sendPacket(networkId, BedrockModalFormCodec.createRequest(
                    formId, MAPPER.writeValueAsString(form)), true);
        } catch (IOException exception) {
            state.pendingEquipmentForm = null;
            throw new IllegalStateException("Unable to encode NEXORA equipment form", exception);
        }
    }

    private List<BedrockPacket> createEquipmentReconciliationPackets(SessionState state) {
        List<BedrockPacket> packets = new java.util.ArrayList<>();
        packets.add(BedrockMobEquipmentCodec.createPlayerEquipment(state.entityId, state.inventory, itemRegistry));
        packets.addAll(BedrockPlayerEquipmentCodec.createInitialEquipment(state.equipment, itemRegistry));
        return List.copyOf(packets);
    }

    private void handleInventoryTransaction(
            NetherNetInboundPipeline.InboundMessage message, SessionState state, BedrockPacket packet
    ) {
        String networkId = message.networkId();
        if (!message.reliable()) {
            throw new SecurityException("InventoryTransaction must arrive on ReliableDataChannel");
        }
        if (!state.loginAuthenticated || !state.clientInitialized) {
            throw new SecurityException("InventoryTransaction arrived before player initialization");
        }

        BedrockItemRegistry registry = itemRegistry;
        BedrockPacket authoritativeContent;
        try {
            authoritativeContent = BedrockInventoryContentCodec.createPlayerInventory(state.inventory, registry);
        } catch (IllegalArgumentException exception) {
            // If a persisted item has no protocol definition, do not mutate the inventory.
            System.err.println("NEXORA: inventory transaction blocked because an item has no Bedrock definition");
            return;
        }

        BedrockInventoryTransactionCodec.DecodedTransaction decoded;
        try {
            decoded = BedrockInventoryTransactionCodec.decode(packet, registry);
        } catch (UnsupportedOperationException exception) {
            // Unsupported transaction variants are not interpreted; restore the client's prediction.
            List<BedrockPacket> reconciliation = new java.util.ArrayList<>();
            reconciliation.add(authoritativeContent);
            reconciliation.addAll(createEquipmentReconciliationPackets(state));
            sendPackets(networkId, reconciliation, true);
            return;
        } catch (IllegalArgumentException exception) {
            state.rejected = true;
            throw new SecurityException("Malformed Bedrock InventoryTransaction", exception);
        }

        InventoryTransactionService.Result result =
                inventoryTransactionService.applyPlayerInventoryAndUiTransaction(
                        state.inventory, state.equipment, decoded.actions(), armorSlotRegistry);
        if (result == InventoryTransactionService.Result.APPLIED) {
            authoritativeContent = BedrockInventoryContentCodec.createPlayerInventory(state.inventory, registry);
        }
        // Always reconcile client prediction, including stale, unsupported, and rejected actions.
        List<BedrockPacket> reconciliation = new java.util.ArrayList<>();
        reconciliation.add(authoritativeContent);
        reconciliation.addAll(createEquipmentReconciliationPackets(state));
        sendPackets(networkId, reconciliation, true);
    }

    private void handleNetworkSettingsRequest(
            NetherNetInboundPipeline.InboundMessage message,
            SessionState state
    ) {
        if (!message.reliable()) {
            throw new IllegalArgumentException("RequestNetworkSettings must arrive on ReliableDataChannel");
        }
        List<BedrockPacket> packets = BedrockBatchCodec.decode(message.payload(), false);
        if (packets.size() != 1) {
            throw new IllegalArgumentException("Initial Bedrock batch must contain only RequestNetworkSettings");
        }
        int clientProtocol = BedrockBatchCodec.readRequestedProtocol(packets.getFirst());
        if (clientProtocol != serverProtocol) {
            int status = clientProtocol < serverProtocol
                    ? LOGIN_FAILED_CLIENT_OLD
                    : LOGIN_FAILED_SERVER_OLD;
            state.rejected = true;
            sendPacket(message.networkId(), BedrockBatchCodec.createPlayStatus(status), false);
            System.out.printf("NEXORA: rejected Bedrock protocol %d; server requires %d%n",
                    clientProtocol, serverProtocol);
            return;
        }

        state.compressionNegotiated = true;
        state.clientProtocol = clientProtocol;
        sendPacket(message.networkId(), BedrockBatchCodec.createNetworkSettings(
                compressionThreshold, BedrockBatchCodec.COMPRESSION_ZLIB), false);
        System.out.printf("NEXORA: negotiated Bedrock protocol %d for %s%n",
                clientProtocol, message.networkId());
    }

    private void handleLogin(
            NetherNetInboundPipeline.InboundMessage message,
            SessionState state,
            BedrockPacket packet
    ) {
        if (!message.reliable()) throw new SecurityException("Login must arrive on ReliableDataChannel");
        if (state.loginAuthenticated) throw new SecurityException("Duplicate Login packet");
        if (state.identity == null) throw new SecurityException("Login has no verified NetherNet identity");

        BedrockLoginPacketParser.LoginRequest login = BedrockLoginPacketParser.parse(packet);
        if (login.authenticationType() != 0) {
            throw new SecurityException("Only authenticated Xbox Live login is supported");
        }
        if (!state.identity.gameServerToken().equals(login.multiplayerToken())) {
            throw new SecurityException("Login GameServerToken does not match the verified NetherNet identity");
        }
        verifyClientDataToken(login.rawClientDataToken(), state.identity);
        if (inventoryStorage != null) {
            try {
                state.inventory = inventoryStorage.load(state.identity.xuid()).orElseGet(PlayerInventory::new);
            } catch (IOException | RuntimeException exception) {
                state.rejected = true;
                throw new SecurityException("Unable to safely restore authenticated player inventory", exception);
            }
        }
        if (equipmentStorage != null) {
            try {
                state.equipment = equipmentStorage.load(state.identity.xuid()).orElseGet(PlayerEquipment::new);
            } catch (IOException | RuntimeException exception) {
                state.rejected = true;
                throw new SecurityException("Unable to safely restore authenticated player equipment", exception);
            }
        }
        // Do not mark the session authenticated until its persisted inventory has been restored.
        // Otherwise a failed load could be replaced by an empty inventory during autosave.
        state.loginAuthenticated = true;
        state.entityId = nextEntityId.getAndIncrement();
        if (state.entityId < 1) {
            throw new IllegalStateException("NEXORA entity ID space exhausted");
        }

        String displayName = state.identity.displayName() == null
                ? "unknown" : state.identity.displayName();
        if (playerStorage != null) {
            try {
                Optional<PlayerData> saved = playerStorage.load(state.identity.xuid());
                if (saved.isPresent() && saved.get().worldName().equals(world.name())) {
                    PlayerData player = saved.get();
                    if (WorldCollision.canOccupy(world, player.x(), player.y(), player.z())) {
                        state.positionX = player.x();
                        state.positionY = player.y();
                        state.positionZ = player.z();
                        state.yaw = player.yaw();
                        state.pitch = player.pitch();
                        state.positionInitialized = true;
                    } else {
                        System.err.println("NEXORA: saved position for " + displayName
                                + " is obstructed; using the world spawn");
                    }
                }
            } catch (IOException | RuntimeException exception) {
                System.err.println("NEXORA: unable to load player profile for " + displayName
                        + ": " + exception.getMessage());
            }
        }
        System.out.printf("NEXORA: authenticated player %s%n", displayName);
    }

    private void handleClientHandshake(String networkId, SessionState state, BedrockPacket packet) {
        if (!state.loginAuthenticated) {
            throw new SecurityException("ClientToServerHandshake arrived before authenticated Login");
        }
        if (state.clientHandshakeReceived) {
            throw new SecurityException("Duplicate ClientToServerHandshake packet");
        }
        if (packet.payload().length != 0) {
            throw new IllegalArgumentException("ClientToServerHandshake must have an empty payload");
        }

        // NetherNet's DTLS layer provides transport encryption; no Bedrock application cipher is enabled.
        state.clientHandshakeReceived = true;
        state.resourcePacksInfoSent = true;
        sendPacket(networkId, BedrockBatchCodec.createPlayStatus(LOGIN_SUCCESS), true);
        sendPacket(networkId, BedrockBatchCodec.createResourcePacksInfo(), true);
    }

    private void handleResourcePackResponse(
            String networkId,
            SessionState state,
            BedrockPacket packet,
            boolean resourcePacksInfoWasSent,
            boolean resourcePackStackWasSent
    ) {
        if (!state.clientHandshakeReceived || !resourcePacksInfoWasSent) {
            throw new SecurityException("ResourcePackClientResponse arrived before ResourcePacksInfo");
        }
        BedrockBatchCodec.ResourcePackClientResponseData response =
                BedrockBatchCodec.parseResourcePackClientResponse(packet);
        switch (response.response()) {
            case 0 -> throw new SecurityException("Client refused resource-pack negotiation");
            case 1 -> throw new SecurityException("Client requested packs from an empty server pack list");
            case 2 -> {
                if (state.resourcePackStackSent) {
                    throw new SecurityException("Duplicate all-packs-downloaded response");
                }
                state.resourcePackStackSent = true;
                sendPacket(networkId, BedrockBatchCodec.createResourcePackStack(serverVersion), true);
            }
            case 3 -> {
                if (!resourcePackStackWasSent) {
                    throw new SecurityException("Resource-pack stack completed before the server sent its stack");
                }
                state.resourcePacksCompleted = true;
                if (!state.gameStarted) {
                    state.gameStarted = true;
                    if (!state.positionInitialized) {
                        state.positionX = world.spawnX() + 0.5f;
                        state.positionY = world.spawnY();
                        state.positionZ = world.spawnZ() + 0.5f;
                        if (!WorldCollision.canOccupy(world, state.positionX, state.positionY, state.positionZ)) {
                            throw new IllegalStateException("Configured NEXORA world spawn is obstructed");
                        }
                        state.positionInitialized = true;
                    }
                    state.lastMovementNanos = nanoTime.getAsLong();
                    state.movementBudget = 2.0;
                    for (BedrockPacket worldPacket : BedrockStartGameCodec.createInitialWorldPackets(
                            state.entityId,
                            world.name(),
                            serverVersion,
                            serverVersion,
                            world.seed(),
                            world.spawnX(),
                            world.spawnY() - 1,
                            world.spawnZ(),
                            state.positionX,
                            state.positionY,
                            state.positionZ,
                            state.pitch,
                            state.yaw
                    )) {
                        if (worldPacket.packetId() == 0x0b) {
                            // Bedrock requires ItemRegistry immediately after StartGame.
                            sendPackets(networkId, List.of(worldPacket,
                                    BedrockItemRegistryPacketCodec.createPacket(itemRegistry)), true);
                        } else {
                            sendPacket(networkId, worldPacket, true);
                        }
                    }
                    sendPacket(networkId, BedrockBiomeDefinitionCodec.createPacket(), true);
                    sendInitialChunkArea(networkId, state);
                }
                System.out.printf("NEXORA: resource-pack negotiation completed for %s%n", networkId);
            }
            default -> throw new IllegalArgumentException("Unknown resource-pack response");
        }
    }


    private void handlePlayerAuthInput(String networkId, SessionState state, BedrockPacket packet) {
        if (!state.clientInitialized) {
            throw new SecurityException("PlayerAuthInput arrived before client initialization");
        }
        BedrockPlayerAuthInputCodec.PlayerInput input = BedrockPlayerAuthInputCodec.decode(packet);
        if (state.hasReceivedInput && input.tick() <= state.lastInputTick) return;

        if (Math.abs(input.x()) > 30_000_000 || Math.abs(input.z()) > 30_000_000
                || input.y() < WorldChunk.MIN_Y - 16 || input.y() > WorldChunk.MAX_Y + 16) {
            return;
        }
        long nowNanos = nanoTime.getAsLong();
        double dx = input.x() - state.positionX;
        double dy = input.y() - state.positionY;
        double dz = input.z() - state.positionZ;
        long elapsedNanos = Math.max(0, nowNanos - state.lastMovementNanos);
        double movementBudget = Math.min(2.0,
                state.movementBudget + (Math.min(elapsedNanos, 1_000_000_000L) / 1_000_000_000.0) * 8.0);
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance > movementBudget) {
            return; // Token-bucket speed guard; full movement simulation follows later.
        }
        if (!WorldCollision.canMove(world,
                state.positionX, state.positionY, state.positionZ,
                input.x(), input.y(), input.z())) {
            return; // Reject movement that clips through solid world blocks.
        }
        state.movementBudget = movementBudget - distance;
        state.lastMovementNanos = nowNanos;
        state.hasReceivedInput = true;
        state.positionX = input.x();
        state.positionY = input.y();
        state.positionZ = input.z();
        state.yaw = input.yaw();
        state.pitch = input.pitch();
        state.lastInputTick = input.tick();

        for (BedrockPlayerAuthInputCodec.BlockAction action : input.blockActions()) {
            handleBlockAction(networkId, state, action);
        }

        int chunkX = Math.floorDiv((int) Math.floor(input.x()), 16);
        int chunkZ = Math.floorDiv((int) Math.floor(input.z()), 16);
        if (chunkX != state.lastChunkX || chunkZ != state.lastChunkZ) {
            sendPacket(networkId, BedrockStartGameCodec.createChunkPublisherUpdate(
                    (int) Math.floor(input.x()), (int) Math.floor(input.y()), (int) Math.floor(input.z())), true);
            sendChunkArea(networkId, state, chunkX, chunkZ);
        }
    }

    private void handleBlockAction(
            String networkId,
            SessionState state,
            BedrockPlayerAuthInputCodec.BlockAction action
    ) {
        if (Math.abs((long) action.x()) > 30_000_000
                || Math.abs((long) action.z()) > 30_000_000
                || action.y() < WorldChunk.MIN_Y || action.y() > WorldChunk.MAX_Y
                || (action.action() == 0 && (action.face() < 0 || action.face() > 5))) {
            return;
        }
        if (action.action() == 0) { // START_DESTROY_BLOCK
            if (!isWithinBlockReach(state, action.x(), action.y(), action.z())) return;
            BlockType type = world.getBlock(action.x(), action.y(), action.z());
            if (type == BlockType.AIR || type == BlockType.BEDROCK) return;
            if (!matchesBreakTarget(state.breakTarget, action)) {
                state.breakTarget = new BreakTarget(action.x(), action.y(), action.z(), nanoTime.getAsLong());
            }
            return;
        }

        if (action.action() == 1) { // ABORT_DESTROY_BLOCK
            if (matchesBreakTarget(state, action)) state.breakTarget = null;
            return;
        }

        if (action.action() != 2 && action.action() != 26 && action.action() != 27) return;
        // STOP / PREDICT / CONTINUE_DESTROY_BLOCK
        BreakTarget target = state.breakTarget;
        if (target == null || !matchesBreakTarget(target, action)) return;

        if (!isWithinBlockReach(state, target.x(), target.y(), target.z())) {
            state.breakTarget = null;
            return;
        }
        BlockType current = world.getBlock(target.x(), target.y(), target.z());
        if (current == BlockType.AIR) {
            state.breakTarget = null;
            return;
        }
        long requiredNanos = requiredBreakNanos(current);
        if (requiredNanos == Long.MAX_VALUE
                || nanoTime.getAsLong() - target.startedAtNanos() < requiredNanos) return;

        world.setBlock(target.x(), target.y(), target.z(), BlockType.AIR);
        state.breakTarget = null;
        BedrockPacket update = BedrockBlockUpdateCodec.createPacket(
                target.x(), target.y(), target.z(), BlockType.AIR);
        int changedChunkX = Math.floorDiv(target.x(), 16);
        int changedChunkZ = Math.floorDiv(target.z(), 16);
        long changedChunkKey = ((long) changedChunkX << 32) | (changedChunkZ & 0xffff_ffffL);
        for (var entry : sessions.entrySet()) {
            SessionState recipient = entry.getValue();
            // Only update clients that currently have this chunk in their sent view.
            // The set is concurrent because different session event loops can publish chunks
            // while another player breaks a block.
            if (recipient.clientInitialized && recipient.sentChunkKeys.contains(changedChunkKey)) {
                sendPacket(entry.getKey(), update, true);
            }
        }
    }

    private static boolean matchesBreakTarget(SessionState state, BedrockPlayerAuthInputCodec.BlockAction action) {
        return matchesBreakTarget(state.breakTarget, action);
    }

    private static boolean matchesBreakTarget(BreakTarget target, BedrockPlayerAuthInputCodec.BlockAction action) {
        return target != null && target.x() == action.x() && target.y() == action.y() && target.z() == action.z();
    }

    private boolean isWithinBlockReach(SessionState state, int x, int y, int z) {
        // Bedrock player position is at the feet; block interaction originates near eye height.
        double eyeX = state.positionX;
        double eyeY = state.positionY + 1.62;
        double eyeZ = state.positionZ;
        double targetX = x + 0.5;
        double targetY = y + 0.5;
        double targetZ = z + 0.5;
        double dx = eyeX - targetX;
        double dy = eyeY - targetY;
        double dz = eyeZ - targetZ;
        return dx * dx + dy * dy + dz * dz <= 36.0
                && WorldRaycast.hasLineOfSight(world, eyeX, eyeY, eyeZ, x, y, z);
    }

    private static long requiredBreakNanos(BlockType type) {
        return switch (type) {
            case AIR, BEDROCK -> Long.MAX_VALUE;
            case DIRT, GRASS_BLOCK -> 600_000_000L; // 0.6 seconds
            case STONE -> 7_500_000_000L; // 7.5 seconds without a tool
        };
    }

    private void sendInitialChunkArea(String networkId, SessionState state) {
        int centerX = Math.floorDiv((int) Math.floor(state.positionX), 16);
        int centerZ = Math.floorDiv((int) Math.floor(state.positionZ), 16);
        sendChunkArea(networkId, state, centerX, centerZ);
    }

    private void sendChunkArea(String networkId, SessionState state, int centerX, int centerZ) {
        int radius = 1;
        Set<Long> desiredChunks = new HashSet<>();
        for (int ring = 0; ring <= radius; ring++) {
            for (int offsetZ = -ring; offsetZ <= ring; offsetZ++) {
                for (int offsetX = -ring; offsetX <= ring; offsetX++) {
                    if (Math.max(Math.abs(offsetX), Math.abs(offsetZ)) != ring) continue;
                    int chunkX = centerX + offsetX;
                    int chunkZ = centerZ + offsetZ;
                    long key = ((long) chunkX << 32) | (chunkZ & 0xffff_ffffL);
                    desiredChunks.add(key);
                    if (state.sentChunkKeys.add(key)) {
                        WorldChunk chunk = world.getChunk(chunkX, chunkZ);
                        sendPacket(networkId, BedrockLevelChunkCodec.createPacket(chunk), true);
                    }
                }
            }
        }
        state.sentChunkKeys.retainAll(desiredChunks);
        state.lastChunkX = centerX;
        state.lastChunkZ = centerZ;
    }

    private void handleClientInitialized(
            NetherNetInboundPipeline.InboundMessage message,
            SessionState state,
            BedrockPacket packet
    ) {
        if (!message.reliable()) {
            throw new SecurityException("SetLocalPlayerAsInitialized must arrive on ReliableDataChannel");
        }
        if (!state.gameStarted) {
            throw new SecurityException("SetLocalPlayerAsInitialized arrived before StartGame");
        }
        if (state.clientInitialized) {
            throw new SecurityException("Duplicate SetLocalPlayerAsInitialized packet");
        }
        long runtimeEntityId = readUnsignedVarLong(packet.payload());
        if (runtimeEntityId != state.entityId) {
            throw new SecurityException("SetLocalPlayerAsInitialized runtime ID does not match this player");
        }
        state.clientInitialized = true;
        sendPacket(message.networkId(), BedrockBatchCodec.createPlayStatus(3), true);
        sendInitialPlayerInventory(message.networkId(), state);
    }

    /**
     * Sends the first authoritative player inventory snapshot only after the client
     * has acknowledged StartGame. Unknown persisted items are never serialized with
     * guessed Bedrock IDs; in that case the inventory remains server-side until its
     * exact protocol definition is registered.
     */
    private void sendInitialPlayerInventory(String networkId, SessionState state) {
        try {
            List<BedrockPacket> initialPackets = new java.util.ArrayList<>();
            initialPackets.add(BedrockInventoryContentCodec.createPlayerInventory(state.inventory, itemRegistry));
            initialPackets.add(BedrockMobEquipmentCodec.createPlayerEquipment(state.entityId, state.inventory, itemRegistry));
            initialPackets.addAll(BedrockPlayerEquipmentCodec.createInitialEquipment(state.equipment, itemRegistry));
            sendPackets(networkId, initialPackets, true);
        } catch (IllegalArgumentException exception) {
            System.err.println("NEXORA: initial inventory sync deferred because a stored item has no Bedrock definition");
        }
    }

    private void handleMobEquipment(
            NetherNetInboundPipeline.InboundMessage message, SessionState state, BedrockPacket packet
    ) {
        String networkId = message.networkId();
        if (!message.reliable()) {
            throw new SecurityException("MobEquipment must arrive on ReliableDataChannel");
        }
        if (!state.loginAuthenticated || !state.clientInitialized) {
            throw new SecurityException("MobEquipment arrived before player initialization");
        }

        BedrockMobEquipmentCodec.DecodedEquipment equipment;
        try {
            equipment = BedrockMobEquipmentCodec.decode(packet, itemRegistry);
        } catch (UnsupportedOperationException exception) {
            sendPackets(networkId, createEquipmentReconciliationPackets(state), true);
            return;
        } catch (IllegalArgumentException exception) {
            state.rejected = true;
            throw new SecurityException("Malformed Bedrock MobEquipment", exception);
        }

        if (equipment.runtimeEntityId() != state.entityId) {
            throw new SecurityException("MobEquipment runtime entity ID does not match this player");
        }
        if (equipment.containerId() != BedrockInventoryContentCodec.PLAYER_INVENTORY_CONTAINER_ID
                || equipment.inventorySlot() >= PlayerInventory.HOTBAR_SIZE
                || equipment.hotbarSlot() >= PlayerInventory.HOTBAR_SIZE
                || equipment.inventorySlot() != equipment.hotbarSlot()) {
            sendPackets(networkId, createEquipmentReconciliationPackets(state), true);
            return;
        }

        Optional<ru.nexora.inventory.ItemStack> authoritative =
                state.inventory.getSlot(equipment.inventorySlot());
        if (!authoritative.equals(equipment.item())) {
            List<BedrockPacket> reconciliation = new java.util.ArrayList<>();
            reconciliation.add(BedrockInventoryContentCodec.createPlayerInventory(state.inventory, itemRegistry));
            reconciliation.addAll(createEquipmentReconciliationPackets(state));
            sendPackets(networkId, reconciliation, true);
            return;
        }
        state.inventory.selectHotbarSlot(equipment.hotbarSlot());
    }

    private static long readUnsignedVarLong(byte[] payload) {
        long value = 0;
        int shift = 0;
        for (int i = 0; i < 10; i++) {
            if (i >= payload.length) throw new IllegalArgumentException("Truncated unsigned VarLong");
            int current = Byte.toUnsignedInt(payload[i]);
            if (i == 9 && (current & 0xfe) != 0) {
                throw new IllegalArgumentException("Unsigned VarLong exceeds 64 bits");
            }
            value |= (long) (current & 0x7f) << shift;
            if ((current & 0x80) == 0) {
                if (i + 1 != payload.length) throw new IllegalArgumentException("Trailing bytes in unsigned VarLong");
                if (value < 0) throw new IllegalArgumentException("Runtime entity ID exceeds signed long range");
                return value;
            }
            shift += 7;
        }
        throw new IllegalArgumentException("Unsigned VarLong is too long");
    }

    private void sendPacket(String networkId, BedrockPacket packet, boolean compressionNegotiated) {
        sendPackets(networkId, List.of(packet), compressionNegotiated);
    }

    private void sendPackets(String networkId, List<BedrockPacket> packets, boolean compressionNegotiated) {
        byte[] batch = BedrockBatchCodec.encode(
                packets, compressionNegotiated, compressionThreshold);
        List<byte[]> frames = NetherNetMessageFramer.fragment(
                batch, SCTP_MAX_MESSAGE_BYTES, true);
        try {
            for (byte[] frame : frames) {
                outbound.sendFrame(networkId, "ReliableDataChannel", frame);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to send Bedrock packet batch", exception);
        }
    }

    private static void verifyClientDataToken(String token, NetherNetClientIdentity identity) {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || parts[2].isEmpty()) {
                throw new SecurityException("Malformed client-data JWT");
            }
            JsonNode header = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if (!"ES384".equals(header.path("alg").asText())) {
                throw new SecurityException("Client-data JWT must use ES384");
            }

            byte[] publicKeyDer = identity.publicKeyDer();
            String x5u = header.path("x5u").asText("");
            if (!x5u.isBlank()) {
                byte[] headerKey = Base64.getDecoder().decode(x5u);
                if (!MessageDigest.isEqual(publicKeyDer, headerKey)) {
                    throw new SecurityException("Client-data JWT x5u does not match the verified client key");
                }
            }

            PublicKey publicKey = KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(publicKeyDer));
            Signature verifier = Signature.getInstance("SHA384withECDSA");
            verifier.initVerify(publicKey);
            verifier.update((parts[0] + "." + parts[1]).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            if (!verifier.verify(joseToDer(Base64.getUrlDecoder().decode(parts[2])))) {
                throw new SecurityException("Invalid client-data JWT signature");
            }

            JsonNode claims = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
            long now = Instant.now().getEpochSecond();
            JsonNode expires = claims.get("exp");
            if (expires != null && (!expires.canConvertToLong() || now > expires.asLong() + 30)) {
                throw new SecurityException("Client-data JWT has expired or has an invalid expiry");
            }
            JsonNode notBefore = claims.get("nbf");
            if (notBefore != null && (!notBefore.canConvertToLong() || now + 30 < notBefore.asLong())) {
                throw new SecurityException("Client-data JWT is not active yet");
            }
        } catch (SecurityException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SecurityException("Unable to validate client-data JWT", exception);
        }
    }

    private static byte[] joseToDer(byte[] jose) {
        if (jose.length != 96) throw new SecurityException("ES384 signature must be 96 bytes");
        byte[] r = derInteger(jose, 0);
        byte[] s = derInteger(jose, 48);
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

    private static byte[] derInteger(byte[] jose, int offset) {
        int first = offset;
        int end = offset + 48;
        while (first < end - 1 && jose[first] == 0) first++;
        int length = end - first;
        boolean needsZero = (jose[first] & 0x80) != 0;
        byte[] result = new byte[length + (needsZero ? 1 : 0)];
        System.arraycopy(jose, first, result, result.length - length, length);
        return result;
    }

    @Override
    public void close() {
        try {
            savePlayers();
        } catch (IOException exception) {
            System.err.println("NEXORA: unable to save player profiles on shutdown: " + exception.getMessage());
        }
        sessions.clear();
    }

    @FunctionalInterface
    public interface OutboundFrameSender {
        void sendFrame(String networkId, String channelLabel, byte[] frame) throws Exception;
    }

    public record PacketEvent(String networkId, String channelLabel, BedrockPacket packet) {
        public PacketEvent {
            Objects.requireNonNull(networkId, "networkId");
            Objects.requireNonNull(channelLabel, "channelLabel");
            Objects.requireNonNull(packet, "packet");
        }
    }

    public record PlayerPosition(float x, float y, float z, float yaw, float pitch, long tick) {
    }

    private record BreakTarget(int x, int y, int z, long startedAtNanos) {
    }

    private enum EquipmentFormKind { MAIN, SLOT }

    private record PendingEquipmentForm(
            int formId, EquipmentFormKind kind, EquipmentSlot slot,
            List<Integer> inventorySlots, boolean hasUnequip
    ) {
    }

    private static final class SessionState {
        private PlayerInventory inventory = new PlayerInventory();
        private PlayerEquipment equipment = new PlayerEquipment();
        private boolean compressionNegotiated;
        private volatile boolean loginAuthenticated;
        private boolean clientHandshakeReceived;
        private boolean resourcePacksInfoSent;
        private boolean resourcePackStackSent;
        private volatile boolean resourcePacksCompleted;
        private boolean gameStarted;
        private volatile boolean clientInitialized;
        private boolean positionInitialized;
        private float positionX;
        private float positionY;
        private float positionZ;
        private float yaw;
        private float pitch;
        private long lastInputTick;
        private boolean hasReceivedInput;
        private long lastMovementNanos;
        private double movementBudget = 2.0;
        private int lastChunkX = Integer.MIN_VALUE;
        private int lastChunkZ = Integer.MIN_VALUE;
        private final Set<Long> sentChunkKeys = ConcurrentHashMap.newKeySet();
        private BreakTarget breakTarget;
        private long entityId;
        private boolean rejected;
        private PendingEquipmentForm pendingEquipmentForm;
        private int clientProtocol;
        private NetherNetClientIdentity identity;
    }
}
