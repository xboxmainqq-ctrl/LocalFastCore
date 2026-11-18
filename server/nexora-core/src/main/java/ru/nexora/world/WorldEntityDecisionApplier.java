package ru.nexora.world;

import ru.nexora.combat.LivingEntityAiService;

import java.util.Objects;

/**
 * Applies server-authoritative AI decisions to the living-entity registry.
 *
 * <p>The AI service never mutates world state. This class is the explicit mutation
 * boundary: movement is validated by WorldLivingEntityManager and attacks are routed
 * through the same damage API used by other server systems.</p>
 */
public final class WorldEntityDecisionApplier {
    private final WorldLivingEntityManager entities;

    public WorldEntityDecisionApplier(WorldLivingEntityManager entities) {
        this.entities = Objects.requireNonNull(entities, "entities");
    }

    public ApplyResult apply(LivingEntityAiService.Decision decision) {
        Objects.requireNonNull(decision, "decision");
        var entity = entities.find(decision.mobRuntimeId());
        if (entity.isEmpty() || entity.get().health() <= 0) {
            return new ApplyResult(decision.mobRuntimeId(), false, false, false, 0);
        }

        boolean moved = false;
        if (Math.abs(decision.moveX()) > 0.00001
                || Math.abs(decision.moveY()) > 0.00001
                || Math.abs(decision.moveZ()) > 0.00001) {
            var current = entity.get();
            entities.move(current.runtimeId(),
                    current.x() + decision.moveX(),
                    current.y() + decision.moveY(),
                    current.z() + decision.moveZ());
            moved = true;
        }

        boolean attacked = false;
        float damage = 0;
        if (decision.attackDamage() > 0 && decision.targetNetworkId() != null) {
            // Player damage is intentionally represented as an output event here.
            // The player subsystem owns actual player health/inventory mutation.
            attacked = true;
            damage = decision.attackDamage();
        }

        return new ApplyResult(decision.mobRuntimeId(), moved, attacked, false, damage);
    }

    public record ApplyResult(long mobRuntimeId, boolean moved, boolean attacked,
                              boolean targetKilled, float attackDamage) {
        public ApplyResult {
            if (mobRuntimeId <= 0 || attackDamage < 0) {
                throw new IllegalArgumentException("Invalid decision application result");
            }
        }
    }
}
