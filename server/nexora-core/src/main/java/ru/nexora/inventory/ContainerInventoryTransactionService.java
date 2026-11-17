package ru.nexora.inventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Validates atomic Bedrock inventory transfers between a player's main
 * inventory and one currently-open block container.
 *
 * The packet decoder must already have resolved item descriptors through the
 * server's Bedrock item registry. This service checks the active window ID,
 * stale slot snapshots, duplicate references and per-item conservation.
 */
public final class ContainerInventoryTransactionService {
    public static final int PLAYER_CONTAINER_ID = 0;
    public static final int MAX_CONTAINER_WINDOW_ID = 100;
    public static final int MAX_ACTIONS = 64;

    public Result apply(
            PlayerInventory player,
            ContainerInventory container,
            int activeWindowId,
            List<InventoryTransactionService.Action> actions
    ) {
        return apply(player, container, activeWindowId, actions, null);
    }

    /**
     * The UI crafting grid uses container ID 124 and only the caller-approved slot range.
     */
    public Result apply(
            PlayerInventory player,
            ContainerInventory container,
            int activeWindowId,
            List<InventoryTransactionService.Action> actions,
            Set<Integer> allowedContainerSlots
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(container, "container");
        Objects.requireNonNull(actions, "actions");
        boolean blockWindow = activeWindowId >= 1 && activeWindowId <= MAX_CONTAINER_WINDOW_ID;
        boolean uiCraftingGrid = activeWindowId == 124 && allowedContainerSlots != null;
        if (!blockWindow && !uiCraftingGrid) return Result.INVALID_WINDOW;
        if (actions.isEmpty() || actions.size() > MAX_ACTIONS) {
            return Result.INVALID_ACTION_COUNT;
        }

        // Keep the same lock order as ContainerInventory.moveFrom/moveTo:
        // player inventory first, then container inventory.
        synchronized (player) {
            synchronized (container) {
                Set<String> touched = new HashSet<>();
                Map<String, Integer> itemDeltas = new HashMap<>();
                List<PendingChange> pending = new ArrayList<>(actions.size());

                for (InventoryTransactionService.Action action : actions) {
                    if (action == null) return Result.INVALID_ACTION;
                    if (action.sourceType() != InventoryTransactionService.CONTAINER_INVENTORY_SOURCE) {
                        return Result.UNSUPPORTED_SOURCE;
                    }

                    int containerId = action.containerId();
                    int slot = action.slot();
                    if (containerId == PLAYER_CONTAINER_ID) {
                        if (slot < 0 || slot >= player.size()) return Result.INVALID_SLOT;
                    } else if (containerId == activeWindowId) {
                        if (slot < 0 || slot >= container.size()
                                || (allowedContainerSlots != null && !allowedContainerSlots.contains(slot))) {
                            return Result.INVALID_SLOT;
                        }
                    } else {
                        return Result.UNSUPPORTED_CONTAINER;
                    }

                    String key = containerId + ":" + slot;
                    if (!touched.add(key)) return Result.INVALID_ACTION;

                    Optional<ItemStack> current = containerId == PLAYER_CONTAINER_ID
                            ? player.getSlot(slot) : container.getSlot(slot);
                    if (!current.equals(action.from())) return Result.REJECTED_STALE_OR_UNSAFE;

                    Optional<ItemStack> replacement = action.to();
                    current.ifPresent(stack -> itemDeltas.merge(stack.itemId(), -stack.count(), Integer::sum));
                    replacement.ifPresent(stack -> itemDeltas.merge(stack.itemId(), stack.count(), Integer::sum));
                    pending.add(new PendingChange(containerId, slot, replacement));
                }

                if (itemDeltas.values().stream().anyMatch(delta -> delta != 0)) {
                    return Result.REJECTED_STALE_OR_UNSAFE;
                }

                // All references and snapshots are validated before the first mutation.
                for (PendingChange change : pending) {
                    if (change.containerId() == PLAYER_CONTAINER_ID) {
                        player.setSlot(change.slot(), change.replacement().orElse(null));
                    } else {
                        container.setSlot(change.slot(), change.replacement().orElse(null));
                    }
                }
                return Result.APPLIED;
            }
        }
    }

    private record PendingChange(int containerId, int slot, Optional<ItemStack> replacement) {
    }

    public enum Result {
        APPLIED,
        INVALID_WINDOW,
        INVALID_ACTION_COUNT,
        INVALID_ACTION,
        INVALID_SLOT,
        UNSUPPORTED_SOURCE,
        UNSUPPORTED_CONTAINER,
        REJECTED_STALE_OR_UNSAFE
    }
}
