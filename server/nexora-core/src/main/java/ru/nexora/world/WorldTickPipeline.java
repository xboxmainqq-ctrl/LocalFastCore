package ru.nexora.world;

import ru.nexora.combat.LivingEntityAiService;
import ru.nexora.combat.MobAiLoadController;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Server-authoritative world tick coordinator.
 *
 * <p>The pipeline deliberately separates scheduling from behavior: the load controller
 * decides whether generic mob AI gets a slice this tick, while the AI service decides
 * what that mob should do. The caller remains responsible for applying movement/attack
 * decisions to the actual world, keeping this layer deterministic and testable.</p>
 */
public final class WorldTickPipeline {
    private final MobAiLoadController loadController;
    private final LivingEntityAiService aiService;

    public WorldTickPipeline(MobAiLoadController loadController, LivingEntityAiService aiService) {
        this.loadController = Objects.requireNonNull(loadController, "loadController");
        this.aiService = Objects.requireNonNull(aiService, "aiService");
    }

    public TickResult tick(
            long tick,
            long nowMillis,
            List<WorldLivingEntity> mobs,
            List<LivingEntityAiService.PlayerTarget> players,
            Consumer<LivingEntityAiService.Decision> decisionSink
    ) {
        if (tick < 0 || nowMillis < 0) throw new IllegalArgumentException("tick/time must be non-negative");
        Objects.requireNonNull(mobs, "mobs");
        Objects.requireNonNull(players, "players");
        Objects.requireNonNull(decisionSink, "decisionSink");

        int scheduled = 0;
        int deferred = 0;
        int decisions = 0;
        for (WorldLivingEntity mob : mobs) {
            if (mob == null || mob.health() <= 0) continue;

            LivingEntityAiService.PlayerTarget nearest =
                    LivingEntityAiService.nearestAlivePlayer(mob, players);
            double distanceSquared = nearest == null
                    ? Double.POSITIVE_INFINITY
                    : distanceSquared(mob, nearest);

            boolean activeTarget = nearest != null && distanceSquared
                    <= LivingEntityAiService.ACQUIRE_RANGE * LivingEntityAiService.ACQUIRE_RANGE;

            if (!loadController.shouldUpdate(mob.runtimeId(), tick,
                    finiteDistance(distanceSquared), activeTarget)) {
                deferred++;
                continue;
            }

            scheduled++;
            LivingEntityAiService.Decision decision = aiService.decide(
                    mob, players, nowMillis, 0L, null, nearest);
            decisionSink.accept(decision);
            decisions++;
        }

        return new TickResult(tick, mobs.size(), scheduled, deferred, decisions);
    }

    private static double finiteDistance(double value) {
        return Double.isFinite(value) ? value : Double.MAX_VALUE;
    }

    private static double distanceSquared(WorldLivingEntity mob,
                                          LivingEntityAiService.PlayerTarget player) {
        double dx = player.x() - mob.x();
        double dy = player.y() - mob.y();
        double dz = player.z() - mob.z();
        return dx * dx + dy * dy + dz * dz;
    }

    public record TickResult(long tick, int observedMobs, int scheduledMobs,
                             int deferredMobs, int decisionsProduced) {
        public TickResult {
            if (tick < 0 || observedMobs < 0 || scheduledMobs < 0
                    || deferredMobs < 0 || decisionsProduced < 0) {
                throw new IllegalArgumentException("Invalid tick result");
            }
        }
    }
}
