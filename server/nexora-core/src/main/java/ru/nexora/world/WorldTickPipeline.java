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
    private final ru.nexora.combat.MobAiTaskScheduler taskScheduler;

    public WorldTickPipeline(MobAiLoadController loadController, LivingEntityAiService aiService) {
        this.loadController = Objects.requireNonNull(loadController, "loadController");
        this.aiService = Objects.requireNonNull(aiService, "aiService");
        this.taskScheduler = new ru.nexora.combat.MobAiTaskScheduler();
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
        int highPriority = 0;
        int normalPriority = 0;
        int lowPriority = 0;
        long budgetStart = System.nanoTime();
        for (WorldLivingEntity mob : mobs) {
            if (mob == null || mob.health() <= 0) continue;

            if (taskScheduler.admit(ru.nexora.combat.MobAiTaskScheduler.Task.TARGETING,
                    priorityHint(mob), true, 0, loadController.aiBudgetNanos())
                    == ru.nexora.combat.MobAiTaskScheduler.Admission.DEFER) {
                deferred++;
                continue;
            }
            LivingEntityAiService.PlayerTarget nearest =
                    LivingEntityAiService.nearestAlivePlayer(mob, players);
            double distanceSquared = nearest == null
                    ? Double.POSITIVE_INFINITY
                    : distanceSquared(mob, nearest);

            boolean activeTarget = nearest != null && distanceSquared
                    <= LivingEntityAiService.ACQUIRE_RANGE * LivingEntityAiService.ACQUIRE_RANGE;
            double finiteDistanceSquared = finiteDistance(distanceSquared);
            MobAiLoadController.Priority priority = loadController.priority(
                    finiteDistanceSquared, activeTarget,
                    LivingEntityAiService.isFlyingMob(mob.identifier())
                            || LivingEntityAiService.isTimidMob(mob.identifier()));
            priority = loadController.effectivePriority(mob.runtimeId(), priority);
            if (taskScheduler.admit(ru.nexora.combat.MobAiTaskScheduler.Task.NAVIGATION,
                    priority, activeTarget, System.nanoTime() - budgetStart,
                    loadController.aiBudgetNanos())
                    == ru.nexora.combat.MobAiTaskScheduler.Admission.DEFER) {
                deferred++;
                loadController.recordDeferred(mob.runtimeId());
                continue;
            }
            switch (priority) {
                case HIGH -> highPriority++;
                case NORMAL -> normalPriority++;
                case LOW -> lowPriority++;
            }

            if (!loadController.shouldUpdate(mob.runtimeId(), tick,
                    finiteDistanceSquared, activeTarget)) {
                deferred++;
                loadController.recordDeferred(mob.runtimeId());
                continue;
            }

            long consumedNanos = System.nanoTime() - budgetStart;
            if (!loadController.admit(mob.runtimeId(), tick, finiteDistanceSquared,
                    activeTarget, priority, consumedNanos)) {
                deferred++;
                loadController.recordDeferred(mob.runtimeId());
                continue;
            }

            scheduled++;
            loadController.recordScheduled(mob.runtimeId());
            long decisionStart = System.nanoTime();
            LivingEntityAiService.Decision decision = aiService.decide(
                    mob, players, nowMillis, 0L, null, nearest);
            if (decision.attack()) {
                taskScheduler.admit(ru.nexora.combat.MobAiTaskScheduler.Task.COMBAT,
                        priority, true, System.nanoTime() - budgetStart,
                        loadController.aiBudgetNanos());
            } else if (decision.moving()) {
                taskScheduler.admit(ru.nexora.combat.MobAiTaskScheduler.Task.MOVEMENT,
                        priority, priority == MobAiLoadController.Priority.HIGH,
                        System.nanoTime() - budgetStart, loadController.aiBudgetNanos());
            } else {
                taskScheduler.recordRun(ru.nexora.combat.MobAiTaskScheduler.Task.IDLE);
            }
            decisionSink.accept(decision);
            decisions++;
            loadController.recordBudgetNanos(System.nanoTime() - decisionStart);
        }

        loadController.updateAdaptiveBudget(System.nanoTime() - budgetStart);
        return new TickResult(tick, mobs.size(), scheduled, deferred, decisions,
                highPriority, normalPriority, lowPriority);
    }

    private MobAiLoadController.Priority priorityHint(WorldLivingEntity mob) {
        return LivingEntityAiService.isFlyingMob(mob.identifier())
                ? MobAiLoadController.Priority.HIGH : MobAiLoadController.Priority.NORMAL;
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
                             int deferredMobs, int decisionsProduced,
                             int highPriorityMobs, int normalPriorityMobs, int lowPriorityMobs) {
        public TickResult {
            if (tick < 0 || observedMobs < 0 || scheduledMobs < 0
                    || deferredMobs < 0 || decisionsProduced < 0 || highPriorityMobs < 0
                || normalPriorityMobs < 0 || lowPriorityMobs < 0
                || highPriorityMobs + normalPriorityMobs + lowPriorityMobs > observedMobs) {
                throw new IllegalArgumentException("Invalid tick result");
            }
        }
    }
}
