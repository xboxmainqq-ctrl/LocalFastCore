package ru.nexora.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Converts repeated diagnostic signals into bounded, advisory repair proposals.
 *
 * <p>This class never edits files, executes commands, restarts services, or mutates
 * gameplay state. It deliberately prefers safe operational actions such as inspecting
 * a subsystem, draining a bounded queue, or rebuilding an in-memory cache only when
 * a future runtime explicitly registers such an action.</p>
 */
public final class NexoraAiRepairAdvisor {
    private final NexoraAiRepairProposals proposals;

    public NexoraAiRepairAdvisor(NexoraAiRepairProposals proposals) {
        this.proposals = Objects.requireNonNull(proposals, "proposals");
    }

    /**
     * Creates at most one proposal per distinct sustained signal. Existing pending
     * proposals are reused so a noisy subsystem cannot flood the proposal store.
     */
    public synchronized List<NexoraAiRepairProposals.Proposal> analyze(
            NexoraAiDiagnostics diagnostics
    ) {
        Objects.requireNonNull(diagnostics, "diagnostics");
        List<NexoraAiRepairProposals.Proposal> created = new ArrayList<>();

        for (NexoraAiDiagnostics.FailureTrendSummary trend : diagnostics.failureTrendSummaries()) {
            if (!trend.sustainedFailure()) continue;
            String issue = "Устойчивая ошибка подсистемы: " + trend.subsystem()
                    + " (" + trend.failureCount() + " ошибок за три минуты).";
            String plan = "1) остановить только затронутую подсистему; "
                    + "2) собрать последние диагностические события; "
                    + "3) проверить зависимость/хранилище; "
                    + "4) выполнить восстановление только после проверки; "
                    + "5) повторно измерить состояние три минуты. "
                    + "Автоматический запуск этого плана запрещён.";
            findOrCreate(created, issue, plan);
        }

        for (NexoraAiDiagnostics.LatencyTrendSummary trend : diagnostics.latencyTrendSummaries()) {
            if (!trend.sustainedOverBudget()) continue;
            String issue = "Устойчивая задержка подсистемы: " + trend.subsystem()
                    + " (" + trend.overBudgetCount() + " превышений бюджета).";
            String plan = "1) собрать профиль нагрузки подсистемы; "
                    + "2) проверить очереди и кэш; "
                    + "3) применить только зарегистрированное безопасное восстановление; "
                    + "4) сравнить latency до/после. "
                    + "Изменение конфигурации или исходников автоматически не выполняется.";
            findOrCreate(created, issue, plan);
        }

        NexoraAiDiagnostics.ResourceTrendSummary resources = diagnostics.resourceTrendSummary();
        if (resources.threadGrowthDetected()) {
            findOrCreate(created,
                    "Устойчивый рост числа JVM-потоков за три минуты.",
                    "1) определить источник создания потоков; 2) проверить lifecycle закрытия executor-ов; "
                            + "3) закрыть только подтверждённые утечки ресурсов; 4) повторить измерение. "
                            + "Перезапуск сервера автоматически не выполняется.");
        }
        if (resources.sustainedHeapPressure()) {
            findOrCreate(created,
                    "Устойчивая нагрузка heap JVM за три минуты.",
                    "1) собрать allocation/heap evidence; 2) определить удерживаемые объекты; "
                            + "3) очистить только зарегистрированные безопасные кэши; "
                            + "4) повторить измерение. Full GC и рестарт автоматически не выполняются.");
        }

        return List.copyOf(created);
    }

    private void findOrCreate(
            List<NexoraAiRepairProposals.Proposal> created,
            String issue,
            String plan
    ) {
        for (NexoraAiRepairProposals.Proposal proposal : proposals.list()) {
            if (proposal.status() == NexoraAiRepairProposals.Status.REJECTED) continue;
            if (proposal.issue().equals(issue)) return;
        }
        created.add(proposals.create("nexora-ai-monitor", issue, plan));
    }
}
