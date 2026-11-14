package ru.nexora.core.rpg;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-memory ability cooldown tracker using the server's monotonic tick counter.
 * Cooldowns intentionally reset on server restart; persistent cooldowns can be
 * added by a gameplay module if its design requires them.
 */
public final class AbilityCooldownService {
    private final ConcurrentMap<CooldownKey, Long> readyAtTick = new ConcurrentHashMap<>();

    public boolean tryStart(UUID playerId, String abilityId, long currentTick, long cooldownTicks) {
        Objects.requireNonNull(playerId, "playerId");
        String normalizedAbilityId = normalizeAbilityId(abilityId);
        if (currentTick < 0 || cooldownTicks < 0) {
            throw new IllegalArgumentException("Ticks cannot be negative");
        }
        if (cooldownTicks == 0) {
            return true;
        }

        CooldownKey key = new CooldownKey(playerId, normalizedAbilityId);
        long nextReadyTick = currentTick > Long.MAX_VALUE - cooldownTicks
                ? Long.MAX_VALUE : currentTick + cooldownTicks;
        AtomicBoolean accepted = new AtomicBoolean(false);
        readyAtTick.compute(key, (ignored, existingReadyTick) -> {
            if (existingReadyTick == null || currentTick >= existingReadyTick) {
                accepted.set(true);
                return nextReadyTick;
            }
            return existingReadyTick;
        });
        return accepted.get();
    }

    public long remainingTicks(UUID playerId, String abilityId, long currentTick) {
        Objects.requireNonNull(playerId, "playerId");
        if (currentTick < 0) {
            throw new IllegalArgumentException("currentTick cannot be negative");
        }
        Long ready = readyAtTick.get(new CooldownKey(playerId, normalizeAbilityId(abilityId)));
        return ready == null || currentTick >= ready ? 0 : ready - currentTick;
    }

    public void clearPlayer(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        readyAtTick.keySet().removeIf(key -> key.playerId().equals(playerId));
    }

    public int activeCooldownCount() {
        return readyAtTick.size();
    }

    private static String normalizeAbilityId(String abilityId) {
        Objects.requireNonNull(abilityId, "abilityId");
        String normalized = abilityId.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9_-]{0,31}")) {
            throw new IllegalArgumentException("Ability id must be a 1-32 character slug");
        }
        return normalized;
    }

    private record CooldownKey(UUID playerId, String abilityId) { }
}
